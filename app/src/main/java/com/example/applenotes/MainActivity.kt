package com.example.applenotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.applenotes.theme.MyApplicationTheme
import com.example.applenotes.ui.AppleNotesApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run BEFORE super.onCreate to back-port the Android-12+ splash
        // API to older releases. Theme is configured in res/values/themes.xml
        // (Theme.App.Starting) and switches to Theme.MyApplication once the
        // first frame of content is ready.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppleNotesApp()
                }
            }
        }
    }
}
