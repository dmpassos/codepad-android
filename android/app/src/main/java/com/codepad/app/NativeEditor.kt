package com.codepad.app

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Editor nativo (beta): ponte entre o HTML e o [CodeEditText].
 *
 * O HTML continua dono do ficheiro/workspace/executar. Quando o editor nativo está ligado, o JS troca as propriedades do
 * <textarea> (value, selectionStart/End, setSelectionRange, setRangeText, focus, blur) por chamadas a window.CPEditor,
 * e o resto do app continua a funcionar sem saber. Realce, gutter, teclas inteligentes, desfazer e a seta do joystick
 * vivem inteiros aqui, na thread de UI.
 *
 * A view é desenhada POR CIMA do WebView, na posição do .editor-wrap que o JS envia (rect). Quando o JS deteta uma
 * sobreposição web (menu, gaveta, diálogo), manda esconder (hide) e o editor web volta a aparecer por baixo.
 */
class NativeEditor(
    private val activity: Activity,
    private val webView: WebView,
    container: ViewGroup,
) : CodeEditText.Host {

    private val view = CodeEditText(activity)
    private val main = Handler(Looper.getMainLooper())
    private val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private val d = activity.resources.displayMetrics.density
    private val pending = HashMap<String, Runnable>()

    /** O editor nativo está visível (é o alvo das teclas e do joystick nativos). */
    val isActive: Boolean get() = view.visibility == View.VISIBLE && view.isShown

    init {
        view.visibility = View.GONE
        view.host = this
        container.addView(view, FrameLayout.LayoutParams(0, 0))
    }

    // ───────────── usado pela barra de teclas nativa (sem passar pelo JS) ─────────────

    fun pressKey(k: String) {
        when (k) {
            "↶" -> view.undoStep()
            "↷" -> view.redoStep()
            else -> view.insertKey(k)
        }
    }

    fun pointerStart() = view.pointerStart()
    fun pointerVelocity(vx: Float, vy: Float) = view.pointerVelocity(vx, vy)
    fun pointerEnd(tap: Boolean) = view.pointerEnd(tap)

    // ───────────── JS -> Kotlin (thread do WebView; tudo salta para a thread de UI) ─────────────

    @JavascriptInterface
    fun configure(json: String) {
        main.post {
            try {
                val o = JSONObject(json)
                o.optString("lang").takeIf { it.isNotEmpty() }?.let { view.setLanguage(it) }
                o.optJSONObject("rules")?.let { view.setHighlighter(buildHighlighter(it)) }
                o.optJSONObject("theme")?.let { view.applyTheme(parseTheme(it)) }
            } catch (t: Throwable) {
                Log.e(TAG, "configure falhou", t)
            }
        }
    }

    @JavascriptInterface
    fun setLang(lang: String) { main.post { view.setLanguage(lang) } }

    @JavascriptInterface
    fun setTheme(json: String) {
        main.post {
            try { view.applyTheme(parseTheme(JSONObject(json))) } catch (t: Throwable) { Log.e(TAG, "setTheme falhou", t) }
        }
    }

    @JavascriptInterface
    fun setText(text: String) { main.post { view.setTextProgrammatic(text) } }

    @JavascriptInterface
    fun getText(): String = onUi("") { view.text.toString() }

    @JavascriptInterface
    fun getSel(): String = onUi("0,0") { "${maxOf(0, view.selectionStart)},${maxOf(0, view.selectionEnd)}" }

    @JavascriptInterface
    fun setSel(s: Int, e: Int) { main.post { view.setSelectionSafe(s, e) } }

    @JavascriptInterface
    fun replace(start: Int, end: Int, text: String, selS: Int, selE: Int) {
        main.post { view.replaceRange(start, end, text, selS, selE) }
    }

    @JavascriptInterface
    fun focus() {
        main.post {
            if (view.visibility == View.VISIBLE) {
                view.requestFocus()
                imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    @JavascriptInterface
    fun blur() {
        main.post {
            view.clearFocus()
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    @JavascriptInterface
    fun rect(left: Double, top: Double, width: Double, height: Double) {
        main.post {
            val lp = FrameLayout.LayoutParams((width * d).toInt(), (height * d).toInt(), Gravity.TOP or Gravity.START)
            lp.leftMargin = (left * d).toInt()
            lp.topMargin = (top * d).toInt()
            view.layoutParams = lp
            view.visibility = View.VISIBLE
        }
    }

    @JavascriptInterface
    fun hide() {
        main.post {
            view.visibility = View.GONE
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    @JavascriptInterface
    fun markers(json: String) {
        main.post {
            try {
                val a = JSONArray(json)
                view.setBreakpoints(HashSet<Int>().apply { for (i in 0 until a.length()) add(a.getInt(i)) })
            } catch (t: Throwable) { Log.e(TAG, "markers falhou", t) }
        }
    }

    @JavascriptInterface
    fun dbgLine(line: Int) { main.post { view.setDebugLine(line) } }

    @JavascriptInterface
    fun undoRedo(dir: Int) { main.post { if (dir < 0) view.undoStep() else view.redoStep() } }

    // ───────────── Kotlin -> JS ─────────────

    private fun js(code: String) { webView.evaluateJavascript(code, null) }

    /** Eventos agrupados: vários toques seguidos geram um só aviso ao JS. */
    private fun schedule(kind: String, delayMs: Long) {
        pending[kind]?.let { main.removeCallbacks(it) }
        val r = Runnable {
            pending.remove(kind)
            js("window.__cpNE&&window.__cpNE.ev('$kind')")
        }
        pending[kind] = r
        main.postDelayed(r, delayMs)
    }

    override fun onUserEdit() = schedule("input", 150L)
    override fun onSelectionMoved() = schedule("sel", 120L)
    override fun onFocus(focused: Boolean) = js("window.__cpNE&&window.__cpNE.ev('${if (focused) "focus" else "blur"}')")
    override fun onGutterTap(line: Int) = js("window.__cpNEbp&&window.__cpNEbp.toggle($line)")
    override fun onRunShortcut() = js("window.__cpk&&window.__cpk.k(-2,0)")

    // ───────────── auxiliares ─────────────

    /** Executa na thread de UI e espera (as propriedades do textarea são síncronas no JS). */
    private fun <T> onUi(default: T, f: () -> T): T {
        if (Looper.myLooper() === Looper.getMainLooper()) return f()
        val box = arrayOfNulls<Any?>(1)
        val latch = CountDownLatch(1)
        main.post {
            try { box[0] = f() } finally { latch.countDown() }
        }
        if (!latch.await(2, TimeUnit.SECONDS)) return default
        @Suppress("UNCHECKED_CAST")
        return (box[0] as T?) ?: default
    }

    private fun buildHighlighter(rules: JSONObject): Highlighter {
        val out = LinkedHashMap<String, List<HlRule>>()
        for (lang in rules.keys()) {
            val arr = rules.getJSONArray(lang)
            val list = ArrayList<HlRule>()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                try {
                    list.add(HlRule(r.getString("t"), Pattern.compile(r.getString("s")), r.optInt("o", 0)))
                } catch (t: Throwable) {
                    Log.w(TAG, "regra de realce ignorada ($lang/${r.optString("t")}): ${t.message}")
                }
            }
            out[lang] = list
        }
        return Highlighter(out)
    }

    private fun parseTheme(o: JSONObject): EditorTheme {
        val def = EditorTheme()
        fun c(k: String, dflt: Int) = if (o.has(k)) o.getLong(k).toInt() else dflt
        fun f(k: String, dflt: Float) = if (o.has(k)) o.getDouble(k).toFloat() else dflt
        val toks = HashMap<String, Int>()
        o.optJSONObject("tok")?.let { t -> for (k in t.keys()) toks[k] = t.getLong(k).toInt() }
        return EditorTheme(
            fg = c("fg", def.fg), bg = c("bg", def.bg), selection = c("sel", def.selection), cursor = c("cur", def.cursor),
            tokens = toks,
            gutterBg = c("gBg", def.gutterBg), gutterFg = c("gFg", def.gutterFg), gutterBorder = c("gBd", def.gutterBorder),
            gutterWidthDp = f("gW", def.gutterWidthDp), fontDp = f("font", def.fontDp), lineHeightDp = f("lh", def.lineHeightDp),
            padLeftDp = f("padL", def.padLeftDp), padTopDp = f("padT", def.padTopDp),
            bpColor = c("bp", def.bpColor), dbgBg = c("dbgBg", def.dbgBg), dbgFg = c("dbgFg", def.dbgFg),
        )
    }

    private companion object {
        const val TAG = "CodePadEditor"
    }
}
