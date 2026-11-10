package com.codepad.app

import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {

    private var nativeKeys: NativeKeys? = null
    private var nativeEditor: NativeEditor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // plugins locais têm de ser registados ANTES do super.onCreate
        registerPlugin(LocalFolderPlugin::class.java)
        super.onCreate(savedInstanceState)
        setupNativeUi()
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
