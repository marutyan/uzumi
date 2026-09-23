# 開発引き継ぎ仕様（2026-09-23）

この文書はClaudeが次の作業単位から再開するための現状記録である。調査・設計の根拠は[Phase 0提案](phase0-proposal.md)、[ライブ変換の状態設計](live-conversion-design.md)、[開発計画](../plans/development-plan.md)を参照する。ここにあるGit・端末状態は記録時点のものであり、再開時に必ず確認する。

## 目的と確定した方針

最終目標は日常的に使えるAndroid日本語IME。「正しく変換されている限り、確定操作なしで入力を続ける」を中心に、間違ったsegmentだけを低コストで訂正できるようにする。ライブ変換はON/OFF可能とし、明示選択した候補は後続の自動変換で変えない。初期の基盤MVPでは入力・Editor同期を優先し、ライブ変換はPhase 2で実装する。

- 対応下限はAndroid 11/API 30を暫定とし、ニューラル推論の性能上必要なら引き上げられる。
- APKに`INTERNET`権限を付けず、通常入力をサーバーへ送らない。モデル・辞書更新は同梱またはユーザーが選ぶファイル等を検討する。
- 製品のニューラルエンジンは一つ。選定前に複数候補を開発時の同じ条件で比較してよいが、エンジン切替UIは要件ではない。「最強」は実測前に断定しない。
- 当面は本人の常用を目指す。OSS・一般公開は品質と各資産の配布条件を確定してから判断する。

## 現在地

| 状態 | 作業単位 | 証拠と残件 |
| --- | --- | --- |
| done | Phase 0、[PR #1](https://github.com/marutyan/uzumi/pull/1) | API・変換器・UX調査、MVPと状態設計をmainへ統合（merge `2882dde`）。 |
| done | Phase 1入力基盤、[PR #2](https://github.com/marutyan/uzumi/pull/2) | 12-key、英語QWERTY、数字、composition、かな・カナ候補、確定・削除・カーソル・editor action。JVMテスト49件、APK、lint、独立レビュー（merge `a4dddf2`）。漢字変換はない。 |
| done | Pixelで見つかった問題の修正、[PR #3](https://github.com/marutyan/uzumi/pull/3) | 画面端の重なりと「あい」→左→削除→「か」で「いか」になる問題を修正。JVMテスト50件、APK、lint、独立レビュー、Pixel再試験（merge `8b46744`）。 |
| done | Mozcローカルビルド試作、[PR #4](https://github.com/marutyan/uzumi/pull/4) | 固定commitのnative・辞書・Java liteをビルドし結果を記録（merge `80637d3`）。APKへの組込み・端末上の漢字変換は未実施。 |
| in_progress | Phase 1a/1b/1cの受入 | 他アプリ・機密欄・回転等の互換性、記号/long press/TalkBack、漢字変換・ユーザー辞書が未完了。詳細は[進捗表](../plans/development-plan.md)。 |
| in_progress | Mozc配布物の監査 | NOTICE、事業所郵便番号・emoji等のデータ由来、native実リンク閉包、Java lite runtimeを確認中。公式条件の一部は確認済みだが、適用範囲は未確定。 |
| pending | Phase 2以降 | 安全な基盤変換と互換試験を先に満たす。ニューラル採用・常用機能・外観設定は後続段階。 |

[Pixel実機記録](phase1-device-validation.md)では、Pixel 10 Pro（Android 17/API 37）の**Uzumi自身の試用画面**で、修正版の最下段「わ」「改行」、カーソル編集の「かい」、カナ候補「カイ」、QWERTYの`ab`、数字の`12`を確認した。試験後は既定IMEを元のSimejiへ戻し、有効IME一覧も復元した。修正版APKは2,688,373 bytes、SHA-256 `53e0a0a06f90da1e14a6a2a6ca4864ebc6b2dea7a727be04dee3edea63e7008b`。端末の実画像は残っていない。EditText以外のアプリ、password/PIN、回転、再起動、実機性能は合格扱いしない。QWERTYから数字へは現在かな配列を経由する。

## Mozc試作の再利用可能な結果

詳細な命令・試行錯誤・ハッシュは[Mozcローカルビルド結果](mozc-build-probe.md)と[接続計画](mozc-integration-plan.md)。対象は公式`google/mozc` commit `13c98988247aa711d99db9e348ec2a597d14b5cd`、Bazel 9.0.2、NDK r29。実物はGit追跡対象外の`.local-build/mozc-probe/`にあり、再現できない場合はこの固定条件から作り直す。

| 実物パス（`.local-build/mozc-probe/`からの相対） | サイズ | SHA-256 |
| --- | ---: | --- |
| `artifacts/native_libs.zip`（4 ABI） | 15,753,780 bytes | `b04cbb1dbb0ec198cef85be8f74f967ba1b6bb2abbb7c1f28edfee234a63934c` |
| `artifacts/arm64-v8a/libmozc.so` | 16,182,000 bytes | `05e757129db76bfc66db0224b3cfa9b7d8bc1e0ccd986919a5b66a251128aeee` |
| `artifacts/mozc.data` | 18,994,682 bytes | `720d0cb42651fe35692578ea5f3c752940b8357fc24efc46230c904d2f5d5d70` |
| `artifacts/libcommands_proto-lite.jar` | 295,977 bytes | `b0b9497de0c9fc6f1f52f2069d856facc9d2401fdb1b942f82360b005634065a` |
| `artifacts/commands_proto-lite-src.jar` | 1,141,258 bytes | `d2104e904ee23e1f36f3477c7a71c6c66c6e22491def9750094e32949be60840` |

終了時の専用領域は約6.9 GiB、所要約18分。**総通信量は未計測**であり、20 GiB以下と証明できない。Android `package`とMac hostの辞書・Java liteを分けてビルドした。`native_libs.zip`だけでは辞書が入らず、`mozc.data`がない場合はJNIがminimal engineへfallbackし得る。ビルド成功は端末上の変換成功・品質・配布許諾の証拠ではない。

Mozc本体のBSD 3-Clause系条件、OSS辞書の複数条件、固定された`japanese-usage-dictionary`のBSD 2-Clauseは一部確認済み。ただし郵便番号についてREADMEの「OSS版に含まない」とBUILDの`zip_code_data`参照が食い違う。日本郵便の[住所郵便番号CSV説明](https://www.post.japanpost.jp/service/search/zipcode/download/utf-readme.html)は使用・再配布等を自由と明記するが、同BUILDで使う[`JIGYOSYO.CSV`の説明](https://www.post.japanpost.jp/service/search/zipcode/download/office/readme.html)にその条件をどこまで適用できるかは未確認。`emoji_data.tsv`の生成元CLDRファイル・版も未特定で、[Unicode Terms](https://www.unicode.org/copyright.html)のLicense v3表示をこの生成物へ適用してよいかは未確定。顔文字・記号等も含め、実際に生成物へ入る資産と条件を確認する。`libmozc.so`の実リンク閉包、Java protobuf lite runtimeの版と条件、APKに添付するNOTICEは未確定。

## 接続時に守る設計

配布物の棚卸しとNOTICEを確定した後、既存の[Editor接続](../app/src/main/java/dev/uzumi/ime/editor/EditorSession.kt)へ最小JNIブリッジを接続する。公式JNIのJava class名は固定され、`initialize()`、`onPostLoad(profilePath, dataPath)`、`evalCommand(byte[])`、`getDataVersion()`を使う。Java lite生成物と整合するruntimeを選び、SessionHandlerを一つの直列workerから呼ぶ。Editor sessionとMozc session IDは別管理し、作成→読み入力→候補→確定→破棄を一連で検証する。

キー表示と読み更新は変換を待たせない。変換結果は`sessionEpoch`、`revision`、読み、対象範囲、保護segment、辞書/モデル世代を照合してから適用し、古い応答が新しい入力や別フィールドを上書きしない。`provisional`だけを自動更新し、`stable`・明示選択済み`chosen`を保護する。機密欄や`IME_FLAG_NO_PERSONALIZED_LEARNING`では**要求を送る前に**学習・文脈利用を止める。応答を後で破棄するだけではエンジン側の学習を防げない。辞書欠落・破損、時間超過、低メモリ時は読みまたは安全な既存候補へ戻し、`getDataVersion()`と既知のかな漢字例でminimal engineへのfallbackを検出する。

composition内のUndo/Redoと過去segment訂正の保証範囲、読みと表示のalignment、句点/改行と読点の境界は[状態設計](live-conversion-design.md)に従う。Android Editor内の任意の過去文字列を常に置換できるとは仮定しない。

## 次の作業単位と完了条件

1. **配布物監査を閉じる。** 固定checkoutのBUILDと実生成物を照合し、郵便番号・emoji等の由来、nativeに実際に入る第三者コード、Java lite runtimeのversion/配布条件を確定する。APKに入る全資産の著作権表示・条件・免責条項を漏れなくNOTICEにまとめる。出典と未確認事項を文書化し、独立レビューで食い違いと漏れを点検する。未解消なら同梱へ進まない。
2. **最小Mozc接続を別作業単位で実装する。** 配布条件と新しいAPK同梱の扱いを確認してから、JNI class、同versionのJava lite/runtime、辞書のロード、sessionの生存期間を最小差分で接続する。JVMテストだけでなくPixelで既知のかな漢字変換、候補、確定、破棄、辞書欠落fallback、機密欄・フィールド切替を検証し、アプリのmerged manifestに`INTERNET`がないことを確認する。固定差分の独立レビューを通す。端末試験では既定IMEを復元する。
3. **Phase 1受入を完了する。** EditText/Compose TextField/WebView/Chrome、inputTypeとeditor action、選択・接続切替・回転・再作成、12-keyの長押し/記号/TalkBackを互換表で試す。ユーザー辞書の登録・検索・編集・削除と再起動後復元を実装する。入力欠落、二重入力、別接続への書込みがあればPhase 2へ進まない。
4. **Phase 2以降を計画順に進める。** incremental変換、segment安定化・局所訂正・Undo/Redo、ライブON/OFFを決定的なイベント試験と実機操作数で評価する。その後に単一ニューラルエンジンを同条件の訂正率・遅延・メモリ・電池で選定する。評価条件は実験前に固定する。

各作業単位で関連箇所を直接読んでから編集し、テスト・ビルド・固定差分レビュー後にcommitする。PR作成・mergeの前には対象差分、CI、GitHubの現在状態を確認する。通常の実装は分担し、PMは判断と統合を担う。

## 承認境界と再開確認

ユーザーが許可したMozcの取得・ビルドは、固定commitとBazel/NDKを`.local-build/mozc-probe/`だけで扱う**ローカル試作**である。Pixelの許可は既存の検証済みdebug APKをインストールし、Uzumi自身の試用画面で架空の文章を入力して元のIMEへ戻す具体的な試験範囲である。Mozc資産を新しいAPKへ同梱することや一般公開まで自動承認されたものと解釈しない。配布条件未確定の資産を現在のAPKへ入れない。通常入力をネット送信しない。未実機の変換品質を断定しない。

記録時点の作業ブランチは`feature/mozc-notice-inventory`、HEADとローカルの`origin/main`は`80637d3`（PR #4 merge）。文書作成前のworking treeはclean。GitHub APIへの接続はこの確認時に失敗したため、再開時は`git status --short --branch`、`git fetch origin`、`git log -5 --oneline --decorate`、`gh pr list`でremoteのPR・CI・mainの位置を再確認する。`.local-build/`は`.gitignore`対象で、生成物をcommitしない。端末の既定IMEと接続状態も試験前に再確認する。
