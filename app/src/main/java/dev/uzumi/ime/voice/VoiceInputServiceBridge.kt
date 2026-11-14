package dev.uzumi.ime.voice

/**
 * IMEサービスが起動している間だけ音声入力と音声翻訳の開始・停止・取り消しの入口を公開する受け渡し口。
 * debugビルドのbroadcast受信からIMEと同じプロセス内で呼ぶために使い、releaseビルドでは外部から呼ばれない。
 * メインスレッドから読み書きする。
 */
object VoiceInputServiceBridge {
    /** 音声認識を開始する関数。IMEが起動していなければnull。 */
    var starter: (() -> VoiceStartStatus)? = null

    /** 向きを指定して音声翻訳を開始する関数。IMEが起動していなければnull。 */
    var translationStarter: ((VoiceTranslationDirection) -> VoiceStartStatus)? = null

    /** 音声認識または音声翻訳を停止する関数。IMEが起動していなければnull。 */
    var stopper: (() -> Boolean)? = null

    /** 音声認識または音声翻訳を取り消す関数。IMEが起動していなければnull。 */
    var canceller: (() -> Boolean)? = null
}
