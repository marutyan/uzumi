# 音声入力（GboardのAI音声入力の再現）の調査

確認日：2026-11-14。公式資料、報道、個人の解析を区別して記録する。実機での品質、速度、電池は未検証。

## 結論

- GboardのAI音声入力（英語名Rambler）の完全版は、Googleのサーバーで処理される（音声も送られる）。Googleは、この機能を他のアプリ向けのAPIとして公開していない。uzumiの方針（入力内容をサーバーへ送らない）と合わないため、クラウドは使わない（ユーザーが2026-11-14に決定）。
- 以前からのPixelの高精度な音声入力は、端末内の音声認識と自動句読点による。uzumiから使える端末内の部品を組み合わせ、フィラー除去と言い直しの反映を端末内で行う方式を、試作で比べて選ぶ。

## Gboardの音声入力の構成

| 段 | 内容 | 処理の場所 | 根拠 |
|---|---|---|---|
| 従来の音声入力（Pixel 6以降） | 端末内の音声認識と自動句読点。日本語に対応 | 端末内。公式に「サーバーへ送らない」とある | [Gboardヘルプ 11197787](https://support.google.com/gboard/answer/11197787) |
| AI音声入力のオフライン時 | 基本的な整形（フィラー除去）、句読点、大文字化 | 端末内。使うモデルは不明 | [Gboardヘルプ 17468539](https://support.google.com/gboard/answer/17468539) |
| AI音声入力の完全版 | フィラー除去、言い直しの反映、声での編集、言語の混在 | Googleのサーバー（「temporarily processed by Google」） | 同上。Googleは2026-08-26の発表で、Gemini 3.5 Transcribeとの関係を述べている（[発表](https://blog.google/innovation-and-ai/models-and-research/gemini-models/gemini-3-5-transcribe/)） |

- 音声認識はSpeech Services by Google（`com.google.android.tts`）が担っているとみられる。根拠は、非公式移植PixelBoardのissueに書かれた個人の解析だけである。
- AI音声入力は、話している間は文字を出さない。確定の操作の後に、整形済みの文をまとめて出す（Gboardヘルプ、9to5Google 2026-08-13）。
- 提供範囲：Pixel 11（2026-08）に続き、2026-11-10のPixel DropでPixel 10シリーズにも提供された（[Pixel Drop](https://blog.google/products-and-platforms/devices/pixel/november-2026-pixel-drop/)）。有効にするには、AICoreの更新とGboard 18.6系が要るという報道がある（Android Authority 2026-11-12）。
- 日本語の例（記事から）：
  - フィラーそのものを話題にした文では、消さずにカッコで残した（テクノエッジ 2026-08-27）。
  - 「やっぱりバナナはやめて」でリストからバナナが消えた（ケータイWatch 2026-05-13、Googleのデモ）。

## 試験した端末

Pixel 10 Pro。Android 17 QPR3 Beta 4（build `DP11.261030.003`、2026-11-12公開）、Gboard 18.5.6、AICore 20261015。

- `config_defaultOnDeviceSpeechRecognitionService`は`com.google.android.as/com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService`（Android System Intelligence）である。
- 既定の`voice_recognition_service`は`com.google.android.tts/...GoogleTTSRecognitionService`である。

## uzumiで使える部品

| 部品 | 処理の場所 | 分かっていること | 主なリスク |
|---|---|---|---|
| `SpeechRecognizer.createOnDeviceSpeechRecognizer` | 端末内（この端末ではASI） | 標準API。`EXTRA_ENABLE_FORMATTING`で句読点を要求できる。フィラー除去の指定はない | 日本語の品質と句読点は未確認 |
| 既定の`createSpeechRecognizer` | サーバーへ送る可能性がある | — | 方針に合わないため使わない |
| ML Kit GenAI Speech Recognition（Advanced） | 端末内（Gemini Nano、AICore） | ja-JPに正式対応。Pixel 10に対応。alpha版 | 推論は「最前面のアプリだけ」に許される（`BACKGROUND_USE_BLOCKED`）。IMEは最前面のアプリではない。AARが`INTERNET`を足す |
| ML Kit GenAI Prompt API | 端末内（Gemini Nano） | 入力4000トークン未満。beta版 | 同上 |
| Gemma 4 E2B（llama.cpp） | 端末内（既存の`:neural`の仕組み） | Apache 2.0。音声を入力でき、30秒まで | 日本語の品質、速度、メモリは未確認 |

出典：[ML Kit GenAI Speech Recognition](https://developers.google.com/ml-kit/genai/speech-recognition/android)、[ML Kit GenAIの概要](https://developers.google.com/ml-kit/genai)、[RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent)、[Gemma 4 model card](https://ai.google.dev/gemma/docs/core/model_card_4)。

## 試作と比べ方

3つの試作をdebugビルドだけで作り、同じ録音で比べる。

| 試作 | 内容 | branch |
|---|---|---|
| A | IMEから端末内の認識器で日本語を認識する。句読点とフィラーが残るかを見る | `probe/voice-ondevice-recognizer` |
| B | IMEからML KitのGemini Nano（音声認識、Prompt API）が推論できるかを見る | `probe/voice-mlkit-genai` |
| C | Gemma 4 E2Bで、音声から直接整形済みの文を出す。文字起こしの結果を文字だけで整形する方式も比べる | `probe/voice-gemma4-audio` |

- 入力は、ユーザー本人が[台本](../voice-input-script.tsv)を読んだ録音とする。録音はGit追跡外の`.local-build/voice-eval/recordings/`に置き、リポジトリへ入れない。
- 基準として、同じ録音をMacのスピーカーから再生し、端末のGboard（AI音声入力）に入力した出力も記録する。
- 見るものは次の4つ。
  - 台本の許容表記と一致した件数。
  - 残ったフィラーの数。
  - 言い直しを正しく反映した件数。
  - 話し終えてから文が確定するまでの時間。
- 方式を選んだ後に、電池を測る。測り方はPhase 3aと同じで、Mozcだけの場合との比が1.2以内を目安とする。
- この比較は探索であり、方式を選ぶために使う。件数が少ないため、品質の差を一般化しない。
