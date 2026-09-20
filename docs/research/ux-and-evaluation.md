# 既存IMEのUXと評価計画

確認日：2026-09-20。一次資料の調査であり、実機操作・性能測定は未実施。Android 11以上を暫定対象とし、性能上必要なら引上げ可能。INTERNETなし、製品のニューラルエンジン一つという方針で評価する。

## 既存の体験から利用するもの

| 対象 | 公式資料で確認できたこと | 設計への示唆・限界 |
|---|---|---|
| Gboard | Space左右swipeでcursor移動、長押し追加文字、Clipboard履歴/pin、写真/色の背景、音/振動 | 操作とパネルの近い配置を参考にする。日本語12-keyで同一gestureが使えるかは未検証 |
| Simeji | Android高さ・片手、flick guide、flick-only、候補長押し学習reset、顔文字/着せ替え、Clipboard最大30件/pinの説明 | 良い既定値と詳細設定の両立を参考。顔文字数だけを目標にしない |
| Samsung | サイズ/レイアウト/theme/text shortcuts/emoji候補/toolbar、Clipboardや編集機能への入口 | 発見可能性を参考。ただし言語・地域差あり |
| iOS純正 | Space長押しtrackpad、Undo/Redo gesture、自動訂正箇所から復帰、emoji分類/最近/検索/skin tone | 変更箇所の理解と戻しやすさを参考。Android第三者IMEへ同じEditor連携を移植できるとは扱わない |
| macOS日本語 | Live Conversion、Space別候補、Return確認、Escape読み復帰 | 自動変換の参考。スマートフォンの確定不要UXを実証したものではない |
| azooKey | モバイルlive変換、custom key/tab、offline Zenzai、Swift公開実装 | Android移植費用と過去segment訂正の同等性は別評価 |

出典：[Gboard基本操作](https://support.google.com/gboard/answer/2842292?hl=en-GB)、[Clipboard](https://support.google.com/gboard/answer/10742542?hl=en)、[Theme](https://support.google.com/gboard/answer/6102154?hl=en-CI)、[Simeji FAQ](https://simeji.me/faq)、[操作ガイド](https://simeji.me/guide)、[Clipboard発表](https://simeji.me/blog/details/16107)、[Samsung設定](https://www.samsung.com/us/support/answer/ANS10001592/)、[機能入口](https://www.samsung.com/us/support/answer/ANS10002366/)、[iOS編集](https://support.apple.com/guide/iphone/select-edit-and-move-text-iph1a9cae52c/27/ios/27)、[iOS emoji](https://support.apple.com/en-ie/102507)、[macOS Live Conversion](https://support.apple.com/en-mt/guide/japanese-input-method/jpim10265/mac)、[azooKey](https://azookey.com/)、[公開実装](https://github.com/azooKey/azooKey)。

Samsungの英語総説にはtoolbar非表示の説明がある一方、2026-05-20更新の日本語記事では日本語でtoolbarを表示するとしている。日本語UXに英語総説を一般化しない。[Samsung日本語記事](https://www.samsung.com/jp/support/mobile-devices/how-do-i-show-and-hide-the-keyboard-toolbar-for-galaxy/)

GboardのFix it/Undo/Smart Composeは言語・端末・地域条件があり、日本語live変換のsegment訂正の証拠にはならない。[候補と訂正](https://support.google.com/gboard/answer/7068415?hl=en-GB)

競合のflick threshold、long press時間、repeat速度、濁点/小文字の現行挙動、誤変換訂正の実操作数、画面占有率は不明。公開ヘルプから最適値を断定しない。

## UIの提案

候補バーは常設し、訂正対象segmentをラベルや下線で示す。過去segmentへはIME内一覧から到達可能にし、選択→候補選択の2タップを設計目標とする。ただし一覧展開やスクロールが必要な場合の操作も計測する。

通常は候補行とキーを主とし、候補行端のメニュー案と常設toolbar案を比較する。segment一覧は必要時展開も候補にする。Undoには見える入口を設け、長押しや多指gestureだけに依存しない。

Clipboardと定型文は共通パネルから開けても、保持方針を分ける。前者は期限付きの観測履歴、後者は明示保存の再利用文。pinから定型文へ移す操作を用意する。Clipboardの自動保存可否は機密情報の制約を解決してから決める。

絵文字・顔文字・記号は共通検索、カテゴリ、最近使用、お気に入りを基本とする。文脈による候補混在は、かな漢字候補を押しのけない件数・順位から試す。外観と入力設定を分け、背景画像でも可読性・キー境界・押下状態が分かるpreviewを設ける。

## 12-keyの検証

flick/toggle併用とflick-onlyは明示設定とし、現用IME設定と比較する。既定値はまだ決めない。

- タッチ開始点、距離、方向、斜め境界、方向変更、キー外release、`ACTION_CANCEL`、複数pointer、片手モードを試す。
- 閾値はpx固定にせずdp・キーサイズとの関係を評価する。12/18/24dpは比較候補の例であり推奨値ではない。Android touch slopはflick最適値ではない。[ViewConfiguration](https://developer.android.com/develop/ui/views/touch-and-input/gestures/viewgroup)
- long pressによるガイドと文字選択、repeat開始の衝突を避ける。削除repeatの加速は別評価にする。
- `は→ば→ぱ`、`つ→っ`、`や→ゃ`、`う→ゔ`、同一行の連続入力、数字・記号・英語切替を固定課題に含める。
- 未変換かな、`よい→良い`、結合文字、ZWJ絵文字、範囲選択、segment境界を分ける。
- 誤方向率、意図しない長押し率、削除しすぎ率、取りこぼし率、訂正込み完了時間と主観評価を記録する。

## ライブ変換の指標案

| 指標 | 定義 |
|---|---|
| 無介入正解文率 | 候補選択、訂正、明示確定がすべてゼロで許容正解に達した文の割合。主指標候補 |
| 確定操作ゼロ率 | 変換確定だけの操作なしで文を終えた割合。候補訂正ありでも達成できる |
| 訂正なし正解文率 | 候補選択・再入力等なしで最終文が正しい割合 |
| 最終正解率 | 介入回数と併記。誤文を放置して無操作率を高めない |
| first-correct latency | 必要な読み入力から初めて正解を表示するまで |
| 正解安定時間 | その後も正解を維持する表示になるまで |
| 変換揺れ | 同じ読み範囲の自動表示変更回数/100読み文字。初回表示と手動変更を除外 |
| 過去の正解の破壊 | ユーザーが変更していない範囲を後続入力が誤表記へ変えた回数 |
| stableの書換え | 内部で安定と判定済みの範囲を自動更新した回数 |
| chosenの侵害 | 明示選択範囲を自動変更した回数。回帰試験では0 |
| 訂正コスト | segment選択、候補tap、再入力、Backspace、Undo、訂正時間、入力復帰時間 |
| 候補品質 | 意図候補の順位、欠落率、横スクロール/展開回数 |
| stale結果侵害 | 古い非同期結果による最新入力上書き。回帰試験では0 |
| 入力効率・信頼性 | keystrokes、文完成時間、crash/ANR率、打鍵欠落/二重入力率 |

句点・改行・Send等の本来の終端操作と、変換を確定するためだけの操作を分ける。許容表記は課題ごとに事前定義し、数値・記号・幅の正規化を後から変えない。

## 暫定の性能目標

以下は開発上の提案であり、測定結果や業界標準ではない。端末と課題を決めて測定前に合意する。

| 対象 | 仮目標 | 計測区間 |
|---|---|---|
| 押下表示 | p95 ≤ 50ms | touch→押下描画 |
| composition表示 | p95 ≤ 50ms | 入力受理→Editor表示。API返却とは区別 |
| 辞書候補 | p95 ≤ 100ms | 入力受理→候補描画 |
| neural改善 | p95 ≤ 300ms | 最新入力受理→適用可能結果。待ち・推論・表示を分離 |
| warm keyboard | p95 ≤ 150ms | focus→操作可能 |
| cold keyboard | p95 ≤ 500ms | processなし→操作可能。model準備は別 |
| frame | refresh周期内 | 60Hz約16.7ms、120Hz約8.3ms |
| memory/battery/size | 不明、実機選定後に予算化 | idle/active/peak PSS・RSS、energy、配布容量 |

frame周期は[Android描画性能](https://developer.android.com/topic/performance/vitals/render)を参照。neuralがdeadlineを超えても操作を止めず、最新性検査・保護範囲・取消しを守る。

## 測定方法

常用端末、比較的弱い端末、別メーカー端末を基本案とする。機種・SoC・RAM・OS・IME版・Hz・省電力・温度・充電状態を記録する。端末未指定のため性能合格は判定できない。

同一端末で現用IME、新IME Live OFF、Live ON、neural ONを比較する。製品へ複数エンジンを載せる要求ではなく、開発評価の比較条件である。辞書・履歴・課題を固定し、順序効果を避けて実施順を入れ替える。通常文、同音異義語、名前、数値、URL、長文、過去訂正、句点なし、英語混在を分ける。

入力・revision・辞書・推論queue/run/discard・Editor更新・描画を共通clockで記録する。実際の日常入力本文を収集せず、固定評価文だけ詳細traceを許す。release相当のprofileable buildを用い、UI Automatorでhost editorを操作し、IME/hostのtraceとPerfettoで測る。Activity startup指標をIME service startupと同一視しない。[Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)

cold/warm、model未load/load済み、連続入力後の熱、メモリ圧迫後の再生成を分ける。電力は同じ時間・輝度・操作列で比較する。`PowerMetric`はPixel 6以降の対応実機で端末全体を測るため、IME単体電力とは断言しない。[PowerMetric](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)

memoryはJava heapだけでなくnative buffer・model mmap・辞書を含むPSS/RSS/peak、GC、low-memory killを確認する。[Perfetto memory](https://developer.android.com/topic/performance/memory/guide/system-wide-memory)

UI比較では同じ端末・向き・keyboardサイズで画面占有率、Editor可視行数、候補訂正・貼付・emoji検索・定型文挿入の操作を動画から計測する。初見の発見可能性と慣れた操作速度を別評価とする。

未決：端末、flick既定値、toolbar、許容表記、推論適用条件、各予算。条件は提案段階であり、実験開始前に固定する。
