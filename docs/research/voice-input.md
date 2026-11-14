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
- 2026-11-14、ユーザーは整形の範囲を「フィラー除去と句読点だけ」に決めた。言い直しの反映は対象外とする（試作Cで、Gemma 4 E2Bは4件とも反映できなかったため）。

## 試作の結果（2026-11-14、Pixel 10 Pro、本人の録音16件）

生の記録は、Git追跡外の`.local-build/voice-eval/results/`に置いた。試作の手順と出力全文は、各branchにある。

| 部品 | IMEから使えるか | 認識・整形の質 | 速さと重さ |
|---|---|---|---|
| A：端末内の認識器（ASI） | 使える | 誤りは少ない（「Google Meet」→「Googleミント」程度）。句読点は`EXTRA_ENABLE_FORMATTING=quality`で付く。フィラー（えっと、え、あの、まあ、なんか）は残る | 末尾に足した1.5秒の無音を流し終えてから確定まで0.03〜0.16秒（無音なしでは0.05〜0.56秒だが語尾が欠ける）。マイクでは無音の検出待ちが加わる |
| B：ML Kitの音声認識（Advanced） | **使える**（別アプリが最前面、uzumiのキーボードを表示中で成功） | 読点が細かく入る。フィラーは残る | ファイル一括で、20秒の音声に8〜10秒 |
| B：ML KitのPrompt API（整形） | **使えない**（`BACKGROUND_USE_BLOCKED`、error 30） | uzumiが最前面なら、フィラーをきれいに除いた | 1.25秒（最前面のとき） |
| C：Gemma 4 E2B（音声を直接） | 未試験（品質不足のため端末では測らず） | Macで許容表記との一致0〜3/16。聞き取りの誤りが多い | Macで音声長の約0.15倍 |
| C：Gemma 4 E2B（Aの結果を文字で整形） | 未試験（llama.cppなので制限は受けない見込み） | 「え」「えっと」は消すが、「あの」「まあ」「なんか」は残る | 端末のCLIで1件6〜10秒（読み込み1.6〜4.8秒を含む）、最大RSS 4.4GB |
| 規則（境界のはっきりしたフィラーだけ消す） | 使える | 消せる範囲はGemma 4 E2Bとほぼ同じ。話題としてのフィラー（V12）を消し、句点を巻き込む誤りがある | ほぼ0 |

- 端末内の認識器は、ファイル入力の末尾で語が欠けた。1.5秒の無音を足すと戻った。確定結果（`onResults`）は空で、本文は最後の途中結果に入っていた。
- 試験中にAPKを入れ直すと、既定のIMEがSimejiへ変わった。最初のBの試行はこのため条件を満たさず、記録から除いた。

## 決めた方式（2026-11-14）

- ユーザーは、Aの端末内の認識器に、規則によるフィラー除去を足す方式に決めた。規則で消すのは「え」「えー」「えっと」などの境界がはっきりした語だけで、「あの」単独、「まあ」「なんか」は残す。
- Gemini Nanoによる整形は採らない。Prompt APIはIMEから呼べず（`BACKGROUND_USE_BLOCKED`）、AICoreはGoogleのアプリなどの一部にしか直接の利用を許していないためである。uzumiの画面を一瞬最前面に出して回避する方法は、話すたびにキーボードが閉じて開き直ることと、制限の回避にあたることから試さない。
- マイクのボタンなどの見た目は、別に進んでいるUIの作業に合わせる。
- ユーザーは、話した日本語をその場で翻訳する機能も望んだ。ML Kit GenAI Speech Translation（`genai-speech-translation:1.0.0-alpha1`、端末内、Pixel 10に対応）が、IMEから呼べるかを試作Bのbranchで確かめる。

## 音声翻訳の試作の結果（2026-11-14、Pixel 10 Pro、本人の録音16件、日本語→英語）

試作はbranch `probe/voice-mlkit-genai`（commit 9c3e766）にある。生の記録はGit追跡外の`.local-build/voice-eval/results/t/`に置いた。

- **IMEから使える。** 条件は、連絡先の入力欄を最前面にし、uzumiのキーボードを表示した状態（importance 125）である。この条件で、状態の確認、日本語→英語の翻訳、マイク入力のいずれもエラーなく通った。英語→日本語も使える状態になった。
- モデルは約33MBを追加で取得し、4.4秒かかった。実体はSpeech Services経由のAICoreのモデル（`nano-v4-full`）である。`INTERNET`を外したdebugビルドでも動いた。
- 訳文は、端末内の発話区切りごとに1文ずつ確定して届く。途中結果（後から書き換わる文）と、日本語の書き起こしは返らない。
- **最後の文を受け取るには、言い終えた後に約2秒以上の無音を流し続ける必要がある。** 音声の入力を終えると完了がすぐに返り、まだ確定していない最後の文は捨てられる。`stopTranslation()`でも同じだった。録音の末尾に足した無音の長さで比べると、最後の文まで出た件数は次のとおりである。
  - 無音なし：7/16
  - 1.5秒：10/16
  - 4秒：16/16
- 最後の文は、録音の末尾から0.6〜2.2秒後に出た。短い文は言い終えるまで何も出ないため、最初の文が出るまでに4〜8秒かかった。
- 訳文には、フィラーが英語として残った（Well、Um、oh、you know）。誤訳もあった。
  - V02「寝不足気味」→「it's a bit lacking」
  - V06「さっき言ったのはなしで」→「I didn't go.」
  - V10「Pixel」→「X-Cell」
  - V12は意味が変わった。
- 試験の途中で別の作業が同じ端末を操作し、一部の試行は条件を満たさなかった。それらの試行は捨て、操作が止まった後にやり直した。表の値は、やり直した試行だけから作った。

## 本体の実機確認で分かったこと（2026-11-14、Pixel 10 Pro）

Macのスピーカーで本人の録音を流し、連絡先の入力欄に入った文を読んで確かめた。

- 端末内の認識器にマイクを任せると、話の途中の短い間で認識が終わった（V13で約8秒の時点）。区切りの付け直しのために、それより前の文も途中結果から消えた。
  - 確定の段では、本文は途中結果にしか入らず、`ERROR_NO_MATCH`（「no speech」）で終わった。
  - 無音の長さで終える区切りつきの連続認識（`EXTRA_SEGMENTED_SESSION`＝`EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`）を指定しても、認識器は1回きりの認識（`AMBIENT_ONESHOT`）のまま動いた。
- そこで、Uzumiが`AudioRecord`で録った音をpipeで認識器へ渡し、pipeを閉じるまで続く区切りつきの連続認識（`EXTRA_SEGMENTED_SESSION`＝`EXTRA_AUDIO_SOURCE`）に変えた。
  - この形で、V13（約30秒、3文）は最後の文まで入り、「えっと」「え」も消えた。V07とV15も、期待どおりに入った。
- キーボードを表示しているIMEのprocessで`AudioRecord`を使っても、音は無音に置き換えられなかった（`silenced:false`）。
  - ただし、Macのスピーカーから離れて流した音は小さかった（最大値で約-24dBFS）。Macの音量が75では訳がほぼ崩れたため、確認は音量100で行った。端末に向かって話す場合との差は確かめていない。
