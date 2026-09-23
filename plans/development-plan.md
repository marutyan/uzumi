# 開発計画と進捗

## 現在地

Phase 0はPR #1で完了し、2026-09-21のユーザー指示でPhase 1へ着手。入力基盤とキーUIはAPK生成・単体テスト49件・lint・独立レビューを経て、2026-09-22に[PR #2](https://github.com/marutyan/uzumi/pull/2)でmainへ統合した（`a4dddf2`）。Pixel 10 Proの試用画面で見つかった画面端の重なりとカーソル編集の誤りは[実機で再確認](../docs/phase1-device-validation.md)し、50件の単体テスト、APK生成、lint、独立レビューを経て[PR #3](https://github.com/marutyan/uzumi/pull/3)でmainへ統合した。Phase 1cではMozcの[ローカルビルド試作](../docs/mozc-build-probe.md)が成功した。QWERTYと数字を含む基本入力は試用欄で確認したが、別アプリや機密欄との互換性・性能は未検証であり、漢字変換とユーザー辞書を含むPhase 1全体の完成ではない。

| 作業 | 状態 | 成果物・証拠 |
|---|---|---|
| 要求整理と前提確認 | done | Android 11暫定下限・必要なら引上げ可、INTERNETなし、製品のニューラルエンジン一つをユーザーが指定 |
| Android API調査 | done | docs/research/android-platform.md |
| エンジン・ライセンス調査 | done | docs/research/conversion-engines.md |
| UX・評価調査 | done | docs/research/ux-and-evaluation.md |
| 状態設計とMVP・段階計画 | done | docs/phase0-proposal.md、docs/live-conversion-design.md、本書 |
| 独立レビューと文書検証 | done | 2026-09-20、7文書の要求/API保証/license区分/状態/MVP整合を確認し合格。Android 17メモリ制限の指摘を修正して再確認。相対リンク・表列数・fence・git diff --cached --checkも合格 |
| Phase 1a 入力基盤 | in_progress | 50単体テストとPixel試用欄の基本編集は合格。2026-09-23にIMEと別processのEditText・WebViewで、inputTypeとaction、選択範囲の置換、フィールド・アプリ・IMEの切替、バック、回転、プロセスの再作成を試し、欠落・二重入力・別接続への書込みは無かった（[互換試験](../docs/phase1-compatibility.md)）。Compose TextField、ライブ変換OFF、通常のQWERTY、hardware keyboard等は未実行 |
| Phase 1b キーUI | in_progress | 12-key、英語QWERTY、数字の試用欄入力と最下段操作をPixelで確認。2026-09-23に記号面（3ページ）、QWERTYから数字・記号への直接切替、削除・カーソルの連続実行、QWERTY長押しの数字・記号、触覚、TalkBack用の読み上げ・操作メニューを実装し、63単体テスト・APK・lintは合格（[記録](../docs/phase1-validation.md#キー入力の追加2026-09-23)）。実機での操作・TalkBack・触覚は未確認 |
| Phase 1c 漢字変換・辞書 | in_progress | [Mozcのnative・辞書・Java liteのローカルビルド](../docs/mozc-build-probe.md)成功。[配布物監査](../docs/mozc-distribution-audit.md)でNOTICE添付を条件に本人用debug APKへの同梱可と判定し、[`NOTICE.txt`](../third_party/mozc/NOTICE.txt)を作成。APK組込み・端末変換は未実施。ユーザー辞書は保存・登録・検索・編集・削除・TSV入出力・管理画面・参照APIを実装しJVMテストで確認したが、実機操作とIME候補への接続は未実施 |
| Phase 2a ライブ状態 | in_progress | Android・Mozcに依存しない状態機械`dev.uzumi.ime.live`を実装し、古い結果の拒否、安定化、chosen保護、境界、ON/OFF、Undo/Redo、書記素境界を試験用変換器の決定的なイベント列32件で確認（2026-09-23）。2026-09-23にIMEへ配線し、Mozcの部分範囲ごとの変換と確定時の学習、候補バーの過去segment訂正・末尾復帰・取消、ユーザー辞書の登録語の候補、設定のON/OFF（既定ON）と第三者ライセンス表示を接続。164単体テスト・APK・lintは合格、Pixelの試用画面で表示・句点確定・訂正・Undo・OFF時の明示変換・登録語・password欄を確認（[記録](../docs/live-conversion-validation.md)）。文脈を含む学習の効き方、Compose/WebView、性能は未確認 |
| Phase 2以降 | pending | Phase 1の受入条件を満たしてから開始 |

## 二つの到達点

**基盤MVP（Phase 1）**：12-key、英語QWERTY、数字・記号、composing、常時候補バー、基本変換、確定、削除、カーソル、editor actionが互換試験を通る。明示変換を使い、入力基盤の正しさを確認する。

**ライブ変換MVP（Phase 2）**：基盤MVPに非同期変換、segmentの安定化と選択保護、IME内からの過去segment訂正、境界処理、ON/OFF、composition内Undo/Redo、最小ユーザー辞書を加える。正しい文で確定操作を省けることを測定する。自分が常用できるかの評価を開始する段階であり、完成した一般公開版ではない。

ユーザー辞書は必須要件のため、Phase 1で登録・検索・編集・削除の最小形、Phase 2終了までにimport/exportを用意する案とする。Phase 4では大量編集・形式互換・管理UIを充実する。

## 段階と検証

| Phase / 作業単位 | 依存・担当範囲 | 完了条件・検証 | 次へ進めない条件 |
|---|---|---|---|
| 0 調査・設計 | API、変換器、UXを独立調査し統合 | 一次資料、license区分、状態遷移、要求対応、未決事項の独立レビュー | APIの保証や未実測値を事実として記載している |
| 1a 接続・状態 | IME基盤担当。開発環境とSDK導入範囲を先に決める | EditText/Compose/WebView試験、inputType/action、selection、接続切替、再作成 | 文字の欠落・二重入力・別接続書込み |
| 1b キー入力 | UI担当。1aの入力イベント契約を共有 | flick、濁点/半濁点、小文字、long press、repeat、QWERTY、数字記号、haptics、TalkBack | ジェスチャーの誤判定や押下が推論待ちになる |
| 1c 基本変換・辞書 | 変換担当。ライセンスとAndroidビルドの試作後に採用 | offline候補、読み・範囲の整合、辞書CRUD、数値・固有名詞、modelなし起動 | 配布許諾不明、戻り値とalignmentが不整合 |
| 2a ライブ状態 | 編集状態担当。1a/1c完了後 | 決定的なイベント列で安定化、chosen保護、古い応答拒否、境界、ON/OFF | 過去の正しい保護segmentを書き換える |
| 2b 訂正とUndo | UI担当と状態担当、所有範囲を分ける | 過去segment訂正・末尾復帰、「よい→良い」、候補誤選択の一回Undo/Redo、辞書入出力 | 文全体の再入力を要する、Undoが外部編集を壊す |
| 2c 常用試験 | 評価担当。機種と条件を固定 | 同じ文課題でライブON/OFF、既存IMEと操作数・時間・誤書換え比較 | 安全性の未解決不具合、訂正コストの悪化 |
| 3a 単一neural選定 | 変換担当。候補を開発時だけ入替え評価 | 品質・latency・RSS・cold start・battery・OSメモリ制限下の終了有無と訂正率を同じ条件で測定 | 品質向上が入力遅延や過去書換えを悪化させる、メモリ制限で終了する |
| 3b 品質改善 | 3aとデータ条件の合意後 | 文脈・reranking・学習・辞書、held-outで比較、個人学習削除確認 | test混入、条件不明、秘密情報利用、改善の再現不能 |
| 4a Autofill | Android連携担当。provider含む実機環境 | username/password/address、利用可能ならpasskey、認証/取消、候補共存、秘密がIMEへ露出しない | 独自credential取得、すべてのproviderで動くと誤認 |
| 4b 日常パネル | 保存方針確定後にUI/保存担当を分離 | Clipboard pin/削除/全削除/検索/期限/重複、emoji/顔文字/記号の分類・最近・検索・お気に入り、定型文、辞書互換 | sensitive保存や期限超過保持、import破損で既存データ消失 |
| 5 外観設定 | 入力設定と分離したデータ形式担当、描画担当 | 背景画像、サイズ、片手、色・文字・透過・明度、テーマ保存/複製/入出力、回転/大文字設定 | タップ領域の破壊、読めない初期値、テーマimportの安全性不足 |

各作業単位を検証後にcommitし、目的が一つにまとまったPRを作成・確認・mergeする。依存先をmergeしてから次の固定した開始点を選び、並列担当の所有範囲を重ねない。レビュー担当は作成担当と分ける。今回の調査PR以降も、無関係な設定変更・公開release・ライセンス決定を包括的に許可されたものと解釈しない。

## 共通の互換試験

最小OS/API 30、中間API 33/35、調査時の最新安定版を対象に、arm64実機の性能とemulatorのAPI互換性を分ける。previewは補助試験にする。接続端末はPixel 10 Pro、Android 17/API 37、約16 GB RAMと確認した。これを常用端末とするかは未確認で、実機性能の合格もまだ判定していない。

View EditText、Compose TextField、WebView、Chrome、単一行/複数行、Search/Send/Done/Next、URL/email/number、password/PIN、no-personalized-learning、選択範囲の置換を含む。アプリ・OS・WebView/providerのversionと試験日を記録する。

回転、split screen、insets/ジェスチャーナビ、hardware keyboard、IME切替、バックキー、フォーカス喪失、低メモリ・プロセス再作成、入力中モデル切断/失敗を追加する。未実行のセルを合格扱いにしない。

Android 17の実機では`adb shell am memory-limiter status`で制限状態を記録し、モデル・辞書・UIの同時使用を評価する。`ApplicationExitInfo`の`REASON_OTHER`かつdescriptionに`MemoryLimiter:AnonSwap`を含む終了は採用試験の失敗とする。制限を無効化した計測で合格させない。詳細は[Android調査](../docs/research/android-platform.md)を参照する。

## 常用機能の設計方針

- Clipboardは履歴、定型文は意図して保存した再利用文としてデータ上は分け、共通パネルからアクセスできるようにする。pinは自動削除の例外として明示する。保持数・期限・backupは採用前に決める。
- Emoji/顔文字/記号はオフライン検索可能な許諾済みデータを用いる。検索語とカテゴリ・最近使用・お気に入りを共通化し、入力内容に応じた混在候補は後から評価する。
- Themeはversionを持つデータとし、外観と入力挙動の設定を分離する。背景画像はファイル選択を使い、bitmapサイズ制限と縮小を行う。fontやblur等は効果と描画コストを確認して段階導入する。
- 常時toolbar一行は初期既定にしない案とする。候補行の入口からパネルを開き、Inline Suggestions表示時は高さと訂正への戻りやすさを測る。発見可能性を実機で検証して確定する。

## 次の一手

1. `mozc.data`の入力資産、郵便番号データ、nativeの実リンク閉包、Java lite runtimeを棚卸しし、APK同梱用のNOTICEを確定する。完了：[配布物監査](../docs/mozc-distribution-audit.md)。
2. 配布条件を満たした資産と最小JNIブリッジを接続し、Pixel 10 Proで既知のかな漢字変換、辞書不在fallback、session破棄を確認する。
3. Phase 1a/1bの未実施互換試験と、Phase 1cのユーザー辞書を進める。性能選定の実験条件は実行前に固定する。

本計画の検証基準・性能値は提案であり、まだ測定を実施した研究条件ではない。実験開始前に課題集合、比較条件、測定方法と合格基準を固定し、実行後に都合よく変えない。
