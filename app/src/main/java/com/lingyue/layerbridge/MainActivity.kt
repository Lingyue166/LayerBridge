package com.lingyue.layerbridge

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.lingyue.layerbridge.ui.LayerBridgeApp
import com.lingyue.layerbridge.ui.LocaleHelper
import com.lingyue.layerbridge.ui.theme.LayerBridgeTheme

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LayerBridgeTheme {
                LayerBridgeApp()
            }
        }
    }
}
