package dev.uzumi.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * 入力Viewの根。機能タイルの並べ替え中だけ、バーや置き場のアイコンへの押下をドラッグの処理へ回す。
 * 持ち上げたタイルを全体の上に描くため、影を描ける入れ物としても使う。
 */
class KeyboardRootLayout(context: Context) : FrameLayout(context) {
    /** 並べ替え中のタッチの受け手。 */
    interface EditTouchHandler {
        /** 押した位置にドラッグできるアイコンがあれば、以後のタッチを奪うためtrueを返す。 */
        fun capturesDown(event: MotionEvent): Boolean

        /** 奪ったタッチを処理する。 */
        fun onTouch(event: MotionEvent)
    }

    /** 並べ替え中だけ設定する。nullなら通常どおり子へタッチを渡す。 */
    var touchHandler: EditTouchHandler? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        ev.actionMasked == MotionEvent.ACTION_DOWN && touchHandler?.capturesDown(ev) == true

    // 並べ替え中のドラッグだけを扱う根で、根自体はクリックの対象ではないためperformClickは要らない。
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handler = touchHandler ?: return false
        handler.onTouch(event)
        return true
    }
}

/**
 * 並べ替えでタイルを並べる置き場。キーボードの上に重ねる面（[ToolbarEditPanel]）と、設定画面のキーボードの画面が持つ。
 * 描画だけを受け持ち、ドラッグの判定は[ToolbarEditController]が行う。
 */
interface ToolbarTrayArea {
    /** タイルを並べた面。ドラッグ中の位置の判定に使う。内容の上の余白と行の間隔は[ToolbarLayout.trayDropIndex]に合わせる。 */
    val area: View

    /** 並べ替えを始めるときに置き場を見せる。 */
    fun show()

    /** 置き場のタイルを[entries]の順に2列で並べる。nullは見えない空きマス。 */
    fun render(entries: List<ToolbarItem?>)

    /** ドラッグ中に置き場の端へ近づいたとき、置き場を[deltaPx]だけ送る。 */
    fun scrollAreaBy(deltaPx: Int)
}

/**
 * 機能タイルの並べ替え。バーの中央と右端の丸、置き場のタイルを、押して動かして並べ替える。
 * 押した機能を持ち上げ、バーの上では丸いアイコンに縮め、離したところへ入れる。結果は[onCommit]で保存し、
 * 入る位置の判定は[ToolbarLayout]の計算に任せる。
 *
 * @param itemFor 機能から表示の名前を作る
 * @param onDone 左端の✓を押したとき（タイル一覧へ戻る）。nullなら左端は通常のボタンの見た目で置くだけにする（設定画面の見本）
 * @param onCommit 離して確定した並びを保存し、保存後の（整えた）並びを返す
 * @param barRows バーの中央を何段に分けて描くか（機能バーの段数）
 * @param movesOnTap 押しただけで動かさなかったときも[ToolbarLayout.tapTarget]の位置へ移すか（設定画面の見本）
 */
class ToolbarEditController(
    private val context: Context,
    private val views: ToolbarBarViews,
    private val rootLayout: KeyboardRootLayout,
    private val barHost: ViewGroup,
    private val panel: ToolbarTrayArea,
    private val itemFor: (ToolbarAction) -> ToolbarItem,
    private val onDone: (() -> Unit)?,
    private val onCommit: (ToolbarLayout) -> ToolbarLayout,
    private val barRows: Int = 1,
    private val movesOnTap: Boolean = false,
) : KeyboardRootLayout.EditTouchHandler {
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    // 離したときに確定する並び。ドラッグ中もここは変えず、見せる並びだけを[display]で計算する。
    private var layout = ToolbarLayout(emptyList(), null, emptyList())
    private var center: ToolbarBarViews.EditCenter? = null
    private var right: ToolbarBarViews.EditRight? = null
    private var drag: Drag? = null
    private var ghost: View? = null
    private var ghostAsIcon = false

    // 今のバーの中央に描いている段の数。指のある段を決めるために使う。
    private var shownBarLines = 1

    // 満杯のバーへ押して入れようとしたとき、枠を少しの間だけ赤くしている間か。
    private var flashingFull = false
    private val endFullFlash = Runnable {
        flashingFull = false
        render(animate = false)
    }

    /** 押してから離すまでの一回のドラッグ。[active]は動かし始めて持ち上げた後か。 */
    private class Drag(
        val action: ToolbarAction,
        val pointerId: Int,
        val downX: Float,
        val downY: Float,
        var active: Boolean = false,
        var over: ToolbarLayout.DropTarget? = null,
        var x: Float = downX,
        var y: Float = downY,
    )

    /** 見せるバー・置き場・右端の並び。nullは見えない空きマスで、持ち上げた機能の元の位置と入る位置に置く。 */
    private class Display(
        val bar: List<ToolbarAction?>,
        val tray: List<ToolbarAction?>,
        val right: ToolbarAction?,
        val rightHole: Boolean,
        val rightHighlighted: Boolean,
        val barFull: Boolean,
    )

    /** 並べ替えを始める。[current]の並びをバーと置き場へ描き、タッチの受け取りを始める。 */
    fun start(current: ToolbarLayout) {
        layout = current
        rootLayout.touchHandler = this
        panel.show()
        render(animate = false)
    }

    /** 並べ替えを終え、ドラッグ中の表示とタッチの受け取りをやめる。 */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        flashingFull = false
        rootLayout.touchHandler = null
        drag = null
        removeGhost()
        center = null
        right = null
    }

    /** 押した位置にドラッグできる機能があれば、その機能のドラッグを待ち受ける状態にする。 */
    override fun capturesDown(event: MotionEvent): Boolean {
        val x = event.rawX
        val y = event.rawY
        val action = draggableViews().firstOrNull { it.screenRect().contains(x.toInt(), y.toInt()) }
            ?.tag as? ToolbarAction ?: return false
        drag = Drag(action, event.getPointerId(0), x, y)
        return true
    }

    override fun onTouch(event: MotionEvent) {
        val current = drag ?: return
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            finish(current, cancelled = true)
            return
        }
        val index = event.findPointerIndex(current.pointerId)
        // ドラッグしていた指の行方が分からなくなったら、持ち上げた表示を残さないよう取り消す
        if (index < 0) {
            finish(current, cancelled = true)
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> move(current, event.getRawX(index), event.getRawY(index))
            MotionEvent.ACTION_UP -> finish(current, cancelled = false)
            // 別の指を置いたまま、ドラッグしていた指だけを先に離した場合も、その位置で確定する
            MotionEvent.ACTION_POINTER_UP -> if (event.actionIndex == index) finish(current, cancelled = false)
        }
    }

    /** 指が一定以上動いたら持ち上げ、以後は指の位置に合わせて入る位置を決め直す。 */
    private fun move(current: Drag, x: Float, y: Float) {
        current.x = x
        current.y = y
        if (!current.active) {
            if (hypot(x - current.downX, y - current.downY) < START_DISTANCE_DP * density) return
            current.active = true
            current.over = hitTest(current)
            ensureGhost(current)
            render(animate = true)
            scheduleAutoScroll()
        } else {
            val over = hitTest(current)
            if (over != current.over) {
                current.over = over
                render(animate = true)
            }
            ensureGhost(current)
        }
        positionGhost(current)
    }

    /** 離したとき、持ち上げていれば入る位置へ確定する。取り消しや位置なしなら元の位置へ戻す。 */
    private fun finish(current: Drag, cancelled: Boolean) {
        drag = null
        handler.removeCallbacksAndMessages(null)
        flashingFull = false
        removeGhost()
        if (!current.active) {
            if (movesOnTap && !cancelled) tap(current.action) else render(animate = false)
            return
        }
        layout = onCommit(layout.drop(current.action, current.over.takeUnless { cancelled }))
        render(animate = true)
    }

    /**
     * 押しただけの機能を[ToolbarLayout.tapTarget]の位置へ移して保存する。満杯のバーへは入れず、枠を少しの間だけ赤くする。
     * TalkBackでの選択（タイルのクリック）からも呼ぶ。
     */
    fun tap(action: ToolbarAction) {
        val target = layout.tapTarget(action)
        if (target == ToolbarLayout.DropTarget.BarFull) {
            flashingFull = true
            handler.removeCallbacks(endFullFlash)
            handler.postDelayed(endFullFlash, FULL_FLASH_MILLIS)
            render(animate = false)
            return
        }
        layout = onCommit(layout.drop(action, target))
        render(animate = true)
    }

    /** 指のある場所が、右端の丸、バーの帯、置き場のどれの上かを決める。どれでもなければnull。 */
    private fun hitTest(current: Drag): ToolbarLayout.DropTarget? {
        val x = current.x
        val y = current.y
        val rightRect = right?.host?.screenRect()
        if (rightRect != null && rightRect.expandedBy(RIGHT_MARGIN_DP).contains(x.toInt(), y.toInt())) {
            return ToolbarLayout.DropTarget.Right
        }
        val frame = center?.frame?.screenRect()
        if (frame != null) {
            val inBand = x >= frame.left - BAR_LEFT_MARGIN_DP * density && x <= frame.right + BAR_RIGHT_MARGIN_DP * density &&
                y >= frame.top - BAR_TOP_MARGIN_DP * density && y <= frame.bottom + BAR_BOTTOM_MARGIN_DP * density
            if (inBand) {
                val lineHeight = frame.height().toFloat() / shownBarLines
                val row = ((y - frame.top) / lineHeight).toInt().coerceIn(0, shownBarLines - 1)
                return layout.barTarget(current.action, x, frame.left.toFloat(), frame.width().toFloat(), row, barRows)
            }
        }
        val area = panel.area.screenRect()
        if (area.expandedBy(TRAY_MARGIN_DP).contains(x.toInt(), y.toInt())) {
            val contentX = (x - area.left) / density
            val contentY = (y - area.top + panel.area.scrollY) / density
            return layout.trayTarget(current.action, contentX, contentY, panel.area.width / density)
        }
        return null
    }

    /** 置き場の端へ指が近い間、置き場を少しずつ送り、そのたびに入る位置を決め直す。 */
    private fun scheduleAutoScroll() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                val current = drag?.takeIf { it.active } ?: return
                if (current.over is ToolbarLayout.DropTarget.Tray) {
                    val area = panel.area.screenRect()
                    when {
                        current.y > area.bottom - EDGE_DP * density -> panel.scrollAreaBy((SCROLL_STEP_DP * density).toInt())
                        current.y < area.top + EDGE_DP * density -> panel.scrollAreaBy(-(SCROLL_STEP_DP * density).toInt())
                    }
                    val over = hitTest(current)
                    if (over != current.over) {
                        current.over = over
                        render(animate = true)
                    }
                }
                handler.postDelayed(this, SCROLL_INTERVAL_MILLIS)
            }
        }, SCROLL_INTERVAL_MILLIS)
    }

    /** 持ち上げた機能の見た目を、指の位置から決める。バーと右端の上では丸いアイコン、それ以外ではタイルにする。 */
    private fun ensureGhost(current: Drag) {
        val asIcon = current.over is ToolbarLayout.DropTarget.Bar || current.over is ToolbarLayout.DropTarget.BarFull ||
            current.over is ToolbarLayout.DropTarget.Right
        if (ghost != null && ghostAsIcon == asIcon) return
        removeGhost()
        val item = itemFor(current.action)
        ghost = (if (asIcon) views.ghostIcon(item) else views.ghostTile(item)).also { rootLayout.addView(it) }
        ghostAsIcon = asIcon
    }

    /** 持ち上げた見た目の中心を指の位置に合わせる。 */
    private fun positionGhost(current: Drag) {
        val view = ghost ?: return
        val origin = IntArray(2).also { rootLayout.getLocationOnScreen(it) }
        view.x = current.x - origin[0] - view.layoutParams.width / 2f
        view.y = current.y - origin[1] - view.layoutParams.height / 2f
    }

    private fun removeGhost() {
        ghost?.let { rootLayout.removeView(it) }
        ghost = null
    }

    /** ドラッグ中に離したときの並びを先に見せるための、見せる並びを計算する。 */
    private fun display(): Display {
        val current = drag?.takeIf { it.active }
            ?: return Display(layout.bar, layout.tray, layout.right, rightHole = false, rightHighlighted = false, barFull = flashingFull)
        val action = current.action
        val over = current.over
        val fromBar = layout.bar.indexOf(action)
        val fromTray = layout.tray.indexOf(action)
        val fromRight = layout.right == action
        fun List<ToolbarAction>.hidden(): MutableList<ToolbarAction?> = map { if (it == action) null else it }.toMutableList()
        return when (over) {
            is ToolbarLayout.DropTarget.Bar, is ToolbarLayout.DropTarget.Tray -> {
                val moved = layout.drop(action, over)
                val bar = moved.bar.hidden()
                val tray = moved.tray.hidden()
                // 別の場所へ移るときは、元の位置を空きマスとして残す。
                if (over is ToolbarLayout.DropTarget.Tray && fromBar >= 0) bar.add(fromBar.coerceAtMost(bar.size), null)
                if (over is ToolbarLayout.DropTarget.Bar && fromTray >= 0) tray.add(fromTray.coerceAtMost(tray.size), null)
                Display(bar, tray, moved.right, rightHole = fromRight, rightHighlighted = false, barFull = false)
            }
            else -> Display(
                bar = layout.bar.hidden(),
                tray = layout.tray.hidden(),
                right = layout.right.takeUnless { fromRight },
                rightHole = fromRight,
                rightHighlighted = over is ToolbarLayout.DropTarget.Right,
                barFull = over is ToolbarLayout.DropTarget.BarFull,
            )
        }
    }

    /** バーと置き場を描き直す。[animate]なら、機能ごとの位置の変化をアニメーションで見せる。 */
    private fun render(animate: Boolean) {
        val before = if (animate) capturePositions() else emptyMap()
        val shown = display()
        val slots = shown.bar.map { views.editSlot(it, it?.let { action -> itemFor(action).label }.orEmpty()) }
        shownBarLines = ToolbarPreferences.splitAcrossRows(slots, barRows).size.coerceAtLeast(1)
        val newCenter = views.editCenter(slots, shown.barFull, barRows)
        val newRight = views.editRight(
            action = shown.right,
            label = shown.right?.let { itemFor(it).label }.orEmpty(),
            highlighted = shown.rightHighlighted,
            hole = shown.rightHole,
        )
        center = newCenter
        right = newRight
        // 押しただけで移す見本では、TalkBackで選んだ（クリックした）ときも同じく移す。指の操作は根が先に受け取る。
        if (movesOnTap) {
            (collectTagged(newCenter.frame) + collectTagged(newRight.ring)).forEach { view ->
                val action = view.tag as ToolbarAction
                view.setOnClickListener { tap(action) }
            }
        }
        val left = onDone?.let { views.leftButton(ToolbarMode.EDIT, it) }
            ?: views.leftButton(ToolbarMode.NONE) {}.apply {
                // 見本の左端は形を見せるだけで押しても何も起きないため、TalkBackには読ませない。
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        barHost.removeAllViews()
        barHost.addView(
            views.assemble(left, newCenter.host, newRight.host),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        panel.render(shown.tray.map { it?.let(itemFor) })
        if (before.isNotEmpty()) animateFrom(before)
    }

    /** 描き直す前の、機能ごとの画面上の位置を控える。 */
    private fun capturePositions(): Map<ToolbarAction, Rect> =
        draggableViews().associate { (it.tag as ToolbarAction) to it.screenRect() }

    /** 描き直した後の各機能を、控えた位置から新しい位置へ滑らせる。 */
    private fun animateFrom(before: Map<ToolbarAction, Rect>) {
        val observer = rootLayout.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                draggableViews().forEach { view ->
                    val old = before[view.tag as ToolbarAction] ?: return@forEach
                    val now = view.screenRect()
                    // 画面上のずれを、縮小して描いている親の中のずれへ直す。
                    val (scaleX, scaleY) = view.screenScale()
                    val dx = (old.left - now.left) / scaleX
                    val dy = (old.top - now.top) / scaleY
                    if (dx == 0f && dy == 0f) return@forEach
                    view.translationX = dx
                    view.translationY = dy
                    view.animate().translationX(0f).translationY(0f).setDuration(MOVE_MILLIS).start()
                }
                return true
            }
        })
    }

    /** バーの中央、右端、置き場にある、機能の印（tag）を持つView。持ち上げ中の機能の空きマスは含まない。 */
    private fun draggableViews(): List<View> = buildList {
        center?.frame?.let(::collectTagged)?.let(::addAll)
        right?.ring?.let(::collectTagged)?.let(::addAll)
        collectTagged(panel.area).let(::addAll)
    }

    /** [root]以下で、機能の印を持つViewを集める。 */
    private fun collectTagged(root: View): List<View> {
        if (root.tag is ToolbarAction) return listOf(root)
        val group = root as? ViewGroup ?: return emptyList()
        return (0 until group.childCount).flatMap { collectTagged(group.getChildAt(it)) }
    }

    /** Viewの画面上の位置と大きさ。設定画面の見本のように親ごと縮小して描いている場合は、縮小後の大きさにする。 */
    private fun View.screenRect(): Rect {
        val location = IntArray(2).also { getLocationOnScreen(it) }
        val (scaleX, scaleY) = screenScale()
        return Rect(location[0], location[1], location[0] + (width * scaleX).toInt(), location[1] + (height * scaleY).toInt())
    }

    /** 親をたどって掛け合わせた、画面上での縮小率（横、縦）。キーボードの上では常に1。 */
    private fun View.screenScale(): Pair<Float, Float> {
        var scaleX = 1f
        var scaleY = 1f
        var view: View? = this
        while (view != null) {
            scaleX *= view.scaleX
            scaleY *= view.scaleY
            view = view.parent as? View
        }
        return scaleX to scaleY
    }

    /** 矩形を上下左右へ[marginDp]だけ広げた矩形。 */
    private fun Rect.expandedBy(marginDp: Float): Rect {
        val margin = (marginDp * density).toInt()
        return Rect(left - margin, top - margin, right + margin, bottom + margin)
    }

    private companion object {
        /** 押してからこの距離（dp）動いたらドラッグを始める。 */
        const val START_DISTANCE_DP = 6f

        /** 右端の丸、バーの帯、置き場の判定を広げる量（dp）。バーは左・上・右・下で別々に広げる。 */
        const val RIGHT_MARGIN_DP = 4f
        const val BAR_LEFT_MARGIN_DP = 8f
        const val BAR_RIGHT_MARGIN_DP = 4f
        const val BAR_TOP_MARGIN_DP = 14f
        const val BAR_BOTTOM_MARGIN_DP = 16f
        const val TRAY_MARGIN_DP = 6f

        /** 置き場の端からこの距離（dp）以内に指があると置き場を送る。1回に送る量と間隔。 */
        const val EDGE_DP = 24f
        const val SCROLL_STEP_DP = 6f
        const val SCROLL_INTERVAL_MILLIS = 16L

        /** 位置の変化を見せる時間（ミリ秒）。 */
        const val MOVE_MILLIS = 180L

        /** 満杯のバーへ押して入れようとしたとき、枠を赤くしておく時間（ミリ秒）。試作と同じ0.9秒。 */
        const val FULL_FLASH_MILLIS = 900L
    }
}
