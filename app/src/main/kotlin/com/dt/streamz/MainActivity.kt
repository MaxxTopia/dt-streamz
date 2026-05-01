package com.dt.streamz

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.dt.streamz.ui.DtApp
import com.dt.streamz.ui.brand.SplashScreen
import com.dt.streamz.ui.theme.DtTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DtTheme {
                val ctx = LocalContext.current
                val prefs = remember {
                    ctx.getSharedPreferences(SPLASH_PREFS, Context.MODE_PRIVATE)
                }
                // Splash plays once *per version*. Sideload "Update" preserves
                // SharedPreferences, so a single boolean would mean every
                // future release's splash never shows. Keying the flag on
                // VERSION_NAME makes each new tagged release replay the
                // splash once, then go quiet for that version.
                val seenKey = remember { "$KEY_SPLASH_SEEN_PREFIX${BuildConfig.VERSION_NAME}" }
                var splashDone by remember {
                    mutableStateOf(prefs.getBoolean(seenKey, false))
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    DtApp()
                    AnimatedVisibility(
                        visible = !splashDone,
                        enter = fadeIn(tween(0)),
                        exit = fadeOut(tween(400)),
                    ) {
                        SplashScreen(
                            onFinished = {
                                if (!splashDone) {
                                    splashDone = true
                                    prefs.edit().putBoolean(seenKey, true).apply()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val SPLASH_PREFS = "ui"
        private const val KEY_SPLASH_SEEN_PREFIX = "splash_seen_v"
    }
}
