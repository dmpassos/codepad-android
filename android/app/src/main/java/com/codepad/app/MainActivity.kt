package com.codepad.app

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // plugins locais têm de ser registados ANTES do super.onCreate
        registerPlugin(LocalFolderPlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
