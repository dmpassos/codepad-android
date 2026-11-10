package com.codepad.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import org.json.JSONArray
import java.util.Locale

/**
 * Ponte entre o HTML e a barra nativa.
 *
 * JS -> Kotlin: objeto `window.CPKeys` (setKeys / show / hide). Os métodos chegam numa thread do WebView,
 * por isso passam sempre para a thread de UI antes de mexer em views.
 * Kotlin -> JS: evaluateJavascript direto (sem o JSON/Promise do Capacitor), chamando
 * window.__cpk.k(...) e window.__cpkJoy.start/vel/end(...), definidos no index.html.
 *
 * A barra é desenhada POR CIMA do WebView, colada ao fundo da área de conteúdo (logo acima do teclado, porque o
 * Capacitor já recua a janela pela altura do IME). No HTML, o #cpKeys fica como espaçador invisível com a
 * mesma altura, para o editor não saltar.
 */
class NativeKeys(
    private val activity: Activity,
    private val webView: WebView,
    container: ViewGroup,
) : NativeKeyBar.Host {

    private val bar = NativeKeyBar(activity)
    private val main = Handler(Looper.getMainLooper())

    /** Quando o editor nativo está ativo, teclas e joystick vão direto para ele (sem JavaScript). */
    var editor: NativeEditor? = null
    private var keyList: List<String> = emptyList()
    private var joyNative = false

    init {
        bar.visibility = View.GONE
        bar.host = this
        container.addView(
            bar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
    }

    // ---------- JS -> Kotlin ----------

    @JavascriptInterface
    fun setKeys(json: String) {
        main.post {
            try {
                val arr = JSONArray(json)
                keyList = List(arr.length()) { arr.getString(it) }
                bar.setKeys(keyList)
            } catch (t: Throwable) {
                Log.e(TAG, "setKeys falhou", t)
            }
        }
    }

    @JavascriptInterface
    fun show(totalDp: Double, btnDp: Double, leftDp: Double, rightDp: Double) {
        main.post {
            bar.applyMetrics(totalDp, btnDp, leftDp, rightDp)
            bar.visibility = View.VISIBLE
            bar.bringToFront()
        }
    }

    @JavascriptInterface
    fun hide() {
        main.post { bar.visibility = View.GONE }
    }

    // ---------- Kotlin -> JS (sempre na thread de UI, vindo de eventos de toque) ----------

    private fun js(code: String) {
        webView.evaluateJavascript(code, null)
    }

    override fun onKey(index: Int, upWallMs: Long) {
        val ed = editor
        if (ed != null && ed.isActive && index in keyList.indices) {
            ed.pressKey(keyList[index])          // símbolos, Tab, desfazer/refazer: direto no editor nativo
            return
        }
        js("window.__cpk&&window.__cpk.k($index,$upWallMs)")   // executar / fechar teclado e o caminho web
    }

    override fun onJoyStart() {
        val ed = editor
        joyNative = ed != null && ed.isActive
        if (joyNative) ed?.pointerStart() else js("window.__cpkJoy&&window.__cpkJoy.start()")
    }

    override fun onJoyVelocity(vx: Float, vy: Float) {
        if (joyNative) editor?.pointerVelocity(vx, vy)
        else js(String.format(Locale.US, "window.__cpkJoy&&window.__cpkJoy.vel(%.2f,%.2f)", vx, vy))
    }

    override fun onJoyEnd(tap: Boolean, upWallMs: Long) {
        if (joyNative) editor?.pointerEnd(tap) else js("window.__cpkJoy&&window.__cpkJoy.end($tap)")
        joyNative = false
    }

    private companion object {
        const val TAG = "CodePadKeys"
    }
}
