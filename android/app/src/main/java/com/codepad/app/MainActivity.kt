package com.codepad.app

import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {

    private var nativeKeys: NativeKeys? = null
    private var nativeEditor: NativeEditor? = null
    private var splash: NativeSplash? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // plugins locais têm de ser registados ANTES do super.onCreate
        registerPlugin(LocalFolderPlugin::class.java)
        super.onCreate(savedInstanceState)
        setupSplash()
        setupNativeUi()
    }

    override fun onDestroy() {
        splash?.release()
        super.onDestroy()
    }

    /**
     * Página de espera nativa (imagem + círculo de carregamento em Kotlin), por cima de tudo até o JS avisar que a
     * interface está pronta (window.CPSplash.ready(), no native-bridge.js). Vem antes do resto, em bloco próprio:
     * se a UI nativa falhar, a página de espera continua a sair sozinha (tempo máximo de 8 s).
     */
    private fun setupSplash() {
        try {
            val s = NativeSplash(this)
            s.show()
            getBridge()?.webView?.addJavascriptInterface(s, "CPSplash")
            splash = s
        } catch (t: Throwable) {
            Log.e("CodePad", "Página de espera indisponível", t)
        }
    }

    /**
     * Barra de símbolos + joystick (e o editor nativo, opcional) por cima do WebView. Corre logo a seguir ao
     * super.onCreate (ainda na mesma tarefa da thread de UI, antes de a página fazer commit), para que window.CPKeys
     * e window.CPEditor existam quando o JS arrancar. Se algo falhar, o app continua com o HTML de sempre.
     */
    private fun setupNativeUi() {
        try {
            val webView = getBridge()?.webView ?: return
            val content = findViewById<FrameLayout>(android.R.id.content) ?: return
            val editor = NativeEditor(this, webView, content)        // primeiro: a barra de teclas fica por cima
            val keys = NativeKeys(this, webView, content)
            keys.editor = editor
            webView.addJavascriptInterface(editor, "CPEditor")
            webView.addJavascriptInterface(keys, "CPKeys")
            nativeEditor = editor
            nativeKeys = keys
        } catch (t: Throwable) {
            Log.e("CodePad", "UI nativa indisponível; a usar o HTML de sempre", t)
        }
    }
}
