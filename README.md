# uzumi

確定操作を減らし、誤変換をその場で直せるAndroid向け日本語IMEを目指すプロジェクトです。

Phase 1の入力基盤として、Android IMEサービス、かな・英字・数字キーボード、composition、かな・カナ候補、確定・削除・カーソル・editor actionを実装しています。設定画面からIMEの有効化と切替へ進み、同じ画面のEditTextで入力を試せます。

漢字変換、ニューラル変換、ユーザー辞書はまだ含みません。ローカルのbuild・JVMテスト・lintは通過し、Pixel 10 Proの試用画面で基本入力を確認しました。別のアプリや機密欄との互換性はまだ未検証です。確認済みの範囲は[Phase 1のローカル検証](docs/phase1-validation.md)と[実機確認](docs/phase1-device-validation.md)に記載します。

- [要求・推奨方針](docs/phase0-proposal.md)
- [Android公式APIと制約](docs/research/android-platform.md)
- [変換エンジンの比較](docs/research/conversion-engines.md)
- [既存IMEのUXと評価](docs/research/ux-and-evaluation.md)
- [ライブ変換の状態設計](docs/live-conversion-design.md)
- [段階的な計画・進捗](plans/development-plan.md)

ユーザーが選んだ候補と安定した過去の変換を保護し、通常の入力内容をサーバーへ送らず、日々の入力速度・訂正しやすさ・見た目を優先します。

## ローカル確認

Android SDKを指定できる環境で、次を実行します。

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug
```

debug APKは`app/build/outputs/apk/debug/app-debug.apk`へ生成されます。このプロジェクトは`INTERNET`権限を宣言しません。
