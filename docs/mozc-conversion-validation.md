# Mozc明示変換の接続と検証

確認日：2026-09-23。対象は公式`google/mozc` commit `13c98988247aa711d99db9e348ec2a597d14b5cd`の[ローカルビルド生成物](mozc-build-probe.md)。Phase 1の明示変換として、読みを入力して変換キーを押すとMozcの漢字候補が候補行に出て、選ぶと確定する。ライブ変換、文節の伸縮、過去文節の訂正、ユーザー辞書との接続は対象外。配布物の監査は別作業であり、この記録は同梱の許諾を意味しない。

## 構成

| 役割 | 実装 | 要点 |
| --- | --- | --- |
| JNI | `com.google.android.apps.inputmethod.libs.mozc.session.MozcJni` | 公式`mozcjni.cc`の登録先に合わせたclass名と`initialize()`、`onPostLoad(profilePath, dataPath)`、`evalCommand(byte[])`、`getDataVersion()`だけを持つ。 |
| 命令列 | `MozcConversionEngine` | 変換ごとに`REVERT`→読みの各文字を`KeyEvent.key_string`（`AS_IS`）で送る→`SPACE`で変換し、preeditの文節とfocus中文節の`all_candidate_words`を取り出す。確定は`SUBMIT_CANDIDATE`（先頭文節）と`SUBMIT`（全文節）。学習と履歴の停止は`SET_REQUEST`の`Request.is_incognito_mode`。 |
| 直列worker | `ConversionWorker` | 単一threadのexecutorだけがエンジンを呼ぶ。UI threadは要求を置いて戻り、JNIを待たない。未処理の要求は最新の一件だけを変換する。`sessionEpoch`ごとにMozc sessionを作り、Editor sessionの終了時に`DELETE_SESSION`する。 |
| 世代の照合 | `EditorSession` | 要求は`sessionEpoch`、`revision`、読み、incognitoを持つ。応答は待機中の要求と完全に一致し、文節の読みの連結が読み全体と一致する場合だけ表示へ反映する。候補のタップも表示元の要求を照合する。 |
| 機密欄 | `InputFieldPolicy` | password等の候補抑止欄では要求を作らない。`IME_FLAG_NO_PERSONALIZED_LEARNING`では、読みを送る前に必ずincognitoを指定し、指定に失敗したら読みを送らない。学習通知もincognitoの要求では送らない。前後の文脈（`Context`）は送らない。 |
| fallback | `ConversionWorker`、`MozcConversionEngine` | native libraryが無い、`getDataVersion()`が空または`0.0.0`（minimal engine）、または「かんじ」の候補に「漢字」が無い場合は`Unavailable`とし、変換要求を送らない。応答の失敗・不整合・2秒の時間超過では読みの表示を保ち、かな・カナ候補へ戻す。 |

候補を選ぶと先頭文節を確定し、残りの読みを新しいcompositionとして続けて変換する。変換結果の表示中に次の文字を入力すると表示を確定してから新しい読みを始める。変換表示中の削除とカーソル移動は、従来どおり読みの編集として扱い、表示を読みへ戻す。

## 生成物の取り込み

Mozcの生成物はGitへcommitしない。Gradle propertyまたは`local.properties`の`uzumi.mozcArtifactsDir`に生成物のディレクトリを指定すると、ビルド時に`native_libs.zip`から指定ABIの`libmozc.so`をjniLibsへ、`mozc.data`をassetsの`mozc/mozc.data`へ取り込む。`third_party/mozc/NOTICE.txt`があれば`licenses/mozc-NOTICE.txt`としてassetsへ入れる。どれも無い場合はビルドを失敗させない。

| property | 既定値 | 用途 |
| --- | --- | --- |
| `uzumi.mozcArtifactsDir` | 未指定 | 生成物のディレクトリ。未指定ならMozcなしでビルドする。 |
| `uzumi.mozcAbis` | `arm64-v8a` | APKへ入れるABI。emulator確認時は`arm64-v8a,x86_64`とする。 |
| `uzumi.mozcIncludeData` | `true` | `false`で辞書を外したAPKを作り、fallbackを確かめる。 |

Java liteは、固定checkoutの`src/protocol/`から`commands.proto`とimport先4件（`candidate_window.proto`、`config.proto`、`engine_builder.proto`、`user_dictionary_storage.proto`）を`third_party/mozc/proto/protocol/`へ複製し、protoc `4.34.1`でビルド時に生成する。runtimeは`com.google.protobuf:protobuf-javalite:4.34.1`で、Mozcの`MODULE.bazel`が固定するprotobuf 34.1と版をそろえた。protobuf Gradle plugin `0.9.6`はAGP 9.4で`BaseExtension`へのcastに失敗して適用できなかったため、protocのMaven成果物（`com.google.protobuf:protoc:4.34.1`、端末のOSとCPUに合うclassifier）を小さなGradle taskから直接実行する。

辞書はAPKのassetsから`noBackupFilesDir/mozc/mozc.data`へ展開し、APKの更新時刻が変わった場合だけ作り直す。Mozcの学習履歴などは`noBackupFilesDir/mozc/profile`に置く。アプリのbackupは既に無効である。

## ローカル検証

JDKはAndroid Studio同梱、Android SDK compile/target 36、Gradle 9.7.1、AGP 9.4.0。

```sh
./gradlew clean testDebugUnitTest assembleDebug lintDebug
```

結果は成功。JVMテストは75件（既存50件、追加25件）で、失敗・error・skipは0件。追加したテストは次を守る。

- `ConversionWorkerTest`（9件）：要求の呼び出し時点ではエンジンを呼ばないこと、最新の要求だけを変換すること、incognitoの指定が読みの送信より先であること、incognitoを指定できなければ読みを送らないこと、終了したsessionEpochの要求を送らずsessionを破棄すること、学習通知を現在の非incognitoの要求だけに限ること、minimal engineと読み込み失敗の検出、文節と読みの不整合を失敗として返すこと。
- `MozcConversionEngineTest`（7件）：native library不在、辞書不在時のminimal engine検出、data version、命令列（`REVERT`、`AS_IS`の文字入力、`SPACE`）と応答の読み取り、Mozcの失敗応答、`SET_REQUEST`によるincognito、`SUBMIT_CANDIDATE`の候補ID。
- `EditorSessionTest`（追加9件）：一致する応答の反映と確定、読みの変更後に届いた古い応答の破棄、別sessionEpochの応答の破棄、password欄で要求を送らないこととno-learning欄のincognito、時間超過後の応答の破棄とカナへのfallback、失敗・不整合な応答で読みを保つこと、先頭文節の確定と残りの読みの変換、変換表示中の入力による確定、古い候補タップの拒否。

lintはerror 0件。警告4件は、既存のcompile/target 36指定に関する2件と、protobufの新しい版（4.36.2）があるという2件である。後者はMozcのprotobuf 34.1に合わせた意図的な固定。

`-Puzumi.mozcArtifactsDir=`で生成物を指定しない`clean testDebugUnitTest assembleDebug`も成功し、APKに`libmozc.so`と`mozc.data`は入らなかった（4,448,673 bytes）。

## APK

最終commitから生成物を指定して作ったdebug APKは34,110,290 bytes、SHA-256は`4ab9e588d9c0de5837021d80841c96d8a124fc2da0b420b6a1ece2b85c7ceba5`。内訳は`lib/arm64-v8a/libmozc.so`が16,182,000 bytes（無圧縮）、`assets/mozc/mozc.data`が18,994,682 bytes（APK内の圧縮後13,472,787 bytes）。前回のPixel試験版（2,688,373 bytes）より約31.4 MB増えた。端末では展開した辞書が別に約19 MBを使う。`third_party/mozc/NOTICE.txt`はこのbranchに無いため、このAPKには第三者表示が入っていない。

`apksigner verify`は成功。`aapt dump permissions`はpackage名だけを返し、merged manifestに`uses-permission`と`INTERNET`は無い。

## 実機での確認

Pixel 10 Pro（Android 17/API 37）。開始時の既定IMEは`com.adamrocker.android.input.simeji/.OpenWnnSimeji`、有効IMEはSimeji、Google音声入力、Gboard、Uzumiの4件だった。下表の試験は、最終commitとコメントだけが異なるAPK（SHA-256 `bf0e59d38e108b318582b9cbee51bcf0d8017e178854dfe1c6e7d4637b828acc`、同じサイズ）で行った。最後に上記の最終APKを入れ、試用画面の「かんじ」→「漢字」の選択と削除を再確認した。確認は`adb shell uiautomator dump`とスクリーンショットで確認した。入力は架空の文だけである。スクリーンショットはリポジトリへ入れていない。

| 対象 | 操作 | 結果 |
| --- | --- | --- |
| 起動時の確認 | IMEを表示 | 候補行に「変換キーで漢字候補」と出た。辞書の読み込みと「かんじ」→「漢字」の確認に合格したことを表す。 |
| Uzumi試用画面 | 「かんじ」→変換 | 候補「感じ」「漢字」「幹事」「監事」「カンジ」「かんじ」。「漢字」を選び本文が「漢字」になった。 |
| Uzumi試用画面 | 「きょうはいいてんき」→変換 | 表示「今日はいい天気」、先頭文節の候補「今日は」「きょうは」「教は」等。「今日は」を選ぶと残り「いいてんき」が続けて変換され、候補「いい天気」「良い天気」等から「良い天気」を選び本文が「漢字今日は良い天気」になった。 |
| Uzumi試用画面 | 変換表示中の削除、改行 | 「かんじ」→変換→削除で読み「かん」へ戻った。「じ」を足して変換し改行で「感じ」と改行が確定した。確定済み文字の削除は一文字ずつだった。 |
| Chromeの検索欄 | 「てんき」→変換→「天気」 | 候補「天気」「転機」「転記」と絵文字。確定後の削除で「天」になった。検索は実行していない。 |
| 連絡先の新規作成 | 姓で「やまだ」→変換（表示「山田」のまま）→名をタップ→「はなこ」→変換→「花子」 | 姓「山田」、名「花子」で、旧フィールドの文字が新フィールドへ入ることはなかった。連絡先アプリはcomposing中の文字をよみがな欄へ写すため、よみがな欄にも漢字が入った。保存せず破棄した。 |
| Wi-Fiのpassword欄 | 「abc」を入力 | 候補行は「機密入力：候補を表示しません」のまま候補が出なかった。この欄では英字QWERTYだけが出るため、変換キー自体が無い。要求を送らないことはJVMテストで確認した。取り消して接続はしていない。 |
| Mozc sessionの破棄 | 候補を確定→ホームへ移動 | profileの`.history.db`は確定の時点では変わらず、フィールドを離れた直後（19:46:17）に更新された。`DELETE_SESSION`の処理が行う`Sync`と整合する。session IDそのものは記録していない。 |
| 辞書を外したAPK | `-Puzumi.mozcIncludeData=false` | 起動時の表示「辞書なし：かな・カナ候補のみ」、入力中は「辞書なし」とかな・カナ候補、変換キーで「カンジ」。展開済みの古い辞書は削除された。 |
| 破損した辞書 | 先頭1,000,000 bytesだけの`mozc.data` | 同じく「辞書なし」となり、processは終了しなかった。 |
| 生成物なしのAPK | `-Puzumi.mozcArtifactsDir=` | 「辞書なし」とかな・カナ候補で動いた。 |

試用画面で「漢字今日は良い天気」を確定して約10分操作しなかった後、削除キーを一度tapすると本文全体が消えた。processの再起動、Activityの作り直し、クラッシュの記録は無かった。同じ手順を操作直後に2回、約7分の放置後に1回繰り返すと、いずれも一文字だけ消えたため再現していない。原因は`不明`。削除キーには400 ms後に60 ms間隔で繰り返す長押し処理があり、指を離す入力が遅れて長押しと判定された可能性があるが、推測である。

試験の途中で、次の二点がIMEの外で起きた。どちらも架空の文だけである。

- Chromeの検索欄へpassword欄を作るための`data:` URL文字列を入力したところ、ChromeがURLではなく検索語としてGoogle検索を実行した。
- 画面が消灯した後、前面がホームアプリの検索欄に変わったまま「今日はいい天気」を入力し、ホームアプリがWeb候補を表示した。検索は実行せず、欄をクリアした。

## 試験後の復元

既定IMEを`com.adamrocker.android.input.simeji/.OpenWnnSimeji`へ戻し、`enabled_input_methods`を開始時の値へ書き戻した。`settings get secure default_input_method`と`enabled_input_methods`の出力は、開始時に保存した出力とbyte単位で一致した。試験で作られたMozcの学習履歴（`noBackupFilesDir/mozc/profile`）はアプリを停止してから削除した。展開済みの辞書と最終APKは端末に残っている。

## 未確認

- APKに第三者表示（NOTICE）が入った状態のビルドと表示経路。
- Compose TextField、WebView、回転、プロセス再作成、低メモリ、長文での変換時間とメモリ使用量。
- 数字・英字・記号を含む読みの変換。Mozcが読みを正規化して文節の読みが元の読みと一致しない場合は、応答を適用せずかな・カナ候補へ戻る。
- `IME_FLAG_NO_PERSONALIZED_LEARNING`の欄での実機確認（JVMテストだけ）。
- 変換表示中に削除すると、変換前の読みではなく読みの末尾を消す。一般的なIMEの「変換を取り消す」動作と異なる。
