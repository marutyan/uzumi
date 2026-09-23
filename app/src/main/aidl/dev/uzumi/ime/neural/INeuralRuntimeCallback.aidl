package dev.uzumi.ime.neural;

// 推論serviceからIMEへの結果の通知。onewayで、serviceの推論threadを止めない。
oneway interface INeuralRuntimeCallback {
    // モデルの読み込みの結果。reasonは失敗の理由の番号（NeuralRuntimeServiceの定数）。
    void onLoaded(long requestId, boolean ok, int reason);
    // 変換の結果。terminationは終わり方の番号、textは出力のUTF-8、inferenceMicrosは推論時間。
    void onResult(long requestId, int termination, in byte[] text, long inferenceMicros);
    // reportMemoryへの返答。
    void onMemory(int pssKb);
}
