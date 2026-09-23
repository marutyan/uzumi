package dev.uzumi.ime.neural

/**
 * llama.cppを呼ぶJNIの橋渡し（`libuzumi_neural.so`、ソースは`app/src/main/cpp/neural/`）。`:neural`のプロセスだけで使う。
 * 共有ライブラリはGradleの外でビルドし、`uzumi.neuralArtifactsDir`から取り込む。無いビルドでは[load]がfalseを返し、
 * IMEはMozcだけで入力を続ける。モデルは一度に一つ、推論も一度に一つだけ行う。
 */
object NativeNeuralBridge {
    // 共有ライブラリを読み込めたか。APKにnativeの生成物が無ければfalse。
    private val libraryLoaded: Boolean by lazy {
        runCatching { System.loadLibrary(LIBRARY_NAME) }.isSuccess
    }

    /** 共有ライブラリが使えるか。 */
    val isAvailable: Boolean
        get() = libraryLoaded

    /** モデルを読み込む。既に読み込んだモデルは解放してから読む。共有ライブラリが無ければfalse。 */
    fun load(path: String, nCtx: Int, nThreads: Int): Boolean = libraryLoaded && nativeLoad(path, nCtx, nThreads)

    /** これから処理する最新の要求番号を知らせる。これより古い要求の推論は中断される。binderのthreadから呼んでよい。 */
    fun markLatest(requestId: Long) {
        if (libraryLoaded) nativeMarkLatest(requestId)
    }

    /** 要求番号以下の推論を中断する。binderのthreadから呼んでよい。 */
    fun cancel(requestId: Long) {
        if (libraryLoaded) nativeCancel(requestId)
    }

    /**
     * プロンプトを変換する。返り値の先頭1 byteが終わり方（[NeuralTermination]の値）、残りが出力のUTF-8。
     * 区切り記号で止まった場合は記号より前だけを返す。共有ライブラリが無ければモデル未準備を返す。
     */
    fun generate(requestId: Long, prompt: ByteArray, parseSpecial: Boolean, maxTokens: Int): ByteArray =
        if (libraryLoaded) {
            nativeGenerate(requestId, prompt, parseSpecial, maxTokens)
        } else {
            byteArrayOf(NeuralTermination.NOT_LOADED.toByte())
        }

    /** モデルを解放する。 */
    fun close() {
        if (libraryLoaded) nativeClose()
    }

    private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Boolean
    private external fun nativeMarkLatest(requestId: Long)
    private external fun nativeCancel(requestId: Long)
    private external fun nativeGenerate(requestId: Long, prompt: ByteArray, parseSpecial: Boolean, maxTokens: Int): ByteArray
    private external fun nativeClose()

    // JNIの橋渡しの共有ライブラリ名。依存するllama.cppとggmlの共有ライブラリは、同じAPKの中から自動で読まれる。
    private const val LIBRARY_NAME = "uzumi_neural"
}
