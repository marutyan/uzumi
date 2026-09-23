# ライブ変換のIME接続と検証

確認日：2026-09-23。[ライブ変換の状態設計](live-conversion-design.md)の状態機械`dev.uzumi.ime.live.LiveConversionCore`を、IMEの編集セッションとMozc（公式`google/mozc` commit `13c98988247aa711d99db9e348ec2a597d14b5cd`、[明示変換の記録](mozc-conversion-validation.md)と同じ生成物）へ接続した。ライブ変換ONでは、打つたびに変換結果が表示され、句点・改行・Editor actionで確定操作なしに確定する。過去segmentの訂正、取り消し、ユーザー辞書の登録語の候補、設定画面のON/OFFと第三者ライセンス表示を加えた。

## 構成

| 役割 | 実装 | 要点 |
| --- | --- | --- |
| Editorへの書込み | `EditorSession.applyLiveUpdate` | コアの`EditorCommand`（`SetComposition`、`Commit`、`PerformEditorAction`）をInputConnectionへ送る唯一の経路。`SetComposition`は明示変換と共通の`writeComposition`で送り、selection通知の照合に使う期待値を同じ規則で記録する。書込みに失敗したら接続を閉じ、同じ本文を再送しない。 |
| 入力欄ごとの切替 | `InputFieldPolicy.usesLiveConversion`、`UzumiSettings` | 入力開始ごとに設定を読み、ONかつ通常の文字欄ならライブ変換、それ以外（OFF、password、`TYPE_NULL`、数字・電話・日時、URL、メール）は従来の明示変換にする。学習禁止欄はライブ変換を使い、要求へ学習禁止を付ける。 |
| 範囲分割 | `SegmentedLiveConverter` | 要求の対象範囲を、保護範囲（stable/chosen、Undoで戻した範囲、カーソルを含む範囲）と対象範囲内の入力カーソルで区切り、自由な部分範囲ごとに変換する。各範囲の境界が必ずsegment境界になるため、コアの照合で結果が捨てられない。ASCIIだけの範囲はMozcへ送らず入力のまま表示する。 |
| Mozcの変換 | `MozcConversionEngine.convertSegments` | 部分範囲の読みを`AS_IS`で積み（`Input.request_suggestion=false`で入力途中の予測を求めない）、`SPACE`で変換する。preeditの各文節の`key`と`value`を読み、focusを`RIGHT`で右へ動かしながら、強調された文節の`all_candidate_words`から候補を取る。`key`が文節の読みと異なる候補（予測）と`num_segments_in_candidate`が2以上の候補（複数文節）は除く。確定しないため学習は起きない。文節の読みの連結が範囲と一致しない場合は、範囲全体を一segmentにする。 |
| 学習 | `ConversionWorker.learnCommitted`、`MozcConversionEngine.learnSegments` | 確定したsegment列を、句読点・未変換・ASCIIのsegmentで区切った単位ごとに学習させる。単位の読みを変換し、文節の幅をShift+右・左で伸縮して区切りを合わせ、表記が違う文節は`SELECT_CANDIDATE`で選んでから`SUBMIT`する。合わせられなければ`REVERT`して確定せず、segmentごとに学習し直す。自動変換、候補の選択、Undoだけでは学習させない。学習禁止欄では送らない。 |
| 直列worker | `ConversionWorker` | 明示変換と同じ単一threadだけがJNIを呼ぶ。ライブ変換も未処理の要求は最新の一件だけを変換する。学習と`endSession`は呼び出し時点で終了判定し、同じ世代のsession破棄より先にキューへ積むため、Send直後に欄が閉じても確定した内容を学習できる。 |
| ユーザー辞書 | `UserDictionaryCandidates` | 各segmentの読みに完全一致する登録語を候補の先頭へ置き、Mozc候補との重複を除く。部分範囲全体の読みに一致する語があれば範囲を一segmentにまとめて全体の候補として出す。表示（第一候補）はMozcの結果のまま。明示変換では先頭文節と読み全体に一致する語を先頭へ置き、確定してもMozcへ通知しない。辞書はworkerの初期化時に開き、UIスレッドで初めて読まない。 |
| 候補バー | `UzumiInputMethodService.refreshLiveCandidates` | 対象segmentの候補を常時表示し、現在の表記を太字にする。左右に「◀」「▶」（前後のsegmentへ。読点は飛ばす）、「末尾」（入力位置へ戻る）、「取消」（直前の操作を取り消す）を置く。訂正中のsegmentはEditor上で背景色を付ける。変換済み表示の内部をEditorでタップすると、そのsegmentを対象にしてカーソルを入力位置へ戻す。変換キーは対象segmentの次の候補を選ぶ。 |
| 設定・表示 | `MainActivity`、`LicenseActivity` | ライブ変換のON/OFF（既定ON）をSharedPreferencesへ保存する。第三者ライセンスはAPKのassets`licenses/mozc-NOTICE.txt`を原文のまま表示する。 |

Mozcが使えない間（読み込み中・辞書なし）は要求を送らず、部分範囲ごとに読みとカタカナを候補にする。機密欄では明示変換と同じく要求を作らない。

明示変換（OFF）の表示中に削除すると、読み末尾の書記素を消し、残りの読みを変換し直す（「良い」→「よ」→再変換）。ライブ変換では、変換済みsegmentの末尾の削除はそのsegmentだけを読みへ戻して再変換する（コアの既存規則）。

## ローカル検証

```sh
./gradlew clean testDebugUnitTest assembleDebug lintDebug
```

結果は成功。JVMテストは164件（起点`7360320`の134件、追加30件）で、失敗・error・skipは0件。lintはerror 0件、警告4件（既存のcompile/target 36指定の2件、protobufの新しい版の2件）で起点と同じ。

| テスト | 件数 | 守る内容 |
| --- | ---: | --- |
| `LiveConversionAdapterTest` | 9 | 保護範囲とカーソルでの分割、範囲の失敗、コアとの結合で候補選択・カーソル移動後も結果が拒否されないこと、Mozc文節の写像と読み不一致時の境界維持、登録語の合成（segment、範囲全体、明示変換）、学習単位の区切り |
| `MozcConversionEngineTest` | 3 | ライブ変換の命令列（予測なしの文字入力、`RIGHT`での各文節の候補、予測・複数文節候補の除外、確定しない）、学習時の幅の伸縮・候補選択・`SUBMIT`、表記を選べないときの`REVERT` |
| `ConversionWorkerTest` | 5 | ライブ変換の最新要求だけの処理とincognitoの先行指定、保護範囲とASCII範囲をMozcへ送らないこと、登録語の合成（ライブ・明示）、学習がsession破棄より先に走り、合わせられない単位をsegmentごとに学習し直すこと |
| `EditorSessionTest` | 10 | 入力ごとの変換表示と句点での確定・学習、「良い」の末尾削除と再変換、過去segmentの訂正・強調・取り消し・末尾復帰、変換済み表示内のタップ、Enterの確定とaction一回・改行一回、password欄で要求なし・学習禁止欄で学習なし、エンジンなしのかな・カナ候補、古い結果の破棄、登録語の確定を通知しないこと、明示変換の表示中の削除と再変換 |
| `InputFieldPolicyTest` | 1 | 設定と欄の種類によるライブ変換の使い分け |
| `LiveConversionCoreTest` | 2 | 書込みなしの破棄と保留中の結果の拒否、訂正中の判定 |

## APK

生成物を指定して作ったdebug APKは34,547,793 bytes、SHA-256は`866fa2a7a8c2b5ef201be408de659251379f8e56795fd1ebde22fe210f059aee`（commit `b7d4049`の作業ツリーから作成。以後の変更は文書だけ）。明示変換の記録のAPK（34,110,290 bytes）から約0.4 MB増えた。今回から`third_party/mozc/NOTICE.txt`がassetsの`licenses/mozc-NOTICE.txt`に入り、アプリ内の「第三者ライセンス」で表示される。manifestへの権限の追加は無い。

## 実機での確認

Pixel 10 Pro（Android 17/API 37）、2026-09-23 21:04〜21:15。開始時の`default_input_method`は`dev.uzumi.ime/.UzumiInputMethodService`、`enabled_input_methods`はSimeji、Google音声入力、Gboard、Uzumiの4件だった（明示変換の記録時の既定IMEはSimejiであり、開始前に誰かが変えていた。変更者は不明）。APKの上書きで既定IMEが一時的にGboardへ変わったため、試験中はUzumiを選んだ。入力は架空の文だけで、Uzumiの試用画面・ユーザー辞書画面と、Wi-Fiのpassword欄（接続せず取り消し）だけを使った。確認は`uiautomator dump`（IMEの窓は含まれないため、本文だけ）とスクリーンショットで行い、スクリーンショットはリポジトリへ入れていない。キーは同じ座標の`input swipe`（60 ms）で押下時間を固定して押し、フリックは`input swipe`で送った。

| 対象 | 操作 | 結果 |
| --- | --- | --- |
| ライブ変換の表示 | 試用欄で「きょうはいいてんきですね」を一文字ずつ入力 | 打つたびに表示が変わり、最後は下線付きの「今日はいい天気ですね」。候補バーは末尾segment「いい天気ですね」の候補（太字が現在の表記）と「◀」「▶」「末尾」「取消」。 |
| 過去segmentの訂正とUndo | 「◀」→候補「橋は」→「取消」→「末尾」 | 「◀」で「今日は」が背景色で強調され、候補「今日は」「鏡は」「橋は」等に変わった。「橋は」で「橋はいい天気ですね」、「取消」で「今日はいい天気ですね」へ戻った。 |
| 句点での確定 | 「。」 | 確定操作なしに「今日はいい天気ですね。」が確定し、下線が消えた。 |
| 末尾の削除 | 「よい」→「良い」の後に削除キーを一回 | 「よ」になり（一文字だけ消え、連続削除は起きなかった）、「よ」を再変換した結果もMozcの第一候補「よ」だった。 |
| 文節の訂正 | 改行後「こうえんにいく」→「◀」→「校園に」→「末尾」→「よ」→改行 | 「公園に行く」の「公園に」を「校園に」へ直し、末尾で「よ」を続けても「校園に行くよ」のまま保たれ、改行で確定した。 |
| 変換キー | ライブ変換中の「かんじ」で変換キー | 対象segmentの次の候補「幹事」を選んだ。 |
| ユーザー辞書 | 辞書画面で「うずみ」→「UzumiTest」を登録し、試用欄で「うずみ」 | 候補の先頭に「UzumiTest」、続いて「うずみ」「ウズミ」「渦見」。表示はMozcの結果のまま。 |
| ライブ変換OFF | 設定をOFFにし、別画面へ移って戻ってから「かんじ」→変換 | 入力中は「かんじ」のまま、変換キーで「幹事」になった（この試験中の学習で先頭が変わっていた）。同じ欄でキーボードを閉じて開き直しただけでは入力の開始にならず、切替は反映されなかった。 |
| 第三者ライセンス | 設定画面の「第三者ライセンス」 | NOTICEの本文（Mozc、辞書、protobuf等）が等幅で表示された。 |
| password欄 | Wi-Fiのpassword欄で「abc」 | QWERTYだけが出て、候補バーは「機密入力：候補を表示しません」のまま。ライブ変換の操作ボタンも出なかった。要求を送らないことはJVMテストで確認した。取り消して接続していない。 |
| 確定時の学習 | 上記の確定の後 | Mozcのprofileの`segment.db`が確定後に更新された。「かんじ」「うずみ」の選択は次の変換で先頭になった。一方、「校園に行くよ」を確定した後に「こうえんにいく」を打つと「公園に」のままだった（文脈が違う読みでは学習が効かないのか、学習の単位が合わなかったのかは未確認）。 |

## 試験後の復元

アプリを停止してから、試験で作ったユーザー辞書のファイル（開始時は無かった）と設定のファイル（同じく無かった）を消した。Mozcのprofileは開始時にアプリ内へ複製しておいたものへ戻し、試験中の学習を消した（戻した後のファイルの時刻は開始時と同じ）。`enabled_input_methods`と既定IMEを開始時の値へ書き戻し、`settings get`の出力が開始時に保存した出力とbyte単位で一致することを`cmp`で確認した。最終APKは端末に残っている。

## 未確認・既知の制限

- 訂正中のsegmentへ背景色を付けると、試用欄ではcompositionの下線が表示されなかった。確定・入力の動作は変わらない。
- 文脈を含む読み（「こうえんにいく」）での学習の効き方。学習の単位ごとの成否はログに出していない。
- 設定の切替は次の入力開始から反映する。同じ欄でキーボードを閉じて開いただけでは反映されない。
- Compose TextField、WebView、回転、プロセス再作成、長文での変換時間とメモリ使用量、TalkBackでの候補バーの操作。
- 学習禁止欄（`IME_FLAG_NO_PERSONALIZED_LEARNING`）での実機確認（JVMテストだけ）。
- 数字・英字・記号が混ざる読みのライブ変換。ASCIIだけの範囲はMozcへ送らないが、混在する範囲はMozcの正規化によって範囲全体が一segmentになる場合がある。
- 登録語は候補の先頭に出るだけで、自動では表示されない（表示は常にMozcの第一候補）。
