package dev.uzumi.ime.live

/**
 * ライブ変換コアと変換エンジン（Mozc、将来のニューラル変換）をつなぐ小さなport。
 * コアはこのportを直接呼ばず、統合担当がUIスレッド外の直列workerで呼んで結果をコアへ返す。
 *
 * 実装は次を守る。
 * - 対象範囲`request.targetReading`だけをsegmentへ分け、各segmentの読みを連結すると対象範囲の読みに一致させる。
 * - 対象範囲内の`protectedRanges`は、その境界で必ず区切る。表記はコアが保持するため変えても無視される。
 * - `inputCursor`が対象範囲の内部にあれば、その位置で必ず区切る（Mozcではカーソル位置で分けて変換する）。
 * - `learningAllowed`がfalseなら、変換前から学習と履歴保存を止める（Mozcでは`Request.is_incognito_mode`等）。
 * - 時間超過・モデル不在・失敗ではnullを返す。コアは現在の表示を保つ。
 *
 * Mozcでは、Preedit.Segmentの`key`を`ResultSegment.reading`、`value`を`surface`、
 * そのsegmentへ焦点を移したときのCandidateWindowの各`value`を`candidates`へ写す想定である。
 */
fun interface LiveConverter {
    /** 要求を変換する。呼び出し側は結果をそのまま`LiveConversionCore.onConversionResult`へ渡す。 */
    fun convert(request: ConversionRequest): ConversionResult?
}
