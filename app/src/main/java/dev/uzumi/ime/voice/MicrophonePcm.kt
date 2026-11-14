package dev.uzumi.ime.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音声入力と音声翻訳が端末内の処理へ渡す音声の形式（PCM 16bit・16kHz・mono）と、1回に渡す長さ（100ミリ秒分）。
 * 端末内の認識器と翻訳器のどちらもこの形式を受け付けるため、録音の設定を一か所にまとめる。
 */
object MicrophonePcm {
    const val SAMPLE_RATE = 16_000
    const val CHUNK_MS = 100L
    const val CHUNK_BYTES = (SAMPLE_RATE * 2 * CHUNK_MS / 1000).toInt()

    /**
     * マイクを開き、[stopSignal]が立つまで[CHUNK_BYTES]ずつ読んで[onChunk]へ渡す。読み終えたらマイクを閉じる。
     * 呼び出し元のthreadを塞ぐので、録音専用のthreadかI/O用のthreadから呼ぶ。
     * 録音権限は呼び出し元が確かめてから呼ぶため、権限の確認漏れの警告を抑える。
     *
     * @throws MicrophoneException マイクを開けない、または読めないとき
     */
    @SuppressLint("MissingPermission")
    inline fun read(stopSignal: AtomicBoolean, onChunk: (chunk: ByteArray) -> Unit) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // 音声認識向けの経路で録り、通話向けの加工（エコー消去など）を避ける。
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, CHUNK_BYTES * 4),
        )
        try {
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) throw MicrophoneException("AudioRecord not initialized")
            audioRecord.startRecording()
            val chunk = ByteArray(CHUNK_BYTES)
            while (!stopSignal.get()) {
                val read = audioRecord.read(chunk, 0, chunk.size)
                if (read < 0) throw MicrophoneException("AudioRecord.read returned $read")
                if (read == 0) continue
                onChunk(chunk.copyOf(read))
            }
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
        }
    }
}

/** マイクを開けない、または読めないことを表す。[VoiceInputError.AUDIO]として知らせる。 */
class MicrophoneException(message: String) : Exception(message)
