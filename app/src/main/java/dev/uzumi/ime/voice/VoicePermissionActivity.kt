package dev.uzumi.ime.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * IMEサービス自身は実行時権限を要求できないため、録音権限（RECORD_AUDIO）の許可ダイアログを出して閉じるだけの画面。
 * 見た目は設定画面のテーマに合わせ、独自のUIを持たない。
 */
class VoicePermissionActivity : Activity() {
    /** 権限が未付与ならシステムの許可ダイアログを出し、すでに付与済みならすぐ閉じる。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        if (savedInstanceState == null) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE_RECORD_AUDIO)
        }
    }

    /** システムダイアログの結果を受け取ったら画面を閉じる。 */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_RECORD_AUDIO) {
            finish()
        }
    }

    private companion object {
        const val REQUEST_CODE_RECORD_AUDIO = 1
    }
}
