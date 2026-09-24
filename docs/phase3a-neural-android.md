# Phase 3a ニューラル変換のAndroidへの組み込み

2026-09-24に、[設計](neural-direct-conversion-design.md)どおりにニューラル変換をAndroidへ組み込んだ。**製品の既定では無効**で、debugビルドでモデルを選んだときだけ使う。モデルはAPKへ同梱しない（[評価条件](phase3a-model-selection-protocol.md)の配布可否の関門の前のため）。モデルと`.so`はcommitしない。実機での確認は2026-09-24に行った（下の「実機での確認（2026-09-24）」）。

## 結論

- llama.cpp（azooKey fork `66afb885`）とJNIの橋渡しを、Gradleの外でビルドする（`tools/neural/build_android.sh`）。生成物の場所をGradle property `uzumi.neuralArtifactsDir`で受け取り、jniLibsとして取り込む。指定しなければ、ニューラル変換なしでビルドとJVMテストが通り、IMEはMozcだけで動く。
- 推論は別プロセス`:neural`のbound service（`NeuralRuntimeService`、exportしない）で行う。IMEとの受け渡しはoneway AIDLで、要求番号を付けて送る。新しい入力が来たら、古い要求の推論をllama.cppの中断callbackで止める。入力の受理から300 msを超えたら、モデルを待たずにMozcの結果を使う。
- `NeuralRangeConverter`を`SegmentedLiveConverter`の部分範囲の変換へつないだ。保護範囲の表記と前の部分範囲の表記は、左文脈としてモデルへ渡る。学習禁止欄と機密欄では、左文脈を渡さない。30文字を超えるかなの連なりは、30文字以内で最も後ろにあるMozcの文節境界で区切る。
- 評価の条件の切り替え（Mozcだけ／ZS／ZX／JS／JX）は、debugビルドだけで有効な選択としてadbから行う。debugビルドでは、ユーザーが試すために設定画面の「開発用」に「変換エンジン（開発用）」も出す（中身は`NEURAL_SELECT`と同じ`NeuralSelection.select`で、端末にモデルが無い項目は選べない）。releaseビルドの設定画面には出さない。INTERNET権限は足していない。
- 評価条件が求める計数（H3の3つの件数と最終文の数字の並び、正→誤の遷移、分割とfallbackの回数、推論時間、適用までの時間、PSS）を足した。本文は記録しない。

## 構成

| 部分 | 場所 | 役割 |
| --- | --- | --- |
| 部分範囲の変換 | `conversion/NeuralConversion.kt` | かなの連なりだけをモデルへ渡し、検査1〜4を通した表記をMozcの文節へ切り分ける。検査で捨てたら、そのかなの連なりはMozcの結果にする。推論が中断されたら要求全体を取り下げる |
| プロンプト形式 | `conversion/NeuralModels.kt` | [段階0](phase3a-stage0.md)で固定した形式、比較条件のモデル（ファイル名、SHA-256、形式）、推論の設定（threads 4、`n_ctx` 512、最大出力64 token、300 ms） |
| workerへの接続 | `conversion/ConversionWorker.kt` | ライブ変換の要求ごとに期限付きのモデルを作り、ASCIIだけでない部分範囲をニューラルで変換する。新しい要求が来たら前の推論を止める |
| IME側の受け渡し | `neural/NeuralRuntimeClient.kt` | プロンプトを組み立てて送り、結果か期限まで待つ。同じプロンプトの結果をcacheから返す。期限を過ぎたら中断を送って時間超過を返す |
| serviceへの接続 | `neural/NeuralRuntimeConnection.kt` | `:neural`へbindしてモデルを読み込ませる。別プロセスが終了したら、間隔を空けて3回まで接続し直す |
| 推論service | `neural/NeuralRuntimeService.kt`、`aidl/.../INeuralRuntime*.aidl` | モデルのSHA-256を確かめてから読み込み、一つのthreadで順に推論する |
| JNIの橋渡し | `cpp/neural/uzumi_neural_jni.cpp`、`neural/NativeNeuralBridge.kt` | tokenize、greedyの生成、停止（EOG、区切り記号、最大出力token）、要求番号による中断 |
| 条件の選択 | `neural/NeuralSelection.kt`、`res/values/neural.xml` | debugビルドだけで有効（`neural_selection_enabled`）。IMEは入力欄の開始ごとに読む |
| 評価用計数 | `evaluation/NeuralCounts.kt`、`NeuralAudit.kt`、`CorrectnessJudge.kt`、`conversion/LiveEvaluationCollector.kt` | 下の「評価用の計数」 |

設計からの変更は次の2点である。

- 変換結果のcacheを、設計の「別プロセス側」ではなくIME側の`NeuralRuntimeClient`に置いた。cache hitではプロセス間の受け渡しも不要になり、cache hitの数をIME側で数えられるためである。
- 左文脈を渡すため、`SegmentedLiveConverter`の`convertRange`へ左文脈の引数を足した。Mozcの経路は左文脈を使わない。

ユーザーが伸縮で区切りを決めた範囲（`fixedRanges`）は、今までどおりMozcの一文節の変換だけを使い、ニューラルへは渡さない。

`converterGeneration`はモデルのSHA-256とプロンプト形式の版から決め、モデルごとに変える。モデルの切り替えは次の入力欄から反映する。

### 中断と時間超過

1. UIスレッドがライブ変換の要求を受けると、通し番号を付けてworkerへ積む。あわせて、それより前の要求の推論を`NeuralRuntimeClient.cancelInFlight`で止める。待っている推論は中断として終わり、その要求は結果を返さない。serviceへは`cancel`を送る。
2. service側では、binderのthreadが新しい要求番号をnativeへ知らせる（`markLatest`）。実行中の古い推論は、llama.cppの中断callbackとtokenごとの確認で止まる。
3. workerは、要求の受理から300 msを期限として結果を待つ。期限を過ぎたら`cancel`を送り、そのかなの連なりはMozcの結果にする。期限の後に届いた結果は、待ち終えた時点で届いていても使わず、cacheにも入れない。期限を過ぎた後は、cacheにある結果も使わない。期限は入力の受理からの時間であり、cache hitを含む「適用までの時間」の目標と同じ起点だからである（推論時間の母集団は送った要求だけで、この規則で変わらない）。
4. 要求番号はIMEのプロセスの中で単調に増やし、nativeはモデルの読み込みと解放で中断済みの番号を消す。同じ`:neural`でモデルを切り替えても、新しいモデルの要求が中断済みと判定されない。
5. 一つの要求には、ニューラルとMozcを合成した一つの結果だけを返す（二段の表示はしない）。

## ビルド

```sh
# native生成物（Gradleの外）。既定の出力先は .local-build/neural-artifacts/
tools/neural/build_android.sh
# 生成物あり
./gradlew clean testDebugUnitTest assembleDebug lintDebug -Puzumi.neuralArtifactsDir=/Users/marutyan/PrivateDev/uzumi/.local-build/neural-artifacts
# 生成物なし（local.propertiesに指定しなければ既定でこちら）
./gradlew clean testDebugUnitTest assembleDebug lintDebug
```

`build_android.sh`は、[ローカル試作](neural-probe.md)で取得したllama.cppのソース、NDK r29、`.local-build/neural-probe/tools/`のcmake・ninjaを使う。Web UI（npm）、server、例、試験は作らない。CPU最適化は`armv8.2-a+dotprod`（Pixel 6以降を想定した仮の値）で、C++ runtimeは静的にリンクする。出力は`arm64-v8a/`のstrip済み`.so`5本と、commitとSHA-256を記録した`manifest.txt`である。生成物を取り込んだビルドだけ、llama.cppの著作権表示（`third_party/llama.cpp/NOTICE.txt`）をライセンス画面に加える。

Gradleの依存は増やしていない。AIDLを使うため`buildFeatures.aidl = true`だけを足した（AGPに含まれる機能で、外部の依存ではない）。CMakeの外部ビルド（externalNativeBuild）も使っていない。

### 2026-09-24のビルドの結果

| 生成物 | bytes | SHA-256 |
| --- | ---: | --- |
| `libuzumi_neural.so` | 37,312 | `9dcdd0843368638a29f399b04782bfd9ea426bca442ee83323d22fdd54b4f59c` |
| `libllama.so` | 2,653,040 | `a4ce36f05e1ca85aeb56524fd2e04c078be4230d833593a69a76d12f3d2bd13a` |
| `libggml-base.so` | 1,056,136 | `f3fed93ec40e4e27a91f2cca35300ee0a9841008fb205eea94ad4e2d6e9f622c` |
| `libggml-cpu.so` | 898,096 | `8bbd8d27a77a9e34eb8eda9bad7b7fe70d29be2fd51fb1ca1ce61d19e2b6600d` |
| `libggml.so` | 668,080 | `09687b74144cf1ab6d72e21d7773466beef7fe5e1ae1528502984d54a4931517` |

`libuzumi_neural.so`の依存は、llama.cppとggmlの4本、`libm`、`libdl`、`libc`だけである。全LOAD segmentのalignmentは`0x4000`（16 KB page対応）。

debug APK（arm64-v8a、Mozcの辞書を含む）は、生成物なしで34,823,557 bytes、生成物ありで40,159,967 bytes（+5,336,410）だった（2回目のレビュー後の修正を入れた版）。`.so`は圧縮せずに入る。

同じ橋渡しのソースをMac向けにビルドし、JVMから同じnative関数を呼んで確かめた（一時の検査で、リポジトリには入れていない）。jinen v2 xsmallの「きょうはいいてんきですね」→「今日はいい天気ですね」、「かんじ」→「感じ」、zenz v3.2 xsmallの「ゆう」→「You」（区切り記号で停止）、「あしたのかいぎはじゅうじからです」→「明日の会議は10時からです」が、段階0の出力と一致した。あわせて次を確かめた。

- 最大出力2 tokenでの打ち切り
- 中断済みの要求番号の拒否
- 実行中に新しい番号を知らせたときの中断
- モデルの解放後に「未準備」を返すこと

## 端末での準備

モデルはアプリのfilesの下の`neural/`へ、条件の表と同じファイル名で置く。

```sh
adb push ggml-model-Q5_K_M.gguf /data/local/tmp/zenz-v3.2-xsmall-Q5_K_M.gguf
adb shell run-as dev.uzumi.ime mkdir -p files/neural
adb shell run-as dev.uzumi.ime cp /data/local/tmp/zenz-v3.2-xsmall-Q5_K_M.gguf files/neural/
# 条件の切り替え（ZS、ZX、JS、JX、Mozcだけ＝M）。次の入力欄から反映する
adb shell am broadcast -n dev.uzumi.ime/.compat.EvaluationCounterReceiver -a dev.uzumi.ime.debug.NEURAL_SELECT --es model ZX
# モデルの準備の確認（neural_ready=1）
adb shell am broadcast -n dev.uzumi.ime/.compat.EvaluationCounterReceiver -a dev.uzumi.ime.debug.EVAL_STATUS
```

`NeuralRuntimeService`は、読み込む前にファイルのSHA-256を`NeuralModelSpec`の固定値と比べる。合わなければ読み込まず、`neural_load_reason=3`になる。

## 評価用の計数

`EVAL_FINISH`の行（計数の版3）は、版2の列の後ろへ次の列を足したものである。どの列も件数で、本文・読み・候補の文字列は含まない。

| 列 | 内容 |
| --- | --- |
| `neural_ranges`、`neural_kana_runs`、`neural_long_splits` | 部分範囲の数、モデルへ渡したかなの連なりの数、30文字を超えて区切った数 |
| `neural_requests`、`neural_cache_hits` | 実際に送った要求の数（cache hitを除く）、cache hitの数 |
| `neural_cancelled`、`neural_timeouts`、`neural_unavailable` | 新しい入力による中断、時間超過、モデルが使えなかった数 |
| `check1_rejected`〜`check4_rejected` | 検査1〜4で捨てた数（`check4_rejected`がH3の拒否件数） |
| `mozc_fallbacks`、`reading_fallbacks` | Mozcへ切り替えた数、読みのまま表示した数 |
| `h3_generated` | H3の生成件数（検査の前に数える） |
| `h3_unrejected` | 生成件数に当たる出力のうち、検査1〜4のどれでも捨てられなかったもの。1件でもあれば試験を止める |
| `h3_applied_violations` | H3の適用後の違反件数 |
| `input_modifications` | 入力した数字・英字を改変したsegmentの数（全角・半角の違いは数えない） |
| `correct_to_wrong` | 正→誤の遷移 |
| `digit_violation` | 最終文の数字の並びの違反（1）、一致（0）、判定していない（-1） |

- 生成件数と適用後の違反件数は`NeuralAudit`で数える。変換器の検査4は呼ばず、照合に使うMozcの文節も、評価モードの間だけworkerがMozcを直接呼んで取り出す。JVMテスト（`LiveEvaluationCollectorTest`）では、検査4を外した変換器の出力に対して、この計数が違反を数えることを確かめた。
- 生成件数は、出力を読みへ割り当てる方法のうち、裏付けの無い連なりが最も少ないもので数える。割り当てが無い場合とMozcが使えない場合は、すべての連なりを数える。
- 適用後の違反件数は、適用した結果のsegmentの読みを、そのsegmentだけでMozcに直接変換し直して照合する。かなの連なり全体で変換した文節とは区切りが異なり得るため、実際には違反でないものを数える側にずれる可能性がある（限界）。ユーザー辞書と学習語が作った数字も区別しない。評価条件では、どちらも空にして測る。
- 正→誤の遷移と最終文の数字の並びは、課題の許容表記を使って端末内で判定する。許容表記は`EVAL_START`の`accepted_b64`（`|`区切りの許容表記をBase64にしたもの）で、最終文は`EVAL_FINISH`の`final_b64`で受け取る。許容表記は課題の間だけ`CorrectnessJudge`のメモリに持ち、ファイルへは書かない。本文を持たないことをテストで固定している計数器とは分けて置いた。
- 推論時間、中断までの時間、適用までの時間は、`EVAL_DUMP_TIMINGS`で返す別のファイル（`no_backup/phase3a-timings.tsv`、列は`counter_version task_id kind millis outcome`）に1件1行で残す。時間超過は、評価条件どおり300 msで打ち切った値にする。適用までの時間は、変換要求を作った入力の受理から、結果の適用までである。
- PSSは`EVAL_MEMORY`で返す（IMEのプロセスは`Debug.getPss`、`:neural`は問い合わせた結果で、1回遅れて入る）。評価条件の1秒ごとの記録は`dumpsys meminfo`で行う。cold start（bindから最初の推論結果まで）も同じ行で返す。

Mozcだけの条件（M）でも同じ列を出す。ニューラルの列は0になり、`input_modifications`、`h3_applied_violations`、`correct_to_wrong`、`digit_violation`はMozcの結果について数える。

## 検証

- `./gradlew clean testDebugUnitTest assembleDebug lintDebug`：生成物ありと生成物なしの両方で合格。JVMテストは284件、失敗0件。lintの指摘は既存の4件だけ（依存と`targetSdk`の新しい版の案内）。
- 追加したJVMテストは次のとおり。
  - `NeuralRuntimeClientTest`：別threadで結果を返すfakeのserviceで、次を確かめる。
    - 要求番号の照合とcache
    - 時間超過での中断と遅れた結果の破棄
    - 新しい要求による中断
    - 接続が切れた場合
  - `ConversionWorkerTest`：ニューラルの経路、新しい要求による中断、中断した要求の取り下げ、実時間での300 msの時間超過からMozcの結果へ戻ること、条件Mの計数が0であること。
  - `LiveEvaluationCollectorTest`、`NeuralAuditTest`、`NeuralPromptFormatTest`、`NeuralRangeConverterTest`の追加分：左文脈、30文字での区切り、検査2、中断。

## 段階2の道具

`tools/phase3a/run_stage2.py`は、Phase 2cの自動測定の道具の版2（状態の確認口で待つ方式）をそのまま呼び、評価条件の段階2の手順を足したものである。端末では、まだ動かしていない。

| 命令 | 内容 |
| --- | --- |
| `plan` | 5回×5条件の順番を表示する（端末を使わない）。巡回の5×5のラテン方格の行と記号を、seed `20261015`で並べ替えたもの |
| `probe` | キーボードの各面の配置を読む（Phase 2cと同じ） |
| `run` | 指定の回（既定は1〜5）を、方格の順番で流す。各条件の間に5分空け、温度状態がNONEに戻るまで待つ |
| `coldstart` | モデルごとに10回、アプリを止めてから`:neural`をbindし、最初の推論結果が届くまでの時間を測る |
| `battery` | 電池の次の回を一つ測る。各条件を充電を切った状態で10分再生し、`dumpsys batterystats`の推定消費を読む。条件の順番はその回の方格の行、条件の間は5分空けて温度を戻す。3回で判定し、Mとの比の最小と最大が1.2をまたぐ候補があるときだけ4回目・5回目を許し、5回でもまたげば不合格とする。回どうしは、前の回と日付が違うか、前の回の開始から4時間以上離す（評価条件の「別の日または時間帯」の解釈として、ユーザーが2026-09-24に承認） |

`run`では条件ごとに次を行う。

1. 設定の内容（APKのSHA-256、モデルのSHA-256、プロンプト形式の版、道具のcommit）を読み、最初の実行で記録した値と違えば止める。記録は実行をまたいで残る（`--rounds 1`の後にAPKを入れ替えて`--rounds 2`を流しても止まる）。
2. `NEURAL_SELECT`でモデルを選んでから、学習を消し、ライブ変換をONにして試験画面を開く（Phase 2cの条件Nと同じ準備）。`EVAL_STATUS`の`neural_model_generation`（モデルのSHA-256の先頭48 bitとプロンプト形式の版から作る識別子）が条件のモデルと一致し、`neural_ready`になるまで待つ。Mでは選択が無いことを確かめる。一致しなければ止める。
3. 端末の状態を記録する（`ro.build.fingerprint`、画面の明るさ、リフレッシュレート、省電力、充電、温度、`am memory-limiter status`）。
4. 課題ごとに`accepted_b64`と`final_b64`を渡して流す。キーの間隔は150 msで、Phase 2cの道具の値のまま。この間、IMEと`:neural`のPSSを1秒ごとに`dumpsys meminfo`で読む。
5. 条件の終わりに、計数（`EVAL_DUMP`）、時間の記録（`EVAL_DUMP_TIMINGS`）、`EVAL_MEMORY`、状態の記録を`round<回>-<条件>-*`のファイルへ残す。

課題は既定で開発用の30文だけを読む。試験用の54文は`--test-set`を付けたときだけ読む。記録は課題集合（ファイルの内容のSHA-256）ごとに`.local-build/phase3a/test-set-ledger/<集合>/`へ一つで、設定を変えても、同じ回・条件の記録があれば止める。流し直しは`--exploration <名前>`で探索として別の場所へ記録する（評価条件の規則どおり、探索は採用の判断に使わず、採用には新しい未使用の集合が要る）。試験用の集合では、休みを省く指定と課題の指定は使えない。

Phase 2cの道具には、課題の開始と終了で受信口へextraを渡す差し込み口（`START_EXTRAS`、`FINISH_EXTRAS`、既定は空）だけを足した。Phase 2cの測定の動作は変わらない。端末を使わない部分（方格、extra、出力の読み取り、試験用の集合の記録）は`tools/phase3a/test_run_stage2.py`で確かめた（20件合格）。

端末で確かめていない点は次のとおりである。

- `dumpsys thermalservice`、`dumpsys meminfo`、`dumpsys batterystats`の出力の形式（読み取りは一般的な形式を想定した）。
- cold startの値は、準備の確認の間隔（0.5秒）の分だけ長く出得る。
- 電池の測定は再生のたびに計数の行も増える。

## 実機で確かめること

1. `:neural`で`.so`とモデルが読み込めること（SHA-256の確認の時間を含むcold start）。
2. 推論時間、適用までの時間、時間超過率。Macの値（段階0）は端末の根拠にしない。
3. `:neural`とIMEのPSS。`am memory-limiter status`と`ApplicationExitInfo`で、メモリ制限が2つのプロセスの合計にかかるかを見る。
4. 新しい入力による中断が実際に推論を止めているか（中断までの時間の分布）。
5. `:neural`を強制終了したとき（`am kill`など）に、IMEがMozcだけで続き、接続し直すこと。
6. 条件の切り替えと`EVAL_STATUS`の`neural_ready`。
7. 段階2の道具（下記）を、開発用の集合で一度通すこと。

1〜6は下の「実機での確認（2026-09-24）」で確かめた。7は、開発用の3文を1回だけ通した（5回×30文は未実行）。

## 実機での確認（2026-09-24）

**数値はすべて開発用の入力（`phase3a-dev-tasks.tsv`のVN01〜VN03などの数文）での参考値であり、段階2の結果ではない。** 品質（正誤）の比較はしていない。回数は各条件1回で、区間や合否の判定には使えない。

### 条件

| 項目 | 値 |
| --- | --- |
| 端末 | Pixel 10 Pro（`google/blazer_beta/blazer:17/CP41.260828.004.A8/16319058:user/release-keys`）、AC給電、電池100%、省電力OFF、温度状態0、明るさ34 |
| APK | debug（生成物あり）。確認の途中で下の不具合を直して入れ替えた。直す前 SHA-256 `193b5ca7…ca0c`、直した後 `360ea2f8…f50c`（どちらも40,160,555 bytes）。下の1〜5と7は直す前、6（強制終了）は両方で確かめた |
| `.so` | `tools/neural/build_android.sh`で作り直した（ビルドの作業場所に別のworktreeのcmakeの記録が残っていたため消してから作った）。`libuzumi_neural.so` `5337cd04…9a13`、`libggml-base.so` `26931d84…66fe`（前回の表と違う2本。前者は`05a9b56`の橋渡しの修正を含む。後者は大きさが同じで、違いの原因は未確認）、ほかの3本は前回の表と同じ |
| モデル | `.local-build/neural-probe/models/`の4本を`files/neural/`へ置いた。端末上のSHA-256は`NeuralModelSpec`の値と4本とも一致 |
| 道具 | `run_stage2.py run --rounds 1 --tasks VN01,VN02,VN03 --no-rest`（Phase 2cの道具は版3）。強制終了と準備の時間は、今回だけの補助の手順で測った |

### 結果

1. **読み込み**：4モデルとも`:neural`で`.so`とモデルを読み込めた（`neural_load_reason=0`、`neural_ready=1`）。
2. **準備までの時間**：アプリを止めた状態から試験画面を開き、`neural_ready`になるまで（IMEのプロセスの起動、bind、SHA-256の確認、読み込みを含む、端末の外から0.1秒ごとに確認）は、各3回でZX 585〜655 ms、JX 622〜657 ms、ZS 633〜796 ms、JS 646〜853 msだった。IMEが返す`neural_cold_start_ms`（bindから最初の推論結果まで）は4モデルとも10.5〜11.5秒だったが、これは最初の入力が届くまでの待ち（試験画面の準備と道具の確認）を含む値で、読み込みの時間を表していない（下の未確認事項）。
3. **推論時間・適用までの時間・時間超過**（3文、各条件1回。適用までの時間はcache hitを含む全要求）：

| 条件 | 送った要求 | 推論 中央値 / p95 / 最大 ms | 適用まで 中央値 / p95 / 最大 ms | 時間超過 | 中断 |
| --- | ---: | --- | --- | ---: | ---: |
| M | 0 | — | 18.4 / 29.1 / 41.4 | — | — |
| ZX | 47 | 64.9 / 93.1 / 123.2 | 97.6 / 129.4 / 170.1 | 0 | 0 |
| JX | 47 | 52.6 / 93.8 / 117.6 | 87.7 / 138.0 / 154.8 | 0 | 0 |
| ZS | 47 | 136.9 / 238.4 / 264.5 | 174.9 / 279.2 / 292.6 | 0 | 0 |
| JS | 44 | 134.7 / 262.0 / 280.9（完了した43件） | 165.7 / 302.1 / 306.8 | 1（2.3%） | 3 |

4. **PSS**（`dumpsys meminfo`を1秒ごと、各条件32〜41回）：IMEは最大109,613〜116,790 KB（Mだけでも116,790 KB）。`:neural`は最大でZX 71,049、JX 78,486、ZS 183,413、JS 188,845 KB。合計の最大はZX 185,067、JX 191,908、ZS 299,782、JS 298,396 KB。`am memory-limiter status`は有効（visibleは10,240 MB、`uid-*`の項目がある）で、計数の対象がuid単位なら2つのプロセスの合計にかかる（推測、確かめていない）。`ApplicationExitInfo`には、今回の強制終了（SIGNALED、status 9）と`am force-stop`だけがあり、低メモリやメモリ制限による終了は無かった。
5. **新しい入力による中断**：JSで3件あり、IME側で中断を決めるまでの待ちは255.7〜281.8 msだった。中断の直後に送った要求は、適用までの時間と推論時間の差が28〜38 msで、ほかの要求（約30 ms）と同じだった。`:neural`は推論を一つのthreadで順に行うため、中断した推論が最後まで続いていれば次の要求はその分待たされるはずで、待たされていないことから推論は止まっていたと判断した（間接の確認。native側で止まった時刻は記録していない）。
6. **`:neural`の強制終了**（ZX、`run-as`で`kill -9`）：終了の直後に`neural_ready=0`になり、IMEのプロセスは残った。1回目・2回目・3回目の終了の後は、それぞれ1.5〜1.6、2.4〜2.7、3.7〜3.8秒で接続し直した（間隔を1、2、3秒と延ばす規則どおり）。4回目の終了の後は接続し直さず、その状態で流した課題（VH01）は、14件の要求がすべてモデルを使えない扱い（`neural_unavailable=14`、`mozc_fallbacks=14`）になり、Mozcだけで最後まで入力できた（最終文は正解）。
7. **条件の切り替えと`neural_ready`**：`NEURAL_SELECT`で5条件を順に切り替え、`EVAL_STATUS`の`neural_model_generation`が条件のモデルと一致し、`neural_ready=1`（Mでは`neural_selected=0`）になることを段階2の道具が各条件の開始時に確かめた。

H3の計数は、ZXで生成1件・検査4での拒否1件・拒否されなかったもの0件、他のモデルは0件だった。適用後の違反は全条件0件だった。

### 見つけた不具合と修正

- **上限を超えて`:neural`が終了した後、同じモデルのままではMozcだけの状態が続いた。** `NeuralRuntimeConnection.MAX_RESTARTS`の説明は「超えたらこの入力の間はMozcだけで続ける」だが、接続し直す回数は接続を作ってからの通算で、IMEは同じモデルなら接続を作り直さないため、IMEのプロセスが終わるかモデルを選び直すまでニューラル変換が戻らなかった。接続し直すのをやめた接続を`gaveUp`で示し、IMEが次の入力欄の開始で作り直すようにした。回数と間隔の規則は`NeuralRestartPolicy`へ分け、`NeuralRestartPolicyTest`で固定した。直した版で、4回目の終了の後に次の入力欄を開くと接続し直し（`neural_requests=18`、`neural_unavailable=0`）、IMEのプロセスは同じままであることを確かめた。

## 未確認事項

- 段階2の本番（5回×5条件×30文）、cold startの10回の測定、電池は行っていない。
- `neural_cold_start_ms`は、bindから最初の推論結果までなので、最初の入力が届くまでの待ちを含む。評価条件のcold startとして使うには、読み込みの完了までの時間を別に記録するか、道具が準備の直後に入力するかを決める必要がある（要確認）。
- メモリ制限が2つのプロセスの合計にかかるかは、`am memory-limiter status`の表示からの推測で、制限を超える条件で確かめていない。
- 中断が推論を止めることは、次の要求の待ち時間からの間接の確認である。
- `armv8.2-a+dotprod`で作ったため、dotprodの無い端末では動かない（最低対応端末は未決）。
- `java.text.Normalizer`（Android）のUnicodeの版と、段階0で使ったPythonの版の違いで、NFKCの結果が異なる文字があり得る（開発用の集合の文字では違いは無いと見込むが、未確認）。
- llama.cppの共有ライブラリとNDKのC++ runtimeの配布条件は、配布可否の関門で確かめる（未実施）。
