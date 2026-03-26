package com.suxsem.havoicecontrol

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.suxsem.havoicecontrol.ui.theme.HAVoiceControlTheme

class ThirdPartyNoticesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HAVoiceControlTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AndroidView(modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                        factory = { context ->
                            android.webkit.WebView(context).apply {
                                loadUrl("file:///android_asset/THIRD_PARTY_NOTICES.html")
                            }
                        }
                    )
                }
            }
        }
    }
}