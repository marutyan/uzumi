package com.google.android.apps.inputmethod.libs.mozc.session;

/**
 * Mozc公式JNI（src/android/jni/mozcjni.cc）が登録先として固定しているJava class。
 * nativeの{@code initialize()}がこのclassへ残り3つのmethodを登録するため、package名・class名・署名を変えない。
 * 呼び出しは変換workerの一つのthreadだけから行う。
 */
public final class MozcJni {
    private MozcJni() {}

    /** 残りのnative methodをこのclassへ登録する。成功時にtrueを返す。 */
    public static native boolean initialize();

    /** user profileの場所と辞書fileを渡してSessionHandlerを作る。辞書を開けない場合もtrueを返し得る。 */
    public static native boolean onPostLoad(String userProfileDirectoryPath, String dataFilePath);

    /** 直列化した{@code mozc.commands.Command}を評価し、出力を詰めたCommandを返す。 */
    public static native byte[] evalCommand(byte[] command);

    /** 読み込んだ辞書のversionを返す。minimal engineでは"0.0.0"、未初期化では空文字になる。 */
    public static native String getDataVersion();
}
