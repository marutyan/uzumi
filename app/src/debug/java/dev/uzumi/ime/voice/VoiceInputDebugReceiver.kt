package dev.uzumi.ime.voice

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 端末内音声入力と音声翻訳を実機から試すためのdebugビルド専用受信口。
 * IMEと同じプロセスで動き、[VoiceInputServiceBridge]経由で[dev.uzumi.ime.UzumiInputMethodService]の
 * 開始・停止・取り消しを呼ぶ。停止と取り消しは音声入力と音声翻訳のどちらにも効く。`android.permission.DUMP`で保護され、releaseビルドには含まれない。
 */
class VoiceInputDebugReceiver : BroadcastReceiver() {
    /** actionに応じて音声入力・音声翻訳を開始・停止・取り消し、結果を`am broadcast`のresult dataへ返す。 */
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_VOICE_START -> {
                val starter = VoiceInputServiceBridge.starter ?: return reject("ime_not_running")
                reportStart(starter())
            }
            ACTION_VOICE_TRANSLATE_START -> {
                val direction = VoiceTranslationDirection.fromId(intent.getStringExtra(EXTRA_DIRECTION))
                    ?: return reject("bad_direction")
                val starter = VoiceInputServiceBridge.translationStarter ?: return reject("ime_not_running")
                reportStart(starter(direction))
            }
            ACTION_VOICE_STOP -> {
                val stopper = VoiceInputServiceBridge.stopper ?: return reject("ime_not_running")
                if (stopper()) {
                    setResult(Activity.RESULT_OK, "stopping", null)
                } else {
                    reject("not_listening")
                }
            }
            ACTION_VOICE_CANCEL -> {
                val canceller = VoiceInputServiceBridge.canceller ?: return reject("ime_not_running")
                if (canceller()) {
                    setResult(Activity.RESULT_OK, "cancelled", null)
                } else {
                    reject("idle")
                }
            }
            else -> reject("unknown_action")
        }
    }

    /** 開始の結果を`am broadcast`のresult dataへ返す。 */
    private fun reportStart(status: VoiceStartStatus) {
        when (status) {
            VoiceStartStatus.STARTED -> setResult(Activity.RESULT_OK, "started", null)
            VoiceStartStatus.ALREADY_ACTIVE -> reject("already_active")
            VoiceStartStatus.UNAVAILABLE -> reject("unavailable")
            VoiceStartStatus.PERMISSION_DENIED -> reject("permission_required")
            VoiceStartStatus.DESTROYED -> reject("destroyed")
            VoiceStartStatus.DISPATCHED -> setResult(Activity.RESULT_OK, "dispatched", null)
        }
    }

    private fun reject(reason: String) {
        setResult(Activity.RESULT_CANCELED, reason, null)
    }

    private companion object {
        const val ACTION_VOICE_START = "dev.uzumi.ime.debug.VOICE_START"
        const val ACTION_VOICE_TRANSLATE_START = "dev.uzumi.ime.debug.VOICE_TRANSLATE_START"
        const val ACTION_VOICE_STOP = "dev.uzumi.ime.debug.VOICE_STOP"
        const val ACTION_VOICE_CANCEL = "dev.uzumi.ime.debug.VOICE_CANCEL"

        // 翻訳の向きを受け取るextra。値は`ja-en`または`en-ja`。
        const val EXTRA_DIRECTION = "direction"
    }
}
