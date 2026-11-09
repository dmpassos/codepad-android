package com.codepad.app

import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {

    private var nativeKeys: NativeKeys? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // plugins locais têm de ser registados ANTES do super.onCreate
        registerPlugin(LocalFolderPlugin::class.java)
        super.onCreate(savedInstanceState)
        setupNativeKeys()
    }

    /**
     * Barra de símbolos + joystick nativos. Corre logo a seguir ao super.onCreate (ainda na mesma tarefa da
     * thread de UI, antes de a página fazer commit), para que o window.CPKeys exista quando o JS arrancar.
     * Se algo falhar, o app continua com a barra web de sempre (o JS só usa a nativa se CPKeys existir).
     */
    private fun setupNativeKeys() {
        try {
            val webView = getBridge()?.webView ?: return
            val content = findViewById<FrameLayout>(android.R.id.content) ?: return
            val keys = NativeKeys(this, webView, content)
            webView.addJavascriptInterface(keys, "CPKeys")
            nativeKeys = keys
        } catch (t: Throwable) {
            Log.e("CodePad", "Barra nativa indisponível; a usar a barra web", t)
        }
    }
}
