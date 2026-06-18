package com.mirrorx.app

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mirrorx.app.network.MirrorWebSocket
import com.mirrorx.app.ui.theme.MirrorXTheme

class HermesActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // Immersive sticky: hide nav bar / status bar, swipe to reveal
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val initialIp = intent.getStringExtra(EXTRA_IP)
            ?: getSharedPreferences("mirrorx_prefs", Context.MODE_PRIVATE)
                .getString("last_ip", "192.168.100.11")
            ?: "192.168.100.11"

        setContent {
            MirrorXTheme {
                val client = remember { MirrorWebSocket() }
                var sensitivity by remember { mutableStateOf(1.5f) }
                var ip by remember { mutableStateOf(initialIp) }

                TouchpadWithConnection(
                    client = client,
                    ip = ip,
                    onIpChange = { ip = it },
                    sensitivity = sensitivity,
                    onSensitivityChange = { sensitivity = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        const val EXTRA_IP = "extra_ip"
    }
}
