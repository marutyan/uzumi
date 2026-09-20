# 変換エンジンの比較と選定案

確認日: 2026-09-20。公開一次資料、ビルド定義、主要な実装を読み取った調査であり、Android実機での品質・速度・電力の測定は未実施。以下の「推奨」は採用確定を意味しない。Android 11以上を暫定対象とし、`INTERNET`権限なしを優先する。

## 結論

**IME状態管理を自前で持ち、Mozcの変換ライブラリを高速な基盤として評価し、ニューラル処理は単一モデルによる限定的な再評価から導入する案を推奨する。** 暫定の統合試験の第一候補は `Miwa-Keita/zenz-v3.2-small-gguf` の `Q5_K_M`。Androidの先行統合例、Mozcの後段補正例、配布サイズを根拠とした着手順であり、品質が最強と確認できたわけではない。品質上限の探索をsmall Q5に限定せず、以下の公開上位サイズ・高bit・浮動小数点版も比較する。品質上の利益が明確なら、新しい端末への限定も選択肢にする。

製品ではニューラルエンジンの切替UIを設けず、大小2モデルの同時常駐も行わない。`jinen-v2-small` 等との比較は開発時だけ行い、同一条件の実測で勝った1つを採用する。辞書変換は入力の即時応答・数字保護・ニューラル停止時を支える内部処理とする。既存モデルが訂正コストと性能の両方を満たさなければ、追加学習、さらに必要な場合だけ自作へ進む。

モデルの出力文字列を、そのままEditorや全segmentへ上書きしてはいけない。読み、表示、ユーザー選択、保護対象をIME側で管理し、検証を通った未安定部分だけ更新する。状態機械、Undo、Androidとの同期は変換器を採用しても別途必要になる。

## 調査対象の同定と利用方針

| 対象 | 一次プロジェクト | 確認できた実体 | 利用方針案 |
|---|---|---|---|
| Sumire | [KazumaProject/JapaneseKeyboard](https://github.com/KazumaProject/JapaneseKeyboard) | Android IME。Kotlinの辞書変換と任意のzenz統合。ライブ変換はβ設定 | 部品・互換性事例・試験対象として利用。丸ごとのforkは初手にしない |
| Mozc | [google/mozc](https://github.com/google/mozc) | C++変換系。現行Android用`libmozc.so`あり | 高速変換、文節、候補、辞書の第一評価候補 |
| mozkey | [koyasi777/mozkey](https://github.com/koyasi777/mozkey) | Mozcのfork。遅延ライブ変換とローカルzenz補正 | 採用条件、結果破棄、保護規則を参考にする。Windows実装をそのまま移植しない |
| Karukan | [togatoga/karukan](https://github.com/togatoga/karukan) | Rust + llama.cppのニューラルKKC。Linux/macOS向け | jinenモデル、chunk管理、辞書併用を比較・参考にする |
| zenz | [Miwa-Keitaのv3.2 small GGUF](https://huggingface.co/Miwa-Keita/zenz-v3.2-small-gguf) | GPT-2系KKCモデル。単独ではIMEでも辞書管理系でもない | small Q5を暫定第一候補として実験 |

`mozkey`には同名forkが複数ある。本調査の対象は上表のowner/repo。検索結果だけで別forkの機能や動作確認を混ぜない。

### Sumireを「Mozcそのもの」と扱わない

READMEはMozc基盤と説明するが、確認した[KanaKanjiEngine.kt](https://github.com/KazumaProject/JapaneseKeyboard/blob/dev/app/src/main/java/com/kazumaproject/markdownhelperkeyboard/converter/engine/KanaKanjiEngine.kt)にはKotlinのgraph構築、DP、backward A*、`MozcSegmenter`、増分セッションがある。Mozc由来の辞書・規則の利用と、ネイティブMozcライブラリの利用は区別する。両者の候補品質や文節境界が一致する保証はない。

同ファイルは`Context`、辞書repository、学習repository等に依存するため、そのまま独立ライブラリとして切り出せるとは判断しない。再利用範囲は、実機比較後に依存関係とライセンスを確認して決める。

### MozcのAndroid対応を正確に区別する

公式Android APKのクライアントは廃止されたが、変換ライブラリは現行でビルド手順がある。クライアント最終revisionは`afb03ddfe72dde4cf2409863a3bfea160f7a66d8`。公式説明はmacOS/Linuxでのビルド、Bazelisk、Python 3.12以上、NDK r29を挙げる。現在の作業環境Python 3.9.6だけではその手順の要件を満たさない。[公式Androidビルド手順](https://github.com/google/mozc/blob/master/docs/build_mozc_for_android.md)

現行[JNIビルド定義](https://github.com/google/mozc/blob/master/src/android/jni/BUILD.bazel)にはarm64、arm32、x86_64、x86_32と16KBページ対応のlink optionがある。まず製品用arm64とエミュレータ用x86_64を検証する案とし、上流にtargetがあることを当プロジェクトのビルド成功と混同しない。

## ライセンスをコード・モデル・データに分ける

| 対象 | コード | モデル | 辞書・学習データ／配布時の確認 |
|---|---|---|---|
| Sumire | [MIT](https://github.com/KazumaProject/JapaneseKeyboard/blob/dev/LICENSE) | full版のzenz等は別条件。アプリMITを重みに適用しない | Mozc由来データ、英語辞書、追加UT辞書等を個別棚卸し。全同梱物の権利確認は未完了 |
| Mozc | Google作成部分は[BSD-3-Clause](https://github.com/google/mozc/blob/master/LICENSE)。第三者コードは個別 | 基本系はニューラルモデル不要 | [dictionary_oss](https://github.com/google/mozc/blob/master/src/data/dictionary_oss/README.txt)は混合。IPAdic/NAIST・ICOT条項、沖縄辞書等の表示を保持 |
| mozkey | [Mozc由来のBSD系条件](https://github.com/koyasi777/mozkey/blob/main/LICENSE) | 現在の同梱v3.2 smallはApache-2.0との[明示](https://github.com/koyasi777/mozkey/blob/main/src/win32/installer/zenz_runtime/licenses/THIRD_PARTY_NOTICES.md) | [追加辞書notice](https://github.com/koyasi777/mozkey/blob/main/THIRD_PARTY_NOTICES.md)はUT混合、Wikipedia由来、Web由来等を分離。daily辞書を一括してBSDと扱わない |
| Karukan | [MIT OR Apache-2.0](https://github.com/togatoga/karukan) | [jinen v2 small](https://huggingface.co/togatogah/jinen-v2-small.gguf)／[xsmall](https://huggingface.co/togatogah/jinen-v2-xsmall.gguf)はCC BY-SA 4.0 | READMEは`karukan-engine/data/`のMozc派生データをBSD-3-Clauseと記載。別配布のシステム辞書と学習コーパス全体を同じ条件と推定しない |
| zenz v3.2 | 実行runtimeと統合コードの条件は別 | [small](https://huggingface.co/Miwa-Keita/zenz-v3.2-small-gguf)／[xsmall](https://huggingface.co/Miwa-Keita/zenz-v3.2-xsmall-gguf)のHub表示はApache-2.0 | 開いたv3.2 GGUFページのmodel card本文は空。全学習データの由来・条件は不明 |
| zenz v3.1 | 同上 | [xsmall](https://huggingface.co/Miwa-Keita/zenz-v3.1-xsmall)はCC BY-SA 4.0 | v3.2の条件を旧版へ遡及適用しない |
| llama.cpp | [MIT](https://github.com/ggml-org/llama.cpp) | GGUFにしただけでモデル条件は変わらない | tokenizer、変換元モデル、量子化版のversionを固定 |

これは公開表示の確認であり、配布パッケージ全体の権利監査完了ではない。採用時にはコードcommit、モデルrevision・SHA-256、辞書生成元・生成手順、license/noticeを同じmanifestへ残す。CC BY-SAモデルを同梱しただけでアプリ全体のライセンスが自動的に決まるとも断定しない。改変重み、帰属表示、再配布形態ごとに条件を確認する。

Mozc OSS辞書はGoogle日本語入力のWeb由来大語彙全体と同一ではなく、公開READMEも差を明示する。「Mozc採用だからGboard相当の固有名詞が出る」という前提は置かない。[辞書の違い](https://github.com/google/mozc/blob/master/src/data/dictionary_oss/README.txt)

## 公表値と測定の限界

| モデル／形式 | パラメータ・ファイル容量 | 公表品質・時間 | Androidで未確認の項目 |
|---|---|---|---|
| zenz v3.2 xsmall Q5_K_M | 25.6M、約21MB | 開いたmodel cardに比較可能な品質・latency値なし | 全項目 |
| zenz v3.2 small Q5_K_M | 95.1M、約73.9MB | 同上 | 全項目 |
| jinen v2 xsmall Q5_K_M | 35.7M、約28.3MB | exact 74.0%、NFKC 79.0%；p50 13ms、p90 29ms、p99 45ms | 実機latency、常駐PSS/RSS、cold start、電力 |
| jinen v2 small Q5_K_M | 109.5M、約81.1MB | exact 80.0%、NFKC 86.0%；p50 48ms、p90 111ms、p99 171ms | 同上 |

出典: [zenz small](https://huggingface.co/Miwa-Keita/zenz-v3.2-small-gguf)、[zenz xsmall](https://huggingface.co/Miwa-Keita/zenz-v3.2-xsmall-gguf)、[jinen small](https://huggingface.co/togatogah/jinen-v2-small.gguf)、[jinen xsmall](https://huggingface.co/togatogah/jinen-v2-xsmall.gguf)。

jinenの表は開発者公表値。条件はAJIMEE-Bench `JWTD_v2/v1` 200問、greedy、llama.cpp b10200、CPU 4 threads、`n_ctx=1024`、左文脈64文字。CPU型番は開いたカードには記載がなく、不明。NFKC列は参照側も正規化するため、幅や記号の正確さを評価する数値として単独利用しない。スマートフォンの時間目標や、未完の読みを逐次入力するライブ変換の品質へ転用できない。

ファイル容量は常駐メモリ量ではない。KV cache、計算領域、tokenizer、辞書、JNI、Kotlin UI等を含む実プロセスのPSSを測る。Mozc、Sumire、mozkeyを含む全候補について、同条件のAndroid端末別PSS・電力・cold start比較は今回未確認。

### 品質上限と量子化差を切り分ける追加候補

| 公開を確認した候補 | 役割 | 条件・限界 |
|---|---|---|
| [zenz v2.5 medium](https://huggingface.co/Miwa-Keita/zenz-v2.5-medium) | 著者説明310M、BF16の大きいモデルを品質上限候補にする | CC BY-SA 4.0。旧世代なのでv3.2 smallより高品質とは断定できない。世代差とサイズ差が交絡する。Android用変換・実測は未実施 |
| [zenz v3.1 small](https://huggingface.co/Miwa-Keita/zenz-v3.1-small) | 90.5M BF16。旧版について低bit化の影響を調べられる | CC BY-SA 4.0。v3.2 Q5との差を「量子化だけの差」と扱えない |
| [jinen v2 small Q8_0 / F16](https://huggingface.co/togatogah/jinen-v2-small.gguf/tree/main) | 同一世代・サイズのQ5と比べ、量子化差を切り分ける | Q8_0約117MB、F16約220MB。配布ファイルを確認。浮動小数点版も品質で必ず勝つとは限らない |

[著者のモデル一覧](https://huggingface.co/Miwa-Keita/models)で確認したzenz v3.2の公開候補はsmall/xsmall GGUF。[smallのファイル一覧](https://huggingface.co/Miwa-Keita/zenz-v3.2-small-gguf/tree/main)にはQ5_K_Mが1点あり、同版のmedium、BF16/F16、Q8の公開は今回確認できなかった。取得失敗だけから不存在とは判断しない。[jinen公式collection](https://huggingface.co/collections/togatogah/jinen)で確認したv2はsmall/xsmallであり、より大きなv2の公開は未確認。未公開版の入手を前提に計画しない。

実験は二段階にする。まず公開大サイズ・浮動小数点版を含む品質比較で有望なモデルを選び、次に同じcheckpointのF16/BF16、Q8、Q5等で品質低下と端末費用を分離する。v3.2の元重みが得られない間は、同モデルの量子化損失を測れないという限界を残す。高品質候補が最新端末でだけ成立する場合は、旧端末への適合だけを理由に落とさず、最低端末条件との組合せを比較する。製品に配布・常駐させるニューラルモデルは、その結果から選ぶ1つだけとする。

## 機能と品質上の適性

「あり」はソース／公開説明で機構を確認したことを意味し、当IMEの受入試験合格ではない。

| 比較軸 | Mozc | Sumire | mozkey | Karukan / jinen | zenz単体 |
|---|---|---|---|---|---|
| 変換品質 | 辞書・統計系の基準候補。最終品質は実測 | Kotlin実装。Mozcとの一致は未確認 | Mozcに文脈規則とneural補正を追加 | 公開200問の評価あり。実入力比較は未実施 | v3.2の比較可能な公開値は未確認 |
| 増分変換 | session、文節、予測系を活用可能 | graph/DP増分stateあり | 遅延ライブ変換あり | chunk単位ライブ変換あり | 差分適用・cache・取消しは統合側で作る |
| 文脈 | history segmentあり | N-gram、zenz文脈の設定あり | 前後文脈、保護規則あり | 周辺文脈設定あり | promptで渡す統合が必要 |
| segment / alignment | keyとcandidate、固定境界・固定値を持つ | 文節候補型、MozcSegmenterあり | Mozcの文節系が土台 | chunkと読みを管理。文字単位対応の保証は未確認 | テキスト生成だけでは対応表を保証しない |
| ユーザー辞書 | 既存辞書機構を検証して利用 | 登録・予測・import/exportあり | Mozc辞書に加え補正からの保護あり | Mozc TSVと独自binaryの読込みあり | 外付けが必要 |
| 学習 | 既存history系。Undoとの整合は別途必要 | 学習repositoryと設定あり | feedback学習あり | recency・頻度によるcacheあり | オンライン学習を自動で提供するわけではない |
| 固有名詞 | OSS辞書の範囲に制限 | 任意UT追加辞書あり | daily追加辞書あり、権利条件が増える | 辞書併用を公式に推奨 | 読みにない語の生成を防ぐ検証が必要 |
| 数字・記号 | 専用候補・規則を評価可能 | 数値候補の専用処理あり | mixed-script/記号を壊さない対策あり | 既定で数字・英字をAI対象外に区切る | 桁・幅・URL・識別子の保護を別途実装 |

根拠となる構造: Mozcの[segments.h](https://github.com/google/mozc/blob/master/src/converter/segments.h)は`FREE`、`FIXED_BOUNDARY`、`FIXED_VALUE`、`SUBMITTED`、`HISTORY`を区別する。ただし同ファイルのrevertは複数Undo/Redo対応をTODOにしており、製品の意味単位Undoをそのまま満たさない。

Karukanは[辞書候補の優先順位](https://github.com/togatoga/karukan/blob/main/docs/dictionary.md)と[Mozc TSV互換](https://github.com/togatoga/karukan/blob/main/docs/user-dictionary.md)を文書化する。大規模TSVを起動時にtrieへ変換するとcold startが増えるため、事前binary化を推奨している。学習cache・文脈・モデル切替は[設定資料](https://github.com/togatoga/karukan/blob/main/docs/configuration.md)で確認した。当製品ではその複数モデル方式をそのまま採用しない。

Karukanの[chunk設計](https://github.com/togatoga/karukan/blob/main/docs/chunking.md)は、数字の欠落・重複を危険として扱い、非日本語chunkをニューラルへ渡さない。参考になるが、PCの`Ctrl+J`で手動固定する操作をスマートフォンの標準UXには持ち込まない。

## Android実行と保守

| 比較軸 | Mozc | Sumire | mozkey | Karukan / jinen | zenz + llama.cpp |
|---|---|---|---|---|---|
| Android組込み | 公式JNI native libraryあり。アプリ側は作成 | Androidアプリそのもの | 主な追加機能はdesktop向け。Android移植未確認 | Android frontend未確認。Rust/C++/JNI接続が必要 | JNI/NDK統合。先行例あり |
| latency | 入力主経路の第一候補。数値は実測待ち | 増分処理あり。実機比較待ち | default debounce 1000msは推論時間ではない | 公表CPU値あり、Androidは不明 | 推論と更新を非同期化。実機値は不明 |
| memory / model size | 辞書とnative heapを測定 | Kotlin辞書 + optional modelを測定 | 二系統・runtimeの合計を測定 | 単一smallでも辞書・cache等が追加 | small重み約73.9MB以外の領域も必要 |
| battery | neuralなしの基準線を測る | 同左＋neural ON比較 | desktop実装からは推定しない | 全打鍵推論の頻度を制御する必要 | debounce、取消し、対象範囲制限を評価 |
| cold start | 辞書・library初期化を測る | asset展開・辞書構築の影響を測る | desktop常駐方式を前提にしない | upstream初回model取得を製品には持ち込まない | 事前同梱、初回load中も辞書系で入力継続 |
| 保守性 | Googleの公開基盤。ただし公式QA付きstable releaseではない | Android向け機能が豊富。アプリ依存の切出し費用あり | upstream追従と独自変更の二重保守 | Rust+native+Kotlinとなる場合の境界が増える | runtime/API/modelを固定し再評価が必要 |

Sumireの確認時[app/build.gradle](https://github.com/KazumaProject/JapaneseKeyboard/blob/dev/app/build.gradle)はversion `1.7.116`、minSdk 24、target/compile 36、Java 17、任意splitにarm64-v8a/x86_64を記載する。これは公開branchの構成であり、本プロジェクトのAndroid 11最低要件や性能を決める根拠にはしない。

mozkeyで確認できた公開releaseは[v0.7.7 / dfc744b](https://github.com/koyasi777/mozkey/releases/tag/v0.7.7)、2026-07-03、pre-release。READMEの現行branchはその後も変わり得る。補正の[説明](https://github.com/koyasi777/mozkey)はgeneration/keyによる古い結果の破棄を含む。同梱runtime noticeはllama.cpp `b10437` / `16d222fc5ead59d20039501a37251c9ed457a454`とtokenizer patchを記載しており、任意の最新runtimeへ置換して同じ結果が出るとは限らない。

llama.cppの[公式Android手順](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md)はAndroid Studio bindingとNDK/CMakeのarm64例を持つ。端末CPU機能の検出、context sizeとメモリの関係が重要。初期案はCPUで測定してからGPU等を検討し、NNAPI/LiteRT/ONNXへの変換が自動で高速化すると仮定しない。

## 構成案の比較

| 案 | Merit | Demerit | 判断 |
|---|---|---|---|
| Mozc基盤 + 単一neuralによる候補再評価 | 即時応答、辞書、候補、文節を先に安定させられる。既存候補の読み対応を保持しやすい | 基底候補に正答がなければrerankだけでは救えない。スコア比較・更新抑制が必要 | 第一案 |
| Mozc基盤 + zenz後段生成補正 | 辞書外の改善可能性。mozkeyの先行例あり | 全文書換え・誤生成・alignment再構成のリスク。表示が二段階で揺れる | 初期は候補へ追加。自動採用は制約検証と実測後 |
| Karukan型のneural主変換 + 辞書補助 | neuralに長めの文脈を利用させやすい。jinenの公開評価あり | cold start、全打鍵推論、数字保護、候補・文節操作、Android移植の費用 | 同条件実験の代替案。MVPの前提にはしない |
| 独自候補生成 + neural reranking | UXに合うsegment契約を直接設計可能 | 語彙・形態接続・数字・学習・未知語を自作する負担が大きい | 既存候補生成の欠陥が実測で特定された範囲だけ自作 |
| Sumireを土台に大幅改修 | 12-key、QWERTY、設定、辞書、neural接続が既存 | 現在の状態管理・依存関係・機能量に設計が引っ張られる | 小さな部品再利用と比較対象を優先 |

「rerank」と「自由な生成補正」は分けて評価する。rerankは候補集合内の並び替えなので読み対応を保持しやすいが、候補数に比例した計算費用があり、学習に合う採点方法かを検証する必要がある。zenzの名称だけでその用途の優位性を保証しない。

## 他の現実的な候補

[AzooKeyKanaKanjiConverter](https://github.com/azooKey/AzooKeyKanaKanjiConverter)はMITのSwift製変換器で、Zenzai、学習、`ComposingText`、辞書を持つ。確認済み環境はApple系とUbuntu、Swift 6.1以上。neuralと辞書の統合方式を参考にする価値はあるが、Androidを含むと断定せず、初手のSwift移植は避ける。デフォルト辞書は別submoduleであり、コードMITだけで辞書条件を判断しない。

[fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)はAndroid実装・clipboard・theme・候補UIの比較対象になる。READMEの日本語対応はAnthy pluginによるもので、Mozc搭載と読み替えない。主repoの表示はLGPL-2.1。部品を流用する場合は依存ライブラリとpluginの条件も確認する。

## 単一ニューラルモデルを決める実験

以下は実験計画案であり、corpus、split、metricの重みや学習条件はまだ確定していない。通常入力を収集・送信せず、同意して用意した固定例文と手元実機から始める。

1. **同条件化**: 統合試験はzenz v3.2 small Q5とjinen v2 small Q5から始めるが、品質比較には上記medium、Q8、F16/BF16も含める。同じ端末、runtime revision、CPU threads、文脈上限、熱状態、計測方法で比較し、同一checkpoint内の量子化比較と、世代・サイズを跨ぐ比較を分ける。各モデル固有のpromptと正規化要件は保持し、差として記録する。推論単体だけでなく候補生成を含む総時間も測る。
2. **逐次入力を再生**: 完成文だけでなく、各打鍵、削除、途中挿入、候補選択、読点、数字、英字、URL、氏名、emojiを含む操作列を与える。正答候補率、候補修正回数、以前正しかった部分の誤変更を測る。
3. **安全条件を先に適用**: ユーザー固定segment、数字、URL、識別子の無断変更は不採用。古い結果の適用、Editor越境、sensitive fieldの文脈利用も不採用。点数の平均でこれらを相殺しない。
4. **UXで比較**: 同じ候補UIで訂正操作数、確定不要で完成できた割合、文完成時間、first-correct latency、ちらつきを比較する。公開benchのexact/NFKC accuracyは補助指標とする。
5. **端末条件を適用**: p50/p95/p99、cold/warm、PSS増分、長時間入力中の電力・発熱・性能低下を分ける。品質優位の候補が旧端末では遅い場合は、最低対応端末条件を引き上げる案も比較する。最終的に宣言する対応端末では操作応答を満たすことを採用条件にする。
6. **勝者を1つ配布**: 基準を通り、訂正コストで勝る1モデルを採用する。差が判断できなければ、少ないmemory/電力/統合保守費用を優先する。製品内で自動的にモデルを切り替える構成にはしない。

モデルごとのiteration数、cache、文字数、量子化、thread数、端末SoC/RAM/OS、runtime SHA、計測build、入力例文versionを記録する。推論中のキャンセルが実際に計算を止めたか、結果破棄だけかも分ける。

### 追加学習・自作へ進む条件

既存最良モデルでも、同じ種類の誤りが再現し、それが辞書追加、読み保護、候補生成、更新抑制では解消しない場合に追加学習を検討する。まず失敗例を「候補に正答なし」「順位誤り」「入力途中の不安定」「固有名詞不足」「読み・数字破壊」に分類する。単に新しいモデルが欲しいという理由では始めない。

追加学習で必要品質に届かない、または学習データ条件・量子化後の性能・Androidでの電力が根本制約であると確認した場合のみ、新規モデルを設計する。dataset、split、Loss、seed、checkpoint選択、baseline、評価方法を確定・承認してから学習する。費用・期間・改善判定・中止条件が不明なまま学習を開始しない。

## 再利用と自作の境界

| 再利用を優先するもの | 自作すべきもの |
|---|---|
| 辞書・候補生成・文節情報、GGUF runtime、検証済みモデル、ユーザー辞書互換形式 | Androidのsession所有権、読みと表示の対応、安定／明示選択状態、非同期結果の採否 |
| Sumire等のフリック、JNI、設定、互換性事例 | 目的の訂正UX、意味単位Undo/Redo、プライバシー方針と学習の確定時点 |
| 既存benchのうち利用条件と目的が合う部分 | 実際の入力操作列、Editor別互換試験、ちらつき・訂正コスト・実機電力の評価 |

## 未決・導入前の確認事項

- 対象実機のSoC/RAM、通常の入力長、許容PSS・電力・cold start。
- Mozc JNIを固定revisionでビルドし、segment／候補APIが必要な操作を公開できるか。
- zenz v3.2のmodel card不足、実際のtokenizer/runtime互換、学習データ説明。
- jinenのNFKC要件を満たしながら、元の数字・記号・幅を保持する方法。
- Sumireから取り出す部品のAndroid依存と、同梱辞書の由来・権利表示。
- 実装時に各upstreamのcommitとlicenseを再固定すること。本調査はbranch URLを含み、全repoのhead SHAは未取得。
- 権限なしの更新経路はAPK更新またはユーザーが選択したモデル／辞書ファイルのimportを基本候補とする。実行時自動downloadは採用しない。Sumireの[ビルド時モデル同梱方式](https://github.com/KazumaProject/JapaneseKeyboard#zenz-モデルをビルド時に生成する)が先行例で、公開例のxsmall revisionは`4f5423f0fad41a73b1242eb96fe5c12ae4fdca83`。
