package dev.uzumi.ime.neural;

import dev.uzumi.ime.neural.INeuralRuntimeCallback;

// 別プロセス（:neural）の推論serviceの窓口。すべてonewayで、IMEのthreadを推論で止めない。
// 要求番号は呼び出し側が増やし、新しい番号の要求が来たら古い推論は中断される。
oneway interface INeuralRuntime {
    // モデルを読み込む。SHA-256が合わなければ読み込まず、onLoadedでokをfalseにして返す。
    void load(long requestId, String modelPath, String sha256, int nCtx, int nThreads, INeuralRuntimeCallback callback);
    // プロンプト（UTF-8、モデルごとの形式で組み立て済み）を変換する。結果はonResultで返す。
    void convert(long requestId, in byte[] prompt, boolean parseSpecial, int maxTokens, INeuralRuntimeCallback callback);
    // 要求番号の推論を中断する。
    void cancel(long requestId);
    // このプロセスのPSS（KB）をonMemoryで返す。評価用。
    void reportMemory(INeuralRuntimeCallback callback);
    // モデルを解放する。
    void close();
}
