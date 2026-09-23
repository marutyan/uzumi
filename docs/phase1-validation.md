# Phase 1入力基盤の検証記録

検証日：2026-09-21。追加修正と統合先での再検証：2026-09-22。

## 対象

Android 11（API 30）以上を対象に、IMEの最小起動構成、composition、かな・カナ基本候補、確定、削除、カーソル移動、editor action、設定導線、試用EditTextを実装した。漢字変換、ニューラル変換、辞書CRUDは対象外であり、仮の漢字辞書も含めていない。

入力本文、composition、候補をログまたは永続領域へ保存する処理はない。password欄では候補を抑止し、`IME_FLAG_NO_PERSONALIZED_LEARNING`では学習抑止方針を有効にする。現段階には学習処理自体がない。`TYPE_NULL`ではcompositionを作らず、候補を抑止して直接確定する。

## ローカル検証

JDKはAndroid Studio同梱環境、Android SDKはcompile/target 36、Gradle 9.7.1、Android Gradle Plugin 9.4.0を使用した。

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
```

結果は成功。JVMテストは49件で、失敗・error・skipは0件だった。書記素境界、選択置換、かな・カナ候補、「は→ば→ぱ」「つ→っ」等の小文字・濁点・半濁点変換、password/no-learning/`TYPE_NULL`方針、InputConnectionのfalse返却、フィールド切替後の無効化、確定済み文字の削除とカーソル移動、標準・独自editor actionを確認した。カナ候補と「小゛゜」キーは別操作として検証した。Android APIを既定値へ置き換えるunit test設定は使用していない。

composition同期は、hostが「あ」のspanを終了した後に「い」を入力して本文が「あい」になること、「かな」の`[0,1]`を範囲選択して「き」を入力すると選択外の「な」を残して「きな」になること、初期selection不明かつ実cursor 2の「XX」で「あ」「い」と入力して「XXあい」になることを確認した。また「あ」「い」を通知より先に入力し、古い「あ」の通知を受けた後に「う」を入力しても、cursorを巻き戻さず「あいう」になることを確認した。逆向きselectionは範囲として扱い、span確定に失敗した接続は無効化して旧readingを再送しない。

空白確定後に次のcompositionが始まり、その後に空白確定時のno-span通知が届いても「あ いう」の「い」を失わないことを追加検証した。確定操作の選択範囲の遷移と一致する通知だけを消費し、実際のhost側span終了は引き続き処理する。読み確定と空白確定の通知が次入力前・入力の間・入力後へ分かれて届く場合も確認し、既知の確定通知では後続の予測selectionと未到着の通知履歴を保持する。

lintはerror 0件。警告は2件で、端末にAPI 37 SDKがある一方、今回の確定条件どおりcompile/target 36を指定していることだけを報告している。アプリ固有のsecurity、accessibility、国際化警告は解消した。

## 独立レビュー

2026-09-22、固定差分`e913a06740c6b036249dbbd825136bfbcd863678`の入力同期修正を再レビューし、指摘の解消を確認した。関連8テストも独立に再実行して成功した。これ以後の変更は検証記録と進捗の記載のみである。実機での通知順序・互換性の保証とは区別する。

## APKとManifest

生成物は`app/build/outputs/apk/debug/app-debug.apk`、サイズは2,686,609 bytes、SHA-256は`101af81b189bf7ebd4154948c2e725251c3d71e76a541326d16cea6f17bd6f3c`だった。

`apksigner verify`で署名検証に成功した。`aapt dump permissions`はpackage名だけを返し、権限宣言は0件だった。merged manifestにも`uses-permission`と`INTERNET`はない。`UzumiInputMethodService`は`android.permission.BIND_INPUT_METHOD`で保護し、IMEとしてexportする。launcherの`MainActivity`は設定導線のためexportする。backupと端末間転送は無効化した。

## 差分検査

新規ファイルをstageした差分は`git -c core.whitespace=cr-at-eol diff --cached --check`で検査する。Windows用の自動生成`gradlew.bat`のCRLFを保持し、CRを行末文字として扱う指定を使う。plain `git diff --check`だけでは未追跡ファイルを検査できないため、その結果を新規ソース全体の証拠にはしない。

## 未実施

端末へのinstall、IMEの有効化・切替、実際のEditText/Compose/WebView/Chrome入力、回転やプロセス再作成は未実施である。build成功を実環境の入力互換性とは扱わない。接続済み端末へはユーザー許可を得るまで書き込まない。

## キー入力の追加（2026-09-23）

Phase 1bの残りとして、次を`keyboard/`内で実装した。編集層と`UzumiInputMethodService`は変更していない。

- 記号面：句読点と括弧、全角記号と矢印、半角記号の3ページ（各28個）。QWERTYの「123」「記号」、数字配列の「記号」、12-keyの「123」の長押しから開き、かな配列を経由せずに英字・数字へ戻れる。
- 長押し：QWERTYの英字26キーに数字・記号（上段は1〜0）、数字配列の「.」に「,」（「記号」キーを置くため、従来の「,」キーを長押しへ移した）。キー右上に小さく表示する。長押し時間は端末設定（`ViewConfiguration.getLongPressTimeout()`）に従い、touch slopを超えて動いた押下では成立しない。かなキーには長押しを付けず、フリックとの衝突を避けた。
- 連続実行：削除と左右カーソルは押下時に一回実行し、400ms後から100ms間隔で始め、20回で50ms間隔まで加速する。キー外へ大きく外れると止まる。カーソルキーは従来の「離したとき」から「押したとき」へ実行時点が変わった。
- 触覚：`View.performHapticFeedback`で押下・連続実行時に`KEYBOARD_TAP`、長押し成立時に`LONG_PRESS`を出す。システムのタッチ操作バイブ設定に従い、権限は追加していない。
- TalkBack：TalkBackは指を離したとき、またはダブルタップ時にキーのクリックを実行するため、独自のhover処理は持たず`performClick`だけで一回入力する。キーは短い名前（記号は日本語名、英大文字は「大文字」付き）を読み、フリック先・長押し文字は補足説明と操作メニューから入力できる。各配列に面の名前を付け、切替時に読み上げられるようにした。

`./gradlew testDebugUnitTest assembleDebug lintDebug`は成功。JVMテストは63件で失敗・error・skipは0件。長押しの成立と取り消し、長押し文字と通常入力の区別、連続実行の間隔、記号面の行数・重複・必須記号、読み上げ名の漏れを確認した。lintの警告は従来と同じ2件（compile/target 36）だけ。debug APKは2,663,452 bytes、SHA-256 `51c776db94a69ff68b52e29e807614777a8c5ba284c106668b1e97d8b6517a80`、`aapt dump permissions`の権限宣言は0件。

実機での長押し・連続実行の体感、誤判定率、触覚の有無、TalkBackでの読み上げと入力、記号面の表示崩れは未確認である。
