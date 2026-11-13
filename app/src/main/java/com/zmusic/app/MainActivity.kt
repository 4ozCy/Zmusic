package com.zmusic.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.zmusic.app.ui.ZmusicRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Follows the system light/dark setting; system bar icons adapt automatically.
        enableEdgeToEdge()
        val container = (application as ZmusicApp).container
        // The theme is applied inside ZmusicRoot so it can pick up the current song's seed colour.
        setContent { ZmusicRoot(container) }
    }
}
