# Android IMEの公式仕様と制約

確認日：2026-09-20。公式一次資料による調査。実機の互換性・性能は未検証。Android 11/API 30を暫定下限とし、性能上必要なら引上げ可能、INTERNET権限なしを優先する方針はユーザー確認済み。

## 結論

ライブ変換はIME内の状態管理とcomposing textで構成できる。ただし「任意アプリの本文segmentを直接タップ」「確定済み文章を安全にUndo」「パスワードのClipboard保存を完全防止」は全アプリ共通の保証にできない。所有するcompositionを確実な編集範囲とし、IME内にもsegment選択の入口を設ける。

## APIの基準

| API | 確認できた機能・制約 |
|---|---|
| 30 / Android 11 | Inline Autofillを利用可能。暫定下限 |
| 31 | `getSurroundingText()`。API 30では前後・選択文字列を個別に取得 |
| 33 | `TextAttribute`付き編集API。`takeSnapshot()`はIMEから直接呼ぶと常にnull |
| 34 | `replaceText()`。返値trueは送信を示し、置換成功を保証しない |
| 35以降 | Credential ManagerとAutofillの連携。アプリ・provider対応に依存 |
| 37 / Android 17 | 現行公式資料の互換対象。回転時IME表示と物理キーボードのアクセシビリティに変更 |

Android 17 QPR2の公式資料は2026-09-15のBeta 5と、Android 17初期リリース後であることを記載する。QPR2 betaは補助試験とし、初期実装の必須依存にしない。実装開始時にstable SDKと配布端末の状況を再確認する。[Android 17 SDK](https://developer.android.com/about/versions/17/setup-sdk)、[QPR2 release notes](https://developer.android.com/about/versions/17/qpr2/release-notes)

## compositionと接続

`InputMethodService`と`InputConnection`が基本となる。`setComposingText()`はcomposing領域を置換し、`finishComposingText()`は表示を残してcomposing状態を終了する。`setComposingRegion()`で別領域を指定すると従来領域のcomposing状態は解除される。読み・日本語segment・ユーザー選択状態はIMEが保持する必要がある。[InputConnection](https://developer.android.com/reference/android/view/inputmethod/InputConnection)

**提案：** Androidへは一つの連続したcompositionとして表示し、内部にはreading、segment一覧、表示、active segment、履歴を持つ。安定segmentは内部の自動変更禁止状態とし、Androidへ確定済みの状態とは分ける。

結果適用時はsession、revision、辞書世代、対象segment、明示選択状態を照合する。`beginBatchEdit()`は通知・表示の中間状態を抑えるために使い、transactionや排他制御とは扱わない。取得不能・接続無効時は推測した範囲を書き換えない。

## 本文タップと過去segment

`onViewClicked()`はAPI 29で非推奨で、公式資料はWebView等で呼ばれないことを示す。`onUpdateSelection()`は選択範囲の通知であり、生タップの意味を伝える保証ではない。`CursorAnchorInfo`も任意アプリのタップ通知の代替にはならない。[InputMethodService](https://developer.android.com/reference/android/inputmethodservice/InputMethodService)

**提案：** 候補バーに接続したsegment選択UIを確実な入口とする。selectionが所有中composition内にあり、offset・内容の一致が確認できる場合は対応segmentを選択する。外部変更、範囲選択、composition消失時には勝手に復元しない。

Chrome/WebView/Compose等でタップ後にcompositionが維持されるかは不明。Phase 1で確認する。

## Undoとalignment

周辺文字列取得には失敗・欠落・遅延があり、文書全体を読める保証はない。アプリの編集コンテキスト変更には`restartInput()`/`invalidateInput()`が関わる。[InputMethodManager](https://developer.android.com/reference/android/view/inputmethod/InputMethodManager)

**提案：** MVPのUndo/Redoは同じ接続の所有中composition内に限定する。候補選択、segment訂正、読み入力、削除を操作単位とし、自動変換は起点の入力へまとめる。commit・外部編集・接続喪失・再生成で履歴を区切る。確定後Undoは将来検討とし、Androidアプリ側の履歴と同一動作とは約束しない。

Android offsetはUTF-16として扱う。内部では読み境界と表示書記素を分ける。`deleteSurroundingTextInCodePoints()`だけでZWJ絵文字等を一文字として扱えるわけではない。ICUの`BreakIterator`による境界を利用する案とする。[InputConnectionWrapper](https://developer.android.com/reference/android/view/inputmethod/InputConnectionWrapper)、[BreakIterator](https://developer.android.com/reference/android/icu/text/BreakIterator)

「よい→良い」は読み・表示の長さが異なる。末尾削除は読みを編集し、内部alignmentが不明な位置は対象segmentを読みへ戻す案を検証する。文字数比で削除位置を推定しない。結合濁点・サロゲート・ZWJ・異体字セレクタ・範囲選択を必須ケースに含める。

## inputTypeと機密入力

password判定は通常・表示型・Web型・数値型のvariationを含める。`IME_FLAG_NO_PERSONALIZED_LEARNING`は学習や履歴を更新しない要求。`IME_FLAG_NO_ENTER_ACTION`はEnterをactionへ置換しない要求であり、multi-lineも考慮する。[InputType](https://developer.android.com/reference/android/text/InputType)、[EditorInfo](https://developer.android.com/reference/android/view/inputmethod/EditorInfo)

**提案：** password/PIN・sensitive・no-learningでは、学習・履歴・ニューラル文脈を停止する安全側の方針とする。通常欄から移ると待機中の処理と一時文脈を破棄する。機密欄ではClipboard履歴の取込みを止め、手動プライベートモードも検討する。入力本文はログに残さない。

アプリが秘密情報を通常テキストとして渡した場合、完全な検知はできない。PINと一般数値の区別もinputType等に依存する。Send/Search/Doneはユーザーが選んだactionに限り送信し、句点による変換確定からSendを発火させない。

## Inline Autofillとpasskey

Android 11以降では`supportsInlineSuggestions`、`onCreateInlineSuggestionsRequest()`、`onInlineSuggestionsResponse()`、`InlineSuggestion.inflate()`によりproviderのUIを表示する。候補の内容は選択前にIMEへ公開されない。非対応時は従来のAutofill表示へfallbackする。[IME Autofill](https://developer.android.com/identity/autofill/ime-autofill)

IMEは表示役であり、他アプリのpasskeyをIME自身の`getCredential()`で収集しない。Credential Managerとの連携は対象アプリ側の入力欄設定を含む。確認した公式ページはViewの制約を記載しており、Compose Autofill一般対応と同一視しない。[Credential ManagerとAutofill](https://developer.android.com/identity/autofill/credential-manager-autofill)

**提案：** password欄でも公式Inline候補は表示可能にし、provider Viewを解析・記録しない。候補バーとの共存と切替を試験する。username/password/address/passkeyの出現はOS・アプリ・providerの組合せで確認し、IMEだけでは保証しない。

## Clipboard

Android 10以降でもdefault IMEはClipboardアクセス制限の例外。APIが返すのは現在のprimary clipであり、OS内の過去履歴を列挙するAPIではない。履歴は観測できたclipを自前で保持する機能になる。プロセス停止中の履歴まで取得できるとは約束しない。[Android 10 privacy](https://developer.android.com/about/versions/10/privacy/changes)、[ClipboardManager](https://developer.android.com/reference/android/content/ClipboardManager)

`EXTRA_IS_SENSITIVE`は表示ヒントであり、アクセスや保存の禁止を強制しない。未付与を非機密の証明にしてはいけない。Android 13以降のOS自動消去はIME独自の履歴を削除しない。[ClipDescription](https://developer.android.com/reference/android/content/ClipDescription)、[Secure Clipboard Handling](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling)

**提案・要確認：** sensitive指定は保存しない。機密欄・プライベートモードでは収集停止。自動履歴は既定OFFとし、上限・期限・重複排除・個別/全削除・backup除外を用意する。ただし有効化後も未指定の秘密を完全に除外できない。「パスワードを絶対保存しない」を優先するなら未分類clipの自動永続保存をしない必要があり、Phase 4前にユーザーと仕様を決める。

## INTERNETなしの配布

`INTERNET`はnetwork socketの権限。Storage Access Frameworkではユーザーが選んだファイルURIへアクセスできる。[Network permissions](https://developer.android.com/develop/connectivity/network-ops/managing)、[Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)

**提案：** 辞書・モデルを同梱し、APK更新またはユーザーが選択したファイルで更新する。形式・サイズ・version・hashを検証し、配布元を信頼する仕組みとして署名または既知のhash一覧を検討する。任意ファイルの自己申告hashだけでは配布元を保証できない。

権限なしは端末外へ情報が絶対出ない証明ではない。OS backupや外部Intentは別の経路であり、学習・Clipboard等をbackup ruleでも除外する。[Auto Backup](https://developer.android.com/identity/data/autobackup)

## UIとlifecycle

旧`KeyboardView`はAPI 29で非推奨であり、新規UI基盤にそのまま採用しない。[KeyboardView](https://developer.android.com/reference/android/inputmethodservice/KeyboardView)

入力sessionとViewの表示lifecycleを分け、回転・高さ・片手モードでcontent/touchable insetsを更新する。hardware keyboardの未処理キーをアプリへ渡す。process再生成後は古い本文をディスクから再挿入しない。

Android 17には回転後のIME表示復元の変更がある。また`TextAttribute.Builder.setTextSuggestionSelected()`で明示候補選択をアクセシビリティへ伝えられる。[全アプリ変更](https://developer.android.com/about/versions/17/behavior-changes-all)、[target変更](https://developer.android.com/about/versions/17/behavior-changes-17)

Android 17はtargetSdkにかかわらず、端末RAMに応じたapp memory limitsを導入する。モデル重みだけでなく辞書・native bufferとの合計で評価し、対象端末で`adb shell am memory-limiter status`の制限状態を記録する。制限による終了は`ApplicationExitInfo`の`REASON_OTHER`とdescription内の`MemoryLimiter:AnonSwap`で識別できる。通常のPSS/RSS測定と合わせ、これを採用試験の失敗として記録する。制限を無効化した結果で常用可能と判定しない。[App memory limits](https://developer.android.com/about/versions/17/behavior-changes-all)

## 検証範囲

API 30と最新安定版を重点に、31/32、33、34、35/36、37のAPI差を確認する。全組合せを実機で網羅したと主張せず、端末試験とemulatorを区別する。

View EditText、Compose TextField、WebView、Chrome、メッセージ・検索・ログインを対象とする。連続入力、範囲置換、composition内外への移動、アプリ側整形、Autofill、回転、IME切替、process再生成を試験する。通常・multi-line・数字・小数・電話・URI/email・password各種・no-learning・`TYPE_NULL`を含める。

入力後の推論中に削除/Undo/候補選択/フィールド移動し、古い結果の採用0件を確認する。押下表示、Editor表示、候補表示、推論完了を分離計測する。周辺取得の同期IPCを毎キー実行する設計を避ける。

未確認：実機機種/RAM、各Editorのcomposition維持、latency・電池・メモリ。この調査は実機互換性の証明ではない。
