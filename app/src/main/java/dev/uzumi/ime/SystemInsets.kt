package dev.uzumi.ime

import android.view.View
import android.view.WindowInsets

/** システムバー、ディスプレイカットアウト、IME領域のInsetsを反映し重なりを防ぐ。アプリ内の各画面で共通に使う。 */
internal fun applySystemInsets(view: View) {
    view.setOnApplyWindowInsetsListener { targetView, insets ->
        val systemBars = insets.getInsets(
            WindowInsets.Type.systemBars() or
                WindowInsets.Type.displayCutout() or
                WindowInsets.Type.ime()
        )
        targetView.setPadding(
            systemBars.left,
            systemBars.top,
            systemBars.right,
            systemBars.bottom
        )
        insets
    }
}
