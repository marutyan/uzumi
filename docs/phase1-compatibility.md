# Phase 1a・Phase 2の互換試験

試験日：2026-09-23（22:26〜23:43）。[開発計画の共通の互換試験](../plans/development-plan.md#共通の互換試験)のうち、Pixel 10 Proの実機で試せる項目を、IMEとは別のprocessの入力欄で確かめた。重点は入力の欠落、二重入力、別の接続への書込み、変換中の文字の消失である。

利用者が端末を操作中の可能性が高いと分かったため、23:43で実機試験を打ち切った。下表の「未実行」はこのためであり、合格とは扱わない。

## 結論

実行した項目で、入力の欠落、二重入力、別の接続への書込み、変換中の文字の消失は起きなかった。IMEのコードに直すべき不具合は見つからなかった。一方、Compose TextField、ライブ変換OFFの明示変換、通常のQWERTY配列、hardware keyboardなどは未実行であり、Phase 1aの互換試験の完了とは判定しない。

## 環境

| 項目 | 値 |
| --- | --- |
| 端末 | Pixel 10 Pro（serial 57011FDCH0034X）、Android 17/API 37、build `google/blazer_beta/blazer:17/CP41.260828.004.A8/16319058:user/release-keys` |
| WebView | `com.google.android.webview` 155.0.8059.4（`dumpsys webviewupdate`の現在のprovider） |
| Uzumi | main `4f6a08f`のIMEコードと、`app/src/debug/`の試験画面を加えたdebug APK（versionName 0.1.0）。試験画面だけを直しながら3回作り直した（SHA-256 `41b61471…f9fb`、`7cebed45…a2d2`、`d38f774b…b654`）。IMEのコードはどれも同じ |
| 設定 | ライブ変換はON（既定値）。Mozcの辞書あり |
| 試験画面 | `CompatEditTextActivity`（View EditText 12欄）と`CompatWebViewActivity`（ローカルHTMLの`<input>` 9欄と`<textarea>`）。どちらもprocess `:compat`で動き、IMEとはプロセス間の接続になる。editor actionの回数と欄の値を画面に表示する |

操作は`adb shell input swipe`（押下時間を固定したタップとフリック）で送り、本文は`uiautomator dump`、WebViewの値はページ内の表示、キーボードはスクリーンショットで確かめた。入力は架空の文だけで、ネットや他人へ送られ得る欄は使っていない。スクリーンショットはリポジトリへ入れていない。

## 結果

### EditText（試験画面）

| 項目 | 操作 | 結果 | 判定 |
| --- | --- | --- | --- |
| Search | 「かんじ」→実行キー（検索） | 「感じ」が確定し、`IME_ACTION_SEARCH`が1回だけ届いた。もう一度押すと2回目が1回届いた | 合格 |
| Send | 「てすと」→送信 | 「テスト」、`IME_ACTION_SEND`が1回 | 合格 |
| Next | 「あ」→次へ | 「あ」が確定し、`IME_ACTION_NEXT`が1回、focusが次の欄へ移った | 合格 |
| Done | 「い」→完了 | 「い」、`IME_ACTION_DONE`が1回、キーボードが閉じた | 合格 |
| 複数行・改行 | 「う」→改行→「え」→改行 | 本文「う\nえ\n」。改行ごとに1つだけ | 合格 |
| number | 「120」→完了 | 数字配列が開き「120」、actionが1回 | 合格 |
| phone | 「090-1+2」→完了 | 数字配列、「090-1+2」、actionが1回 | 合格 |
| PIN（numberPassword） | 「4321」→削除→「9」 | 数字配列、本文「4329」 | 合格 |
| password | 「abc」→削除→「d」→完了 | 英字配列だけが開き、候補バーは「機密入力：候補を表示しません」。本文「abd」、actionが1回 | 合格 |
| 学習禁止（`IME_FLAG_NO_PERSONALIZED_LEARNING`） | 「かんじ」→完了 | 「感じ」、actionが1回。Mozcへ学習を送らないことはJVMテストだけで確認 | 合格（学習の抑止は実機未確認） |
| email・URL | 欄を開く | かな配列が開いた（実行キーは「次へ」「移動」）。文字の入力は試していない | 未実行（配列は後述） |
| 選択範囲の置換（全選択） | 「感」を`Ctrl+A`で選択→「い」 | 「い」だけになった | 合格 |
| 選択範囲の置換（一部） | 「あ上」の「上」を`Shift+←`で選択→「お」 | 「あ生」。選択外の「あ」は残った | 合格 |
| 変換中のフィールド切替 | 検索欄で「かん」（表示「感」）→送信欄をタップ→「あ」 | 検索欄は「感」のまま、送信欄は「あ」。旧欄へ文字は入らなかった | 合格 |
| 変換中のバックキー | 「う」の入力中にBack | キーボードが閉じ「う」が残った。欄を再度タップして「え」で「う絵」（重複なし） | 合格 |
| 変換中のアプリ切替 | 「お」の入力中にHome→ランチャーと同じflagで試験画面へ戻る→「か」 | 「お」が残り、続けて「おか」 | 合格 |
| 変換中のIME切替 | 「き」（表示「気」）の入力中にGboardへ切替→Uzumiへ戻す→「く」 | 切替後も「気」が残り、続けて「気区」 | 合格 |
| 変換中の回転 | 複数行欄で「けさ」（表示「今朝」）→横向き→縦向き→「こ」 | 横向きで「今朝」が残り、戻した後に「今朝子」（重複なし） | 合格 |
| プロセスの再作成 | 「さし」（表示「指し」）の入力中にIMEのprocessを`kill -9` | `am kill`ではIMEのprocessは終了しなかった（前面のIMEのため）。`run-as`での`kill -9`後、IMEは新しいprocessで再表示され、「指し」が残った。続けて「す」で「指しす」、検索で1回 | 合格 |
| 12-key | 上の各項目 | かなのタップ・フリック・「小゛゜」で入力できた | 合格 |
| 数字配列 | number・phone・PIN | 入力できた | 合格 |
| QWERTY（password欄） | password欄 | 入力・削除・完了ができた | 合格 |
| QWERTY（通常の欄） | かな配列から「あ/A」 | 操作が前面のアプリへ届いたか確かめられず、結果なし | 未実行 |
| ライブ変換ON | 上の各項目 | 入力ごとに変換表示が変わり、actionと改行で確定した | 合格 |
| ライブ変換OFF（明示変換） | — | — | 未実行 |
| 書式を整える電話番号欄、最大文字数の欄 | 試験画面へ追加したが実機では未試験 | — | 未実行 |

### WebView（ローカルHTML）

| 項目 | 操作 | 結果 | 判定 |
| --- | --- | --- | --- |
| `<input type=search enterkeyhint=search>` | 「かんじ」→実行キー | 値「感じ」、Enterとformの送信が各1回 | 合格 |
| `enterkeyhint=send` | 「てすと」→実行キー | 値「テスト」、Enterと送信が各1回 | 合格 |
| `enterkeyhint=next` | 「あ」→次へ→「い」 | Enterが1回届き、focusは次の欄へ移らず、「い」は同じ欄に入って「あい」 | 要確認（下記） |
| `<textarea>` | 「う」→改行→「え」→削除2回 | 「う\nえ」→「う」。改行のEnterは1回 | 合格 |
| 選択範囲の置換 | textareaを`Ctrl+A`→「く」→改行 | 「区\n」。Enterは通算2回（押した回数と一致） | 合格 |
| `type=password` | 「ab」→削除→「c」 | 英字配列、値「ac」 | 合格 |
| `type=tel` | 「03-1」 | 数字配列、値「03-1」 | 合格 |
| `inputmode=numeric` | 「42」 | 数字配列、値「42」 | 合格 |
| `enterkeyhint=done`、`type=email`、`type=url` | — | — | 未実行 |
| 変換中のフィールド切替 | — | 試験中にLINEが前面へ出たため結果なし | 未実行 |

WebViewの「次へ」は、Uzumiが`performEditorAction`を1回だけ送り、ページにはEnterキーとして届いた（ページの回数表示）。Chromiumは`enterkeyhint`を指定した欄で実行キーをEnterとして渡し、focusの移動をページに任せる（推測。Gboardでの比較は未実行）。EditTextのNextはfocusが移ったため、Uzumiが送るaction自体は正しいと判断した。

### 他のアプリ・その他

| 項目 | 判定 | 理由 |
| --- | --- | --- |
| Compose TextField | 未実行 | 端末内で完結する既存アプリ（時計のアラーム名、連絡先など）を探す前に試験を打ち切った。依存を増やさないため試験画面は作っていない |
| Chrome | 未実行 | 入力がネットへ送られ得るため使わない方針 |
| split screen、hardware keyboard（`Ctrl+A`・`Shift+←`以外）、低メモリ、入力中のモデル切断 | 未実行 | 時間内に試していない |
| TalkBack、長押し・連続削除の体感 | 未実行 | 本試験の対象外（キー入力の担当） |

## 観察した挙動（不具合としては直していない）

- email・URLの欄でかな配列が開く。住所形式の欄では英字配列を開く方が自然だが、配列の選択はキーボード側（`keyboard/`）の処理で、この作業の書込み範囲外。
- number・phoneの欄でも数字をcompositionとして送り、候補バーに「5 かな」のようなかな候補が出る。数字配列には変換キーが無いのに、案内は「変換キーで漢字候補」と表示される。今回の欄では欠落・重複は起きなかった。書式を整える欄（`PhoneNumberFormattingTextWatcher`）との組合せは未試験。
- 最初の試験画面ではeditor actionのlistenerが常に`false`を返し、検索を1回押すと同じ欄のlistenerが2回呼ばれ、focusも次の欄へ移った。TextViewが消費されなかったactionの後に代替のEnterキーを送るためと考えられる（推測。Gboardでの比較は未実行）。Search・Send・Goを消費する形に直した後は、押すごとに1回だけになった。

## 既定IMEの復元

開始時（22:23）の`default_input_method`は`com.adamrocker.android.input.simeji/.OpenWnnSimeji`、`enabled_input_methods`はSimeji、Google音声入力、Gboard、Uzumiの4件だった。試験のまとまりごとに両方を書き戻し、`settings get`の出力が開始時に保存した出力と`cmp`でbyte単位で一致することを5回確かめた（22:38、23:29、23:36、23:41、23:43）。23:29の回は、試験画面の欄が見つからずscriptが最初の操作で止まった（画面が消灯していた可能性がある。推測）。試験で入れたdebug APKは端末に残っている。

試験中の確定でMozcの学習履歴に架空の語（「感じ」「テスト」など）が残っている。試験前の学習履歴は保存しておらず、元へ戻していない。
