# uzumi

確定操作を減らし、誤変換をその場で直せるAndroid向け日本語IMEを目指すプロジェクトです。

現在はPhase 0の調査・設計段階です。Androidアプリ、変換性能、日常利用での互換性はまだ検証していません。

- [要求・推奨方針](docs/phase0-proposal.md)
- [Android公式APIと制約](docs/research/android-platform.md)
- [変換エンジンの比較](docs/research/conversion-engines.md)
- [既存IMEのUXと評価](docs/research/ux-and-evaluation.md)
- [ライブ変換の状態設計](docs/live-conversion-design.md)
- [段階的な計画・進捗](plans/development-plan.md)

ユーザーが選んだ候補と安定した過去の変換を保護し、通常の入力内容をサーバーへ送らず、日々の入力速度・訂正しやすさ・見た目を優先します。
