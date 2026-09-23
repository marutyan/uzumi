package dev.uzumi.ime

import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.uzumi.ime.conversion.ConversionOutcome
import dev.uzumi.ime.conversion.ConversionWorker
import dev.uzumi.ime.conversion.EngineHealth
import dev.uzumi.ime.conversion.JniMozcNativeBridge
import dev.uzumi.ime.conversion.MozcConversionEngine
import dev.uzumi.ime.conversion.MozcDataInstaller
import dev.uzumi.ime.dictionary.UserDictionaries
import dev.uzumi.ime.editor.AndroidInputConnectionPort
import dev.uzumi.ime.editor.EditorSession
import dev.uzumi.ime.editor.InputFieldPolicy
import dev.uzumi.ime.keyboard.KeyboardAction
import dev.uzumi.ime.keyboard.KeyboardPanel
import dev.uzumi.ime.learning.LearningStores
import dev.uzumi.ime.live.ConversionResult as LiveResult
import dev.uzumi.ime.live.DisplaySpan
import dev.uzumi.ime.live.LiveConversionCore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Androidの入力接続、編集セッション、キーボード表示を一つの寿命へ結び付ける。
 */
class UzumiInputMethodService : InputMethodService() {
    private var session: EditorSession? = null
    private var keyboardPanel: KeyboardPanel? = null
    private var candidateRow: LinearLayout? = null
    // ライブ変換の候補バーの両脇に置く、segmentの移動・末尾復帰・取り消しのボタン。明示変換では隠す。
    private var liveControls: List<Button> = emptyList()
    private var previousSegmentButton: Button? = null
    private var nextSegmentButton: Button? = null
    private var returnToInputButton: Button? = null
    private var undoButton: Button? = null
    private var currentPolicy: InputFieldPolicy? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var conversionExecutor: ExecutorService? = null
    private var conversionWorker: ConversionWorker? = null
    // UI threadへ反映済みのエンジン状態。辞書が使えない場合の表示に使う。
    private var engineHealth: EngineHealth = EngineHealth.Loading
    // 編集セッションごとに増やす番号。古い変換応答や学習通知を別のフィールドへ適用しない。
    private var lastSessionEpoch = 0L

    /** 変換エンジンを専用の直列threadで読み込み始める。キー入力はこの完了を待たない。 */
    override fun onCreate() {
        super.onCreate()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "uzumi-conversion")
        }
        val installer = MozcDataInstaller(this)
        val worker = ConversionWorker(
            engine = MozcConversionEngine(
                native = JniMozcNativeBridge(),
                profileDirectory = installer::profileDirectory,
                dataFile = installer::install,
            ),
            executor = executor,
            onHealthChanged = { health ->
                mainHandler.post {
                    engineHealth = health
                    refreshCandidates()
                }
            },
            onOutcome = { outcome -> mainHandler.post { deliverConversion(outcome) } },
            onLiveResult = { sessionEpoch, result -> mainHandler.post { deliverLiveResult(sessionEpoch, result) } },
            userDictionary = { UserDictionaries.get(this) },
            learningStore = { LearningStores.get(this) },
        )
        conversionExecutor = executor
        conversionWorker = worker
        worker.start()
    }

    /** 保留中のUI更新を捨て、エンジン側sessionを破棄してからworkerを止める。 */
    override fun onDestroy() {
        closeSession()
        mainHandler.removeCallbacksAndMessages(null)
        conversionExecutor?.shutdown()
        conversionExecutor = null
        conversionWorker = null
        super.onDestroy()
    }

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
        val previous = controlButton("◀", R.string.live_previous_segment) { it.moveLiveFocus(-1) }
        val next = controlButton("▶", R.string.live_next_segment) { it.moveLiveFocus(1) }
        val returnToInput = controlButton(getString(R.string.live_return_label), R.string.live_return_to_input) {
            it.returnLiveFocusToInput()
        }
        val undo = controlButton(getString(R.string.live_undo_label), R.string.live_undo) { it.undoLive() }
        previousSegmentButton = previous
        nextSegmentButton = next
        returnToInputButton = returnToInput
        undoButton = undo
        liveControls = listOf(previous, next, returnToInput, undo)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        bar.addView(previous)
        bar.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(candidates)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(next)
        bar.addView(returnToInput)
        bar.addView(undo)
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (48 * density).toInt()))

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
        // ライブ変換の設定は入力開始ごとに読み、設定画面での変更を次の入力欄から反映する。
        val live = policy.usesLiveConversion(UzumiSettings.isLiveConversionEnabled(this))
        session = EditorSession(
            connection = AndroidInputConnectionPort(connection),
            policy = policy,
            initialSelectionStart = info.initialSelStart,
            initialSelectionEnd = info.initialSelEnd,
            sessionEpoch = ++lastSessionEpoch,
            conversionClient = conversionWorker,
            liveCore = if (live) LiveConversionCore() else null,
            liveClient = conversionWorker,
            compositionStyler = ::highlightSegment,
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
        scheduleConversionTimeout(current)
        refreshCandidates()
    }

    /** workerの応答を、現在の編集セッションが照合できる場合だけ反映する。 */
    private fun deliverConversion(outcome: ConversionOutcome) {
        val current = session ?: return
        current.applyConversionOutcome(outcome)
        refreshCandidates()
    }

    /** ライブ変換の結果を、同じ編集セッションが照合できる場合だけ反映する。 */
    private fun deliverLiveResult(sessionEpoch: Long, result: LiveResult) {
        val current = session ?: return
        if (current.sessionEpoch != sessionEpoch) return
        if (current.applyLiveResult(result)) refreshCandidates()
    }

    /** 訂正中のsegmentの表示範囲へ背景色を付け、どのsegmentを直しているかをEditor上で示す。 */
    private fun highlightSegment(text: String, span: DisplaySpan?): CharSequence {
        if (span == null || span.start >= span.end || span.end > text.length) return text
        return SpannableString(text).apply {
            setSpan(BackgroundColorSpan(FOCUSED_SEGMENT_COLOR), span.start, span.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** 候補バーの脇に置く小さな操作ボタンを作る。押すと現在の編集セッションへ操作を一度だけ送る。 */
    private fun controlButton(label: String, descriptionId: Int, action: (EditorSession) -> Boolean): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
            contentDescription = getString(descriptionId)
            visibility = View.GONE
            setOnClickListener {
                val current = session ?: return@setOnClickListener
                action(current)
                refreshCandidates()
            }
        }
    }

    /** 変換応答が一定時間内に届かなければ要求を取り下げ、読みとかな・カナ候補へ戻す。 */
    private fun scheduleConversionTimeout(current: EditorSession) {
        val request = current.pendingConversionRequest ?: return
        mainHandler.postDelayed({
            if (session === current && current.abandonConversion(request)) refreshCandidates()
        }, CONVERSION_TIMEOUT_MILLIS)
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
        if (current?.isLiveMode == true) {
            refreshLiveCandidates(row, current)
            return
        }
        liveControls.forEach { it.visibility = View.GONE }
        val snapshot = current?.compositionSnapshot()
        val options = current?.candidateOptions().orEmpty()
        if (current == null || snapshot == null || options.isEmpty()) {
            row.addView(candidateHint(current?.policy))
            return
        }
        if (engineHealth is EngineHealth.Unavailable && !current.policy.suppressSuggestions) {
            row.addView(candidateStatus(getString(R.string.candidate_status_no_dictionary)))
        }

        options.forEach { option ->
            row.addView(Button(this).apply {
                isAllCaps = false
                text = if (option.conversionChoice != null) {
                    option.value
                } else {
                    getString(R.string.candidate_button_label, option.value, option.label)
                }
                setOnClickListener {
                    val latest = session
                    if (latest !== current) return@setOnClickListener
                    val choice = option.conversionChoice
                    if (choice != null) {
                        latest.selectConversionCandidate(choice)
                        scheduleConversionTimeout(latest)
                        refreshCandidates()
                    } else if (latest.compositionSnapshot().reading == snapshot.reading) {
                        latest.applyCandidate(option.value)
                        refreshCandidates()
                    }
                }
            })
        }
    }

    /**
     * ライブ変換の候補バーを作る。対象segmentの候補を並べ、現在の表記を太字にする。
     * 左右のボタンで過去segmentへ移り、「末尾」で入力位置へ戻り、「取消」で直前の操作を取り消す。
     */
    private fun refreshLiveCandidates(row: LinearLayout, current: EditorSession) {
        val state = current.liveCandidateState()
        val choices = state?.choices.orEmpty()
        liveControls.forEach { it.visibility = View.VISIBLE }
        previousSegmentButton?.isEnabled = state?.canFocusPrevious == true
        nextSegmentButton?.isEnabled = state != null && !state.focusAtInput
        returnToInputButton?.isEnabled = state != null && !state.focusAtInput
        undoButton?.isEnabled = state?.canUndo == true
        if (state == null || choices.isEmpty()) {
            row.addView(candidateHint(current.policy, live = true))
            return
        }
        if (engineHealth is EngineHealth.Unavailable) {
            row.addView(candidateStatus(getString(R.string.candidate_status_no_dictionary)))
        }
        choices.forEach { choice ->
            row.addView(Button(this).apply {
                isAllCaps = false
                text = choice.value
                if (choice.value == state.currentValue) setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    val latest = session
                    if (latest !== current) return@setOnClickListener
                    latest.selectLiveCandidate(choice)
                    refreshCandidates()
                }
            })
        }
    }

    /** 候補の前に置く短い状態表示を作る。 */
    private fun candidateStatus(message: String): TextView {
        return TextView(this).apply {
            text = message
            textSize = 12f
            setPadding(0, 0, (8 * resources.displayMetrics.density).toInt(), 0)
        }
    }

    /** 候補を出さない理由または候補行の用途を短く表示する。 */
    private fun candidateHint(policy: InputFieldPolicy?, live: Boolean = false): TextView {
        return TextView(this).apply {
            text = when {
                policy?.isPassword == true -> getString(R.string.candidate_hint_sensitive)
                policy?.isTypeNull == true -> getString(R.string.candidate_hint_compatibility)
                engineHealth is EngineHealth.Unavailable -> getString(R.string.candidate_hint_no_dictionary)
                live && engineHealth is EngineHealth.Ready -> getString(R.string.candidate_hint_live)
                engineHealth is EngineHealth.Ready -> getString(R.string.candidate_hint_conversion)
                else -> getString(R.string.candidate_hint_default)
            }
            textSize = 14f
            setTypeface(typeface, Typeface.NORMAL)
        }
    }

    /** 旧接続への書込みを禁止してセッション参照を破棄する。 */
    private fun closeSession() {
        session?.let { conversionWorker?.endSession(it.sessionEpoch) }
        session?.close()
        session = null
    }

    private companion object {
        /** 変換応答を待つ上限。超えたら読みとかな・カナ候補へ戻す。 */
        const val CONVERSION_TIMEOUT_MILLIS = 2_000L

        /** 訂正中のsegmentの背景色（半透明の青）。下線だけのsegmentと区別できる濃さにする。 */
        const val FOCUSED_SEGMENT_COLOR = 0x553F7FFF
    }
}
