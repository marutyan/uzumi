package dev.uzumi.ime

import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.uzumi.ime.editor.AndroidInputConnectionPort
import dev.uzumi.ime.editor.EditorSession
import dev.uzumi.ime.editor.InputFieldPolicy
import dev.uzumi.ime.keyboard.KeyboardAction
import dev.uzumi.ime.keyboard.KeyboardPanel

/**
 * Androidの入力接続、編集セッション、キーボード表示を一つの寿命へ結び付ける。
 */
class UzumiInputMethodService : InputMethodService() {
    private var session: EditorSession? = null
    private var keyboardPanel: KeyboardPanel? = null
    private var candidateRow: LinearLayout? = null
    private var currentPolicy: InputFieldPolicy? = null

    /** 候補行とキーボード本体を入力Viewとして構築する。 */
    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF4F5F7.toInt())
        }
        val candidates = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = (48 * density).toInt()
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
        }
        candidateRow = candidates
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(candidates)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (48 * density).toInt()))

        keyboardPanel = KeyboardPanel(this, ::handleKeyboardAction).also { panel ->
            root.addView(
                panel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        applyPolicyToKeyboard()
        refreshCandidates()
        applyNavigationInsets(root)
        return root
    }

    /** システムバー（captionBar含む）とカットアウトのInsetsを反映し、システム操作領域との重なりを防ぐ。 */
    private fun applyNavigationInsets(view: View) {
        view.setOnApplyWindowInsetsListener { targetView, insets ->
            val systemInsets = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            targetView.setPadding(systemInsets.left, 0, systemInsets.right, systemInsets.bottom)
            insets
        }
    }

    /** フィールドごとに旧接続を破棄し、新しい編集セッションを開始する。 */
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        closeSession()
        currentPolicy = null
        refreshCandidates()
        val info = attribute ?: return
        val connection = currentInputConnection ?: return
        val policy = InputFieldPolicy.fromEditorInfo(info)
        currentPolicy = policy
        session = EditorSession(
            connection = AndroidInputConnectionPort(connection),
            policy = policy,
            initialSelectionStart = info.initialSelStart,
            initialSelectionEnd = info.initialSelEnd,
        )
        applyPolicyToKeyboard()
        refreshCandidates()
    }

    /** 表示済みキーボードへ現在欄の種別と候補を反映する。 */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyPolicyToKeyboard()
        refreshCandidates()
    }

    /** Editorの選択通知を現在セッションへ限定して渡す。 */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        session?.updateSelection(
            oldSelectionStart = oldSelStart,
            oldSelectionEnd = oldSelEnd,
            newSelectionStart = newSelStart,
            newSelectionEnd = newSelEnd,
            candidatesStart = candidatesStart,
            candidatesEnd = candidatesEnd,
        )
        refreshCandidates()
    }

    /** 非表示になるキーボードの保留タッチを止める。 */
    override fun onFinishInputView(finishingInput: Boolean) {
        keyboardPanel?.cancelPendingInput()
        super.onFinishInputView(finishingInput)
    }

    /** 入力終了時に接続と一時compositionを無効化する。 */
    override fun onFinishInput() {
        keyboardPanel?.cancelPendingInput()
        closeSession()
        currentPolicy = null
        refreshCandidates()
        super.onFinishInput()
    }

    /** キーボード操作を現在の編集セッションへ一度だけ送る。 */
    private fun handleKeyboardAction(action: KeyboardAction) {
        val current = session ?: return
        when (action) {
            is KeyboardAction.Text -> current.inputText(action.value)
            KeyboardAction.Delete -> current.deleteBackward()
            is KeyboardAction.MoveCursor -> current.moveCursor(action.delta)
            KeyboardAction.Enter -> current.handleEnter()
            KeyboardAction.Space -> current.insertSpace()
            KeyboardAction.Convert -> current.convert()
            KeyboardAction.TransformKana -> current.transformKana()
        }
        refreshCandidates()
    }

    /** EditorInfo由来の表示モードとアクション名をキーボードへ反映する。 */
    private fun applyPolicyToKeyboard() {
        val policy = currentPolicy ?: return
        keyboardPanel?.setEditorMode(policy.isNumeric, policy.isPassword)
        keyboardPanel?.setActionLabel(policy.actionLabel)
    }

    /** 現在の読みとセッション世代に一致する基本候補だけを表示する。 */
    private fun refreshCandidates() {
        val row = candidateRow ?: return
        row.removeAllViews()
        val current = session
        val snapshot = current?.compositionSnapshot()
        val options = current?.candidateOptions().orEmpty()
        if (current == null || snapshot == null || options.isEmpty()) {
            row.addView(candidateHint(current?.policy))
            return
        }

        options.forEach { option ->
            row.addView(Button(this).apply {
                isAllCaps = false
                text = getString(R.string.candidate_button_label, option.value, option.label)
                setOnClickListener {
                    val latest = session
                    if (latest === current && latest.compositionSnapshot().reading == snapshot.reading) {
                        latest.applyCandidate(option.value)
                        refreshCandidates()
                    }
                }
            })
        }
    }

    /** 候補を出さない理由または候補行の用途を短く表示する。 */
    private fun candidateHint(policy: InputFieldPolicy?): TextView {
        return TextView(this).apply {
            text = when {
                policy?.isPassword == true -> getString(R.string.candidate_hint_sensitive)
                policy?.isTypeNull == true -> getString(R.string.candidate_hint_compatibility)
                else -> getString(R.string.candidate_hint_default)
            }
            textSize = 14f
            setTypeface(typeface, Typeface.NORMAL)
        }
    }

    /** 旧接続への書込みを禁止してセッション参照を破棄する。 */
    private fun closeSession() {
        session?.close()
        session = null
    }
}
