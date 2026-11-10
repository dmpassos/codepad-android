package com.codepad.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Barra de símbolos nativa do CodePad (equivalente ao #cpKeys do HTML).
 *
 * Desenha tudo em Canvas, sem XML e sem bibliotecas: o feedback do toque (estado "premido" e vibração)
 * acontece na thread de UI do Android, independente de o JavaScript do editor estar ocupado.
 * Nenhuma destas views aceita foco, por isso tocar nelas não tira o teclado nem o foco do <textarea>.
 */

/** Relógio de parede (ms) do instante de um MotionEvent, para medir a latência de ponta a ponta no JS. */
internal fun wallClock(eventUptimeMs: Long): Long =
    System.currentTimeMillis() - (SystemClock.uptimeMillis() - eventUptimeMs)

private fun monoTypeface(): Typeface =
    if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.MONOSPACE, 600, false)
    else Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

internal object KeyColors {
    val BAR = 0xFF070B12.toInt()
    val BAR_BORDER = 0x5900D4FF            // --border-b
    val CYAN = 0xFF00D4FF.toInt()
    val TEXT = 0xFFD6E2F0.toInt()
    val BRACKET = 0xFFEAF6FF.toInt()
    val AMBER = 0xFFFFB700.toInt()
    val GREEN = 0xFF39FF6E.toInt()
    val MUTED = 0xFF4A5568.toInt()
    val PURPLE = 0xFFA855F7.toInt()

    val FILL = 0x1200D4FF                  // rgba(0,212,255,.07)
    val FILL_DOWN = 0x3800D4FF             // rgba(0,212,255,.22)
    val STROKE = 0x1F00D4FF                // --border
    val HASH_FILL = 0x1FA855F7
    val HASH_FILL_DOWN = 0x57A855F7
    val HASH_STROKE = 0x73A855F7
    val RUN_STROKE = 0x6639FF6E
}

/** Uma tecla. Confirma no ACTION_UP (a faixa de símbolos desliza na horizontal, por isso não pode confirmar no DOWN). */
internal class KeyButton(ctx: Context, private val spec: Spec) : View(ctx) {

    enum class Kind { TEXT, PLAY, CHEVRON }

    class Spec(
        val kind: Kind,
        val label: String,
        val description: String,
        val textColor: Int,
        val fill: Int,
        val fillDown: Int,
        val stroke: Int,
        val textDp: Float = 15f,
        val letterSpacingEm: Float = 0f,
        val fixedWidthDp: Float? = null,
    )

    var onTap: ((upWallMs: Long) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = monoTypeface()
        color = spec.textColor
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, spec.textDp, resources.displayMetrics)
        letterSpacing = spec.letterSpacingEm
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d
        color = spec.stroke
    }
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = spec.textColor
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    /** Altura da barra / 40 dp. Teclas maiores: o texto, os cantos e as formas crescem um pouco, não proporcionalmente. */
    private var scale = 1f

    fun applyScale(s: Float) {
        if (s == scale) return
        scale = s
        textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, spec.textDp * (1f + 0.55f * (s - 1f)), resources.displayMetrics)
        requestLayout()
        invalidate()
    }

    private fun grow() = 1f + 0.5f * (scale - 1f)

    private var down = false
    private var downX = 0f
    private var downY = 0f
    private var upWall = 0L

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        contentDescription = spec.description
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val g = grow()
        val w = if (spec.fixedWidthDp != null) {
            (spec.fixedWidthDp * d * g).toInt()
        } else {
            val text = textPaint.measureText(spec.label)
            max(36f * d * g, text + 18f * d * g).toInt()   // min-width 36px, padding 0 9px (maiores com a barra)
        }
        setMeasuredDimension(w, getDefaultSize(0, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val half = d / 2f
        rect.set(half, half, w - half, h - half)
        val g = grow()
        val r = 8f * d * g
        fillPaint.color = if (down) spec.fillDown else spec.fill
        canvas.drawRoundRect(rect, r, r, fillPaint)
        canvas.drawRoundRect(rect, r, r, strokePaint)

        val cx = w / 2f
        val cy = h / 2f
        when (spec.kind) {
            Kind.TEXT -> {
                val baseline = cy - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(spec.label, cx, baseline, textPaint)
            }
            Kind.PLAY -> {
                shapePaint.style = Paint.Style.FILL
                path.reset()
                path.moveTo(cx - 4.5f * d * g, cy - 6.5f * d * g)
                path.lineTo(cx + 6f * d * g, cy)
                path.lineTo(cx - 4.5f * d * g, cy + 6.5f * d * g)
                path.close()
                canvas.drawPath(path, shapePaint)
            }
            Kind.CHEVRON -> {
                shapePaint.style = Paint.Style.STROKE
                shapePaint.strokeWidth = 1.8f * d * g
                path.reset()
                path.moveTo(cx - 5f * d * g, cy - 2f * d * g)
                path.lineTo(cx, cy + 3f * d * g)
                path.lineTo(cx + 5f * d * g, cy - 2f * d * g)
                canvas.drawPath(path, shapePaint)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down = true
                downX = e.x
                downY = e.y
                invalidate()                       // feedback imediato, na thread de UI
            }
            MotionEvent.ACTION_MOVE -> if (down) {
                val outside = e.x < 0f || e.y < 0f || e.x > width || e.y > height
                if (outside || hypot(e.x - downX, e.y - downY) > slop * 2f) {
                    down = false
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val ok = down
                down = false
                invalidate()
                if (ok) {
                    upWall = wallClock(e.eventTime)
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                down = false
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val t = if (upWall != 0L) upWall else System.currentTimeMillis()   // acessibilidade: sem MotionEvent
        upWall = 0L
        onTap?.invoke(t)
        return true
    }
}

/**
 * Joystick que move a seta de rato virtual do editor.
 * Aqui só se lê o toque e calcula a velocidade com a MESMA curva do HTML (zona morta de 10 %, expoente 1,6,
 * máx. 6 px por frame a 60 fps). O anel ocupa toda a altura da linha de teclas (mesma altura que elas, 64 dp no
 * telemóvel em pé); o curso para a velocidade máxima é de 36 dp, o que dá mais precisão a baixa velocidade.
 */
internal class JoystickView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onStart()
        fun onVelocity(vx: Float, vy: Float)
        fun onEnd(tap: Boolean, upWallMs: Long)
    }

    var listener: Listener? = null

    private val d = resources.displayMetrics.density
    private var active = false
    private var moved = false
    private var ox = 0f
    private var oy = 0f
    private var downTime = 0L
    private var knobX = 0f      // dp
    private var knobY = 0f
    private var lastVx = 0f
    private var lastVy = 0f
    private var back: ValueAnimator? = null

    private val backing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6070B12.toInt() }   // legível sobre o texto do editor
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
    }
    private val knobGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3300D4FF }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KeyColors.CYAN }

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        contentDescription = "Joystick: deslize para mover a seta, toque para pousar o cursor"
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = getDefaultSize(0, heightMeasureSpec)
        setMeasuredDimension(h + (12f * d).toInt(), h)      // célula = anel + 6 dp de cada lado
    }

    /** O anel enche a altura da view (as teclas desenham-se com a mesma margem de meio dp). */
    private fun ringRadius(): Float = (min(width, height).toFloat() - d) / 2f

    private fun centerX() = width / 2f
    private fun centerY() = height / 2f

    /** Só o anel (com uma folga) é zona de toque: o resto da view deixa o toque passar. */
    fun hits(x: Float, y: Float): Boolean = hypot(x - centerX(), y - centerY()) <= ringRadius() + 6f * d

    override fun onDraw(canvas: Canvas) {
        val cx = centerX()
        val cy = centerY()
        val rad = ringRadius()

        canvas.drawCircle(cx, cy, rad, backing)
        ringFill.shader = RadialGradient(cx, cy, rad, 0x3300D4FF, 0x0E00D4FF, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rad, ringFill)
        ringStroke.color = if (active) KeyColors.CYAN else 0x8000D4FF.toInt()
        canvas.drawCircle(cx, cy, rad - 1.5f * d, ringStroke)

        val kx = cx + knobX * d
        val ky = cy + knobY * d
        val knob = ringRadius() * KNOB_RATIO
        canvas.drawCircle(kx, ky, knob * 1.55f, knobGlow)
        canvas.drawCircle(kx, ky, knob, knobPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hits(e.x, e.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                back?.cancel()
                active = true
                moved = false
                ox = e.x
                oy = e.y
                downTime = e.eventTime
                knobX = 0f
                knobY = 0f
                lastVx = 0f
                lastVy = 0f
                invalidate()
                listener?.onStart()
            }
            MotionEvent.ACTION_MOVE -> if (active) {
                val dx = (e.x - ox) / d
                val dy = (e.y - oy) / d
                val len = hypot(dx, dy)
                if (len > 8f) moved = true
                val dd = min(len, FULL_TRAVEL_DP) / FULL_TRAVEL_DP
                val visualMax = (ringRadius() * (1f - KNOB_RATIO) - 4f * d) / d      // dp
                if (len > 0f) {
                    knobX = dx / len * dd * visualMax
                    knobY = dy / len * dd * visualMax
                } else {
                    knobX = 0f
                    knobY = 0f
                }
                val sp = if (dd < 0.1f) 0f else 0.3f + JOY_MAX * ((dd - 0.1f) / 0.9f).pow(1.6f)
                val vx = if (len > 0f) dx / len * sp else 0f
                val vy = if (len > 0f) dy / len * sp else 0f
                if (abs(vx - lastVx) > 0.01f || abs(vy - lastVy) > 0.01f) {
                    lastVx = vx
                    lastVy = vy
                    listener?.onVelocity(vx, vy)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (active) {
                active = false
                val tap = e.actionMasked == MotionEvent.ACTION_UP && !moved && (e.eventTime - downTime) < 350L
                if (tap) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                releaseKnob()
                listener?.onEnd(tap, wallClock(e.eventTime))
            }
        }
        return true
    }

    private fun releaseKnob() {
        val sx = knobX
        val sy = knobY
        back = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 140L
            addUpdateListener {
                val f = it.animatedValue as Float
                knobX = sx * f
                knobY = sy * f
                invalidate()
            }
            start()
        }
    }

    private companion object {
        const val JOY_MAX = 6f
        const val KNOB_RATIO = 0.34f     // raio do botão = 34 % do raio do anel (11 dp com o anel de 64 dp)
        const val FULL_TRAVEL_DP = 36f   // curso para a velocidade máxima (antes 30)
    }
}

/** A barra completa:  [ joystick ] | símbolos (deslizam) | [ ▶ ] [ ⌄ ] */
class NativeKeyBar(ctx: Context) : LinearLayout(ctx) {

    interface Host {
        /** index >= 0: posição em KEYS (JS) · -1: fechar teclado · -2: executar */
        fun onKey(index: Int, upWallMs: Long)
        fun onJoyStart()
        fun onJoyVelocity(vx: Float, vy: Float)
        fun onJoyEnd(tap: Boolean, upWallMs: Long)
    }

    var host: Host? = null

    private val d = resources.displayMetrics.density
    private val joy = JoystickView(ctx)
    private val sep = View(ctx)
    private val scroll = HorizontalScrollView(ctx)
    private val strip = LinearLayout(ctx)
    private val end = LinearLayout(ctx)
    private val borderPaint = Paint().apply { color = KeyColors.BAR_BORDER }
    private var btnPx = (40f * d).toInt()

    /** Fator de tamanho das teclas: altura da linha / 40 dp (40 dp era o tamanho original). */
    private var uiScale = 1f

    init {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
        setWillNotDraw(false)
        isFocusable = false
        isFocusableInTouchMode = false

        joy.listener = object : JoystickView.Listener {
            override fun onStart() { host?.onJoyStart() }
            override fun onVelocity(vx: Float, vy: Float) { host?.onJoyVelocity(vx, vy) }
            override fun onEnd(tap: Boolean, upWallMs: Long) { host?.onJoyEnd(tap, upWallMs) }
        }
        sep.setBackgroundColor(KeyColors.BAR_BORDER)

        strip.orientation = HORIZONTAL
        scroll.addView(strip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT))
        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.isHorizontalFadingEdgeEnabled = true      // esbatido só nas pontas com mais teclas, como no HTML
        scroll.setFadingEdgeLength((18f * d).toInt())
        scroll.isFocusable = false
        scroll.isFocusableInTouchMode = false

        end.orientation = HORIZONTAL
        end.addView(
            endButton(KeyButton.Kind.PLAY, "▶", "Executar", KeyColors.GREEN, KeyColors.RUN_STROKE, -2),
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
        )
        end.addView(
            endButton(KeyButton.Kind.CHEVRON, "⌄", "Fechar teclado", KeyColors.MUTED, KeyColors.STROKE, -1),
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply { leftMargin = (3f * d).toInt() },
        )

        addView(joy, LayoutParams(LayoutParams.WRAP_CONTENT, btnPx))
        addView(sep, LayoutParams(d.toInt().coerceAtLeast(1), btnPx).apply {
            leftMargin = (5f * d).toInt()
            rightMargin = (5f * d).toInt()
        })
        addView(scroll, LayoutParams(0, btnPx, 1f).apply { rightMargin = (5f * d).toInt() })
        addView(end, LayoutParams(LayoutParams.WRAP_CONTENT, btnPx))
        applyMetrics(53.0, 40.0, 0.0, 0.0)
    }

    private fun endButton(kind: KeyButton.Kind, label: String, desc: String, color: Int, stroke: Int, index: Int) =
        KeyButton(
            context,
            KeyButton.Spec(kind, label, desc, color, KeyColors.FILL, KeyColors.FILL_DOWN, stroke, textDp = 17f, fixedWidthDp = 36f),
        ).also { b -> b.onTap = { t -> host?.onKey(index, t) } }

    /** Estilo de cada tecla, igual às classes .ck-* do HTML. */
    private fun specFor(k: String): KeyButton.Spec {
        val bracket = k.length == 1 && "{}()[]".contains(k)
        return when {
            k == "Tab" -> KeyButton.Spec(
                KeyButton.Kind.TEXT, "TAB", "Tab", KeyColors.CYAN, KeyColors.FILL, KeyColors.FILL_DOWN, KeyColors.STROKE,
                textDp = 12f, letterSpacingEm = 0.083f,
            )
            k == "#" -> KeyButton.Spec(
                KeyButton.Kind.TEXT, "#", "Cardinal (#include, #define)", KeyColors.PURPLE,
                KeyColors.HASH_FILL, KeyColors.HASH_FILL_DOWN, KeyColors.HASH_STROKE, textDp = 17f,
            )
            k == "↶" -> KeyButton.Spec(
                KeyButton.Kind.TEXT, k, "Desfazer", KeyColors.AMBER, KeyColors.FILL, KeyColors.FILL_DOWN, KeyColors.STROKE,
            )
            k == "↷" -> KeyButton.Spec(
                KeyButton.Kind.TEXT, k, "Refazer", KeyColors.AMBER, KeyColors.FILL, KeyColors.FILL_DOWN, KeyColors.STROKE,
            )
            else -> KeyButton.Spec(
                KeyButton.Kind.TEXT, k, k, if (bracket) KeyColors.BRACKET else KeyColors.TEXT,
                KeyColors.FILL, KeyColors.FILL_DOWN, KeyColors.STROKE,
                textDp = if (k.length > 1) 13f else 15f,
            )
        }
    }

    /** A lista vem do JS (const KEYS), que continua a ser a única fonte da ordem das teclas. */
    fun setKeys(keys: List<String>) {
        strip.removeAllViews()
        keys.forEachIndexed { i, k ->
            val b = KeyButton(context, specFor(k))
            b.applyScale(uiScale)
            b.onTap = { t -> host?.onKey(i, t) }
            strip.addView(
                b,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                    if (i < keys.size - 1) rightMargin = gapPx()
                },
            )
        }
    }

    /**
     * Medidas vindas do DOM (#cpKeys): altura total do contentor, altura das teclas e recuos laterais
     * (entalhes em landscape). O espaçador do HTML tem a mesma altura, por isso o editor não se mexe.
     */
    fun applyMetrics(totalDp: Double, btnDp: Double, leftDp: Double, rightDp: Double) {
        val total = (totalDp * d).toInt().coerceAtLeast((20f * d).toInt())
        btnPx = (btnDp * d).toInt().coerceAtLeast((24f * d).toInt())
        uiScale = (btnPx / d / 40f).coerceIn(0.6f, 2f)
        setPadding(((6.0 + leftDp) * d).toInt(), (7f * d).toInt(), ((6.0 + rightDp) * d).toInt(), 0)
        for (v in listOf<View>(joy, sep, scroll, end)) {
            val lp = v.layoutParams as LayoutParams
            lp.height = btnPx
            v.layoutParams = lp
        }
        // teclas maiores quando a linha é mais alta (o tamanho vem do HTML, onde o espaçador cresce na mesma medida)
        val gap = gapPx()
        for (i in 0 until strip.childCount) {
            val v = strip.getChildAt(i) as KeyButton
            v.applyScale(uiScale)
            (v.layoutParams as LayoutParams).rightMargin = if (i < strip.childCount - 1) gap else 0
        }
        for (i in 0 until end.childCount) {
            val v = end.getChildAt(i) as KeyButton
            v.applyScale(uiScale)
            if (i > 0) (v.layoutParams as LayoutParams).leftMargin = gap
        }
        strip.requestLayout()
        end.requestLayout()
        val lp = layoutParams
        if (lp != null) {
            lp.height = total
            layoutParams = lp
        }
        minimumHeight = total
    }

    /** Espaço entre teclas: 3 dp na barra original, um pouco mais com teclas maiores. */
    private fun gapPx(): Int = (3f * d * (1f + 0.4f * (uiScale - 1f))).toInt().coerceAtLeast(d.toInt())

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(KeyColors.BAR)
        canvas.drawRect(0f, 0f, width.toFloat(), d, borderPaint)   // border-top: 1px
    }

    /** Ver como tela de toque: nada atravessa a barra para o WebView que está por baixo. */
    override fun onTouchEvent(event: MotionEvent): Boolean = true
}
