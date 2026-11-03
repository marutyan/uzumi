package dev.uzumi.ime

import android.content.Intent
import android.content.ClipboardManager
import android.graphics.drawable.Icon
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.inline.InlineContentView
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import dev.uzumi.ime.conversion.ConversionOutcome
import dev.uzumi.ime.conversion.ConversionWorker
import dev.uzumi.ime.conversion.EnglishWordDictionary
import dev.uzumi.ime.conversion.EngineHealth
import dev.uzumi.ime.conversion.JniMozcNativeBridge
import dev.uzumi.ime.conversion.MozcConversionEngine
import dev.uzumi.ime.conversion.MozcDataInstaller
import dev.uzumi.ime.conversion.NeuralModelSpec
import dev.uzumi.ime.conversion.PredictionCandidate
import dev.uzumi.ime.conversion.PredictionResult
import dev.uzumi.ime.clipboard.ClipboardHistoryCapturePolicy
import dev.uzumi.ime.clipboard.ClipboardHistoryIdentity
import dev.uzumi.ime.clipboard.ClipboardHistoryItem
import dev.uzumi.ime.clipboard.ClipboardHistoryRules
import dev.uzumi.ime.clipboard.ClipboardHistoryStore
import dev.uzumi.ime.dictionary.UserDictionaries
import dev.uzumi.ime.dictionary.UserDictionaryCategory
import dev.uzumi.ime.clipboard.RecentClipboard
import dev.uzumi.ime.editor.AndroidInputConnectionPort
import dev.uzumi.ime.editor.EditorSession
import dev.uzumi.ime.editor.EditPanelAction
import dev.uzumi.ime.editor.EditPanelContextAction
import dev.uzumi.ime.editor.InlineAutofillPolicy
import dev.uzumi.ime.editor.InputFieldPolicy
import dev.uzumi.ime.evaluation.ConversionProgress
import dev.uzumi.ime.evaluation.CorrectnessJudge
import dev.uzumi.ime.evaluation.EvaluationCounter
import dev.uzumi.ime.evaluation.EvaluationStatusSource
import dev.uzumi.ime.evaluation.ImeEvaluationStatus
import dev.uzumi.ime.evaluation.LiveRequestReport
import dev.uzumi.ime.evaluation.NeuralRuntimeStatus
import dev.uzumi.ime.evaluation.OperationClassifier
import dev.uzumi.ime.evaluation.OperationKind
import dev.uzumi.ime.evaluation.TimingKind
import dev.uzumi.ime.evaluation.TimingSample
import dev.uzumi.ime.keyboard.CandidateBarViews
import dev.uzumi.ime.keyboard.CandidateActionPanel
import dev.uzumi.ime.keyboard.CandidateGridView
import dev.uzumi.ime.keyboard.ClipboardHistoryPanel
import dev.uzumi.ime.keyboard.EditPanelView
import dev.uzumi.ime.keyboard.EmojiRecentStore
import dev.uzumi.ime.keyboard.KeyboardAction
import dev.uzumi.ime.keyboard.KeyboardColors
import dev.uzumi.ime.keyboard.KeyboardDimens
import dev.uzumi.ime.keyboard.KeyboardLayoutData
import dev.uzumi.ime.keyboard.KeyboardPanel
import dev.uzumi.ime.keyboard.KeyboardPreferences
import dev.uzumi.ime.keyboard.ToolbarAction
import dev.uzumi.ime.keyboard.ToolbarBarViews
import dev.uzumi.ime.keyboard.ToolbarPreferences
import dev.uzumi.ime.keyboard.ToolbarTilePanel
import dev.uzumi.ime.keyboard.TemplatePickerPanel
import dev.uzumi.ime.learning.LearningStores
import dev.uzumi.ime.learning.HiddenCandidateStores
import dev.uzumi.ime.live.CandidateResult
import dev.uzumi.ime.live.ConversionResult as LiveResult
import dev.uzumi.ime.live.DisplaySpan
import dev.uzumi.ime.live.LiveConversionCore
import dev.uzumi.ime.live.RequestIdentity
import dev.uzumi.ime.neural.NeuralRuntimeConnection
import dev.uzumi.ime.neural.NeuralSelection
import dev.uzumi.ime.neural.shouldRecreateNeuralConnection
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Androidの入力接続、編集セッション、キーボード表示を一つの寿命へ結び付ける。
 */
class UzumiInputMethodService : InputMethodService() {
    /** 同梱の英語読み辞書を初回参照時に読み、不変indexをプロセス内で再利用する。 */
    private val englishWordDictionary by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        EnglishWordDictionary.load(assets.open("english-words.tsv"))
    }

    private var session: EditorSession? = null
    private var keyboardPanel: KeyboardPanel? = null
    // 最近履歴は一覧を開いたときに読み、入力成功後かつ機密欄以外でのみ書く。
    private val emojiRecentStore by lazy { EmojiRecentStore(this) }
    private var candidateRow: LinearLayout? = null
    private var candidateBar: LinearLayout? = null

    // Inline候補を置く常設の枠と、いま枠に入っている候補の一覧。候補が同じ間は付け外ししない。
    // 部品を外すと別プロセスの描画面が作り直され、カーソル移動のたびに候補が一瞬消えるため。
    private var inlineHost: LinearLayout? = null
    private var inlineHostItems: List<InlineSuggestionItem>? = null
    private var toolbarRow: LinearLayout? = null
    // 候補バーの左端（元に戻す・読みの札）と右端（末尾へ戻る・候補一覧の開閉）の置き場。
    private var barLeading: LinearLayout? = null
    private var barTrailing: LinearLayout? = null
    private var barViews: CandidateBarViews? = null
    // ∨で開く候補一覧。キーボードの面を覆って表示する。
    private var candidateGrid: CandidateGridView? = null
    private var candidateActionPanel: CandidateActionPanel? = null
    private var toolbarPanel: ToolbarTilePanel? = null
    private var templatePanel: TemplatePickerPanel? = null
    private var clipboardHistoryPanel: ClipboardHistoryPanel? = null
    // 編集パネルはキーボード面へ重ね、選択モードをIME表示中だけ保持する。
    private var editPanel: EditPanelView? = null
    private var editPanelSelectionMode = false
    // 入力Viewを作ったときのキーボード設定。設定画面で変わっていれば、次に表示するときに作り直す。
    private var builtPreferences: KeyboardPreferences? = null
    private var builtToolbarPreferences: ToolbarPreferences? = null

    // 入力Viewで実際に受け取った下端Insets（px）と、入力Viewを作った時点のバー段数上限・表示用キー行高。
    // 実測Insetsでこの組が変わる場合だけ作り直し、表示に影響しない作り直しで初回表示が崩れるのを避ける。
    private var observedBottomInsetPx = 0
    private var builtInputLayout: Pair<Int, Float>? = null
    private var toolbarViews: ToolbarBarViews? = null
    // クリップボード本文はRecentClipboardの一時候補にだけ保持し、ログや設定へ保存しない。
    private var recentClipboard: RecentClipboard? = null
    // 本文をIME表示中だけ取得し、他の設定・履歴とは分けたnoBackupFilesDirへ保存する。
    private val clipboardHistoryStore by lazy {
        ClipboardHistoryStore(File(noBackupFilesDir, CLIPBOARD_HISTORY_FILE_NAME))
    }
    private var clipboardSuggestion: RecentClipboard.Suggestion? = null
    private var clipboardExpiry: Runnable? = null
    private var clipboardHistoryExpiry: Runnable? = null
    private var clipboardHistorySessionEpoch: Long? = null
    private var clipboardSuggestionSessionEpoch: Long? = null
    private var inputViewVisible = false
    // Inline Autofill候補の表示部品。中身はOSが描画し、IMEは読まない（ログ・計数・学習・設定へ送らない）。
    private var inlineSuggestions: List<InlineSuggestionItem> = emptyList()
    // 受けたInline応答の通し番号。展開中に新しい応答が来た場合や欄が替わった場合に、古い展開結果を捨てる。
    private var inlineResponseGeneration = 0L
    private var currentPolicy: InputFieldPolicy? = null

    // カーソル位置で欄が求める大文字化の種類（TextUtils.CAP_MODE_*、0なら求めない）。キーボードを作り直しても引き継ぐ。
    private var autoCapsMode = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var conversionExecutor: ExecutorService? = null
    private var conversionWorker: ConversionWorker? = null
    // UI threadへ反映済みのエンジン状態。辞書が使えない場合の表示に使う。
    private var engineHealth: EngineHealth = EngineHealth.Loading
    // 編集セッションごとに増やす番号。古い変換応答や学習通知を別のフィールドへ適用しない。
    private var lastSessionEpoch = 0L
    // Phase 2cの評価用計数。debugビルドの受信口で開始したときだけ数え、既定では何もしない。
    private val evaluation = EvaluationCounter.shared
    // 評価で選んだモデルの推論serviceとの接続。Mozcだけ（製品の既定）ならnull。
    private var neuralConnection: NeuralRuntimeConnection? = null
    // 最後に適用したライブ変換の結果の要求と、適用した時刻（System.nanoTime）。評価用の報告の照合にだけ使う。
    private var lastAppliedLive: Pair<RequestIdentity, Long>? = null
    // 変換の直列threadへ積んで、まだ終わっていない仕事の数。評価用の状態の確認口が、処理の終わりを確かめるために読む。
    private val conversionTasksInFlight = AtomicInteger(0)
    // 入力先の欄がUzumi自身の画面か、とその欄のview id。評価用の状態の確認口が、試験欄への入力かを確かめるために読む。
    private var editorInOwnApp = false
    private var editorFieldId = View.NO_ID

    /** 変換エンジンを専用の直列threadで読み込み始める。キー入力はこの完了を待たない。 */
    override fun onCreate() {
        super.onCreate()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "uzumi-conversion")
        }
        val installer = MozcDataInstaller(this)
        // 積んだ仕事を数えるだけの包み。仕事の順番と実行threadは元のexecutorのまま変えない。
        val countingExecutor = Executor { task ->
            conversionTasksInFlight.incrementAndGet()
            try {
                executor.execute {
                    try {
                        task.run()
                    } finally {
                        conversionTasksInFlight.decrementAndGet()
                    }
                }
            } catch (error: RejectedExecutionException) {
                conversionTasksInFlight.decrementAndGet()
                throw error
            }
        }
        val worker = ConversionWorker(
            engine = MozcConversionEngine(
                native = JniMozcNativeBridge(),
                profileDirectory = installer::profileDirectory,
                dataFile = installer::install,
            ),
            executor = countingExecutor,
            onHealthChanged = { health ->
                mainHandler.post {
                    engineHealth = health
                    refreshCandidates()
                }
            },
            onOutcome = { outcome -> mainHandler.post { deliverConversion(outcome) } },
            onLiveResult = { sessionEpoch, result -> mainHandler.post { deliverLiveResult(sessionEpoch, result) } },
            onLiveReport = { sessionEpoch, report -> mainHandler.post { deliverLiveReport(sessionEpoch, report) } },
            // 評価用の報告は評価モードの間だけ作り、表記ゆれ変換も使わない。計数器の状態はworkerのthreadからも読める。
            evaluationActive = { evaluation.isRecording },
            onCandidateResult = { sessionEpoch, result -> mainHandler.post { deliverCandidateResult(sessionEpoch, result) } },
            onPredictionResult = { result -> mainHandler.post { deliverPredictionResult(result) } },
            userDictionary = { UserDictionaries.get(this) },
            englishWords = { englishWordDictionary },
            learningStore = { LearningStores.get(this) },
        )
        conversionExecutor = executor
        conversionWorker = worker
        // 学習の保存ファイルを別threadで先に読む。変換threadはMozcの読込みで塞がるため、そこへは積まない
        Thread({ LearningStores.get(applicationContext) }, "uzumi-learning-preload").start()
        Thread({ HiddenCandidateStores.get(applicationContext) }, "uzumi-hidden-candidates-preload").start()
        worker.start()
        EvaluationStatusSource.provider = ::evaluationStatus
        EvaluationStatusSource.neuralMemoryRequester = { neuralConnection?.requestMemory() }
        scheduleClipboardHistoryExpiry()
    }

    /** 保留中のUI更新と履歴期限timerを捨て、エンジン側sessionを破棄してworkerを止める。 */
    override fun onDestroy() {
        EvaluationStatusSource.provider = null
        EvaluationStatusSource.neuralMemoryRequester = null
        stopClipboardObservation()
        clipboardHistoryExpiry?.let(mainHandler::removeCallbacks)
        clipboardHistoryExpiry = null
        closeSession()
        applyNeuralSelection(null, restarting = false)
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
        candidateGrid?.hide()
        candidateActionPanel?.hide()
        toolbarPanel?.hide()
        templatePanel?.hide()
        clipboardHistoryPanel?.hide()
        clipboardHistorySessionEpoch = null
        hideEditPanel()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(views.colors.keyboardBackground)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(views.colors.bar)
        }
        toolbarRow = toolbar
        toolbarViews = ToolbarBarViews(this)
        val toolbarPreferences = UzumiSettings.toolbarPreferences(this)
        builtToolbarPreferences = toolbarPreferences
        root.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
        ))
        val candidates = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(views.colors.bar)
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
        candidateBar = bar
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        bar.addView(leading, LinearLayout.LayoutParams(wrap, match))
        inlineHost = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }.also { host -> bar.addView(host, LinearLayout.LayoutParams(0, match, 0f)) }
        bar.addView(candidates, LinearLayout.LayoutParams(0, match, 1f))
        inlineHostItems = null
        bar.addView(trailing, LinearLayout.LayoutParams(wrap, match))
        root.addView(bar, LinearLayout.LayoutParams(match, (CandidateBarViews.BAR_HEIGHT_DP * density).toInt()))

        // 候補一覧はキーボードの面と同じ場所に重ね、開いている間はキーを覆う
        val keyboardArea = FrameLayout(this)
        val preferences = UzumiSettings.keyboardPreferences(this)
        builtPreferences = preferences
        val rowBudget = maximumBarRows()
        val reservedBarRows = minOf(toolbarPreferences.toolbarRows + toolbarPreferences.candidateRows, rowBudget)
        val displayPreferences = displayKeyboardPreferences(preferences, reservedBarRows)
        builtInputLayout = rowBudget to displayPreferences.rowHeightDp
        keyboardPanel = KeyboardPanel(
            this,
            ::handleKeyboardAction,
            lineDeleteLength = { session?.lineDeleteLength() },
            preferences = displayPreferences,
            onPanelKey = {
                session?.finishPendingInput()
                evaluation.record(OperationKind.OTHER)
            },
        ).also { panel ->
            keyboardArea.addView(panel, FrameLayout.LayoutParams(match, wrap))
        }
        candidateGrid = CandidateGridView(this).also { grid ->
            keyboardArea.addView(grid, FrameLayout.LayoutParams(match, match))
        }
        toolbarPanel = ToolbarTilePanel(this).also { panel ->
            keyboardArea.addView(panel, FrameLayout.LayoutParams(match, match))
        }
        templatePanel = TemplatePickerPanel(this).also { panel ->
            keyboardArea.addView(panel, FrameLayout.LayoutParams(match, match))
        }
        clipboardHistoryPanel = ClipboardHistoryPanel(this).also { panel ->
            keyboardArea.addView(panel, FrameLayout.LayoutParams(match, match))
        }
        clipboardHistorySessionEpoch = null
        candidateActionPanel = CandidateActionPanel(this).also { actionPanel ->
            keyboardArea.addView(actionPanel, FrameLayout.LayoutParams(match, match))
        }
        editPanel = EditPanelView(this, ::handleEditPanelAction).also { panel ->
            keyboardArea.addView(panel, FrameLayout.LayoutParams(match, match))
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
            // IMEのナビゲーション領域は端末全体の推定より高い場合があるため、実測値が変わったら段数と行高を計算し直す
            if (systemInsets.bottom != observedBottomInsetPx) {
                observedBottomInsetPx = systemInsets.bottom
                val built = builtInputLayout
                if (built != null && built != currentInputLayout()) {
                    targetView.post { setInputView(onCreateInputView()) }
                }
            }
            insets
        }
    }

    /** 現在の設定と画面寸法から、入力Viewを作るときと同じ方法でバー段数上限と表示用キー行高を求める。 */
    private fun currentInputLayout(): Pair<Int, Float> {
        val rowBudget = maximumBarRows()
        val toolbarPreferences = UzumiSettings.toolbarPreferences(this)
        val reservedBarRows = minOf(toolbarPreferences.toolbarRows + toolbarPreferences.candidateRows, rowBudget)
        return rowBudget to displayKeyboardPreferences(UzumiSettings.keyboardPreferences(this), reservedBarRows).rowHeightDp
    }

    /**
     * 入力Viewが使える高さ（dp）を、画面全体からステータスバー・カットアウトと下端の操作領域を除いて求める。
     * 下端は端末全体の推定と、入力Viewで実際に受け取ったInsetsの大きい方を使う。
     */
    private fun availableInputHeightDp(): Int {
        val density = resources.displayMetrics.density
        val metrics = getSystemService(WindowManager::class.java)?.maximumWindowMetrics
            ?: return resources.configuration.screenHeightDp
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        val heightPx = metrics.bounds.height() - insets.top - maxOf(insets.bottom, observedBottomInsetPx)
        return (heightPx / density).toInt().coerceAtLeast(0)
    }

    /** フィールドごとに旧接続を破棄し、新しい編集セッションを開始する。 */
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // 再開始（画面回転などの同じ欄への再接続）ではonFinishInputが呼ばれず、Editorにcomposing spanが残る。
        // 残したまま新しいセッションで入力すると、未確定だった本文全体が新しい文字で置き換わるため、
        // 再開始後に有効な接続（currentInputConnection）で先に確定する。
        closeSession(restartedConnection = if (restarting) currentInputConnection else null)
        clearInlineSuggestions()
        candidateGrid?.hide()
        candidateActionPanel?.hide()
        toolbarPanel?.hide()
        templatePanel?.hide()
        clipboardHistoryPanel?.hide()
        clipboardHistorySessionEpoch = null
        hideEditPanel()
        currentPolicy = null
        autoCapsMode = 0
        refreshCandidates()
        val info = attribute ?: return
        editorInOwnApp = info.packageName == packageName
        editorFieldId = info.fieldId
        val connection = currentInputConnection ?: return
        // 設定で学習を止めている場合は、欄の種類によらずMozcの学習も止める（学習の設定はLearningStoreだけが持つ）。
        // 起動直後で学習キャッシュをまだ読み終えていない欄は、UIスレッドで待たずに学習しない側へ倒す
        val policy = InputFieldPolicy.fromEditorInfo(info)
            .copy(learningDisabledBySetting = LearningStores.peek()?.isEnabled != true)
        currentPolicy = policy
        // ローマ字の層で自動大文字化を使わない判断はキーボード側が層ごとに行い、英字の層では欄の指定に従う。
        val romaji = policy.usesRomajiInput(UzumiSettings.isRomajiInputEnabled(this))
        autoCapsMode = info.initialCapsMode and policy.capsModeFlags
        // ライブ変換の設定は入力開始ごとに読み、設定画面での変更を次の入力欄から反映する。
        val live = policy.usesLiveConversion(UzumiSettings.isLiveConversionEnabled(this))
        // debugビルドで選んだモデル。ニューラル変換はライブ変換の要求だけに使う。releaseビルドでは常にMozcだけ。
        // 明示変換の欄へ移っても接続は切らず、欄を行き来するたびにモデルを読み直さない。
        val neuralSpec = NeuralSelection.current(this)
        applyNeuralSelection(neuralSpec, restarting)
        session = EditorSession(
            connection = AndroidInputConnectionPort(connection),
            policy = policy,
            initialSelectionStart = info.initialSelStart,
            initialSelectionEnd = info.initialSelEnd,
            sessionEpoch = ++lastSessionEpoch,
            conversionClient = conversionWorker,
            // 変換結果の世代はモデルごとに変え、モデルを切り替えた前後の結果を混ぜない。
            liveCore = if (live) LiveConversionCore(initialConverterGeneration = neuralSpec?.generation ?: 0L) else null,
            liveClient = conversionWorker,
            predictionClient = conversionWorker,
            compositionStyler = ::highlightSegment,
            evaluation = evaluation,
            correctness = CorrectnessJudge.shared,
            romajiInputEnabled = romaji,
        )
        if (inputViewVisible) refreshClipboardSuggestion()
        applyPolicyToKeyboard()
        refreshCandidates()
    }

    /** 表示済みキーボードへ現在欄の種別と候補を反映する。 */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputViewVisible = true
        // 設定画面で高さや反応を変えた後は、新しい設定で入力Viewを作り直す
        if ((builtPreferences != null && builtPreferences != UzumiSettings.keyboardPreferences(this)) ||
            (builtToolbarPreferences != null && builtToolbarPreferences != UzumiSettings.toolbarPreferences(this))
        ) {
            setInputView(onCreateInputView())
        }
        startClipboardObservation()
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
        updateAutoCaps()
        refreshCandidates()
        refreshEditPanelState()
    }

    /**
     * カーソル位置で欄が求める大文字化を問い合わせ、英字配列のShiftへ反映する。欄が求めない場合は問い合わせない。
     * ローマ字の層で使わない判断はキーボード側が行う。
     */
    private fun updateAutoCaps() {
        val flags = currentPolicy?.capsModeFlags ?: 0
        autoCapsMode = if (flags == 0) 0 else currentInputConnection?.getCursorCapsMode(flags) ?: 0
        keyboardPanel?.setAutoCapsMode(autoCapsMode)
    }

    /** 非表示になるキーボードの保留タッチを止める。 */
    override fun onFinishInputView(finishingInput: Boolean) {
        inputViewVisible = false
        keyboardPanel?.cancelPendingInput()
        candidateGrid?.hide()
        candidateActionPanel?.hide()
        toolbarPanel?.hide()
        templatePanel?.hide()
        clipboardHistoryPanel?.hide()
        hideEditPanel()
        stopClipboardObservation()
        clearInlineSuggestions()
        super.onFinishInputView(finishingInput)
    }

    /** 入力終了時に接続と一時compositionを無効化する。 */
    override fun onFinishInput() {
        inputViewVisible = false
        keyboardPanel?.cancelPendingInput()
        candidateGrid?.hide()
        candidateActionPanel?.hide()
        toolbarPanel?.hide()
        templatePanel?.hide()
        clipboardHistoryPanel?.hide()
        hideEditPanel()
        stopClipboardObservation()
        closeSession()
        clearInlineSuggestions()
        currentPolicy = null
        refreshCandidates()
        super.onFinishInput()
    }

    /** IME windowが隠れたとき編集パネルと選択モードを解放する。 */
    override fun onWindowHidden() {
        hideEditPanel()
        super.onWindowHidden()
    }

    /**
     * Autofill serviceへ渡すInline候補の要求を作る。札の配色はキーボードのライト／ダークに合わせ、高さは候補バーに収める。
     * OSの描画側がv1の見た目に対応しない場合はnullを返し、従来のAutofill表示に任せる。
     */
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (UiVersions.INLINE_UI_VERSION_1 !in UiVersions.getVersions(uiExtras)) return null
        val colors = KeyboardColors.from(this)
        val density = resources.displayMetrics.density
        val px = { dp: Float -> (dp * density).toInt() }
        val chipBackground = Icon.createWithResource(this, R.drawable.inline_suggestion_chip).setTint(colors.keyBackground)
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(ViewStyle.Builder().setBackground(chipBackground).build())
            .setChipStyle(
                ViewStyle.Builder()
                    .setBackground(chipBackground)
                    .setPadding(px(INLINE_CHIP_PADDING_DP), 0, px(INLINE_CHIP_PADDING_DP), 0)
                    .build(),
            )
            .setTitleStyle(TextViewStyle.Builder().setTextColor(colors.text).setTextSize(INLINE_TITLE_TEXT_SP).build())
            .setSubtitleStyle(TextViewStyle.Builder().setTextColor(colors.textSecondary).setTextSize(INLINE_SUBTITLE_TEXT_SP).build())
            .build()
        val spec = InlinePresentationSpec.Builder(
            Size(px(INLINE_MIN_WIDTH_DP), px(INLINE_HEIGHT_DP)),
            Size(px(INLINE_MAX_WIDTH_DP), px(INLINE_HEIGHT_DP)),
        ).setStyle(UiVersions.newStylesBuilder().addStyle(style).build()).build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(INLINE_MAX_SUGGESTIONS)
            .build()
    }

    /**
     * Inline候補を展開して候補バーへ置く。全件の展開を終えた時点で、同じ欄の最新の応答である場合だけ採用する。
     * 空の応答や入力欄のない応答では一覧を空にしてfalseを返す。候補の中身はどこへも記録しない。
     */
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val generation = ++inlineResponseGeneration
        val epoch = session?.sessionEpoch
        val suggestions = response.inlineSuggestions
        if (epoch == null || suggestions.isEmpty()) {
            detachInlineViews()
            inlineSuggestions = emptyList()
            refreshCandidates()
            return false
        }
        val inflated = arrayOfNulls<InlineContentView>(suggestions.size)
        var remaining = suggestions.size
        val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        suggestions.forEachIndexed { index, suggestion ->
            suggestion.inflate(this, size, mainExecutor) { view ->
                inflated[index] = view
                remaining -= 1
                if (remaining == 0 &&
                    InlineAutofillPolicy.acceptsResponse(epoch, session?.sessionEpoch, generation, inlineResponseGeneration)
                ) {
                    detachInlineViews()
                    inlineSuggestions = suggestions.indices.mapNotNull { i ->
                        inflated[i]?.let { view ->
                            // 展開時にframeworkが描画済みの寸法をLayoutParamsへ入れて返すため、付け直しで失わないよう保持する。
                            val params = view.layoutParams
                            InlineSuggestionItem(
                                view,
                                suggestions[i].info.isPinned,
                                width = params?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                                height = params?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                        }
                    }
                    refreshCandidates()
                }
            }
        }
        return true
    }

    /** Inline候補を捨て、展開中の応答も採用しないようにする。欄の切り替えと入力の終了で呼ぶ。 */
    private fun clearInlineSuggestions() {
        inlineResponseGeneration += 1
        detachInlineViews()
        inlineSuggestions = emptyList()
        inlineHost?.removeAllViews()
        inlineHostItems = null
    }

    /** 表示中のInline候補の部品を親から外す。同じ部品を別の親へ付け直す前と、破棄する前に呼ぶ。 */
    private fun detachInlineViews() {
        inlineSuggestions.forEach { (it.view.parent as? ViewGroup)?.removeView(it.view) }
    }

    /** キーボード操作を現在の編集セッションへ一度だけ送る。 */
    private fun handleKeyboardAction(action: KeyboardAction) {
        val current = session ?: return
        current.setHiddenCandidateSurfaces(HiddenCandidateStores.get(this).all())
        OperationClassifier.keyboardAction(action, current.isLiveMode)?.let(evaluation::record)
        // Backspaceは連続削除をまとめ、絵文字一覧の開閉中は削除Undoを保ち、ほかの編集操作で記録を終える。
        if (action != KeyboardAction.Delete && action != KeyboardAction.DeleteToLineStart &&
            action != KeyboardAction.OpenEmojiPicker && action != KeyboardAction.CloseEmojiPicker
        ) current.forgetDeletionUndo()
        when (action) {
            // 英字配列の文字は、ローマ字かな入力が有効な欄ではかなへ組み立てる（無効な欄と英字の層では従来どおり英字）。
            is KeyboardAction.Text -> if (keyboardPanel?.isRomajiQwertyMode == true) {
                current.inputRomajiText(action.value)
            } else {
                current.inputText(action.value, fromTwelveKey = keyboardPanel?.isKanaMode == true)
            }
            is KeyboardAction.KanaTap ->
                current.inputKanaTap(KeyboardLayoutData.toggleChars(action.type), SystemClock.uptimeMillis())
            KeyboardAction.Delete -> current.deleteBackward()
            is KeyboardAction.MoveCursor -> when {
                // 12キーの連続タップの途中の→は、次のタップを新しい文字にする区切りとして使い、カーソルは動かさない。
                action.delta > 0 && current.endKanaToggle(SystemClock.uptimeMillis()) -> Unit
                // ライブ変換の入力中は、←→で候補バーの対象の文節を前後へ移す（Simejiの変換中の←→に当たる操作）。
                current.isLiveMode && current.hasComposition -> current.moveLiveFocus(action.delta)
                // 明示変換の結果の表示中も、←→で注目文節を移す（Gboardの変換中の←→に当たる操作）。
                current.hasExplicitConversion -> current.moveConversionFocus(action.delta)
                else -> current.moveCursor(action.delta)
            }
            // Space・変換キーの横ドラッグは、ライブ変換中も文節焦点ではなく実カーソルを動かす。
            is KeyboardAction.MoveEditorCursor -> current.moveCursor(action.delta)
            KeyboardAction.Enter -> current.handleEnter()
            // ローマ字かな入力中の英字配列のSpaceは、12キーの空白キーと同じく入力中なら変換にする。
            KeyboardAction.Space -> if (keyboardPanel?.isRomajiQwertyMode == true && current.isRomajiInputEnabled && current.hasComposition) {
                current.convert()
            } else {
                current.insertSpace()
            }
            KeyboardAction.Convert -> current.convert()
            KeyboardAction.TransformKana -> current.transformKana()
            KeyboardAction.ToKatakana -> current.toKatakana()
            KeyboardAction.DeleteToLineStart -> current.deleteToLineStart()
            KeyboardAction.OpenEmojiPicker -> {
                val isSensitive = currentPolicy?.isSensitive != false
                keyboardPanel?.showEmojiPicker(if (isSensitive) emptyList() else emojiRecentStore.load())
            }
            KeyboardAction.CloseEmojiPicker -> keyboardPanel?.hideEmojiPicker()
            is KeyboardAction.InsertEmoji -> {
                val inserted = current.pasteText(action.value)
                // 機密欄に加え、設定で学習を止めている場合も履歴へ残さない。
                if (inserted && currentPolicy?.suppressLearning == false) {
                    val recentValues = emojiRecentStore.record(action.value, insertionSucceeded = true, learningSuppressed = false)
                    keyboardPanel?.setRecentEmojiValues(recentValues)
                }
            }
            // 変換中の←→の長押しで、文節の区切りを一文字ずつ縮める・伸ばす
            is KeyboardAction.ResizeSegment -> current.resizeSegment(action.delta)
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
        current.setHiddenCandidateSurfaces(HiddenCandidateStores.get(this).all())
        if (current.applyLiveResult(result)) {
            lastAppliedLive = result.identity to System.nanoTime()
            refreshCandidates()
        }
    }

    /**
     * 評価モードの間、ライブ変換の要求ごとの報告を計数へ足す。結果が適用された場合だけ、適用後に数える項目
     * （H3の適用後の違反件数、入力した数字・英字の改変）と適用までの時間を足す。報告は結果の直後に届く。
     */
    private fun deliverLiveReport(sessionEpoch: Long, report: LiveRequestReport) {
        if (session?.sessionEpoch != sessionEpoch) return
        val applied = lastAppliedLive?.takeIf { report.identity != null && it.first == report.identity }
        if (applied == null) {
            evaluation.recordNeural(report.counts, report.samples)
            return
        }
        val latency = TimingSample(TimingKind.APPLY, (applied.second - report.requestedAtNanos) / 1_000_000.0)
        evaluation.recordNeural(report.counts + report.appliedOnly, report.samples + latency)
    }

    /**
     * 使うモデルを切り替える。前と同じなら何もしない。nullならMozcだけに戻し、推論serviceとの接続を切る。
     * 新しいモデルは`:neural`へbindして読み込ませ、準備ができるまではMozcの結果で入力を続ける。
     * 同じモデルでも、`:neural`の終了が上限を超えて接続し直すのをやめた接続は、新しい入力欄の開始でだけ作り直す
     * （[restarting]が真の同じ欄の再開始では作り直さない）。
     */
    private fun applyNeuralSelection(spec: NeuralModelSpec?, restarting: Boolean) {
        val current = neuralConnection
        if (!shouldRecreateNeuralConnection(current?.spec, current?.gaveUp == true, spec, restarting)) return
        conversionWorker?.setNeuralBackend(null)
        neuralConnection?.close()
        neuralConnection = spec?.let { NeuralRuntimeConnection(this, it).apply { open() } }
        conversionWorker?.setNeuralBackend(neuralConnection?.client)
    }

    /** 取り直した文節の候補を、同じ編集セッションが照合できる場合だけ反映する。 */
    private fun deliverCandidateResult(sessionEpoch: Long, result: CandidateResult) {
        val current = session ?: return
        if (current.sessionEpoch != sessionEpoch) return
        current.setHiddenCandidateSurfaces(HiddenCandidateStores.get(this).all())
        if (current.applyLiveCandidates(result)) refreshCandidates()
    }

    /** 予測応答を現在のsessionとrevisionが受け入れた場合だけ候補バーへ反映する。 */
    private fun deliverPredictionResult(result: PredictionResult) {
        val current = session ?: return
        if (current.sessionEpoch != result.request.sessionEpoch) return
        if (current.applyPredictionResult(result)) refreshCandidates()
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
        // ローマ字かな入力の欄でだけ、英字配列をローマ字と英字の層に分ける。英字の層を含めるかは設定画面で選ぶ。
        keyboardPanel?.setRomajiInput(
            romajiInput = session?.isRomajiInputEnabled == true,
            includesEnglish = UzumiSettings.isRomajiEnglishQwertyIncluded(this),
        )
        keyboardPanel?.setActionLabel(policy.actionLabel)
        keyboardPanel?.setAutoCapsMode(autoCapsMode)
    }

    /** IME表示中だけクリップボード変更を監視し、貼り付け提案と履歴を更新する。 */
    private fun startClipboardObservation() {
        val manager = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val observer = recentClipboard ?: RecentClipboard(manager, onClipboardChanged = {
            val identity = recentClipboard?.currentHistoryIdentity()
            val capturePolicy = clipboardHistoryCapturePolicy()
            val historyEnabled = UzumiSettings.isClipboardHistoryEnabled(this)
            val visibleAtChange = inputViewVisible
            mainHandler.post {
                if (identity != null && visibleAtChange && historyEnabled) {
                    captureClipboardHistory(identity, capturePolicy, historyEnabled)
                }
                if (inputViewVisible) refreshClipboardSuggestion()
            }
        }).also { recentClipboard = it }
        if (observer.startObserving()) {
            captureClipboardHistory()
            scheduleClipboardHistoryExpiry()
            refreshClipboardSuggestion()
        } else {
            clipboardSuggestion = null
            clipboardSuggestionSessionEpoch = null
            refreshCandidates()
            refreshEditPanelState()
        }
    }

    /** 表示中に作った候補だけを有効にし、60秒後に本文表示を含む一時状態を破棄する。 */
    private fun refreshClipboardSuggestion() {
        if (!inputViewVisible) return
        clipboardExpiry?.let(mainHandler::removeCallbacks)
        clipboardExpiry = null
        val observer = recentClipboard ?: return
        val suggestion = observer.currentSuggestion(sensitiveField = currentPolicy?.isSensitive == true)
        clipboardSuggestion = suggestion
        clipboardSuggestionSessionEpoch = session?.sessionEpoch
        if (suggestion != null) {
            val expiry = Runnable {
                if (inputViewVisible && clipboardSuggestion === suggestion &&
                    clipboardSuggestionSessionEpoch == session?.sessionEpoch
                ) {
                    clipboardSuggestion = null
                    clipboardSuggestionSessionEpoch = null
                    clipboardExpiry = null
                    refreshCandidates()
                    refreshEditPanelState()
                }
            }
            clipboardExpiry = expiry
            mainHandler.postDelayed(expiry, (suggestion.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0L))
        }
        refreshCandidates()
        refreshEditPanelState()
    }

    /** キーボード非表示時にclipboard監視と提案を止め、履歴期限timerは継続する。 */
    private fun stopClipboardObservation() {
        recentClipboard?.close()
        clearClipboardSuggestion()
        clipboardHistorySessionEpoch = null
        clipboardHistoryPanel?.hide()
        refreshEditPanelState()
    }

    /** clip通知時に観測したpolicyと識別情報を使い、機密本文や遅れて変わった欄の条件を持ち込まない。 */
    private fun captureClipboardHistory(
        observedIdentity: ClipboardHistoryIdentity? = null,
        capturePolicy: ClipboardHistoryCapturePolicy = clipboardHistoryCapturePolicy(),
        historyEnabledAtChange: Boolean = UzumiSettings.isClipboardHistoryEnabled(this),
    ) {
        if (!historyEnabledAtChange) return
        val observer = recentClipboard
        val identity = observedIdentity ?: observer?.currentHistoryIdentity() ?: return
        val store = clipboardHistoryStore
        if (store.wasHandled(identity)) return

        val mayStoreText = ClipboardHistoryRules.mayStoreText(
            clipboardSensitive = identity.clipboardSensitive,
            capturePolicy = capturePolicy,
        )
        if (!mayStoreText || evaluation.isRecording || !UzumiSettings.isClipboardHistoryEnabled(this) ||
            !inputViewVisible || session?.isActive != true || observer == null
        ) {
            store.markHandled(identity)
        } else {
            val text = observer.readForHistory(identity, sensitiveField = capturePolicy.sensitiveField)
            if (text == null) store.markHandled(identity) else store.add(identity, text)
        }
        scheduleClipboardHistoryExpiry()
        if (clipboardHistoryPanel?.isShowing == true) refreshClipboardHistoryPanel()
    }

    /** clipboard listenerが届いた時点の欄・学習・評価状態を本文なしで記録する。 */
    private fun clipboardHistoryCapturePolicy(): ClipboardHistoryCapturePolicy {
        val policy = currentPolicy
        return ClipboardHistoryCapturePolicy(
            sensitiveField = policy?.isSensitive ?: true,
            learningSuppressed = policy?.suppressLearning ?: true,
            evaluationRecording = evaluation.isRecording,
        )
    }

    /** 履歴パネルを開ける表示状態、設定、入力欄、評価状態かを一か所で確認する。 */
    private fun canShowClipboardHistory(): Boolean {
        val current = session
        return inputViewVisible && current?.isActive == true &&
            UzumiSettings.isClipboardHistoryEnabled(this) && !current.policy.suppressLearning &&
            !evaluation.isRecording
    }

    /** キーボード面へ履歴を重ね、候補一覧や機能一覧とは同時に表示しない。 */
    private fun showClipboardHistory() {
        if (!canShowClipboardHistory()) return
        candidateGrid?.hide()
        toolbarPanel?.hide()
        clipboardHistorySessionEpoch = session?.sessionEpoch
        refreshClipboardHistoryPanel()
    }

    /** 現在の履歴をパネルへ反映し、各ボタンを同じ編集セッションへ結び付ける。 */
    private fun refreshClipboardHistoryPanel() {
        val panel = clipboardHistoryPanel ?: return
        val current = session
        val epoch = clipboardHistorySessionEpoch
        if (!panel.isShowing && epoch == null) return
        if (!canShowClipboardHistory() || current == null || epoch != current.sessionEpoch) {
            panel.hide()
            clipboardHistorySessionEpoch = null
            return
        }
        panel.show(
            items = clipboardHistoryStore.readItems(),
            onPaste = { item -> pasteClipboardHistoryItem(item, current.sessionEpoch) },
            onPin = { item, pinned -> setClipboardHistoryPinned(item, pinned, current.sessionEpoch) },
            onDelete = { item -> deleteClipboardHistoryItem(item, current.sessionEpoch) },
            onClearAll = { confirmClearClipboardHistory(current.sessionEpoch) },
            onClose = {
                panel.hide()
                clipboardHistorySessionEpoch = null
            },
        )
        scheduleClipboardHistoryExpiry()
    }

    /** 期限切れでない同一履歴項目を、変換や学習を通さない既存の貼り付け経路へ渡す。 */
    private fun pasteClipboardHistoryItem(item: ClipboardHistoryItem, sessionEpoch: Long) {
        val current = session ?: return
        if (!canShowClipboardHistory() || current.sessionEpoch != sessionEpoch) return
        val stored = clipboardHistoryStore.readItems().firstOrNull {
            it.text == item.text && it.copiedAtMillis == item.copiedAtMillis
        } ?: return
        if (!current.pasteText(stored.text)) return
        clipboardHistoryPanel?.hide()
        clipboardHistorySessionEpoch = null
        scheduleConversionTimeout(current)
        refreshCandidates()
    }

    /** 選んだ履歴の固定状態を更新し、期限切れを含む最新一覧を再描画する。 */
    private fun setClipboardHistoryPinned(item: ClipboardHistoryItem, pinned: Boolean, sessionEpoch: Long) {
        if (!canShowClipboardHistory() || session?.sessionEpoch != sessionEpoch) return
        clipboardHistoryStore.setPinned(item.text, pinned)
        refreshClipboardHistoryPanel()
    }

    /** 選んだ履歴を削除し、現在clipの処理済み識別情報を維持する。 */
    private fun deleteClipboardHistoryItem(item: ClipboardHistoryItem, sessionEpoch: Long) {
        if (!canShowClipboardHistory() || session?.sessionEpoch != sessionEpoch) return
        clipboardHistoryStore.delete(item.text)
        refreshClipboardHistoryPanel()
    }

    /** 全削除前に確認し、同じ現在clipが次回表示時に再追加されないよう識別情報を残す。 */
    private fun confirmClearClipboardHistory(sessionEpoch: Long) {
        if (!canShowClipboardHistory() || session?.sessionEpoch != sessionEpoch) return
        clipboardHistoryPanel?.showClearConfirmation {
            if (canShowClipboardHistory() && session?.sessionEpoch == sessionEpoch) {
                clipboardHistoryStore.clearAll(recentClipboard?.currentHistoryIdentity())
                refreshClipboardHistoryPanel()
                scheduleClipboardHistoryExpiry()
            }
        }
    }

    /** 未固定履歴の最古期限に合わせ、IME非表示や設定OFFでもサービス中は自動削除を続ける。 */
    private fun scheduleClipboardHistoryExpiry() {
        clipboardHistoryExpiry?.let(mainHandler::removeCallbacks)
        clipboardHistoryExpiry = null
        val nextExpiry = clipboardHistoryStore.nextExpiryMillis() ?: return
        val expiry = Runnable {
            clipboardHistoryExpiry = null
            clipboardHistoryStore.readItems()
            if (clipboardHistoryPanel?.isShowing == true) {
                refreshClipboardHistoryPanel()
            }
            scheduleClipboardHistoryExpiry()
        }
        clipboardHistoryExpiry = expiry
        mainHandler.postDelayed(expiry, (nextExpiry - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    /** 設定値を変えず、キーボード最小高を残した画面内のバー段数上限を返す。 */
    private fun maximumBarRows(): Int {
        val availableHeight = availableInputHeightDp()
        val minimumKeyboardHeight = maxOf(
            KeyboardPreferences.MIN_ROW_HEIGHT_DP * KANA_KEYBOARD_ROW_COUNT,
            MIN_KEY_TOUCH_DP * QWERTY_KEYBOARD_ROW_COUNT,
        ) + KEYBOARD_PANEL_PADDING_DP
        return ((availableHeight - minimumKeyboardHeight) / BAR_ROW_HEIGHT_WITH_DIVIDER_DP)
            .coerceIn(1, MAX_VISIBLE_BAR_ROWS)
    }

    /** 横画面などで画面に収めるため、保存値を変えず表示用のキーボード行高だけを下げる。 */
    private fun displayKeyboardPreferences(
        configured: KeyboardPreferences,
        reservedBarRows: Int,
    ): KeyboardPreferences {
        val availableHeight = availableInputHeightDp()
        val barsHeight = reservedBarRows * BAR_ROW_HEIGHT_WITH_DIVIDER_DP + KEYBOARD_PANEL_PADDING_DP
        val keyboardHeight = (availableHeight - barsHeight).coerceAtLeast(0)
        val qwertyHeightPerRow = KeyboardDimens.QWERTY_ROW_HEIGHT_DP / KeyboardDimens.ROW_HEIGHT_DP
        val maximumRowHeight = (keyboardHeight / maxOf(
            KANA_KEYBOARD_ROW_COUNT.toFloat(),
            QWERTY_KEYBOARD_ROW_COUNT * qwertyHeightPerRow,
        )).toInt().coerceAtLeast(KeyboardPreferences.MIN_ROW_HEIGHT_DP)
        return configured.copy(rowHeightDp = minOf(configured.rowHeightDp.toInt(), maximumRowHeight).toFloat())
    }

    /**
     * 候補バーを今の入力状態に合わせて作り直す。左端に「元に戻す」や訂正中の読み、中央に候補、右端に「末尾」と候補一覧の開閉を置く。
     * 入力していない間は、設定への入口と案内、キーボードを閉じるボタンを出す。
     */
    private fun refreshCandidates() {
        if (evaluation.isRecording) {
            clipboardHistoryPanel?.hide()
            clipboardHistorySessionEpoch = null
        }
        keyboardPanel?.setComposing(session?.hasComposition == true)
        keyboardPanel?.setSegmentResizable(session?.canResizeSegment == true)
        val row = candidateRow ?: return
        val views = barViews ?: return
        val leading = barLeading ?: return
        val trailing = barTrailing ?: return
        if (evaluation.isRecording) candidateActionPanel?.hide()
        row.removeAllViews()
        leading.removeAllViews()
        trailing.removeAllViews()
        val current = session
        current?.setHiddenCandidateSurfaces(HiddenCandidateStores.get(this).all())
        // 評価の記録中は予測を出さず、操作数を予測導入前と同じ条件で数える。
        val predictionsShown = !evaluation.isRecording
        if (predictionsShown) current?.refreshPredictions()
        if (current?.hasComposition != true) addClipboardSuggestion(current, leading)
        if (current?.canUndoDeletion == true) {
            leading.addView(views.chip(getString(R.string.line_delete_undo), getString(R.string.line_delete_undo_description), true) {
                evaluation.record(OperationKind.CORRECTION)
                if (session === current) current.undoDeletion()
                refreshCandidates()
            })
        }
        val predictions = current?.predictionResult()?.takeIf { predictionsShown }?.let { result ->
            result.candidates.map { candidate -> predictionEntry(current, result, candidate) }
        }.orEmpty()
        val regularEntries = if (current?.isLiveMode == true) liveEntries(current, leading, trailing) else explicitEntries(current)
        val entries = predictions + regularEntries.mapIndexed { index, entry ->
            CandidateEntry(
                label = entry.label,
                selected = entry.selected,
                surface = entry.surface,
                learnedReading = entry.learnedReading,
                sectionBreakBefore = predictions.isNotEmpty() && index == 0,
                isPrediction = entry.isPrediction,
                onPick = entry.onPick,
            )
        }
        // 次の語の予測はInline候補の右に並べるため、Inline候補の表示条件には通常候補だけを使う。
        updateInlineHost(regularEntries.isEmpty() && current != null && InlineAutofillPolicy.shouldShow(
            hasComposition = current.hasComposition,
            hasRegularCandidates = false,
            inlineCount = inlineSuggestions.size,
        ))
        val grid = candidateGrid
        val preferences = UzumiSettings.toolbarPreferences(this)
        val visibleActions = availableToolbarActions(current)
        val requestedToolbarRows = preferences.toolbarActionRows(visibleActions).size
        val availableCandidateWidthDp = (resources.configuration.screenWidthDp - CANDIDATE_FIXED_WIDTH_DP)
            .coerceAtLeast(MIN_CANDIDATE_SLOT_DP)
        val maxItemsPerRow = availableCandidateWidthDp / MIN_CANDIDATE_SLOT_DP
        // 候補の件数で候補列の高さが変わるとキーボード全体が上下し、長押し中の操作が途切れるため、設定段数で固定する。
        val requestedCandidateRows = preferences.candidateRows
        val effectiveRows = fitBarRows(requestedToolbarRows, requestedCandidateRows)
        refreshToolbar(effectiveRows.first)
        if (entries.isEmpty()) {
            grid?.hide()
            if (current?.hasComposition == true) {
                row.addView(views.hint(hintText(current.policy, current.isLiveMode)),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
            } else {
                showIdleBar(current, trailing)
            }
            addToolbarEntry(trailing, current)
            resizeCandidateBar(effectiveRows.second)
            return
        }
        val indexRows = preferences.copy(candidateRows = effectiveRows.second)
            .candidateIndexRows(entries.size, maxItemsPerRow)
        indexRows.forEachIndexed { rowIndex, indexes ->
            val candidateLine = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (rowIndex == 0 && engineHealth is EngineHealth.Unavailable && current?.policy?.suppressSuggestions == false) {
                candidateLine.addView(views.hint(getString(R.string.candidate_status_no_dictionary)))
            }
            indexes.forEach { index ->
                val entry = entries[index]
                if (entry.sectionBreakBefore && index != indexes.first()) candidateLine.addView(views.divider())
                candidateLine.addView(views.item(
                    entry.label,
                    entry.selected,
                    entry.isPrediction,
                    onLongClick = { openCandidateActionMenu(current, entry) },
                ) { pickCandidate(current, entry, index) }.apply {
                    maxLines = 1
                })
            }
            row.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                // 各段の候補は従来どおり横へスクロールでき、段が増えても候補indexは元の順序を保つ
                isHorizontalFadingEdgeEnabled = true
                setFadingEdgeLength((CANDIDATE_FADE_DP * resources.displayMetrics.density).toInt())
                addView(candidateLine, FrameLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (CandidateBarViews.BAR_HEIGHT_DP * resources.displayMetrics.density).toInt()))
            if (rowIndex < indexRows.lastIndex) {
                row.addView(views.divider().apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                })
            }
        }
        // 縦線は右端の固定ボタン（末尾・∨）すべての手前に置き、候補の列がそこで終わることを示す
        trailing.addView(views.divider(), 0)
        if (regularEntries.isEmpty() && current?.hasComposition != true) {
            // 確定後の次の語の予測だけを並べている間は、待機中と同じくキーボードを閉じる操作を残す。
            grid?.hide()
            trailing.addView(views.symbolButton("⌄", getString(R.string.close_keyboard)) {
                evaluation.record(OperationKind.OTHER)
                requestHideSelf(0)
            })
            addToolbarEntry(trailing, current)
            resizeCandidateBar(effectiveRows.second)
            return
        }
        trailing.addView(views.symbolButton(if (grid?.isShowing == true) "∧" else "∨", getString(R.string.candidate_list_toggle)) {
            evaluation.record(OperationKind.OTHER)
            toolbarPanel?.hide()
            clipboardHistoryPanel?.hide()
            clipboardHistorySessionEpoch = null
            if (grid?.isShowing == true) {
                grid.hide()
            } else {
                templatePanel?.hide()
                showCandidateGrid(current, entries)
                // ライブ変換では、一覧を開いた文節の候補を明示変換と同じだけ取り直す。届いたら一覧を描き直す。
                if (current?.isLiveMode == true && session === current) current.requestLiveCandidates()
            }
            refreshCandidates()
        })
        addToolbarEntry(trailing, current)
        resizeCandidateBar(effectiveRows.second)
        // 一覧を開いたまま候補が変わった場合は、一覧も新しい候補で描き直す
        if (grid?.isShowing == true) showCandidateGrid(current, entries)
    }

    /** 候補バーを設定に応じた段数の高さで表示する。候補が少なくても高さを変えず、キーボードの上下動を防ぐ。 */
    private fun resizeCandidateBar(rows: Int) {
        val density = resources.displayMetrics.density
        val dividerHeight = (density + 0.5f).toInt()
        val rowHeight = (CandidateBarViews.BAR_HEIGHT_DP * density).toInt()
        val height = rowHeight * rows + dividerHeight * (rows - 1).coerceAtLeast(0)
        candidateBar?.layoutParams = candidateBar?.layoutParams?.apply { this.height = height }
        candidateBar?.requestLayout()
    }

    /** 選んだショートカットを機能バーへ表示し、使えないUndoなどの空枠は省く。 */
    private fun refreshToolbar(maxRows: Int) {
        val parent = toolbarRow ?: return
        val views = toolbarViews ?: return
        val preferences = UzumiSettings.toolbarPreferences(this)
        val visibleActions = availableToolbarActions(session)
        val rows = if (maxRows <= 0) emptyList() else preferences.copy(toolbarRows = maxRows)
            .toolbarActionRows(visibleActions)
        views.addToolbarRows(parent, rows, ::toolbarActionLabel, ::handleToolbarAction)
        val height = (ToolbarBarViews.BAR_ROW_HEIGHT_DP * resources.displayMetrics.density * rows.size).toInt()
        parent.layoutParams = parent.layoutParams?.apply { this.height = height }
        parent.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        parent.requestLayout()
    }


    /** 画面高に収まらない場合、候補を最低1段残して両バーの実段数を縮める。 */
    private fun fitBarRows(toolbarRows: Int, candidateRows: Int): Pair<Int, Int> {
        var toolbar = toolbarRows
        var candidates = candidateRows.coerceAtLeast(1)
        val budget = maximumBarRows()
        while (toolbar + candidates > budget) {
            when {
                candidates > 1 && (candidates >= toolbar || toolbar == 0) -> candidates -= 1
                toolbar > 1 -> toolbar -= 1
                candidates > 1 -> candidates -= 1
                toolbar > 0 -> toolbar -= 1
                else -> break
            }
        }
        return toolbar to candidates
    }

    /** 機能一覧の入口を候補バーへ常時表示する。候補一覧とは同時に開かない。 */
    private fun addToolbarEntry(trailing: LinearLayout, current: EditorSession?) {
        val views = barViews ?: return
        trailing.addView(views.symbolButton("▦", getString(R.string.toolbar_open)) {
            val panel = toolbarPanel ?: return@symbolButton
            hideEditPanel()
            candidateGrid?.hide()
            clipboardHistoryPanel?.hide()
            clipboardHistorySessionEpoch = null
            if (panel.isShowing) {
                panel.hide()
            } else {
                templatePanel?.hide()
                panel.show(availableToolbarActions(current), ::toolbarActionLabel) { action ->
                    panel.hide()
                    handleToolbarAction(action)
                }
            }
        })
    }

    /** 現在実行できるタイルだけを機能バーと機能一覧へ渡す。 */
    private fun availableToolbarActions(current: EditorSession?): List<ToolbarAction> =
        ToolbarPreferences.AVAILABLE_ACTIONS.filter { action ->
            when (action) {
                // 明示的な貼り付けは60秒の自動提案期限に縛らず、テキストclipがある間は出す。
                ToolbarAction.PASTE -> current?.isActive == true && inputViewVisible &&
                    (hasCurrentClipboardSuggestion(current) || recentClipboard?.hasCurrentText() == true)
                ToolbarAction.CLIPBOARD_HISTORY -> canShowClipboardHistory()
                ToolbarAction.UNDO -> current?.let {
                    it.canUndoDeletion || it.liveCandidateState()?.canUndo == true
                } == true
                ToolbarAction.EDIT -> current?.isActive == true && inputViewVisible
                ToolbarAction.MICROPHONE -> findEnabledVoiceInputMethod() != null
                else -> true
            }
        }

    /** 機能タイルを現在の編集セッションへ適用する。 */
    private fun handleToolbarAction(action: ToolbarAction) {
        val current = session
        when (action) {
            ToolbarAction.PASTE -> pasteFromClipboard()
            ToolbarAction.CLIPBOARD_HISTORY -> showClipboardHistory()
            ToolbarAction.EDIT -> showEditPanel()
            ToolbarAction.MICROPHONE -> switchToEnabledVoiceInputMethod()
            ToolbarAction.UNDO -> {
                evaluation.record(OperationKind.CORRECTION)
                if (current == null || session !== current) return
                when {
                    current.canUndoDeletion -> current.undoDeletion()
                    current.liveCandidateState()?.canUndo == true -> current.undoLive()
                }
                scheduleConversionTimeout(current)
            }
            ToolbarAction.USER_DICTIONARY -> startActivity(
                Intent(this, UserDictionaryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            ToolbarAction.TEMPLATES -> showTemplatePicker()
            ToolbarAction.SETTINGS -> openSettings()
            ToolbarAction.CLOSE_KEYBOARD -> requestHideSelf(0)
        }
        refreshCandidates()
    }

    /** 各タイルの識別名を、候補バーと設定画面で共通の文言へ変換する。 */
    private fun toolbarActionLabel(action: ToolbarAction): String = when (action) {
        // 本文の冒頭は候補バーの提案だけに出し、機能バーでは同じ本文を重ねて見せない。
        ToolbarAction.PASTE -> getString(R.string.toolbar_paste)
        ToolbarAction.CLIPBOARD_HISTORY -> getString(R.string.toolbar_clipboard_history)
        ToolbarAction.EDIT -> getString(R.string.toolbar_edit)
        ToolbarAction.MICROPHONE -> getString(R.string.toolbar_microphone)
        ToolbarAction.UNDO -> getString(R.string.toolbar_undo)
        ToolbarAction.USER_DICTIONARY -> getString(R.string.open_user_dictionary)
        ToolbarAction.TEMPLATES -> getString(R.string.toolbar_templates)
        ToolbarAction.SETTINGS -> getString(R.string.open_settings)
        ToolbarAction.CLOSE_KEYBOARD -> getString(R.string.close_keyboard)
    }

    /** 定型文を別threadで読み込み、タップされた本文を貼り付け経路で入力する。 */
    private fun showTemplatePicker() {
        val current = session ?: return
        val panel = templatePanel ?: return
        candidateGrid?.hide()
        toolbarPanel?.hide()
        panel.showLoading()
        val executor = conversionExecutor ?: run {
            panel.hide()
            return
        }
        val expectedSession = current.sessionEpoch
        try {
            executor.execute {
                val templates = runCatching {
                    UserDictionaries.get(applicationContext).allEntries()
                        .filter { it.category == UserDictionaryCategory.TEMPLATE }
                }.getOrDefault(emptyList())
                mainHandler.post {
                    if (session !== current || current.sessionEpoch != expectedSession || !inputViewVisible ||
                        templatePanel !== panel || !panel.isShowing
                    ) return@post
                    panel.show(templates) { entry ->
                        panel.hide()
                        if (session === current && current.sessionEpoch == expectedSession && !evaluation.isRecording) {
                            current.pasteText(entry.surface)
                            refreshCandidates()
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            panel.hide()
        }
    }

    /** 有効な入力方法から、voice modeの有効サブタイプを一つ見つける。 */
    private fun findEnabledVoiceInputMethod(): Pair<InputMethodInfo, InputMethodSubtype>? {
        val manager = getSystemService(InputMethodManager::class.java) ?: return null
        return manager.enabledInputMethodList.asSequence().flatMap { info ->
            manager.getEnabledInputMethodSubtypeList(info, true).asSequence()
                .filter { subtype -> subtype.mode.equals("voice", ignoreCase = true) }
                .map { subtype -> info to subtype }
        }.firstOrNull()
    }

    /** 有効な音声入力サブタイプへ切り替え、Uzumi側では録音を行わない。 */
    private fun switchToEnabledVoiceInputMethod() {
        val target = findEnabledVoiceInputMethod() ?: return
        hideEditPanel()
        switchInputMethod(target.first.id, target.second)
    }

    /** 編集パネルを開き、他のキーボード面overlayを閉じる。 */
    private fun showEditPanel() {
        val panel = editPanel ?: return
        if (panel.isShowing) {
            hideEditPanel()
            return
        }
        candidateGrid?.hide()
        toolbarPanel?.hide()
        session?.refreshSelectionFromEditor()
        editPanelSelectionMode = false
        panel.show(
            isPassword = session?.policy?.isPassword == true,
            hasSelection = session?.hasEditPanelSelection == true,
            hasClipboardText = inputViewVisible && recentClipboard?.hasCurrentText() == true,
            selectionMode = editPanelSelectionMode,
        )
    }

    /** 編集パネルを閉じ、選択モードを次回へ持ち越さない。 */
    private fun hideEditPanel() {
        editPanelSelectionMode = false
        editPanel?.hide()
    }

    /** 表示中の編集パネルへEditor selectionとclipboardの可否を反映する。 */
    private fun refreshEditPanelState() {
        val panel = editPanel?.takeIf { it.isShowing } ?: return
        val current = session?.takeIf { it.isActive }
        panel.update(
            isPassword = current?.policy?.isPassword == true,
            hasSelection = current?.hasEditPanelSelection == true,
            hasClipboardText = inputViewVisible && recentClipboard?.hasCurrentText() == true,
            selectionMode = editPanelSelectionMode,
        )
    }

    /** 編集パネルのタイル操作を、現在の編集セッションと標準Editor APIへ渡す。 */
    private fun handleEditPanelAction(action: EditPanelAction) {
        if (action == EditPanelAction.CLOSE) {
            hideEditPanel()
            return
        }
        if (action == EditPanelAction.TOGGLE_SELECTION) {
            editPanelSelectionMode = !editPanelSelectionMode
            refreshEditPanelState()
            return
        }
        val current = session?.takeIf { it.isActive } ?: return
        when (action) {
            EditPanelAction.LEFT -> current.moveEditPanelHorizontally(-1, editPanelSelectionMode)
            EditPanelAction.RIGHT -> current.moveEditPanelHorizontally(1, editPanelSelectionMode)
            EditPanelAction.UP -> current.sendEditPanelKeyStroke(
                KeyEvent.KEYCODE_DPAD_UP,
                if (editPanelSelectionMode) KeyEvent.META_SHIFT_ON else 0,
            )
            EditPanelAction.DOWN -> current.sendEditPanelKeyStroke(
                KeyEvent.KEYCODE_DPAD_DOWN,
                if (editPanelSelectionMode) KeyEvent.META_SHIFT_ON else 0,
            )
            EditPanelAction.SELECT_ALL -> current.performEditPanelContextAction(EditPanelContextAction.SELECT_ALL)
            EditPanelAction.COPY -> current.performEditPanelContextAction(EditPanelContextAction.COPY)
            EditPanelAction.CUT -> current.performEditPanelContextAction(EditPanelContextAction.CUT)
            EditPanelAction.PASTE -> current.performEditPanelContextAction(EditPanelContextAction.PASTE)
            EditPanelAction.HOME -> current.sendEditPanelKeyStroke(KeyEvent.KEYCODE_MOVE_HOME)
            EditPanelAction.END -> current.sendEditPanelKeyStroke(KeyEvent.KEYCODE_MOVE_END)
            EditPanelAction.DELETE -> current.deleteFromEditPanel()
            EditPanelAction.TOGGLE_SELECTION, EditPanelAction.CLOSE -> return
        }
        scheduleConversionTimeout(current)
        updateAutoCaps()
        refreshCandidates()
        refreshEditPanelState()
    }

    /** ✕で閉じた提案は同じclipでは再表示せず、通常の候補バーへ戻す。 */
    private fun dismissClipboardSuggestion() {
        clipboardSuggestion?.let { recentClipboard?.dismiss(it) }
        clearClipboardSuggestion()
        refreshCandidates()
    }

    /** 表示中の提案と期限タイマーを捨てる。 */
    private fun clearClipboardSuggestion() {
        clipboardExpiry?.let(mainHandler::removeCallbacks)
        clipboardExpiry = null
        clipboardSuggestion = null
        clipboardSuggestionSessionEpoch = null
    }

    /** 自動提案が現在の編集セッションに対して有効かを返す。 */
    private fun hasCurrentClipboardSuggestion(current: EditorSession?): Boolean =
        current != null && clipboardSuggestion != null && clipboardSuggestionSessionEpoch == current.sessionEpoch

    /**
     * clipをIME本文としてそのまま貼り付ける。
     * 自動提案の表示中はラベルと同じ世代の本文だけを貼り、提案がない場合は期限にかかわらず現在の本文を貼る。
     */
    private fun pasteFromClipboard() {
        val current = session ?: return
        if (!inputViewVisible || !current.isActive) return
        val observer = recentClipboard ?: return
        val suggestion = clipboardSuggestion?.takeIf { hasCurrentClipboardSuggestion(current) }
        captureClipboardHistory()
        val text = if (suggestion != null) observer.readForPaste(suggestion) else observer.readCurrentForPaste()
        if (text == null) {
            refreshClipboardSuggestion()
            return
        }
        if (!inputViewVisible || session !== current || !current.isActive) return
        current.pasteText(text)
        suggestion?.let { observer.dismiss(it) }
        clearClipboardSuggestion()
        scheduleConversionTimeout(current)
        refreshCandidates()
    }

    /** 候補一覧を開く。候補を選ぶと一覧を閉じる。 */
    private fun showCandidateGrid(current: EditorSession?, entries: List<CandidateEntry>) {
        val grid = candidateGrid ?: return
        grid.show(
            values = entries.map { it.label },
            selected = entries.indexOfFirst { it.selected },
            dividerIndex = entries.indexOfFirst { it.sectionBreakBefore }.takeIf { it >= 0 },
            predictionIndexes = entries.indices.filter { entries[it].isPrediction }.toSet(),
            onLongClick = { index -> openCandidateActionMenu(current, entries[index]) },
        ) { index ->
            grid.hide()
            pickCandidate(current, entries[index], index)
        }
    }

    /** OSが描画するInline Autofill候補一つ。[pinned]は候補バーの右端に固定する候補。 */
    private class InlineSuggestionItem(
        val view: InlineContentView,
        val pinned: Boolean,
        val width: Int,
        val height: Int,
    )

    /** 候補バーの一項目。押したときの操作を持つ。 */
    private class CandidateEntry(
        val label: String,
        val selected: Boolean,
        val surface: String = label,
        val learnedReading: String? = null,
        val sectionBreakBefore: Boolean = false,
        // 選ぶとすぐ確定する予測の候補か。選ぶだけの通常候補と見分けられるよう、色を変えて表示する。
        val isPrediction: Boolean = false,
        val onPick: () -> Unit,
    )

    /** 予測候補を、選ぶと未確定全体（待機中はカーソル位置）をその語で確定する候補バーの項目にする。 */
    private fun predictionEntry(
        current: EditorSession,
        result: PredictionResult,
        candidate: PredictionCandidate,
    ): CandidateEntry = CandidateEntry(
        label = candidate.value,
        selected = false,
        surface = candidate.value,
        isPrediction = true,
    ) {
        current.selectPrediction(result.request, candidate)
    }

    /** 待機中のクリップボード提案を予測候補より左へ置き、本文貼付と提案解除を別々に押せるようにする。 */
    private fun addClipboardSuggestion(current: EditorSession?, leading: LinearLayout) {
        val views = barViews ?: return
        val suggestion = clipboardSuggestion?.takeIf { hasCurrentClipboardSuggestion(current) } ?: return
        leading.addView(views.chip(getString(R.string.clipboard_suggestion_label, suggestion.label),
            getString(R.string.clipboard_suggestion_description), true) {
            evaluation.record(OperationKind.OTHER)
            pasteFromClipboard()
        }.apply {
            // 長い本文でもInline候補の幅を残すため、提案は画面幅の半分までに抑えて末尾を省略する。
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = resources.displayMetrics.widthPixels / 2
        })
        leading.addView(views.symbolButton("✕", getString(R.string.clipboard_suggestion_dismiss)) {
            dismissClipboardSuggestion()
        })
    }

    /** [index]番目の候補を選ぶ。表示したときと別の編集セッションになっていれば何もしない。 */
    private fun pickCandidate(shown: EditorSession?, entry: CandidateEntry, index: Int) {
        evaluation.record(OperationClassifier.candidatePick(index))
        if (shown == null || session !== shown) return
        shown.forgetDeletionUndo()
        entry.onPick()
        refreshCandidates()
    }

    /** 長押しされた候補の学習行だけを消すか、表記を非表示一覧へ保存する小さな重ねメニューを開く。 */
    private fun openCandidateActionMenu(shown: EditorSession?, entry: CandidateEntry): Boolean {
        val current = shown ?: return false
        if (evaluation.isRecording || current.policy.suppressLearning || session !== current || !inputViewVisible) return false
        val panel = candidateActionPanel ?: return false
        val learningStore = LearningStores.peek()
        val reading = entry.learnedReading
        val canDeleteLearning = reading != null && learningStore?.contains(reading, entry.surface) == true
        candidateGrid?.hide()
        toolbarPanel?.hide()
        panel.show(
            canDeleteLearning = canDeleteLearning,
            onDeleteLearning = {
                if (session === current && reading != null && !evaluation.isRecording && !current.policy.suppressLearning) {
                    removeLearnedCandidate(current, reading, entry.surface)
                } else {
                    refreshCandidates()
                }
            },
            onHideCandidate = {
                if (session === current && !evaluation.isRecording && !current.policy.suppressLearning) {
                    hideCandidateSurface(current, entry.surface)
                } else {
                    refreshCandidates()
                }
            },
        )
        return true
    }

    /** 学習行の削除をworker threadで行い、現セッションに残る同じ由来の候補も取り除く。 */
    private fun removeLearnedCandidate(current: EditorSession, reading: String, surface: String) {
        Thread({
            if (!evaluation.isRecording && !current.policy.suppressLearning) {
                val removed = LearningStores.get(applicationContext).remove(reading, surface)
                mainHandler.post {
                    if (session === current) {
                        if (removed) current.removeLearnedCandidate(reading, surface)
                        refreshCandidates()
                    }
                }
            }
        }, "uzumi-learning-remove").start()
    }

    /** 非表示表記をworker threadで保存し、同じ欄の候補と表示値へすぐ反映する。 */
    private fun hideCandidateSurface(current: EditorSession, surface: String) {
        Thread({
            if (!evaluation.isRecording && !current.policy.suppressLearning) {
                val store = HiddenCandidateStores.get(applicationContext)
                store.hide(surface)
                mainHandler.post {
                    if (session === current) {
                        current.setHiddenCandidateSurfaces(store.all())
                        refreshCandidates()
                    }
                }
            }
        }, "uzumi-candidate-hide").start()
    }

    /** 明示変換の候補（変換結果、または読みのかな・カナ）を並べる。 */
    private fun explicitEntries(current: EditorSession?): List<CandidateEntry> {
        if (current == null) return emptyList()
        val snapshot = current.compositionSnapshot()
        return current.candidateOptions().map { option ->
            val choice = option.conversionChoice
            val label = if (choice != null) option.value else getString(R.string.candidate_button_label, option.value, option.label)
            CandidateEntry(
                label = label,
                selected = choice != null && option.selected,
                surface = option.value,
                learnedReading = option.learnedReading,
            ) {
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
        if (state.canUndo && !current.canUndoDeletion) {
            leading.addView(views.chip(getString(R.string.line_delete_undo), getString(R.string.live_undo), true) {
                evaluation.record(OperationKind.CORRECTION)
                if (session === current) current.undoLive()
                refreshCandidates()
            })
        }
        if (!state.focusAtInput) {
            state.focusedReading?.let { reading ->
                leading.addView(views.chip(reading, getString(R.string.live_focused_reading, reading), false, null))
            }
            trailing.addView(views.symbolButton(getString(R.string.live_return_label), getString(R.string.live_return_to_input)) {
                evaluation.record(OperationKind.CORRECTION)
                if (session === current) current.returnLiveFocusToInput()
                refreshCandidates()
            })
        }
        return state.choices.map { choice ->
            CandidateEntry(
                label = choice.value,
                selected = choice.value == state.currentValue,
                surface = choice.value,
                learnedReading = choice.learnedReading,
            ) { current.selectLiveCandidate(choice) }
        }
    }

    /**
     * 入力していない間の候補バーに案内とキーボードを閉じる操作を置く。Inline Autofill候補がある間は、案内の代わりに候補を並べる。
     */
    private fun showIdleBar(current: EditorSession?, trailing: LinearLayout) {
        val views = barViews ?: return
        val row = candidateRow ?: return
        val showInline = current != null && InlineAutofillPolicy.shouldShow(
            hasComposition = current.hasComposition,
            hasRegularCandidates = false,
            inlineCount = inlineSuggestions.size,
        )
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Inline候補は常設の枠（updateInlineHost）に出すため、ここでは候補がない場合の案内だけを置く。
        if (!showInline && !hasCurrentClipboardSuggestion(current)) {
            line.addView(views.hint(hintText(current?.policy, current?.isLiveMode == true)))
        }
        row.addView(line, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (CandidateBarViews.BAR_HEIGHT_DP * resources.displayMetrics.density).toInt(),
        ))
        trailing.addView(views.symbolButton("⌄", getString(R.string.close_keyboard)) {
            evaluation.record(OperationKind.OTHER)
            requestHideSelf(0)
        })
    }

    /**
     * 待機中だけInline候補の常設枠を表示する。枠の中身は候補の一覧が変わったときだけ作り直し、
     * 候補バーの再構築では部品を付け外ししない。pinnedの候補は横スクロールの外の右端に固定する。
     */
    private fun updateInlineHost(show: Boolean) {
        val host = inlineHost ?: return
        val row = candidateRow ?: return
        if (show && inlineHostItems !== inlineSuggestions) {
            val density = resources.displayMetrics.density
            val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
            val match = LinearLayout.LayoutParams.MATCH_PARENT
            val margin = (INLINE_MARGIN_DP * density).toInt()
            val (pinned, regular) = inlineSuggestions.partition { it.pinned }
            detachInlineViews()
            host.removeAllViews()
            // 候補の中身は別プロセスのSurfaceで描かれ、部品自身は大きさを測れないため、展開時の寸法をそのまま使う。
            val inlineParams = { item: InlineSuggestionItem ->
                LinearLayout.LayoutParams(item.width, item.height).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setMargins(margin, 0, margin, 0)
                }
            }
            val scrolling = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                regular.forEach { addView(it.view, inlineParams(it)) }
            }
            host.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(scrolling, FrameLayout.LayoutParams(wrap, match))
            }, LinearLayout.LayoutParams(0, match, 1f))
            pinned.forEach { host.addView(it.view, inlineParams(it)) }
            inlineHostItems = inlineSuggestions
        }
        // 表示中は候補行を内容の幅にして残りを枠へ回し、非表示中は候補行が残り幅を使う。
        setBarWeight(row, if (show) 0f else 1f)
        setBarWeight(host, if (show) 1f else 0f)
        val visibility = if (show) View.VISIBLE else View.GONE
        if (host.visibility != visibility) host.visibility = visibility
    }

    /** 候補バー内の子の横幅の配分を、変わる場合だけ更新する。 */
    private fun setBarWeight(view: View, weight: Float) {
        val params = view.layoutParams as? LinearLayout.LayoutParams ?: return
        val width = if (weight > 0f) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
        if (params.weight == weight && params.width == width) return
        params.weight = weight
        params.width = width
        view.layoutParams = params
    }

    /** 設定画面を開く。IMEから開くため新しいtaskで起動する。 */
    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

    /** 評価用の状態の確認口へ返す、IMEの状態の要約（数値と真偽だけ）。UIスレッドから呼ばれる。 */
    private fun evaluationStatus(): ImeEvaluationStatus {
        val current = session?.takeIf { it.isActive }
        return ImeEvaluationStatus(
            inputActive = current != null,
            editorInOwnApp = current != null && editorInOwnApp,
            editorFieldId = if (current != null) editorFieldId else View.NO_ID,
            live = current?.isLiveMode == true,
            composing = current?.hasComposition == true,
            workerTasks = conversionTasksInFlight.get(),
            progress = current?.conversionProgress() ?: ConversionProgress(),
            neural = neuralConnection?.let { connection ->
                NeuralRuntimeStatus(
                    selected = true,
                    modelGeneration = connection.spec.generation,
                    ready = connection.client.isReady,
                    loadReason = connection.loadReason,
                    coldStartMillis = connection.coldStartMillis,
                    lastNeuralPssKb = connection.lastNeuralPssKb,
                )
            } ?: NeuralRuntimeStatus(),
        )
    }

    /**
     * 旧接続への書込みを禁止してセッション参照を破棄する。再開始では[restartedConnection]へ未確定の表示を確定してから閉じる。
     */
    private fun closeSession(restartedConnection: InputConnection? = null) {
        session?.let { conversionWorker?.endSession(it.sessionEpoch) }
        val current = session
        if (current != null && restartedConnection != null) {
            current.closeForRestart(AndroidInputConnectionPort(restartedConnection))
        } else {
            current?.close()
        }
        session = null
    }

    private companion object {
        /** noBackupFilesDirへ保存する履歴ファイル名。 */
        const val CLIPBOARD_HISTORY_FILE_NAME = "clipboard-history.tsv"

        /** 変換応答を待つ上限。超えたら読みとかな・カナ候補へ戻す。 */
        const val CONVERSION_TIMEOUT_MILLIS = 2_000L

        /** 候補の列の左右の端をぼかす幅（dp）。候補が横へ続くことを示す。 */
        const val CANDIDATE_FADE_DP = 24f

        /** 候補以外のUndo・戻る・一覧・機能ボタン用に残す幅（dp）。 */
        const val CANDIDATE_FIXED_WIDTH_DP = 192

        /** 1候補の最小タップ領域（dp）。 */
        const val MIN_CANDIDATE_SLOT_DP = 48

        /** バー段数計算で確保するQWERTYの最小タップ領域（dp）。 */
        const val MIN_KEY_TOUCH_DP = 48

        /** 最大段数を見積もる英字・かなキーボードの行数。 */
        const val QWERTY_KEYBOARD_ROW_COUNT = 5
        const val KANA_KEYBOARD_ROW_COUNT = 4

        /** キーボードパネル外周（dp）。 */
        const val KEYBOARD_PANEL_PADDING_DP = 4

        /** 設定時は両バー最大6段。表示時は画面高に応じて制限する。 */
        const val MAX_VISIBLE_BAR_ROWS = 6
        const val BAR_ROW_HEIGHT_WITH_DIVIDER_DP = 53

        /** Inline Autofill候補の札の高さ（dp）。52dpの候補バーへ上下の余白を残して収める。 */
        const val INLINE_HEIGHT_DP = 36f

        /** Inline候補の札の幅の範囲（dp）。下限はタップ領域、上限は長い名前でも数件が並ぶ幅。 */
        const val INLINE_MIN_WIDTH_DP = 48f
        const val INLINE_MAX_WIDTH_DP = 240f

        /** Inline候補の札の左右の内側余白と、札どうしの間隔（dp）。 */
        const val INLINE_CHIP_PADDING_DP = 12f
        const val INLINE_MARGIN_DP = 4f

        /** Inline候補の題名と補足の文字の大きさ（sp）。候補バーの札（14dp）と揃える。 */
        const val INLINE_TITLE_TEXT_SP = 14f
        const val INLINE_SUBTITLE_TEXT_SP = 12f

        /** Autofill serviceへ求めるInline候補の最大件数。 */
        const val INLINE_MAX_SUGGESTIONS = 6

        /** 訂正中のsegmentの背景色（半透明の青）。下線だけのsegmentと区別できる濃さにする。 */
        const val FOCUSED_SEGMENT_COLOR = 0x553F7FFF
    }
}
