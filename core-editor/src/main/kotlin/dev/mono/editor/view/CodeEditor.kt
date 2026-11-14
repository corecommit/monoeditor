package dev.mono.editor.view

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.mono.editor.control.EditorController
import dev.mono.editor.ime.EditorIMEView
import dev.mono.editor.render.Highlighter
import dev.mono.editor.render.MonoTheme
import dev.mono.editor.state.EditorState
import dev.mono.editor.state.Pos
import dev.mono.indexer.symbols.CompletionItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The custom-rendered code editor.
 *
 * Everything except the caret is drawn on one Canvas — text, gutter,
 * selection with draggable handles, find matches, bracket highlights,
 * diagnostic underlines and scrollbars. The caret lives on its own layer
 * above it so its blink (a layer alpha change) never invalidates the text
 * canvas. Gestures: inertial scroll, pinch-to-zoom font size, tap to place
 * the caret, long-press to select a word. No TextField anywhere.
 */
@Composable
fun MonoEditor(
    state: EditorState,
    controller: EditorController,
    theme: MonoTheme,
    modifier: Modifier = Modifier,
    completionProvider: (suspend (prefix: String, memberAccess: Boolean) -> List<CompletionItem>)? = null
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // ---- IME bridge ----------------------------------------------------------
    var imeView by remember { mutableStateOf<EditorIMEView?>(null) }
    LaunchedEffect(Unit) {
        state.requestIme = { show ->
            imeView?.let { if (show) it.showKeyboard() else it.hideKeyboard() }
        }
    }
    val imeBottom = WindowInsets.ime.getBottom(density)
    state.imeVisible = imeBottom > 0

    // ---- measuring -------------------------------------------------------------
    val textMeasurer = rememberTextMeasurer()
    val cache = remember(theme) { LineVisualCache() }

    LaunchedEffect(state.fontSizeSp, state.textRevision) {
        with(density) {
            val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = state.fontSizeSp.sp)
            val probe = textMeasurer.measure("8", style)
            val charWidth = probe.size.width.toFloat().coerceAtLeast(1f)
            val lineH = (state.fontSizeSp * 1.55f).dp.toPx()
            val digits = state.text.lineCount.toString().length.coerceAtLeast(2)
            val gutter = digits * charWidth + 16.dp.toPx()
            state.lineHeightPx = lineH
            state.gutterWidthPx = gutter
        }
    }
    LaunchedEffect(state.textRevision) {
        state.contentMaxWidthPx = 0f
    }

    // ---- caret animation --------------------------------------------------------
    val caretX = remember { Animatable(0f) }
    val caretY = remember { Animatable(0f) }
    val blinkAlpha = remember { mutableFloatStateOf(1f) }
    val lastCaretMoveNanos = remember { java.util.concurrent.atomic.AtomicLong(System.nanoTime()) }

    LaunchedEffect(state.caretLine, state.caretCol, state.textRevision, state.fontSizeSp, state.gutterWidthPx, theme.generation) {
        lastCaretMoveNanos.set(System.nanoTime())
        val layout = cache.layout(state, theme, textMeasurer, state.caretLine)
        val x = state.gutterWidthPx + padH(density) + layout.xAt(state.caretCol)
        val y = state.caretLine * state.lineHeightPx
        launch { caretX.animateTo(x, tween(130, easing = FastOutSlowInEasing)) }
        caretY.animateTo(y, tween(130, easing = FastOutSlowInEasing))
    }

    // Soft blink: solid shortly after a move, then a gentle sine fade. The
    // loop only runs while the editor is focused — unfocused, the frame
    // callback stops entirely and the layer below holds a static dim caret.
    LaunchedEffect(state.hasFocus) {
        if (!state.hasFocus) return@LaunchedEffect
        while (true) {
            withFrameNanos { now ->
                val since = now - lastCaretMoveNanos.get()
                blinkAlpha.floatValue = if (since < 600_000_000L) {
                    1f
                } else {
                    val t = (now % 1_600_000_000L) / 1_600_000_000.0
                    (0.35f + 0.65f * (0.5 + 0.5 * sin(t * 2.0 * Math.PI))).toFloat()
                }
            }
        }
    }

    // ---- scroll-to-caret requests -------------------------------------------------
    LaunchedEffect(controller.scrollRequest) {
        if (controller.scrollRequest == 0) return@LaunchedEffect
        with(density) {
            val lineTop = state.caretLine * state.lineHeightPx
            val lineBottom = lineTop + state.lineHeightPx
            val topGuard = 3 * state.lineHeightPx
            val bottomGuard = 4 * state.lineHeightPx
            if (lineTop - state.scrollY < topGuard) {
                state.scrollY = (lineTop - topGuard).coerceAtLeast(0f)
            } else if (lineBottom - state.scrollY > state.viewportHeightPx - bottomGuard) {
                state.scrollY = (lineBottom + bottomGuard - state.viewportHeightPx)
                    .coerceIn(0f, state.maxScrollY())
            }
            val layout = cache.layout(state, theme, textMeasurer, state.caretLine)
            val cx = state.gutterWidthPx + padH(density) + layout.xAt(state.caretCol)
            val leftGuard = state.gutterWidthPx + 24.dp.toPx()
            val rightGuard = 32.dp.toPx()
            if (cx - state.scrollX < leftGuard) {
                state.scrollX = (cx - leftGuard).coerceAtLeast(0f)
            } else if (cx - state.scrollX > state.viewportWidthPx - rightGuard) {
                state.scrollX = (cx + rightGuard - state.viewportWidthPx).coerceIn(0f, state.maxScrollX())
            }
        }
    }

    // ---- completion debounce ---------------------------------------------------
    var completionPrefix by remember { mutableStateOf("") }
    var completionMember by remember { mutableStateOf(false) }
    var completionQueryId by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        controller.completionQueryListener = { prefix, member ->
            completionPrefix = prefix
            completionMember = member
            completionQueryId++
        }
    }
    LaunchedEffect(completionQueryId) {
        val provider = completionProvider ?: return@LaunchedEffect
        // Empty prefix is only interesting right after ".", "->" or "::".
        if (completionPrefix.isEmpty() && !completionMember) return@LaunchedEffect
        delay(110)
        val p = completionPrefix
        val member = completionMember
        if (p.isEmpty() && !member) return@LaunchedEffect
        val items = provider(p, member)
        if (p == completionPrefix && member == completionMember) {
            state.setCompletion(items)
        }
    }

    // ---- gesture helpers ----------------------------------------------------------
    val flingJobs = remember { ArrayList<Job>() }
    val velocityTracker = remember { VelocityTracker() }
    var maxSeenWidth by remember { mutableFloatStateOf(0f) }

    fun stopFling() {
        flingJobs.forEach { it.cancel() }
        flingJobs.clear()
    }

    fun applyPan(delta: Offset) {
        val maxX = state.maxScrollX()
        val maxY = state.maxScrollY()
        var nx = state.scrollX - delta.x
        var ny = state.scrollY - delta.y
        if (nx < 0) nx *= 0.35f
        if (nx > maxX) nx = maxX + (nx - maxX) * 0.35f
        if (ny < 0) ny *= 0.35f
        if (ny > maxY) ny = maxY + (ny - maxY) * 0.35f
        state.scrollX = nx
        state.scrollY = ny
    }

    fun applyZoom(zoom: Float, centroid: Offset) {
        if (zoom == 1f) return
        val newFont = (state.fontSizeSp * zoom).coerceIn(7f, 40f)
        val z = newFont / state.fontSizeSp
        state.fontSizeSp = newFont
        // Keep the content point under the pinch centroid stationary.
        val contentX = state.scrollX + (centroid.x - state.gutterWidthPx)
        state.scrollX = contentX * z - (centroid.x - state.gutterWidthPx)
        val contentY = state.scrollY + centroid.y
        state.scrollY = contentY * z - centroid.y
        state.scrollY = state.scrollY.coerceIn(0f, state.maxScrollY())
        state.scrollX = state.scrollX.coerceIn(0f, state.maxScrollX())
        maxSeenWidth *= z
    }

    fun startFling(vx: Float, vy: Float) {
        val decay = splineBasedDecay<Float>(density)
        if (abs(vy) > 300f) {
            flingJobs += scope.launch {
                val a = Animatable(state.scrollY)
                val self = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
                a.animateDecay(-vy, decay) {
                    state.scrollY = value.coerceIn(0f, state.maxScrollY())
                    if (value <= 0f || value >= state.maxScrollY()) self?.cancel()
                }
            }
        }
        if (abs(vx) > 300f) {
            flingJobs += scope.launch {
                val a = Animatable(state.scrollX)
                val self = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
                a.animateDecay(-vx, decay) {
                    state.scrollX = value.coerceIn(0f, state.maxScrollX())
                    if (value <= 0f || value >= state.maxScrollX()) self?.cancel()
                }
            }
        }
    }

    fun charPosAt(pos: Offset): Pos {
        val line = floor((pos.y + state.scrollY) / state.lineHeightPx).toInt()
        val l = line.coerceIn(0, state.text.lineCount - 1)
        val entry = cache.layout(state, theme, textMeasurer, l)
        val textLeft = state.gutterWidthPx + padH(density)
        val charW = entry.charWidth().coerceAtLeast(1f)
        val col = ((pos.x + state.scrollX - textLeft) / charW).toInt()
        return Pos(l, col.coerceIn(0, state.text.lineLength(l)))
    }

    fun handleScreenPositions(): Pair<Offset, Offset>? {
        val b = state.selectionBounds() ?: return null
        val startEntry = cache.layout(state, theme, textMeasurer, b.first.line)
        val endEntry = cache.layout(state, theme, textMeasurer, b.second.line)
        val textLeft = state.gutterWidthPx + padH(density)
        val handleOffset = with(density) { 3.dp.toPx() }
        val start = Offset(
            textLeft + startEntry.xAt(b.first.col) - state.scrollX,
            (b.first.line + 1) * state.lineHeightPx - state.scrollY + handleOffset
        )
        val end = Offset(
            textLeft + endEntry.xAt(b.second.col) - state.scrollX,
            b.second.line * state.lineHeightPx - state.scrollY - handleOffset
        )
        return start to end
    }

    fun diagnosticAt(pos: Offset): dev.mono.editor.state.EditorDiagnostic? {
        val p = charPosAt(pos)
        return state.diagnostics.firstOrNull { it.line == p.line && p.col >= it.colStart && p.col <= it.colEnd }
    }

    fun edgeAutoScroll(pos: Offset) {
        val margin = with(density) { 56.dp.toPx() }
        var dy = 0f
        if (pos.y < margin) dy = -((margin - pos.y) * 0.32f)
        else if (pos.y > state.viewportHeightPx - margin) dy = ((pos.y - (state.viewportHeightPx - margin)) * 0.32f)
        if (dy != 0f) {
            state.scrollY = (state.scrollY + dy).coerceIn(0f, state.maxScrollY())
        }
    }

    // ---- composition --------------------------------------------------------------
    Box(modifier) {
        AndroidView(
            factory = { ctx -> EditorIMEView(ctx, controller).also { imeView = it } },
            modifier = Modifier.size(1.dp)
        )

        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size: IntSize ->
                    with(density) {
                        state.viewportWidthPx = size.width.toFloat()
                        state.viewportHeightPx = size.height.toFloat()
                    }
                }
                .pointerInputGestures(
                    state = state,
                    controller = controller,
                    velocityTracker = velocityTracker,
                    stopFling = ::stopFling,
                    applyPan = ::applyPan,
                    applyZoom = ::applyZoom,
                    startFling = ::startFling,
                    charPosAt = ::charPosAt,
                    handlesAt = ::handleScreenPositions,
                    diagnosticAt = ::diagnosticAt,
                    edgeAutoScroll = ::edgeAutoScroll
                )
        ) {
            drawEditor(
                state, theme, cache, textMeasurer, density,
                caretX.value, caretY.value
            ) { w ->
                if (w > maxSeenWidth + 0.5f) {
                    maxSeenWidth = w
                    state.contentMaxWidthPx = w
                }
            }
        }

        // Caret on its own layer: blinking only touches the layer alpha
        // (cheap, render-thread) instead of re-drawing the whole text canvas
        // every frame. Unfocused, the blink loop stops and the caret stays
        // as a dim static marker.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = if (state.hasFocus) blinkAlpha.floatValue else UNFOCUSED_CARET_ALPHA
                }
        ) {
            drawCaret(state, theme, density, caretX.value, caretY.value)
        }

        if (state.completionVisible && state.completionItems.isNotEmpty()) {
            val entry = cache.layout(state, theme, textMeasurer, state.caretLine)
            with(density) {
                val anchorX = state.gutterWidthPx + padH(density) + entry.xAt(state.caretCol) - state.scrollX
                val belowY = (state.caretLine + 1) * state.lineHeightPx - state.scrollY + 4.dp.toPx()
                val popupHeight = 230.dp.toPx()
                val popupWidth = 320.dp.toPx()
                val y = if (belowY + popupHeight <= state.viewportHeightPx) {
                    belowY
                } else {
                    state.caretLine * state.lineHeightPx - state.scrollY - 4.dp.toPx() - popupHeight
                }
                Popup(
                    offset = IntOffset(
                        anchorX.roundToInt().coerceIn(4, (state.viewportWidthPx - popupWidth).toInt().coerceAtLeast(4)),
                        y.roundToInt().coerceAtLeast(4)
                    ),
                    onDismissRequest = { controller.dismissCompletion() },
                    properties = PopupProperties(focusable = false)
                ) {
                    CompletionList(state, controller, theme)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Gestures
// ---------------------------------------------------------------------------

@Suppress("LongParameterList")
private fun Modifier.pointerInputGestures(
    state: EditorState,
    controller: EditorController,
    velocityTracker: VelocityTracker,
    stopFling: () -> Unit,
    applyPan: (Offset) -> Unit,
    applyZoom: (Float, Offset) -> Unit,
    startFling: (Float, Float) -> Unit,
    charPosAt: (Offset) -> Pos,
    handlesAt: () -> Pair<Offset, Offset>?,
    diagnosticAt: (Offset) -> dev.mono.editor.state.EditorDiagnostic?,
    edgeAutoScroll: (Offset) -> Unit
): Modifier = this.pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    val handleHitRadius = 30.dp.toPx()

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downTime = down.uptimeMillis
        val downPos = down.position
        var moved = false
        var longPressed = false
        var handleDrag = 0 // 0 none, 1 start, 2 end
        var multi = false
        // Uptime of the last seen pointer change; updated from the events
        // themselves so the tap test compares against the same clock the
        // down time came from (never System.currentTimeMillis()).
        var lastChangeTime = downTime

        stopFling()
        velocityTracker.resetTracking()
        velocityTracker.addPosition(down.uptimeMillis, down.position)

        if (state.showHandles) {
            val handles = handlesAt()
            if (handles != null) {
                if ((downPos - handles.first).getDistance() < handleHitRadius) handleDrag = 1
                else if ((downPos - handles.second).getDistance() < handleHitRadius) handleDrag = 2
            }
        }

        while (true) {
            val waiting = !moved && !longPressed && handleDrag == 0 && !multi
            val event: PointerEvent? = withTimeoutOrNull(
                if (waiting) longPressTimeout else 1_000_000_000L
            ) {
                awaitPointerEvent()
            }
            if (event == null) {
                // Timeout during a still press: long-press selects the word.
                if (waiting) {
                    longPressed = true
                    val p = charPosAt(downPos)
                    controller.beginSelectionAt(p.line, p.col, word = true)
                }
                continue
            }

            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                // Release: stamp the up event's clock before breaking.
                for (c in event.changes) {
                    if (c.uptimeMillis > lastChangeTime) lastChangeTime = c.uptimeMillis
                }
                break
            }
            if (pressed.size >= 2) {
                multi = true
                moved = true
                val zoom = event.calculateZoom()
                val centroid = event.calculateCentroid()
                if (zoom != 1f) applyZoom(zoom, centroid)
                val pan = event.calculatePan()
                if (pan != Offset.Zero) applyPan(pan)
                for (c in event.changes) {
                    if (c.uptimeMillis > lastChangeTime) lastChangeTime = c.uptimeMillis
                }
                event.changes.forEach { it.consume() }
                continue
            }

            val change = pressed[0]
            lastChangeTime = change.uptimeMillis
            val pos = change.position
            if (!moved && (pos - downPos).getDistance() > slop) moved = true

            if (handleDrag != 0) {
                val target = charPosAt(pos)
                if (handleDrag == 1) {
                    state.anchorLine = target.line
                    state.anchorCol = target.col
                } else {
                    controller.updateSelectionTo(target.line, target.col)
                }
                edgeAutoScroll(pos)
                moved = true
            } else if (moved) {
                val delta = change.positionChange()
                if (delta != Offset.Zero) {
                    if (longPressed) {
                        val target = charPosAt(pos)
                        controller.updateSelectionTo(target.line, target.col)
                        edgeAutoScroll(pos)
                    } else {
                        applyPan(delta)
                    }
                }
                velocityTracker.addPosition(change.uptimeMillis, pos)
            }
            change.consume()
        }

        // ---- release ----
        if (!moved && !longPressed && handleDrag == 0) {
            val diag = diagnosticAt(downPos)
            if (diag != null) {
                state.diagnosticChip = diag
            } else if (lastChangeTime - downTime < 420) {
                state.diagnosticChip = null
                val target = charPosAt(downPos)
                controller.moveCaret(target.line, target.col)
                state.hasFocus = true
                state.requestIme?.invoke(true)
            }
        } else if (moved && !longPressed && handleDrag == 0 && !multi) {
            val v = velocityTracker.calculateVelocity()
            startFling(v.x, v.y)
        }
    }
}

// ---------------------------------------------------------------------------
// Visual cache: one annotated string + measured layout per line, keyed by
// (textRevision, fontSize, theme). Text edits invalidate only the lines at
// or after the change; font/theme changes clear everything. LRU-capped for
// very large files.
// ---------------------------------------------------------------------------

internal class LineVisualCache {
    private var textGen = -1L
    private var styleGen = -1L
    private val map = object : LinkedHashMap<Int, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Entry>?): Boolean {
            return size > 700
        }
    }

    /** Gutter numbers depend only on font/theme — cache them across frames. */
    private val gutterCache = object : LinkedHashMap<Int, TextLayoutResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, TextLayoutResult>?): Boolean {
            return size > 4096
        }
    }

    class Entry(val annotated: AnnotatedString, val layout: TextLayoutResult) {
        fun xAt(col: Int): Float = try {
            layout.getHorizontalPosition(col.coerceIn(0, annotated.length), true)
        } catch (t: Throwable) {
            0f
        }

        fun charWidth(): Float =
            if (annotated.isNotEmpty()) layout.size.width.toFloat() / annotated.length else 8f
    }

    fun layout(state: EditorState, theme: MonoTheme, measurer: TextMeasurer, line: Int): Entry {
        val style = (state.fontSizeSp.toBits().toLong() * 31L) + theme.generation
        if (style != styleGen) {
            styleGen = style
            textGen = -1L
            map.clear()
            gutterCache.clear()
        }
        if (textGen != state.textRevision) {
            if (textGen == -1L) {
                map.clear()
            } else {
                // Invalidate only the lines the edit could have touched:
                // everything at or after the first changed line (edits can
                // shift the lines below them).
                val from = state.consumeStructureDirtyFrom()
                if (from != Int.MAX_VALUE) {
                    if (from <= 0) {
                        map.clear()
                    } else {
                        val iter = map.entries.iterator()
                        while (iter.hasNext()) {
                            if (iter.next().key >= from) iter.remove()
                        }
                    }
                }
            }
            textGen = state.textRevision
        }
        map[line]?.let { return it }
        val annotated = Highlighter.annotatedFor(state, theme, line)
        val style2 = TextStyle(fontFamily = FontFamily.Monospace, fontSize = state.fontSizeSp.sp)
        // Measured with the default (unbounded) width constraints: a finite
        // magic maxWidth either clips very long lines or — when it exceeds
        // what Compose can pack into a Constraints — corrupts the
        // measurement. softWrap=false + maxLines=1 keeps it one line.
        val measured = measurer.measure(
            text = annotated,
            style = style2,
            softWrap = false,
            maxLines = 1
        )
        val e = Entry(annotated, measured)
        map[line] = e
        return e
    }

    fun gutterNumber(
        number: Int,
        state: EditorState,
        theme: MonoTheme,
        measurer: TextMeasurer
    ): TextLayoutResult {
        gutterCache[number]?.let { return it }
        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = state.fontSizeSp.sp)
        val layout = measurer.measure(
            AnnotatedString(number.toString()),
            style,
            softWrap = false,
            maxLines = 1
        )
        gutterCache[number] = layout
        return layout
    }
}

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------

private fun DrawScope.drawEditor(
    state: EditorState,
    theme: MonoTheme,
    cache: LineVisualCache,
    textMeasurer: TextMeasurer,
    density: Density,
    caretX: Float,
    caretY: Float,
    onWidthsMeasured: (Float) -> Unit
) {
    val w = size.width
    val h = size.height
    val lineH = state.lineHeightPx
    val gutterW = state.gutterWidthPx
    val textLeft = gutterW + padH(density)

    drawRect(theme.background, size = size)

    // Gutter.
    drawRect(theme.gutterBackground, size = Size(gutterW, h))
    drawLine(
        theme.border,
        Offset(gutterW, 0f),
        Offset(gutterW, h),
        strokeWidth = with(density) { 1.dp.toPx() }
    )

    val first = floor(state.scrollY / lineH).toInt().coerceAtLeast(0)
    val last = ceil((state.scrollY + h) / lineH).toInt().coerceIn(first, state.text.lineCount)

    // Current line highlight.
    val caretTop = state.caretLine * lineH - state.scrollY
    if (caretTop >= -lineH && caretTop <= h) {
        drawRect(theme.lineHighlight, topLeft = Offset(0f, caretTop), size = Size(w, lineH))
    }

    // Selection bands.
    val sel = state.selectionBounds()
    if (sel != null) {
        var l = max(sel.first.line, first)
        val selLast = min(sel.second.line, last)
        while (l <= selLast) {
            val entry = cache.layout(state, theme, textMeasurer, l)
            val left = if (l == sel.first.line) {
                textLeft + entry.xAt(sel.first.col) - state.scrollX
            } else {
                textLeft - state.scrollX
            }
            val right = if (l == sel.second.line) {
                textLeft + entry.xAt(sel.second.col) - state.scrollX
            } else {
                textLeft + entry.layout.size.width - state.scrollX + with(density) { 8.dp.toPx() }
            }
            if (right > left) {
                drawRoundRect(
                    theme.selection,
                    topLeft = Offset(left, l * lineH - state.scrollY),
                    size = Size(right - left, lineH),
                    cornerRadius = CornerRadius(with(density) { 2.dp.toPx() })
                )
            }
            l++
        }
    }

    // Find matches.
    for ((idx, m) in state.findMatches.withIndex()) {
        if (m.line < first - 1 || m.line > last) continue
        val entry = cache.layout(state, theme, textMeasurer, m.line)
        val left = textLeft + entry.xAt(m.startCol) - state.scrollX
        val right = textLeft + entry.xAt(m.endCol) - state.scrollX
        if (right > left && right > gutterW && left < w) {
            drawRoundRect(
                if (idx == state.findIndex) theme.findMatchActive else theme.findMatch,
                topLeft = Offset(left, m.line * lineH - state.scrollY),
                size = Size(right - left, lineH),
                cornerRadius = CornerRadius(with(density) { 2.dp.toPx() })
            )
        }
    }

    // Text (clipped so it slides under the gutter, never over it).
    var maxW = 0f
    clipRect(left = gutterW) {
        for (l in first until last) {
            val entry = cache.layout(state, theme, textMeasurer, l)
            val y = l * lineH - state.scrollY
            val x = textLeft - state.scrollX
            drawText(entry.layout, topLeft = Offset(x, y))
            val lineEnd = textLeft + entry.layout.size.width
            if (lineEnd > maxW) maxW = lineEnd
        }
    }
    onWidthsMeasured(maxW)

    // Gutter numbers, dimming away from the caret line. Layouts come from
    // the cache — measuring them here meant one measure call per visible
    // line per frame.
    for (l in first until last) {
        val layout = cache.gutterNumber(l + 1, state, theme, textMeasurer)
        val dist = abs(l - state.caretLine)
        val color = if (l == state.caretLine) {
            theme.gutterTextActive
        } else {
            theme.gutterText.copy(alpha = 1f - (dist.toFloat() / 26f).coerceIn(0f, 0.72f))
        }
        val x = gutterW - with(density) { 10.dp.toPx() } - layout.size.width
        drawText(layout, color = color, topLeft = Offset(x, l * lineH - state.scrollY))
    }

    // Diagnostic underlines: solid for errors, dotted for warnings.
    val underlineY = lineH * 0.88f
    for (d in state.diagnostics) {
        if (d.line < first - 1 || d.line > last) continue
        val entry = cache.layout(state, theme, textMeasurer, d.line)
        val left = textLeft + entry.xAt(d.colStart) - state.scrollX
        val right = textLeft + entry.xAt(d.colEnd) - state.scrollX
        if (right <= left || right < gutterW || left > w) continue
        val y = d.line * lineH - state.scrollY + underlineY
        val color = if (d.isError) theme.diagnosticError else theme.diagnosticWarning
        if (d.isError) {
            drawLine(color, Offset(left, y), Offset(right, y), strokeWidth = with(density) { 1.5.dp.toPx() })
        } else {
            drawLine(
                color, Offset(left, y), Offset(right, y),
                strokeWidth = with(density) { 1.5.dp.toPx() },
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )
        }
    }

    // Bracket match outline.
    state.bracketMatch?.let { (a, b) ->
        for (p in listOf(a, b)) {
            if (p.line < first - 1 || p.line > last) continue
            val entry = cache.layout(state, theme, textMeasurer, p.line)
            val x = textLeft + entry.xAt(p.col) - state.scrollX
            val y = p.line * lineH - state.scrollY
            val cw = entry.charWidth().coerceAtLeast(4f)
            drawRoundRect(
                color = theme.findMatchActive,
                topLeft = Offset(x - 1f, y + with(density) { 2.dp.toPx() }),
                size = Size(cw + 2f, lineH - with(density) { 4.dp.toPx() }),
                cornerRadius = CornerRadius(with(density) { 2.dp.toPx() }),
                style = Stroke(width = with(density) { 1.25.dp.toPx() })
            )
        }
    }

    // Selection handles.
    if (state.showHandles && sel != null) {
        val textLeftX = textLeft
        val startEntry = cache.layout(state, theme, textMeasurer, sel.first.line)
        val endEntry = cache.layout(state, theme, textMeasurer, sel.second.line)
        val start = Offset(
            textLeftX + startEntry.xAt(sel.first.col) - state.scrollX,
            (sel.first.line + 1) * lineH - state.scrollY + with(density) { 4.dp.toPx() }
        )
        val end = Offset(
            textLeftX + endEntry.xAt(sel.second.col) - state.scrollX,
            sel.second.line * lineH - state.scrollY - with(density) { 4.dp.toPx() }
        )
        val r = with(density) { 5.5.dp.toPx() }
        drawCircle(theme.selectionHandle, r, center = start)
        drawCircle(theme.background, r * 0.5f, center = start)
        drawCircle(theme.selectionHandle, r, center = end)
        drawCircle(theme.background, r * 0.5f, center = end)
    }

    // Scrollbars.
    val totalH = state.text.lineCount * lineH
    if (totalH > h) {
        val thumbH = (h / totalH) * h
        val thumbY = (state.scrollY / (totalH - h)) * (h - thumbH)
        drawRoundRect(
            theme.scrollbar,
            topLeft = Offset(w - with(density) { 2.5.dp.toPx() }, thumbY),
            size = Size(with(density) { 2.5.dp.toPx() }, thumbH),
            cornerRadius = CornerRadius(with(density) { 1.dp.toPx() })
        )
    }
    val maxX = state.maxScrollX()
    if (maxX > 4f) {
        val thumbW = (w / (maxX + w)) * w
        val thumbX = (state.scrollX / maxX) * (w - thumbW)
        drawRoundRect(
            theme.scrollbar,
            topLeft = Offset(thumbX, h - with(density) { 2.5.dp.toPx() }),
            size = Size(thumbW, with(density) { 2.5.dp.toPx() }),
            cornerRadius = CornerRadius(with(density) { 1.dp.toPx() })
        )
    }
}

/** The caret, drawn on its own layer so the blink never re-records the text canvas. */
private fun DrawScope.drawCaret(
    state: EditorState,
    theme: MonoTheme,
    density: Density,
    caretX: Float,
    caretY: Float
) {
    val lineH = state.lineHeightPx
    val h = size.height
    val cx = caretX - state.scrollX
    val cy = caretY - state.scrollY
    if (cy > -lineH && cy < h) {
        drawRoundRect(
            theme.caret,
            topLeft = Offset(cx - with(density) { 1.dp.toPx() }, cy + lineH * 0.14f),
            size = Size(with(density) { 2.dp.toPx() }, lineH * 0.72f),
            cornerRadius = CornerRadius(with(density) { 1.dp.toPx() })
        )
    }
}

private fun padH(density: Density): Float = with(density) { 8.dp.toPx() }

/** Static caret visibility while the editor is unfocused (blink loop stopped). */
private const val UNFOCUSED_CARET_ALPHA = 0.3f
