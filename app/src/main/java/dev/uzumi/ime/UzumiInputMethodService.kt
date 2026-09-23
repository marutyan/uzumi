package dev.uzumi.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
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
import dev.uzumi.ime.keyboard.CandidateBarViews
import dev.uzumi.ime.keyboard.CandidateGridView
import dev.uzumi.ime.keyboard.KeyboardAction
import dev.uzumi.ime.keyboard.KeyboardPanel
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
    // 候補バーの左端（元に戻す・読みの札）と右端（末尾へ戻る・候補一覧の開閉）の置き場。
    private var barLeading: LinearLayout? = null
    private var barTrailing: LinearLayout? = null
    private var barViews: CandidateBarViews? = null
    // ∨で開く候補一覧。キーボードの面を覆って表示する。
    private var candidateGrid: CandidateGridView? = null
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

    /** 候補バーとキーボード本体を入力Viewとして構築する。配色は端末のライト／ダーク設定に従う。 */
    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        val views = CandidateBarViews(this)
        barViews = views
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(views.colors.keyboardBackground)
        }
        val candidates = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        candidateRow = candidates
        val leading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val trailing = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        barLeading = leading
        barTrailing = trailing
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(views.colors.bar)
        }
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        bar.addView(leading, LinearLayout.LayoutParams(wrap, match))
        bar.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(candidates, FrameLayout.LayoutParams(wrap, match))
        }, LinearLayout.LayoutParams(0, match, 1f))
        bar.addView(trailing, LinearLayout.LayoutParams(wrap, match))
        root.addView(bar, LinearLayout.LayoutParams(match, (CandidateBarViews.BAR_HEIGHT_DP * density).toInt()))

        // 候補一覧はキーボードの面と同じ場所に重ね、開いている間はキーを覆う
        val keyboardArea = FrameLayout(this)
        keyboardPanel = KeyboardPanel(this, ::handleKeyboardAction, lineDeleteLength = { session?.lineDeleteLength() }).also { panel ->
            keyboardArea.addView(panel, FrameLayout.LayoutParams(match, wrap))
        }
        candidateGrid = CandidateGridView(this).also { grid ->
            keyboardArea.addView(grid, FrameLayout.LayoutParams(match, match))
        }
        root.addView(keyboardArea, LinearLayout.LayoutParams(match, wrap))
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
        // 左ドラッグで消した文字列は、次の操作をした時点で戻せなくする
        if (action != KeyboardAction.DeleteToLineStart) current.forgetLineDelete()
        when (action) {
            is KeyboardAction.Text -> current.inputText(action.value)
            KeyboardAction.Delete -> current.deleteBackward()
            // ライブ変換の入力中は、←→で候補バーの対象の文節を前後へ移す（Simejiの変換中の←→に当たる操作）
            is KeyboardAction.MoveCursor -> if (current.isLiveMode && current.hasComposition) {
                current.moveLiveFocus(action.delta)
            } else {
                current.moveCursor(action.delta)
            }
            KeyboardAction.Enter -> current.handleEnter()
            KeyboardAction.Space -> current.insertSpace()
            KeyboardAction.Convert -> current.convert()
            KeyboardAction.TransformKana -> current.transformKana()
            KeyboardAction.ToKatakana -> current.toKatakana()
            KeyboardAction.DeleteToLineStart -> current.deleteToLineStart()
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

    /**
     * 候補バーを今の入力状態に合わせて作り直す。左端に「元に戻す」や訂正中の読み、中央に候補、右端に「末尾」と候補一覧の開閉を置く。
     * 入力していない間は、設定への入口と案内、キーボードを閉じるボタンを出す。
     */
    private fun refreshCandidates() {
        keyboardPanel?.setComposing(session?.hasComposition == true)
        val row = candidateRow ?: return
        val views = barViews ?: return
        val leading = barLeading ?: return
        val trailing = barTrailing ?: return
        row.removeAllViews()
        leading.removeAllViews()
        trailing.removeAllViews()
        val current = session
        if (current?.canUndoLineDelete == true) {
            leading.addView(views.chip(getString(R.string.line_delete_undo), getString(R.string.line_delete_undo_description), true) {
                if (session === current) current.undoLineDelete()
                refreshCandidates()
            })
        }
        val entries = if (current?.isLiveMode == true) liveEntries(current, leading, trailing) else explicitEntries(current)
        val grid = candidateGrid
        if (entries.isEmpty()) {
            grid?.hide()
            if (current?.hasComposition == true) {
                row.addView(views.hint(hintText(current.policy, current.isLiveMode)))
            } else {
                showIdleBar(current, leading, trailing)
            }
            return
        }
        if (engineHealth is EngineHealth.Unavailable && current?.policy?.suppressSuggestions == false) {
            row.addView(views.hint(getString(R.string.candidate_status_no_dictionary)))
        }
        entries.forEach { entry -> row.addView(views.item(entry.label, entry.selected) { pickCandidate(current, entry) }) }
        trailing.addView(views.divider())
        trailing.addView(views.symbolButton(if (grid?.isShowing == true) "∧" else "∨", getString(R.string.candidate_list_toggle)) {
            if (grid?.isShowing == true) grid.hide() else showCandidateGrid(current, entries)
            refreshCandidates()
        })
        // 一覧を開いたまま候補が変わった場合は、一覧も新しい候補で描き直す
        if (grid?.isShowing == true) showCandidateGrid(current, entries)
    }

    /** 候補一覧を開く。候補を選ぶと一覧を閉じる。 */
    private fun showCandidateGrid(current: EditorSession?, entries: List<CandidateEntry>) {
        val grid = candidateGrid ?: return
        grid.show(entries.map { it.label }, entries.indexOfFirst { it.selected }) { index ->
            grid.hide()
            pickCandidate(current, entries[index])
        }
    }

    /** 候補バーの一項目。押したときの操作を持つ。 */
    private class CandidateEntry(val label: String, val selected: Boolean, val onPick: () -> Unit)

    /** 候補を選ぶ。表示したときと別の編集セッションになっていれば何もしない。 */
    private fun pickCandidate(shown: EditorSession?, entry: CandidateEntry) {
        if (shown == null || session !== shown) return
        shown.forgetLineDelete()
        entry.onPick()
        refreshCandidates()
    }

    /** 明示変換の候補（変換結果、または読みのかな・カナ）を並べる。 */
    private fun explicitEntries(current: EditorSession?): List<CandidateEntry> {
        if (current == null) return emptyList()
        val snapshot = current.compositionSnapshot()
        return current.candidateOptions().map { option ->
            val choice = option.conversionChoice
            val label = if (choice != null) option.value else getString(R.string.candidate_button_label, option.value, option.label)
            CandidateEntry(label, selected = choice != null && option.value == snapshot.display) {
                if (choice != null) {
                    current.selectConversionCandidate(choice)
                    scheduleConversionTimeout(current)
                } else if (current.compositionSnapshot().reading == snapshot.reading) {
                    current.applyCandidate(option.value)
                }
            }
        }
    }

    /**
     * ライブ変換の候補を並べ、対象segmentの現在の表記を選択中として示す。過去segmentを訂正中は、左端に読みの札、
     * 右端に「末尾」を出す。直前の操作を取り消せるときは左端に「元に戻す」を出す。
     */
    private fun liveEntries(current: EditorSession, leading: LinearLayout, trailing: LinearLayout): List<CandidateEntry> {
        val views = barViews ?: return emptyList()
        val state = current.liveCandidateState() ?: return emptyList()
        if (state.canUndo && !current.canUndoLineDelete) {
            leading.addView(views.chip(getString(R.string.line_delete_undo), getString(R.string.live_undo), true) {
                if (session === current) current.undoLive()
                refreshCandidates()
            })
        }
        if (!state.focusAtInput) {
            state.focusedReading?.let { reading ->
                leading.addView(views.chip(reading, getString(R.string.live_focused_reading, reading), false, null))
            }
            trailing.addView(views.symbolButton(getString(R.string.live_return_label), getString(R.string.live_return_to_input)) {
                if (session === current) current.returnLiveFocusToInput()
                refreshCandidates()
            })
        }
        return state.choices.map { choice ->
            CandidateEntry(choice.value, selected = choice.value == state.currentValue) { current.selectLiveCandidate(choice) }
        }
    }

    /** 入力していない間の候補バー。左端に設定、中央に案内、右端にキーボードを閉じるボタンを置く。 */
    private fun showIdleBar(current: EditorSession?, leading: LinearLayout, trailing: LinearLayout) {
        val views = barViews ?: return
        leading.addView(views.symbolButton("⚙", getString(R.string.open_settings)) { openSettings() })
        candidateRow?.addView(views.hint(hintText(current?.policy, current?.isLiveMode == true)))
        trailing.addView(views.symbolButton("⌄", getString(R.string.close_keyboard)) { requestHideSelf(0) })
    }

    /** 設定画面を開く。IMEから開くため新しいtaskで起動する。 */
    private fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 候補を出さない理由または候補バーの用途を短く返す。 */
    private fun hintText(policy: InputFieldPolicy?, live: Boolean): String = when {
        policy?.isPassword == true -> getString(R.string.candidate_hint_sensitive)
        policy?.isTypeNull == true -> getString(R.string.candidate_hint_compatibility)
        engineHealth is EngineHealth.Unavailable -> getString(R.string.candidate_hint_no_dictionary)
        live && engineHealth is EngineHealth.Ready -> getString(R.string.candidate_hint_live)
        engineHealth is EngineHealth.Ready -> getString(R.string.candidate_hint_conversion)
        else -> getString(R.string.candidate_hint_default)
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
