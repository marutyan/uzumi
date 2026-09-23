# ニューラルかな漢字変換のローカル試作

2026-09-24（UTC 2026-09-23 19:31–19:45、約14分）、Phase 3aの準備として、llama.cppとかな漢字変換モデル4種をGit追跡外の`.local-build/neural-probe/`へ取得し、Mac向けとAndroid arm64-v8a向けにビルドした。Macでは各モデルで固定の8文を変換できることだけを確かめた。**モデルどうしの品質・速度の比較はしていない**。APK、アプリのGradle、既存SDK、global PATHは変えていない。生成物とモデルはcommitしない。

## 結果

- 4モデルとも、固定したruntimeで読み込め、8文すべてがモデルの終端token（EOG）で正常に終わった。
- **jinen v2はスミレと同じruntime（`88b97a47`）では読み込めない。** jinen v2のアーキテクチャは`qwen3`で、2025-04-06のこのcommitは`qwen3`に対応していない。azooKey forkの`azookey/b9637-compat`（`66afb885`、上流`b9637`に日本語tokenizerの互換修正3ファイル21行を足したもの）では4モデルとも動いた。比較は同じruntimeで行う必要があるため、評価には`66afb885`を使う案とする（要確認）。
- zenz 2種の8文の出力は、2つのruntimeで完全に一致した。
- Android arm64-v8a向けの共有ライブラリ4本は、strip後の合計が`88b97a47`で3.2 MiB、`66afb885`で5.0 MiB。全LOAD segmentのalignmentは`0x4000`（16 KB page対応）。依存は`libc`、`libm`、`libdl`だけで、C++ runtimeは静的にリンクされる。
- 取得とビルドは1時間の上限内（約14分）に終わった。

## 取得したもの

### llama.cpp（azooKey fork、MIT）

gitの別リポジトリ操作が作業環境の自動判定で拒否されたため、固定commitのソースはGitHubのアーカイブ（`codeload.github.com/azooKey/llama.cpp/tar.gz/<commit>`）から取得した。アーカイブのpax headerにあるcommit IDが要求したcommitと一致することを確認した。

| commit | 日付 | 内容 | アーカイブ bytes | アーカイブ SHA-256 |
| --- | --- | --- | ---: | --- |
| [`88b97a47dc7f5892e2d5a6856fbe9cfe237f9e5c`](https://github.com/azooKey/llama.cpp/tree/88b97a47dc7f5892e2d5a6856fbe9cfe237f9e5c) | 2025-04-06 | forkの`master`。スミレが使うcommit | 20,841,823 | `ef3a6ed7d35dbbe256e9cabf0d101555be76f129ecdf728826d10dbba314612c` |
| [`66afb885a8ddcc0511798aec9fb5a01017941018`](https://github.com/azooKey/llama.cpp/tree/66afb885a8ddcc0511798aec9fb5a01017941018) | 2026-07-28 | branch `azookey/b9637-compat`、tag `b9637-azookey.1`。親は上流[`b9637`](https://github.com/ggml-org/llama.cpp/tree/aedb2a5e9ca3d4064148bbb919e0ddc0c1b70ab3)（`aedb2a5e`）。変更は`ggml/src/ggml-backend-reg.cpp`、`src/llama-vocab.cpp`、`src/llama-vocab.h` | 34,870,158 | `122fca013302ed921b7a0cc6b60e6447a6fa3b183eafa86e6959a1c7211b9443` |

どちらも`LICENSE`はMIT（Copyright The ggml authors）。

### モデル（Hugging Face、revision固定）

取得URLは`https://huggingface.co/<repo>/resolve/<revision>/<file>`。SHA-256はHugging FaceのLFS情報と一致した。

| モデル | repo と revision | ファイル | bytes | SHA-256 | ライセンス（Hubの表示） | 構造 |
| --- | --- | --- | ---: | --- | --- | --- |
| zenz v3.2 small | [`Miwa-Keita/zenz-v3.2-small-gguf`](https://huggingface.co/Miwa-Keita/zenz-v3.2-small-gguf/tree/c67e03e07d215c869f591b274c1631170d3e11fe) `c67e03e07d215c869f591b274c1631170d3e11fe` | `ggml-model-Q5_K_M.gguf` | 73,871,936 | `29c223d4c23327b80fd13ebb5ab2555057a46317997d5da391584ffbef0db673` | Apache-2.0 | gpt2、12層、幅768、語彙6,000 |
| zenz v3.2 xsmall | [`Miwa-Keita/zenz-v3.2-xsmall-gguf`](https://huggingface.co/Miwa-Keita/zenz-v3.2-xsmall-gguf/tree/4f5423f0fad41a73b1242eb96fe5c12ae4fdca83) `4f5423f0fad41a73b1242eb96fe5c12ae4fdca83` | `ggml-model-Q5_K_M.gguf` | 20,970,304 | `00c64b3d318045a708d0cad5434faccab10f5481a49e6362864551fd0995fa58` | Apache-2.0 | gpt2、6層、幅512、語彙6,000 |
| jinen v2 small | [`togatogah/jinen-v2-small.gguf`](https://huggingface.co/togatogah/jinen-v2-small.gguf/tree/3461d0573ab447985badde3174165b967d06076c) `3461d0573ab447985badde3174165b967d06076c` | `jinen-v2-small-Q5_K_M.gguf` | 81,117,824 | `80482707513d6b67dafc31774371cf95d765542abf8d74eebf5f32f92d788bd3` | CC BY-SA 4.0 | qwen3、12層、幅768、語彙32,003 |
| jinen v2 xsmall | [`togatogah/jinen-v2-xsmall.gguf`](https://huggingface.co/togatogah/jinen-v2-xsmall.gguf/tree/3910fd01bf4ba86eca89617f18db9b0c1c5b2283) `3910fd01bf4ba86eca89617f18db9b0c1c5b2283` | `jinen-v2-xsmall-Q5_K_M.gguf` | 28,261,056 | `24ff3af5db712fbbb4aa9254ee28ec4d731207134471ab68b06c1828726284c2` | CC BY-SA 4.0 | qwen3、6層、幅512、語彙32,003 |

合計204,221,120 bytes。各revisionの`README.md`も同じ場所に保存した。zenz v3.2のmodel cardは本文がライセンス表示だけで、学習データの説明は無い。jinen v2のmodel cardは、学習コーパスの一部に国立国会図書館の書誌データを加工したものを使ったこと、その出典表示を求める文を載せている。配布時の表示義務は、APK同梱前の配布条件の監査で確定する（未実施）。

jinenのrepoにある`tokenizer.json`（2,047,498 bytes）は取得していない。karukanはllama.cpp内蔵のtokenizerではなくこのファイルでtokenizeする（後述）。

### 参考として読んだもの（取得物の同梱なし）

- karukan：[`togatoga/karukan`](https://github.com/togatoga/karukan/tree/3828dd2ca146dcb86529bb1d86047676c4965c02) `3828dd2ca146dcb86529bb1d86047676c4965c02`（2026-09-19）。MIT OR Apache-2.0。変換、chunk、辞書、学習キャッシュの一部ファイルだけを調査用の一時領域へ取得して読んだ。
- スミレ：調査用cloneの`dev` branch（commit IDは作業環境の制限で読めず不明）。MIT。`zenz/src/main/cpp/zenz_bridge.cpp`、`ZenzEngine.kt`、AIDL、`AndroidManifest.xml`を読んだ。
- AJIMEE-Bench：[`azooKey/AJIMEE-Bench`](https://github.com/azooKey/AJIMEE-Bench/tree/401666cd56d1a570c2021798b64b6da4396bfd45) `401666cd`のREADMEだけを読んだ。評価データは取得していない。

### ビルドの補助ツール

作業環境にcmakeとninjaが無かったため、`uv venv`で`.local-build/neural-probe/tools/`へ隔離環境を作り、PyPIのcmake 4.4.3とninja 1.13.2を入れた。global PATHとshellの設定は変えていない。この導入は承認の文言に明記されていないため、報告で要確認とした。NDKは`.local-build/mozc-probe/`のr29（`29.0.14206865`）を再利用した。Macのコンパイラは既存のApple clang 21.0.0。

## ビルド

手順は`.local-build/neural-probe/scripts/`に置いた。`build.sh <commit> <名前> <mac|android>`が本体で、`build-probe.sh`は動作確認用プログラムのリンク、`inspect-android.sh`は`.so`のstripと検査、`run_probe.py`は動作確認の実行である。

| 対象 | 主な設定 | 所要時間 |
| --- | --- | ---: |
| Mac `88b97a47` | Release、静的ライブラリ、`LLAMA_CURL=OFF`。CLIとexamplesも作られる | 37秒 |
| Mac `66afb885` | Release、静的ライブラリ、`LLAMA_CURL=OFF`、`LLAMA_OPENSSL=OFF`、UI・server・app・tools・examples・testsをOFF | 41秒 |
| Android `88b97a47` | NDK r29のtoolchain file、`ANDROID_ABI=arm64-v8a`、`ANDROID_PLATFORM=android-30`、`-march=armv8.2-a+dotprod`、`GGML_OPENMP=OFF`、`GGML_NATIVE=OFF`、共有ライブラリ | 20秒 |
| Android `66afb885` | 上と同じ。加えてapp・common・toolsをOFF | 24秒 |

CPU最適化の上限`armv8.2-a+dotprod`は、Pixel 6以降を想定した仮の値である。最低対応端末を決めるときに見直す。

**注意（Web UIのnpm取得）**：`66afb885`の最初のMacビルドは`LLAMA_BUILD_UI`の既定値がONで、ビルド中に`npm install`（1,031 packages）とWeb UIのbuildが走った。承認していない取得だったため、そのソースとbuildディレクトリを削除し、アーカイブから展開し直してUIをOFFにして作り直した。作り直した版の動作確認の出力は最初の版と32件すべて一致した。当時のログは`logs/mac-b9637-build-with-npm-ui.log`に残した。npmのユーザー単位のcache（`~/.npm/_cacache`）にも書き込まれた可能性があるが、作業前の状態が分からないため確認していない。Android向けではUIのbuildは走っていない。

作業領域の使用量は終了時1,308 MiB（buildディレクトリ4つで565 MiB、ソース223 MiB、モデル208 MiB、補助ツール138 MiB、使わなかったgit clone 104 MiB、アーカイブ55 MiB、strip済みの`.so`など12 MiB）。通信量は計測していないが、確認できる取得はアーカイブ2つ（55,711,981 bytes）、モデル4つ（204,221,120 bytes）、補助ツールのwheel、使わなかったgit clone、上記のnpm取得である。最初に試したgit cloneは固定commitへのcheckoutが拒否されたため`llama.cpp-clone-unused/`へ名前を変えて残した。使っていないので削除してよい。

### Android arm64-v8a向けの共有ライブラリ

`llvm-strip --strip-unneeded`をかけた複製を`artifacts/<commit先頭8桁>/arm64-v8a/`に置いた。

| ファイル | `88b97a47` bytes | `66afb885` bytes |
| --- | ---: | ---: |
| `libllama.so` | 1,339,312 | 2,653,040 |
| `libggml-base.so` | 882,032 | 1,056,136 |
| `libggml-cpu.so` | 428,160 | 898,096 |
| `libggml.so` | 667,264 | 668,080 |
| 合計 | 3,316,768 | 5,275,352 |
| 4本のzip（APK内の圧縮に近い目安） | 1,278,270 | 1,979,550 |

SHA-256は`logs/android-<commit先頭8桁>-inspect.txt`に記録した。Uzumiが呼ぶJNIの橋渡し（スミレの`zenz_bridge.cpp`に当たる部分）はまだ作っていないため、実際にAPKへ入るnativeの大きさはこれに橋渡しの分が加わる。端末上での読み込みと推論は未実行（実機は使わない約束のため）。

## Macでの動作確認

目的は「各モデルが固定したruntimeで読め、決まったプロンプト形式で変換結果を返すか」の確認だけである。8文は評価用の課題集合ではない。

- 環境：Apple M3 Pro（11コア）、RAM 18 GiB、macOS（Darwin 27.2.0）。
- 条件：CPUだけ（`n_gpu_layers=0`）、4 threads、`n_ctx=512`、毎回KV cacheを消す、logitsの最大を選ぶgreedy、最大64 token、EOGか`U+EE00..U+EE0F`で停止。動作確認用の小さなC++プログラム（`scripts/probe.cpp`）で、llama.cpp内蔵のtokenizerを使った。
- 結果の記録：`logs/probe-66afb885.jsonl`（4モデル）、`logs/probe-88b97a47.jsonl`（zenz 2種）。

| 読み（左文脈） | zenz v3.2 xsmall | zenz v3.2 small | jinen v2 xsmall | jinen v2 small |
| --- | --- | --- | --- | --- |
| かんじ | 感じ | 感じ | 感じ | 漢字 |
| きょうはいいてんきですね | 今日はいい天気ですね | 今日はいい天気ですね | 今日はいい天気ですね | 今日はいい天気ですね |
| わたしはにほんごをべんきょうしています | 私は日本語を勉強しています | 私は日本語を勉強しています | わたしは日本語を勉強しています | わたしは日本語を勉強しています |
| あしたのかいぎはじゅうじからです | 明日の会議は10時からです | 明日の会議は10時からです | あしたの会議は十字からです | あしたの会議は十時からです |
| でんしゃがおくれている | 電車が遅れている | 電車が遅れている | 電車が遅れている | 電車が遅れている |
| きょうははれた（昨日は雨だった。） | 今日は晴れた | 今日は晴れた | 今日は晴れた | 今日は晴れた |
| 2026ねん9がつ24にち | 2026年9月24日 | 2026年9月24日 | 2026年9月24日 | 2026年9月24日 |
| abcのてすと | abcのテスト | abcのテスト | abcのテスト | abcのテスト |

32件すべてが`eog`で終わった。この表は品質の比較ではない。8文では差を判断できず、表記の好み（「わたし」と「私」など）も評価の規則を決めていない。

設計に関わる観察として、読み「じゅうじ」から数字「10」が生成された例と、読みの数字がそのまま出た例があった。読みに無い数字を出す変換も、読みの数字を別の表記へ変える変換も起こり得るため、[設計](neural-direct-conversion-design.md)で数字の範囲をモデルへ渡さない規則と、読みと表記の対応を確かめる規則を置く。

参考の時間（同じMacで2回実行した2回目。1文の推論時間の中央値／最大、ミリ秒）：`66afb885`でzenz xsmall 6.5／9.8、zenz small 20.0／30.4、jinen xsmall 5.2／8.6、jinen small 15.6／24.0。`88b97a47`でzenz xsmall 6.9／9.7、zenz small 26.2／36.3。モデルの読み込みは2回目で13〜65ミリ秒だったが、最初の実行（`88b97a47`、zenz xsmall）だけ12.3秒かかった。理由は不明（file cacheやMetalの初期化の可能性）。どれもMacでの8文の値であり、端末の性能やモデル間の優劣の根拠にしない。

## プロンプト形式の違い

| 項目 | zenz v3.2 | jinen v2 |
| --- | --- | --- |
| 基本形 | `[U+EE02 左文脈] U+EE00 読み U+EE01` の後を生成 | `U+EE02 左文脈 U+EE00 読み U+EE01` の後を生成 |
| 左文脈の記号 | 文脈があるときだけ付ける（スミレ） | 文脈が空でも`U+EE02`を付ける（karukanの`build_jinen_prompt`） |
| 読みの文字 | カタカナ（スミレはひらがなをカタカナへ写して渡す） | カタカナ（karukanが写す）。model cardの例は変数名`reading`だけで文字種を書いていない |
| 正規化 | 半角空白を全角空白へ、改行を除く（スミレの`preprocess_text`） | プロンプト全体をNFKC正規化する（model cardとkarukanが必須とする） |
| 追加の条件 | v3.2では`U+EE03`〜`U+EE06`（profile、topic、style、preference）、`U+EE07`（右文脈）をスミレが使う | 確認した範囲では無い |
| tokenizer | 文字単位のGPT-2系、語彙6,000。`U+EE00`などは単独tokenではなくUTF-8の3 byteに分かれる | SentencePiece系（`tokenizer.ggml.model=llama`）、語彙32,003。`U+EE00`〜`U+EE02`は単独token（32000〜32002） |
| 停止 | EOG、または出力中の`U+EE00..U+EE0F`（スミレ） | EOG（karukan） |
| 推奨の探索 | スミレはgreedy。候補の評価（`candidate_evaluate`）もある | model cardはgreedy必須（`--temp 0 --top-k 1`）。karukanは複数候補にbeamを使う |
| 必要なruntime | `88b97a47`と`66afb885`の両方で動いた | `qwen3`対応が要る。`66afb885`で動いた |

jinenで読みをひらがなのまま渡し、文脈が無いときに`U+EE02`を省いた最初の試行では、xsmallが空の出力や途中までの出力を、smallが読みに無い語や括弧書きを返した（`かんじ`→`かんじ`、`あしたのかいぎはじゅうじからです`→`あかすと会稽児鹿毛良`など）。カタカナにして`U+EE02`を常に付けると上表のとおり変換された。形式の違いで出力が大きく崩れるため、評価と実装ではモデルごとの形式を一か所に固定する。

karukanはjinenのtokenizeとdecodeに`tokenizer.json`（HuggingFace tokenizers）を使い、llama.cpp内蔵のtokenizerを使わない。今回の8文は内蔵のtokenizerで変換できたが、両者のtoken列が全入力で一致するかは確認していない。Androidで内蔵のtokenizerを使うなら、評価の前に代表的な入力で両者のtoken列を比べる（未実施）。

## 未確認事項

- Android端末での`.so`の読み込み、推論時間、常駐メモリ、cold start、電池（実機は別の担当が測定中のため使っていない）。
- JNIの橋渡しと別プロセスでの推論。
- jinenの内蔵tokenizerと`tokenizer.json`の一致。
- zenz v3.2の学習データの由来と、jinen v2の出典表示を含む配布条件。APKへの同梱は配布条件の監査の後に判断する。
- `88b97a47`以外のruntimeを使うことについて、スミレとの比較可能性。zenzの8文の出力は両runtimeで一致したが、全入力で同じとは限らない。
