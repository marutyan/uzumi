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
| ユーザー辞書 | `UserDictionaryCandidates` | 各segmentの読みに完全一致する登録語を登録順に候補の先頭へ置き、最初の語を表示する（自動表示）。Mozc候補との重複は除く。部分範囲全体の読みに一致する語があれば、範囲を一segmentにまとめてその語を表示する。明示変換では、読み全体に一致する語があればその最初の語を読み全体の一文節として表示し、読み全体と先頭文節に一致する語を候補の先頭へ置く。明示変換では、登録語を選ぶか表示のまま確定してもMozcへ通知しない。ライブ変換では、登録語の表記がMozcの候補にも無ければその単位は学習させない。候補にある場合（例：「はし」→「箸」を登録）は、他の確定と同じく学習させる。辞書はworkerの初期化時に開き、UIスレッドで初めて読まない。 |
| 候補バー | `UzumiInputMethodService.refreshLiveCandidates` | 対象segmentの候補を常時表示し、現在の表記を太字にする。左右に「◀」「▶」（前後のsegmentへ。読点は飛ばす）、「末尾」（入力位置へ戻る）、「取消」（直前の操作を取り消す）を置く。訂正中のsegmentはEditor上で背景色を付ける。変換済み表示の内部をEditorでタップすると、そのsegmentを対象にしてカーソルを入力位置へ戻す。変換キーは対象segmentの次の候補を選ぶ。 |
| 設定・表示 | `MainActivity`、`LicenseActivity` | ライブ変換のON/OFF（既定ON）をSharedPreferencesへ保存する。第三者ライセンスはAPKのassets`licenses/mozc-NOTICE.txt`を原文のまま表示する。 |

Mozcが使えない間（読み込み中・辞書なし）は要求を送らず、部分範囲ごとに読みとカタカナを候補にする。機密欄では明示変換と同じく要求を作らない。

明示変換（OFF）の表示中に削除すると、従来どおり読み末尾の書記素を消して表示を読みへ戻し、自動では再変換しない（「良い」→削除→「よ」）。独立レビューで、自動の再変換の結果が次の文字の入力で確定される不具合（「良い」→削除→「夜」→「る」で「夜」が確定）が見つかったため、この動作に戻した。ライブ変換では、変換済みsegmentの末尾の削除はそのsegmentだけを読みへ戻して再変換する（コアの既存規則）。

selection通知の照合に残す過去のcompositionの数（`EditorSession.MAX_STALE_COMPOSITIONS`）は8から32へ増やした。ライブ変換では一打鍵で読みの表示と変換結果の表示の2回（カーソルが末尾以外なら位置の指定を加えて最大4回）書き込むため、8では2〜4打鍵分の遅れしか照合できない。照合できない遅延通知は外部の変更とみなしてcompositionを確定・破棄するので、速い連続入力で誤って確定しないようにした。

## ローカル検証

```sh
./gradlew clean testDebugUnitTest assembleDebug lintDebug
```

結果は成功。JVMテストは165件（起点`7360320`の134件、追加31件）で、失敗・error・skipは0件。lintはerror 0件、警告4件（既存のcompile/target 36指定の2件、protobufの新しい版の2件）で起点と同じ。

| テスト | 件数 | 守る内容 |
| --- | ---: | --- |
| `LiveConversionAdapterTest` | 9 | 保護範囲とカーソルでの分割、範囲の失敗、コアとの結合で候補選択・カーソル移動後も結果が拒否されないこと、Mozc文節の写像と読み不一致時の境界維持、登録語の合成と表示（segment、範囲全体、明示変換の読み全体と先頭文節）、学習単位の区切り |
| `MozcConversionEngineTest` | 3 | ライブ変換の命令列（予測なしの文字入力、`RIGHT`での各文節の候補、予測・複数文節候補の除外、確定しない）、学習時の幅の伸縮・候補選択・`SUBMIT`、表記を選べないときの`REVERT` |
| `ConversionWorkerTest` | 5 | ライブ変換の最新要求だけの処理とincognitoの先行指定、保護範囲とASCII範囲をMozcへ送らないこと、登録語の合成と表示（ライブ・明示）、学習がsession破棄より先に走り、合わせられない単位をsegmentごとに学習し直すこと |
| `EditorSessionTest` | 11 | 入力ごとの変換表示と句点での確定・学習、「良い」の末尾削除と再変換、過去segmentの訂正・強調・取り消し・末尾復帰、変換済み表示内のタップ、Enterの確定とaction一回・改行一回、password欄で要求なし・学習禁止欄で学習なし、エンジンなしのかな・カナ候補、古い結果の破棄、登録語の候補・表示を確定してもエンジンへ通知しないこと、明示変換の表示中の削除で読みへ戻り再変換せず、続く入力で何も確定しないこと（回帰） |
| `InputFieldPolicyTest` | 1 | 設定と欄の種類によるライブ変換の使い分け |
| `LiveConversionCoreTest` | 2 | 書込みなしの破棄と保留中の結果の拒否、訂正中の判定 |

## APK

実機試験に使ったdebug APKは34,547,793 bytes、SHA-256は`866fa2a7a8c2b5ef201be408de659251379f8e56795fd1ebde22fe210f059aee`（commit `b7d4049`の作業ツリーから作成）。レビュー指摘の修正後のAPKは末尾の「レビュー指摘の修正」に記す。明示変換の記録のAPK（34,110,290 bytes）から約0.4 MB増えた。今回から`third_party/mozc/NOTICE.txt`がassetsの`licenses/mozc-NOTICE.txt`に入り、アプリ内の「第三者ライセンス」で表示される。manifestへの権限の追加は無い。

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
| ユーザー辞書 | 辞書画面で「うずみ」→「UzumiTest」を登録し、試用欄で「うずみ」 | 候補の先頭に「UzumiTest」、続いて「うずみ」「ウズミ」「渦見」。この時点の実装では表示はMozcの結果のままだった（修正後の動作は末尾に記す）。 |
| ライブ変換OFF | 設定をOFFにし、別画面へ移って戻ってから「かんじ」→変換 | 入力中は「かんじ」のまま、変換キーで「幹事」になった（この試験中の学習で先頭が変わっていた）。同じ欄でキーボードを閉じて開き直しただけでは入力の開始にならず、切替は反映されなかった。 |
| 第三者ライセンス | 設定画面の「第三者ライセンス」 | NOTICEの本文（Mozc、辞書、protobuf等）が等幅で表示された。 |
| password欄 | Wi-Fiのpassword欄で「abc」 | QWERTYだけが出て、候補バーは「機密入力：候補を表示しません」のまま。ライブ変換の操作ボタンも出なかった。要求を送らないことはJVMテストで確認した。取り消して接続していない。 |
| 確定時の学習 | 上記の確定の後 | Mozcのprofileの`segment.db`が確定後に更新された。「かんじ」「うずみ」の選択は次の変換で先頭になった。一方、「校園に行くよ」を確定した後に「こうえんにいく」を打つと「公園に」のままだった（文脈が違う読みでは学習が効かないのか、学習の単位が合わなかったのかは未確認）。 |

## 試験後の復元

アプリを停止してから、試験で作ったユーザー辞書のファイル（開始時は無かった）と設定のファイル（同じく無かった）を消した。Mozcのprofileは開始時にアプリ内へ複製しておいたものへ戻し、試験中の学習を消した（戻した後のファイルの時刻は開始時と同じ）。`enabled_input_methods`と既定IMEを開始時の値へ書き戻し、`settings get`の出力が開始時に保存した出力とbyte単位で一致することを`cmp`で確認した。最終APKは端末に残っている。

## 未確認・既知の制限

- 訂正中のsegmentへ背景色を付けると、試用欄ではcompositionの下線が表示されなかった。確定・入力の動作は変わらない。
- 文脈を含む読み（「こうえんにいく」）での学習の効き方は、末尾の「文脈が違う読みでの学習」で原因を確かめ、IME側の学習で補った。
- 設定の切替は次の入力開始から反映する。同じ欄でキーボードを閉じて開いただけでは反映されない。
- Compose TextField、WebView、回転、プロセス再作成、長文での変換時間とメモリ使用量、TalkBackでの候補バーの操作。
- 学習禁止欄（`IME_FLAG_NO_PERSONALIZED_LEARNING`）での実機確認（JVMテストだけ）。
- 数字・英字・記号が混ざる読みのライブ変換。ASCIIだけの範囲はMozcへ送らないが、混在する範囲はMozcの正規化によって範囲全体が一segmentになる場合がある。
- ライブ変換で登録語を表示したまま確定すると、その単位のMozcへの学習は表記を合わせられず取り消される（登録語はMozcの辞書に無いため）。登録語以外の隣のsegmentは、segmentごとの学習し直しで学習される。

## レビュー指摘の修正（2026-09-23）

独立レビューの指摘を受け、commit `ad7aeb5`で次を直した。

- 明示変換の表示中の削除で、自動の再変換をやめた。表示は読みへ戻るだけにした（回帰テスト`explicitDeleteDuringConversionReturnsToReadingWithoutReconverting`を追加）。
- 読みが完全に一致する登録語は、登録順で最初の語を表示するようにした。対象はライブ変換のsegmentと部分範囲、明示変換の読み全体。明示変換で登録語を表示したまま確定しても、Mozcへは通知しない。ライブ変換では、登録語の表記がMozcの候補にもある場合だけ学習される。
- `MAX_STALE_COMPOSITIONS`を8から32にした（理由は「構成」の節）。

`./gradlew clean testDebugUnitTest assembleDebug lintDebug`は成功した。JVMテストは165件で失敗0件、lintの警告は起点と同じ4件。APKは34,408,076 bytes、SHA-256は`fbf0dbb2e25bd6e8b33aef7cbe8be2e9cfc828ea30c0dd80dcaab7dffbfcf3fc`で、端末へ入れた。`libmozc.so`、`mozc.data`、NOTICEが入っていることを確認した。前回とのサイズ差は、cleanビルドによるdexの分割の違いである。

実機（21:26〜21:30）での確認結果は次のとおり。開始時の既定IMEはSimejiだった。

- ライブ変換OFFで「よい」→変換「良い」→削除→「る」と操作すると、「よ」の後に「よる」が入力中のまま残った。確定された文字は無い。
- 「うずみ→UzumiTest」を登録すると、明示変換（辞書画面の検索欄）では変換キーで「UzumiTest」になった。ライブ変換（試用欄）では入力だけで「UzumiTest」が表示され、「。」で確定した。
- 試験後は、辞書ファイルと設定ファイルを消し、Mozcのprofileを開始時の複製へ戻した。既定IMEはSimejiへ戻し、`settings get`の出力は2項目とも開始時とbyte単位で一致した。

## 文脈が違う読みでの学習（2026-09-23）

### 不具合と原因

ライブ変換で「こうえんにいく」の「公園に」を「校園に」へ直し、「校園に行くよ」と確定しても、次に「こうえんにいく」と打つと「公園に」のままだった。Mozcの`segment.db`は更新されていた。

原因はMozcの文節履歴の仕様であり、Uzumiが送る学習の命令列の誤りではない（確定）。根拠は`src/rewriter/user_segment_history_rewriter.cc`の次の条件である。

- 確定時の書き込み（`Finish`→`RememberFirstCandidate`）は、`FIXED_VALUE`の文節ごとに、前後の文節の表記を含む特徴（`LR`、`LL`、`RR`、`L`、`R`）、変換全体が一文節の場合の`Single`、文脈を含まない`Current`を保存する。`Current`は、文節の幅を変えた変換（`segments.resized()`）、`CONTEXT_SENSITIVE`の候補、既定の候補と置き換えられない候補（`Replaceable`：機能語の表記と品詞グループが同じ）では保存しない。
- 次の変換での参照（`Rewrite`→`GetScore`）も同じ条件で、`Current`は置き換えられる候補にだけ使う。それ以外の候補は、前後の文節の表記が学習時と一致する特徴でしか先頭へ上がらない。`L`系の特徴は確定済みの履歴文節も前の文節として数える。

同じcommitのMozcをhost向けにビルドした`session_handler_main`（`bazel build //session:session_handler_main --config oss_macos`）で、既定の`Request`のまま次を再生した。入力はローマ字で、Uzumiの`AS_IS`のかな入力とは経路が違うが、変換と学習は同じ処理を通る。

| 学習した確定 | 次の変換 | 先頭 |
| --- | --- | --- |
| 「こうえんにいくよ」→「校園に行くよ」 | 「こうえんにいく」 | 公園に |
| 同上、2文節目を縮めて「校園に行く よ」 | 「こうえんにいく」 | 公園に |
| 「こうえんに」→「校園に」（一文節） | 「こうえんに」／「こうえんにいく」／「こうえんにはいる」 | 校園に／公園に／公園に |
| 「こうえんにいく」→「校園に行く」 | 同じ読み／「こうえんにいくよ」 | 校園に／公園に |
| 「こうえんに」→「公演に」（一文節） | 「こうえんにいく」／「こうえんにはいる」 | 公演に／公演に |
| 「きしゃが」→「汽車が」（一文節） | 「きしゃがのる」 | 汽車が |

「公演に」「汽車が」は既定の候補と置き換えられるため、別の文脈でも先頭になる。「校園に」は置き換えられないと判定され、学習時と同じ文節の並びでしか先頭にならない。「かんじ→幹事」が効いたのは、一文節の変換で`Single`と`Current`が一致したためである。Mozcの判定を変えずにUzumiの命令列だけで一般に効かせる方法は無いため、Mozcへの学習の送り方は変えず、IME側に学習を持つことにした。

### IME側の学習（`dev.uzumi.ime.learning`）

karukan（MIT OR Apache-2.0）の設定文書にある学習の仕組みを参考にし、コードは移植せずに実装した。

| 項目 | 仕様 |
| --- | --- |
| 記録するもの | 明示変換で選んだ候補（エンジンの候補・学習語・予測）と、ライブ変換で表示のまま確定したsegmentの読みと表記。表記がひらがなだけ・ASCIIだけ・読みと同じ・50文字を超える・タブや改行を含むものは記録しない。自動変換の途中、明示変換の第一候補のままの確定、登録語の確定では記録しない。 |
| 順位 | `最後に使った時刻 + ln(回数) × 1日`の降順。回数がe倍になるごとに1日新しく使ったものとして扱う。 |
| 参照 | 読みの完全一致と前方一致で最大3件。ライブ変換では、完全一致の最上位の語を表示し、候補は「ユーザー辞書 → 学習 → Mozc」の順。登録語がある読みでは登録語を表示する。明示変換では表示を変えず、完全一致（読み全体と先頭文節）をMozcの候補の前へ、前方一致の予測を候補の最後へ置く。学習語がMozcの候補と同じならMozcの候補を前へ移し、確定をMozcへも送る。 |
| 上限 | 10,000語。超えたら順位が最も低い語から捨てる。 |
| 保存 | `noBackupFilesDir/learning.tsv`。記録ごとには書かず、入力欄の終了時（`ConversionWorker.endSession`）にユーザー辞書と同じ原子的な書き込み（一時ファイル→fsync→rename）で保存する。ON/OFF、一語の削除、全消去はすぐに保存する。本文はログに出さない。 |
| 機密欄 | password欄、`TYPE_NULL`は要求そのものを作らない。`IME_FLAG_NO_PERSONALIZED_LEARNING`では記録も参照もしない（明示変換は`incognito`、ライブ変換は`learningAllowed=false`で判定）。 |

設定画面から呼ぶ公開APIは`LearningStores.get(context)`が返す`LearningStore`の次のmethodである。画面は作っていない。

- `isEnabled`、`setEnabled(Boolean)`：学習のON/OFF。OFFの間は記録も参照もせず、保存済みの語は残す。
- `allWords()`：保存している語を順位の順に返す。
- `remove(reading, surface)`：一語を削除する。
- `clearAll()`：学習をすべて消し、Mozcの学習も消す。IMEが動いていれば変換workerの順番で`CLEAR_USER_HISTORY`と`CLEAR_USER_PREDICTION`を送り、動いていなければprofileの`segment.db`、`boundary.db`、`history.db`、`.history.db`を消す。

`UzumiInputMethodService.onCreate`で`ConversionWorker`へ`learningStore = { LearningStores.get(this) }`を渡す1行を加えた（別commit）。

### テスト

`./gradlew clean testDebugUnitTest assembleDebug lintDebug`は成功した。JVMテストは181件（起点165件、追加16件）で、失敗・error・skipは0件。lintはerror 0件、警告4件で起点と同じ。

| テスト | 追加 | 守る内容 |
| --- | ---: | --- |
| `LearningStoreTest` | 8 | 時刻と回数による順位、最大3件と前方一致、10,000語の上限での削除、記録しない表記、OFFでの記録・参照の停止と保存、flushでの保存と読み直し、一語の削除、全消去でMozcの学習も消すこと |
| `ConversionWorkerTest` | 7 | ライブ変換での最新の学習語の表示と「登録語 → 学習 → Mozc」の順、学習禁止欄で参照しないこと、確定の記録と入力欄の終了時の保存、明示変換の候補の順と予測の確定の記録（Mozcへ送らない）、Mozcの候補の確定の通知と記録、学習禁止欄で記録も参照もしないこと、全消去がworker経由でMozcへ届くこと |
| `MozcConversionEngineTest` | 1 | 学習の消去で`CLEAR_USER_HISTORY`と`CLEAR_USER_PREDICTION`を送ること |

### 実機での確認

Pixel 10 Pro（Android 17/API 37）、2026-09-23 22:38〜22:42。開始時の既定IMEはSimejiだった。debug APK（34,440,844 bytes、SHA-256 `8e0edec834509d4871a1ba5a80df6345e723dc193a734f5739c8c93e299a5492`、commit `a609038`）を入れ、試用画面だけで架空の文を入力した。Mozcのprofileは開始時にアプリ内へ複製した。

| 操作 | 結果 |
| --- | --- |
| 「こうえんにいく」を入力 | 「公園に行く」（修正前と同じMozcの結果） |
| 「◀」→候補「校園に」→「末尾」→「よ」→改行 | 「校園に行くよ」を確定 |
| 改行後、同じ欄で「こうえんにいく」 | 「校園に行く」が表示された |
| 試用画面を離れる | `no_backup/learning.tsv`が作られた（本文は表示していない） |
| アプリを停止して開き直し、「こうえんにいく」 | 「校園に行く」が表示された |

アプリの停止で既定IMEがGboardへ変わり、一度だけGboardで意味のないかなが入力された。Gboardの学習へ残った可能性があるが、確認していない。試験後は`learning.tsv`とprofileの複製を消し、Mozcのprofileを複製から戻した（5ファイルのSHA-256が開始時と一致）。既定IMEと`enabled_input_methods`は開始時の値へ戻し、`cmp`で一致を確認した。ただし最初の書き戻しでは、値に含まれる`;`を端末のshellが区切りとして扱い、22:42:31から22:42:41まで`enabled_input_methods`が先頭3件だけ（Uzumiが無い状態）になっていた。値全体を引用して書き直し、一致を確認した。
