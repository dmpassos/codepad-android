package com.codepad.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Cores e medidas (vindas do CSS do próprio HTML, para o editor nativo ficar igual ao web em qualquer tema). */
class EditorTheme(
    val fg: Int = 0xFFD6E2F0.toInt(),
    val bg: Int = 0xFF0B1118.toInt(),
    val selection: Int = 0x5500D4FF,
    val cursor: Int = 0xFF00D4FF.toInt(),
    val tokens: Map<String, Int> = emptyMap(),
    val gutterBg: Int = 0xFF080D14.toInt(),
    val gutterFg: Int = 0xFF4A5568.toInt(),
    val gutterBorder: Int = 0x1F00D4FF,
    val gutterWidthDp: Float = 44f,
    val fontDp: Float = 14f,
    val lineHeightDp: Float = 21f,
    val padLeftDp: Float = 10f,
    val padTopDp: Float = 8f,
    val bpColor: Int = 0xFFFF4D6A.toInt(),
    val dbgBg: Int = 0x33FFB700,
    val dbgFg: Int = 0xFFFFB700.toInt(),
)

/** Token com cor: marcamos os spans do realce com esta classe para os poder remover/refazer sem tocar noutros. */
internal class TokenSpan(color: Int) : ForegroundColorSpan(color)

/**
 * Editor de código nativo: EditText do Android (teclado, seleção, lupa, copiar/colar) + realce por spans,
 * números de linha, pontos de paragem, histórico próprio, regras de pares/indentação portadas do JS
 * e a "seta de rato" do joystick, tudo na thread de UI — sem JavaScript no caminho do toque.
 */
class CodeEditText(ctx: Context) : EditText(ctx) {

    interface Host {
        fun onUserEdit()
        fun onSelectionMoved()
        fun onFocus(focused: Boolean)
        fun onGutterTap(line: Int)
        fun onRunShortcut()
    }

    var host: Host? = null
    var language: String = "c"
        private set

    private val d = resources.displayMetrics.density
    private var theme = EditorTheme()
    private var highlighter: Highlighter? = null
    private var tracker: HlTracker? = null
    private val undo = UndoManager()

    private var programmatic = false
    private var pendingRemoved = ""
    private var lastSelS = -1
    private var lastSelE = -1

    // gutter
    private var gutterPx = 44f * d
    private var breakpoints: Set<Int> = emptySet()
    private var dbgLine = 0
    private val gutterBgPaint = Paint()
    private val gutterBorderPaint = Paint()
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT; typeface = Typeface.MONOSPACE }
    private val bpPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dbgPaint = Paint()

    // rolagem com inércia
    private val scroller = OverScroller(ctx)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(ctx).scaledMaximumFlingVelocity
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var gutterDown = false
    private var savedSelS = 0
    private var savedSelE = 0
    private var contentWidthCache = -1f

    // seta virtual
    private var pOn = false
    private var px = 0f
    private var py = 0f
    private var pvx = 0f
    private var pvy = 0f
    private var pHeld = false
    private var pRunning = false
    private var pLastNs = 0L
    private var pRingStart = 0L
    private val pPaintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pPaintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF0A0E14.toInt(); strokeWidth = 1.2f * d; strokeJoin = Paint.Join.ROUND }
    private val pPaintGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4400D4FF }
    private val pPaintGhost = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC00D4FF.toInt() }
    private val pPaintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val pArrow = Path().apply {
        // a mesma seta do HTML (svg 16x24, ponta em 1,1)
        moveTo(1f, 1f); lineTo(1f, 18f); lineTo(5.2f, 14.2f); lineTo(8.3f, 22f)
        lineTo(11.2f, 20.7f); lineTo(8.2f, 14.2f); lineTo(14f, 14.2f); close()
    }
    private val pArrowDraw = Path()
    private val frameCb = Choreographer.FrameCallback { ns -> pointerFrame(ns) }
    private val hidePointerRun = Runnable { pOn = false; pvx = 0f; pvy = 0f; invalidate() }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = true
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
        setHorizontallyScrolling(true)
        typeface = Typeface.MONOSPACE
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        if (Build.VERSION.SDK_INT >= 26) importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        isSaveEnabled = false
        applyTheme(theme)

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                if (!programmatic) pendingRemoved = if (count > 0) s.subSequence(start, start + count).toString() else ""
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                contentWidthCache = -1f
                if (programmatic) return
                undo.record(start, pendingRemoved, s.subSequence(start, start + count).toString(), SystemClock.uptimeMillis())
            }

            override fun afterTextChanged(e: Editable) {
                rehighlight(e)
                if (!programmatic) host?.onUserEdit()
            }
        })
    }

    // ───────────────────────────── configuração ─────────────────────────────

    fun setHighlighter(h: Highlighter) {
        highlighter = h
        tracker = HlTracker(h)
        rehighlightAll()
    }

    fun setLanguage(lang: String) {
        if (lang == language) return
        language = lang
        rehighlightAll()
    }

    fun applyTheme(t: EditorTheme) {
        theme = t
        setBackgroundColor(t.bg)
        setTextColor(t.fg)
        highlightColor = t.selection
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, t.fontDp)
        val fontH = paint.fontSpacing
        setLineSpacing(max(0f, t.lineHeightDp * d - fontH), 1f)
        gutterPx = t.gutterWidthDp * d
        setPadding((gutterPx + t.padLeftDp * d).toInt(), (t.padTopDp * d).toInt(), (10f * d).toInt(), (40f * d).toInt())
        gutterBgPaint.color = t.gutterBg
        gutterBorderPaint.color = t.gutterBorder
        numberPaint.color = t.gutterFg
        numberPaint.textSize = max(10f * d, t.fontDp * d * 0.85f)
        bpPaint.color = t.bpColor
        dbgPaint.color = t.dbgBg
        if (Build.VERSION.SDK_INT >= 29) {
            textCursorDrawable = GradientDrawable().apply {
                setColor(t.cursor)
                setSize((2f * d).toInt().coerceAtLeast(2), (t.lineHeightDp * d).toInt())
            }
        }
        rehighlightAll()
        invalidate()
    }

    fun setBreakpoints(lines: Set<Int>) {
        breakpoints = lines
        invalidate()
    }

    fun setDebugLine(line: Int) {
        dbgLine = line
        if (line > 0) scrollLineIntoView(line)
        invalidate()
    }

    // ───────────────────────────── texto (chamado pelo JS) ─────────────────────────────

    /** O JS trocou o texto (abrir ficheiro, limpar, colar): não é uma edição do utilizador, o histórico recomeça. */
    fun setTextProgrammatic(newText: String) {
        programmatic = true
        try {
            val e = editableText
            e.replace(0, e.length, newText)
            setSelection(newText.length)
        } finally {
            programmatic = false
        }
        undo.clear()
        scroller.forceFinished(true)
        scrollTo(0, 0)
    }

    /** Edição pedida pelo JS (setRangeText): conta no histórico. */
    fun replaceRange(start: Int, end: Int, text: String, selS: Int, selE: Int) {
        val e = editableText
        val s = start.coerceIn(0, e.length)
        e.replace(s, end.coerceIn(s, e.length), text)
        setSelection(selS.coerceIn(0, e.length), selE.coerceIn(0, e.length))
    }

    fun setSelectionSafe(s: Int, e: Int) {
        val len = text.length
        setSelection(s.coerceIn(0, len), e.coerceIn(0, len))
    }

    private fun lineStarts(t: CharSequence): IntArray {
        var n = 1
        for (i in t.indices) if (t[i] == '\n') n++
        val out = IntArray(n)
        var k = 1
        for (i in t.indices) if (t[i] == '\n') out[k++] = i + 1
        return out
    }

    // ───────────────────────────── realce ─────────────────────────────

    private fun rehighlightAll() {
        val e = editableText ?: return
        for (s in e.getSpans(0, e.length, TokenSpan::class.java)) e.removeSpan(s)
        tracker?.reset()
        rehighlight(e)
    }

    private fun rehighlight(e: Editable) {
        val tr = tracker ?: return
        val changes = tr.update(language, e.toString())
        if (changes.isEmpty()) return
        val starts = lineStarts(e)
        for (c in changes) {
            if (c.line >= starts.size) continue
            val ls = starts[c.line]
            val le = if (c.line + 1 < starts.size) starts[c.line + 1] - 1 else e.length
            if (le > ls) for (s in e.getSpans(ls, le, TokenSpan::class.java)) e.removeSpan(s)
            for (sp in c.res.spans) {
                val color = theme.tokens[sp.cls] ?: continue
                if (sp.end > sp.start && ls + sp.end <= le) e.setSpan(TokenSpan(color), ls + sp.start, ls + sp.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    // ───────────────────────────── teclas e regras de edição ─────────────────────────────

    private fun selS() = max(0, selectionStart)
    private fun selE() = max(0, selectionEnd)

    fun applyEdit(ed: Edit) {
        val e = editableText
        e.replace(ed.start, ed.end, ed.text)
        setSelection(ed.selStart.coerceIn(0, e.length), ed.selEnd.coerceIn(0, e.length))
    }

    private fun handleTyped(c: Char): Boolean =
        when (val r = EditorLogic.typed(text, selS(), selE(), c, language)) {
            KeyResult.Pass -> false
            is KeyResult.Apply -> { applyEdit(r.edit); true }
            is KeyResult.Move -> { setSelection(r.pos); true }
        }

    private fun handleBackspace(): Boolean =
        when (val r = EditorLogic.backspace(text, selS(), selE())) {
            is KeyResult.Apply -> { applyEdit(r.edit); true }
            else -> false
        }

    fun handleEnter() = applyEdit(EditorLogic.enter(text, selS(), selE()))
    fun handleTab(shift: Boolean) = applyEdit(EditorLogic.tab(text, selS(), selE(), shift))

    /** Tecla da barra de símbolos (a mesma regra do JS: um só caractere passa pelas regras de pares). */
    fun insertKey(k: String) {
        if (k == "Tab") { handleTab(false); return }
        if (k.length == 1 && handleTyped(k[0])) return
        applyEdit(Edit(selS(), selE(), k, selS() + k.length, selS() + k.length))
    }

    fun undoStep() = applyHistory(undo.popUndo(), true)
    fun redoStep() = applyHistory(undo.popRedo(), false)

    private fun applyHistory(en: UndoManager.Entry?, isUndo: Boolean) {
        if (en == null) return
        programmatic = true
        try {
            val e = editableText
            if (isUndo) {
                e.replace(en.start, en.start + en.inserted.length, en.removed)
                setSelection((en.start + en.removed.length).coerceIn(0, e.length))
            } else {
                e.replace(en.start, en.start + en.removed.length, en.inserted)
                setSelection((en.start + en.inserted.length).coerceIn(0, e.length))
            }
        } finally {
            programmatic = false
        }
        host?.onUserEdit()
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text != null && text.length == 1) {
                    super.finishComposingText()
                    if (text[0] == '\n') { handleEnter(); return true }
                    if (handleTyped(text[0])) return true
                }
                return super.commitText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength == 1 && afterLength == 0 && handleBackspace()) return true
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_ENTER -> { handleEnter(); return true }
                        KeyEvent.KEYCODE_DEL -> if (handleBackspace()) return true
                        KeyEvent.KEYCODE_TAB -> { handleTab(event.isShiftPressed); return true }
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed || event.isMetaPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_Z -> { if (event.isShiftPressed) redoStep() else undoStep(); return true }
                KeyEvent.KEYCODE_Y -> { redoStep(); return true }
                KeyEvent.KEYCODE_ENTER -> { host?.onRunShortcut(); return true }
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_TAB -> { handleTab(event.isShiftPressed); return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { handleEnter(); return true }
            KeyEvent.KEYCODE_DEL -> if (handleBackspace()) return true
        }
        if (!event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed) {
            val uc = event.unicodeChar
            if (uc > 0 && (uc and KeyCharacterMap.COMBINING_ACCENT) == 0 && uc <= 0xFFFF && handleTyped(uc.toChar())) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Colar sempre como texto simples (sem estilos vindos de outras apps). */
    override fun onTextContextMenuItem(id: Int): Boolean =
        super.onTextContextMenuItem(if (id == android.R.id.paste) android.R.id.pasteAsPlainText else id)

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (lastSelS != selStart || lastSelE != selEnd) {
            lastSelS = selStart
            lastSelE = selEnd
            host?.onSelectionMoved()
        }
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        host?.onFocus(focused)
    }

    // ───────────────────────────── desenho: gutter e seta ─────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val lay = layout
        if (lay != null && dbgLine in 1..lay.lineCount) {
            val l = dbgLine - 1
            val top = extendedPaddingTop + lay.getLineTop(l).toFloat()
            val bottom = extendedPaddingTop + lay.getLineBottom(l).toFloat()
            canvas.drawRect(scrollX + gutterPx, top, (scrollX + width).toFloat(), bottom, dbgPaint)   // faixa sob o texto
        }
        super.onDraw(canvas)
        if (lay != null) drawGutter(canvas, lay)
        drawPointer(canvas)
    }

    private fun drawGutter(c: Canvas, lay: android.text.Layout) {
        val sx = scrollX.toFloat()
        val sy = scrollY.toFloat()
        val g = gutterPx
        c.drawRect(sx, sy, sx + g, sy + height, gutterBgPaint)
        c.drawRect(sx + g - d, sy, sx + g, sy + height, gutterBorderPaint)
        val padTop = extendedPaddingTop
        val first = lay.getLineForVertical(max(0, (sy - padTop).toInt()))
        val last = min(lay.lineCount - 1, lay.getLineForVertical(max(0, (sy + height - padTop).toInt())))
        for (line in first..last) {
            val n = line + 1
            val top = padTop + lay.getLineTop(line).toFloat()
            val bottom = padTop + lay.getLineBottom(line).toFloat()
            if (n == dbgLine) {
                c.drawRect(sx, top, sx + g - d, bottom, dbgPaint)
                numberPaint.color = theme.dbgFg
            } else numberPaint.color = theme.gutterFg
            c.drawText(n.toString(), sx + g - 9f * d, padTop + lay.getLineBaseline(line).toFloat(), numberPaint)
            if (n in breakpoints) c.drawCircle(sx + 7f * d, (top + bottom) / 2f, 3.6f * d, bpPaint)
        }
    }

    private fun drawPointer(c: Canvas) {
        if (!pOn) return
        val sx = scrollX.toFloat()
        val sy = scrollY.toFloat()
        val lay = layout
        if (lay != null) {                          // cursor-fantasma: onde o cursor pousaria
            val line = lineAtY(py)
            val off = lay.getOffsetForHorizontal(line, px + scrollX - compoundPaddingLeft)
            val gx = lay.getPrimaryHorizontal(off) + compoundPaddingLeft          // coordenadas de conteúdo
            val top = (extendedPaddingTop + lay.getLineTop(line)).toFloat()
            val bottom = (extendedPaddingTop + lay.getLineBottom(line)).toFloat()
            c.drawRect(gx - d, top, gx + d, bottom, pPaintGhost)
        }
        val ax = sx + px
        val ay = sy + py
        c.drawCircle(ax, ay, 9f * d, pPaintGlow)
        c.save()
        c.translate(ax - d, ay - d)
        c.scale(d, d)
        pArrowDraw.set(pArrow)
        c.drawPath(pArrowDraw, pPaintFill)
        c.restore()
        c.save()
        c.translate(ax - d, ay - d)
        c.scale(d, d)
        pPaintStroke.strokeWidth = 1.2f
        c.drawPath(pArrow, pPaintStroke)
        c.restore()
        if (pRingStart != 0L) {
            val t = (SystemClock.uptimeMillis() - pRingStart) / 450f
            if (t in 0f..1f) {
                pPaintRing.color = ((1f - t) * 255).toInt() shl 24 or 0x0000D4FF
                c.drawCircle(ax, ay, (6f + 14f * t) * d, pPaintRing)
            } else pRingStart = 0L
        }
    }

    // ───────────────────────────── toque: gutter, rolagem com inércia ─────────────────────────────

    private fun maxScrollY(): Int {
        val lay = layout ?: return 0
        return max(0, lay.height + extendedPaddingTop + extendedPaddingBottom - height)
    }

    private fun contentWidth(): Float {
        if (contentWidthCache >= 0f) return contentWidthCache
        val lay = layout ?: return 0f
        var w = 0f
        for (i in 0 until lay.lineCount) w = max(w, lay.getLineWidth(i))
        contentWidthCache = w
        return w
    }

    private fun maxScrollX(): Int = max(0, (contentWidth() + compoundPaddingLeft + compoundPaddingRight - width).toInt())

    private fun lineAtY(y: Float): Int {
        val lay = layout ?: return 0
        return lay.getLineForVertical((y + scrollY - extendedPaddingTop).toInt().coerceIn(0, max(0, lay.height - 1)))
    }

    fun scrollLineIntoView(line: Int) {
        val lay = layout ?: run { post { scrollLineIntoView(line) }; return }
        val l = (line - 1).coerceIn(0, lay.lineCount - 1)
        val top = extendedPaddingTop + lay.getLineTop(l)
        val bottom = extendedPaddingTop + lay.getLineBottom(l)
        if (top < scrollY) scrollTo(scrollX, max(0, top - height / 4))
        else if (bottom > scrollY + height) scrollTo(scrollX, min(maxScrollY(), bottom - height * 3 / 4))
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, scroller.currY)
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = ev.x
                downY = ev.y
                dragging = false
                gutterDown = ev.x < gutterPx
                savedSelS = selectionStart
                savedSelE = selectionEnd
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
            }
            MotionEvent.ACTION_MOVE -> if (!dragging && hypot(ev.x - downX, ev.y - downY) > touchSlop) dragging = true
        }
        velocity?.addMovement(ev)
        val handled = super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                if (gutterDown && !dragging) {                      // toque no gutter: ponto de paragem, o cursor não mexe
                    setSelectionSafe(savedSelS, savedSelE)
                    host?.onGutterTap(lineAtY(ev.y) + 1)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                } else if (dragging) {
                    velocity?.computeCurrentVelocity(1000, maxFling.toFloat())
                    val vx = velocity?.xVelocity ?: 0f
                    val vy = velocity?.yVelocity ?: 0f
                    if (abs(vx) > minFling || abs(vy) > minFling) {
                        scroller.fling(scrollX, scrollY, -vx.toInt(), -vy.toInt(), 0, maxScrollX(), 0, maxScrollY())
                        postInvalidateOnAnimation()
                    }
                }
                velocity?.recycle(); velocity = null
                gutterDown = false
            }
            MotionEvent.ACTION_CANCEL -> { velocity?.recycle(); velocity = null; gutterDown = false }
        }
        return handled
    }

    // ───────────────────────────── seta virtual (joystick) ─────────────────────────────

    private fun pointerBounds() = floatArrayOf(gutterPx + 2f * d, 2f * d, width - 2f * d, height - 2f * d)

    private fun showPointerAtCaret() {
        val lay = layout ?: return
        val off = selectionEnd.coerceAtLeast(0)
        val line = lay.getLineForOffset(off)
        val x = lay.getPrimaryHorizontal(off) + compoundPaddingLeft - scrollX + d
        val y = extendedPaddingTop + (lay.getLineTop(line) + lay.getLineBottom(line)) / 2f - scrollY
        val b = pointerBounds()
        px = x.coerceIn(b[0], b[2])
        py = y.coerceIn(b[1], b[3])
        pOn = true
    }

    fun pointerStart() {
        if (!isFocused) requestFocus()
        removeCallbacks(hidePointerRun)
        pHeld = true
        pvx = 0f
        pvy = 0f
        startFrames()
    }

    fun pointerVelocity(vx: Float, vy: Float) {
        if (!pHeld) return
        pvx = vx
        pvy = vy
        if ((vx != 0f || vy != 0f) && !pOn) showPointerAtCaret()
    }

    fun pointerEnd(tap: Boolean) {
        pHeld = false
        pvx = 0f
        pvy = 0f
        if (tap) {
            if (!pOn) showPointerAtCaret() else pointerClick()
        }
        removeCallbacks(hidePointerRun)
        if (pOn) postDelayed(hidePointerRun, if (tap) 750L else 6000L)
        invalidate()
    }

    private fun pointerClick() {
        val lay = layout ?: return
        val line = lineAtY(py)
        val off = lay.getOffsetForHorizontal(line, px + scrollX - compoundPaddingLeft)
        if (!isFocused) requestFocus()
        setSelection(off.coerceIn(0, text.length))
        pRingStart = SystemClock.uptimeMillis()
        startFrames()
    }

    private fun startFrames() {
        if (pRunning) return
        pRunning = true
        pLastNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    private fun pointerFrame(ns: Long) {
        if (!pRunning) return
        val dt = if (pLastNs == 0L) 1f else min(40f, (ns - pLastNs) / 1_000_000f) / 16.667f
        pLastNs = ns
        if (pOn && (pvx != 0f || pvy != 0f)) {
            val b = pointerBounds()
            px = (px + pvx * dt * d).coerceIn(b[0], b[2])
            py = (py + pvy * dt * d).coerceIn(b[1], b[3])
            val lh = (theme.lineHeightDp * d)
            var sx = scrollX.toFloat()
            var sy = scrollY.toFloat()
            if (py < b[1] + lh && pvy < 0f) sy += pvy * dt * 1.4f * d
            else if (py > b[3] - lh && pvy > 0f) sy += pvy * dt * 1.4f * d
            if (px < b[0] + 28f * d && pvx < 0f) sx += pvx * dt * 1.4f * d
            else if (px > b[2] - 28f * d && pvx > 0f) sx += pvx * dt * 1.4f * d
            scrollTo(sx.coerceIn(0f, maxScrollX().toFloat()).toInt(), sy.coerceIn(0f, maxScrollY().toFloat()).toInt())
        }
        invalidate()
        val ringActive = pRingStart != 0L && SystemClock.uptimeMillis() - pRingStart < 450L
        if (pHeld || ringActive) Choreographer.getInstance().postFrameCallback(frameCb) else pRunning = false
    }

    override fun onDetachedFromWindow() {
        pRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCb)
        removeCallbacks(hidePointerRun)
        super.onDetachedFromWindow()
    }
}
