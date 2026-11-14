package com.codepad.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Círculo de carregamento do CodePad, desenhado em Canvas (sem XML e sem bibliotecas).
 *
 * Um arco com degradê ciano → verde gira sem parar e "respira" (o comprimento do arco vai e volta entre 150° e 270°),
 * sobre uma pista fraca e com um brilho suave à volta. As animações são ValueAnimator, por isso respeitam a escala de
 * animações do Android (com as animações desligadas nas definições, o arco fica parado em vez de girar).
 *
 * As medidas são relativas ao tamanho da view: 117 unidades = anel de 89 (traço de 10) + 14 de brilho de cada lado,
 * as mesmas proporções do círculo da imagem de arranque.
 */
class LoadingRing(ctx: Context) : View(ctx) {

    private val oval = RectF()
    private val track = newPaint()
    private val glow = newPaint()
    private val arc = newPaint()
    private var stroke = 0f
    private var spin = 0f
    private var sweep = MAX_SWEEP
    private var spinAnim: ValueAnimator? = null
    private var sweepAnim: ValueAnimator? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun newPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val unit = min(w, h) / VIEW_UNITS
        stroke = STROKE_UNITS * unit
        val cx = w / 2f
        val cy = h / 2f
        val r = RADIUS_UNITS * unit
        oval.set(cx - r, cy - r, cx + r, cy + r)
        val shader = SweepGradient(cx, cy, COLORS, STOPS)
        track.shader = shader
        glow.shader = shader
        arc.shader = shader
        track.strokeWidth = stroke * 0.9f
        track.alpha = TRACK_ALPHA
        arc.strokeWidth = stroke
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, oval.width() / 2f, track)             // pista fraca, parada
        canvas.save()
        canvas.rotate(spin, cx, cy)
        for (i in GLOW_WIDTH.indices) {                                 // brilho: três traços largos e quase transparentes
            glow.strokeWidth = stroke * GLOW_WIDTH[i]
            glow.alpha = GLOW_ALPHA[i]
            canvas.drawArc(oval, 0f, sweep, false, glow)
        }
        canvas.drawArc(oval, 0f, sweep, false, arc)
        canvas.restore()
    }

    fun start() {
        if (spinAnim != null) return
        spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = SPIN_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                spin = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        sweepAnim = ValueAnimator.ofFloat(MIN_SWEEP, MAX_SWEEP).apply {
            duration = SWEEP_MS
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { sweep = it.animatedValue as Float }
            start()
        }
    }

    fun stop() {
        spinAnim?.cancel()
        sweepAnim?.cancel()
        spinAnim = null
        sweepAnim = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val VIEW_UNITS = 117f
        const val RADIUS_UNITS = 39.5f      // raio da linha central do traço
        const val STROKE_UNITS = 10f
        const val SPIN_MS = 1000L           // uma volta por segundo
        const val SWEEP_MS = 1100L
        const val MIN_SWEEP = 150f
        const val MAX_SWEEP = 270f
        const val TRACK_ALPHA = 34
        val GLOW_WIDTH = floatArrayOf(2.7f, 1.9f, 1.35f)
        val GLOW_ALPHA = intArrayOf(16, 30, 52)

        private val CYAN = 0xFF00D4FF.toInt()
        private val TEAL = 0xFF00F5D4.toInt()
        private val GREEN = 0xFF39FF6E.toInt()
        // degradê ao longo do arco: a cauda é ciano e a cabeça verde; o último quarto volta ao ciano só para a pista fechar sem emenda
        val COLORS = intArrayOf(CYAN, TEAL, GREEN, GREEN, CYAN)
        val STOPS = floatArrayOf(0f, 0.32f, 0.72f, 0.86f, 1f)
    }
}

/**
 * Página de espera: a imagem de arranque (drawable-nodpi/splash_bg.png, já sem o círculo nem o texto) com o
 * [LoadingRing] e o "Iniciando..." desenhados por cima, na posição que tinham na imagem original.
 *
 * Em retrato a imagem cobre o ecrã (corte centrado); em paisagem ela cabe inteira em altura e as laterais fundem-se
 * com a cor de fundo. Tudo é calculado a partir da escala da imagem, por isso o círculo fica no mesmo sítio em
 * qualquer tamanho de ecrã. Engole os toques: nada chega ao WebView por baixo.
 */
class SplashView(ctx: Context) : FrameLayout(ctx) {

    private val d = resources.displayMetrics.density
    private val art: Bitmap? = try {
        BitmapFactory.decodeResource(resources, R.drawable.splash_bg, BitmapFactory.Options().apply { inScaled = false })
    } catch (t: Throwable) {
        Log.w(TAG, "imagem de arranque indisponível; só o fundo liso", t)
        null
    }
    private val ring = LoadingRing(ctx)
    private val label = ctx.getString(R.string.splash_loading)
    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fadePaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = TEXT
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.02f
    }
    private val dst = RectF()
    private var s = 1f       // px de ecrã por px da imagem
    private var ox = 0f      // canto superior esquerdo da imagem no ecrã
    private var oy = 0f
    private var ringCy = 0f  // centro vertical do círculo, no ecrã
    private var textY = 0f   // linha de base do texto, no ecrã

    init {
        setWillNotDraw(false)
        isClickable = true
        contentDescription = label
        addView(ring, LayoutParams(0, 0))
    }

    private fun place(w: Int, h: Int) {
        val sx = w / ART_W
        val sy = h / ART_H
        s = if (h >= w) max(sx, sy) else min(sx, sy)
        ox = (w - ART_W * s) / 2f
        oy = (h - ART_H * s) / 2f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        place(w, h)
        val outer = max(RING_OUTER * s, MIN_RING_DP * d)          // nunca mais pequeno que 30 dp (ecrãs baixos, paisagem)
        val side = (outer * RING_VIEW / RING_OUTER).toInt()
        ring.measure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY))
        textPaint.textSize = max(LABEL_PX * s, MIN_LABEL_DP * d)
        textPaint.setShadowLayer(textPaint.textSize * 0.25f, 0f, 0f, GLOW)
        // posições da imagem original; com os tamanhos mínimos (paisagem) o círculo sobe para nunca tocar no texto
        textY = oy + LABEL_BASELINE * s
        val aboveText = textY - textPaint.textSize * 0.75f - GAP_DP * d - outer / 2f
        ringCy = min(oy + RING_CY * s, aboveText)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val side = ring.measuredWidth
        val cx = (r - l) / 2
        val cy = ringCy.toInt()
        ring.layout(cx - side / 2, cy - side / 2, cx - side / 2 + side, cy - side / 2 + side)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BG)
        val a = art
        if (a != null && !a.isRecycled) {
            dst.set(ox, oy, ox + ART_W * s, oy + ART_H * s)
            canvas.drawBitmap(a, null, dst, artPaint)
            if (ox > 1f) fadeSides(canvas)
        }
        canvas.drawText(label, width / 2f, textY, textPaint)
    }

    /** Em paisagem a imagem não chega às laterais: esbate as bordas dela para a cor de fundo, sem corte seco. */
    private fun fadeSides(c: Canvas) {
        val fw = FADE_PX * s
        val clear = BG and 0x00FFFFFF
        val right = ox + ART_W * s
        fadePaint.shader = LinearGradient(ox, 0f, ox + fw, 0f, BG, clear, Shader.TileMode.CLAMP)
        c.drawRect(ox, 0f, ox + fw, height.toFloat(), fadePaint)
        fadePaint.shader = LinearGradient(right - fw, 0f, right, 0f, clear, BG, Shader.TileMode.CLAMP)
        c.drawRect(right - fw, 0f, right, height.toFloat(), fadePaint)
    }

    fun release() {
        ring.stop()
        art?.let { if (!it.isRecycled) it.recycle() }
    }

    private companion object {
        const val TAG = "CodePadSplash"
        // medidas na imagem original (841 × 1870 px)
        const val ART_W = 841f
        const val ART_H = 1870f
        const val RING_CY = 1337f          // centro vertical do círculo
        const val RING_OUTER = 89f         // diâmetro exterior do anel
        const val RING_VIEW = 117f         // anel + brilho (ver LoadingRing)
        const val LABEL_BASELINE = 1435f   // linha de base do "Iniciando..."
        const val LABEL_PX = 30.5f
        const val FADE_PX = 70f
        const val MIN_RING_DP = 30f
        const val MIN_LABEL_DP = 12f
        const val GAP_DP = 8f              // folga mínima entre o círculo e o texto
        val BG = 0xFF00060D.toInt()        // = @color/codepad_splash_bg
        val TEXT = 0xFFE9F1F8.toInt()
        val GLOW = 0x6600D4FF
    }
}

/**
 * Dona da página de espera. Mostra a [SplashView] por cima de tudo (na decor view, logo no primeiro quadro) e tira-a
 * quando o JavaScript avisa que a interface já foi pintada: window.CPSplash.ready(), chamado pelo native-bridge.js.
 *
 *  - [MIN_SHOW_MS]: tempo mínimo no ecrã, para a imagem não piscar quando o app abre depressa;
 *  - [MAX_SHOW_MS]: rede de segurança; se o aviso nunca chegar (HTML antigo, erro no JS), sai na mesma.
 */
class NativeSplash(private val activity: Activity) {

    private val main = Handler(Looper.getMainLooper())
    private var view: SplashView? = null
    private var shownAt = 0L
    private var leaving = false

    fun show() {
        val decor = activity.window.decorView as? ViewGroup ?: return
        val v = SplashView(activity)
        decor.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view = v
        shownAt = SystemClock.uptimeMillis()
        main.postDelayed({ finish(force = true) }, MAX_SHOW_MS)
    }

    /** JS -> Kotlin: chega numa thread do WebView, por isso salta para a thread de UI. */
    @JavascriptInterface
    fun ready() {
        main.post { finish(force = false) }
    }

    private fun finish(force: Boolean) {
        val v = view ?: return
        if (leaving) return
        val wait = MIN_SHOW_MS - (SystemClock.uptimeMillis() - shownAt)
        if (!force && wait > 0) {
            main.postDelayed({ finish(force = true) }, wait)
            return
        }
        leaving = true
        main.removeCallbacksAndMessages(null)
        v.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction { remove(v) }.start()
    }

    private fun remove(v: SplashView) {
        (v.parent as? ViewGroup)?.removeView(v)
        v.release()
        if (view === v) view = null
    }

    /** Chamado no onDestroy da Activity. */
    fun release() {
        main.removeCallbacksAndMessages(null)
        view?.let {
            it.animate().cancel()
            remove(it)
        }
    }

    private companion object {
        const val MIN_SHOW_MS = 900L
        const val MAX_SHOW_MS = 8000L
        const val FADE_OUT_MS = 300L
    }
}
