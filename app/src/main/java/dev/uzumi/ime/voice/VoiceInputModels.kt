package dev.uzumi.ime.voice

/** 音声入力セッションの現在の状態。 */
enum class VoiceInputState {
    /** 待機中（認識器は動いていない）。 */
    IDLE,

    /** 認識中（マイク音声を受け付けている）。 */
    LISTENING,

    /** 停止要求を送り、最終結果を待っている。 */
    STOPPING,

    /** 破棄済み。以降の開始・停止は受け付けない。 */
    DESTROYED,
}

/** 音声入力の開始要求に対する結果。 */
enum class VoiceStartStatus {
    /** 認識を開始した。 */
    STARTED,

    /** すでに認識中または停止待ちのため二重開始を避けた。 */
    ALREADY_ACTIVE,

    /** 端末内音声認識が利用できない（API 31未満・認識器なし・生成失敗）。 */
    UNAVAILABLE,

    /** 録音権限（RECORD_AUDIO）が許可されていない。 */
    PERMISSION_DENIED,

    /** すでに破棄されている。 */
    DESTROYED,

    /** メインスレッド以外から呼ばれたためメインスレッドへ投入した。 */
    DISPATCHED,
}

/** 音声入力で発生したエラーの種別。 */
enum class VoiceInputError {
    /** 端末内音声認識が使えない。オンライン経路へのフォールバックは行わない。 */
    UNAVAILABLE,

    /** 録音権限が不足している。 */
    PERMISSION_DENIED,

    /** 指定言語が端末内で使えない。 */
    LANGUAGE_UNAVAILABLE,

    /** 発話が検出できない、または認識結果が得られない。 */
    NO_MATCH,

    /** 音声入力（マイク等）のエラー。 */
    AUDIO,

    /** 認識器が使用中。 */
    BUSY,

    /** その他の認識器エラー。 */
    RECOGNIZER_ERROR,
}

/**
 * 音声入力の状態変化・途中結果・確定結果・エラーを受け取る通知先。
 * すべてのコールバックはメインスレッドで呼ばれる。
 */
interface VoiceInputListener {
    /** 状態が変わったときに呼ばれる。 */
    fun onStateChanged(state: VoiceInputState) {}

    /** 途中結果が届いたときに呼ばれる（フィラー除去前の生の途中結果）。 */
    fun onPartialResult(text: String) {}

    /** 最終結果が確定したときに呼ばれる（フィラー除去済み）。 */
    fun onFinalResult(text: String) {}

    /** エラーで終了したときに呼ばれる。 */
    fun onError(error: VoiceInputError) {}

    /** 取り消しで終了したときに呼ばれる。 */
    fun onCancelled() {}
}
