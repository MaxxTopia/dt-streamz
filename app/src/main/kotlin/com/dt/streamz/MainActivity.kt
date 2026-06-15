package com.dt.streamz

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
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

    private val networkMonitor get() = (application as DtApplication).networkMonitor

    // Run the latency-probe loop only while the app is in the foreground.
    override fun onStart() {
        super.onStart()
        networkMonitor.start()
    }

    override fun onStop() {
        super.onStop()
        networkMonitor.stop()
    }

    private var openSoundPool: SoundPool? = null
    private var openSoundId: Int = 0
    private var openSoundLoaded = false
    // Set when the splash asks to play but the sample hasn't finished
    // decoding yet — onLoadComplete fires the play then.
    private var openSoundPending = false

    /**
     * Preload (but DON'T play) the app-open sound. SoundPool decodes the
     * ~2s ogg off the main thread; we kick this off in onCreate so the
     * sample is ready by the time the splash's first frame triggers it.
     * Decoupling load from play is what lets the guitar start in lockstep
     * with the tile animation instead of whenever the async decode lands.
     */
    private fun preloadOpenSound() {
        runCatching {
            val sp = SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
            openSoundPool = sp
            sp.setOnLoadCompleteListener { pool, id, status ->
                if (status == 0) {
                    openSoundLoaded = true
                    if (openSoundPending) {
                        openSoundPending = false
                        pool.play(id, 0.85f, 0.85f, 1, 0, 1f)
                    }
                }
            }
            openSoundId = sp.load(this, R.raw.app_open, 1)
        }
    }

    /**
     * Play the preloaded open sound NOW (called from the splash's first
     * frame so audio + animation begin together). If the decode hasn't
     * finished, mark it pending so onLoadComplete plays it the instant
     * it's ready. Self-releases after the sample's full length.
     */
    private fun triggerOpenSound() {
        val sp = openSoundPool ?: return
        if (openSoundLoaded) sp.play(openSoundId, 0.85f, 0.85f, 1, 0, 1f)
        else openSoundPending = true
        window.decorView.postDelayed({
            openSoundPool?.release(); openSoundPool = null
        }, 5000)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App-open sound: a soft guitar arpeggio on every cold launch
        // (savedInstanceState == null skips config-change recreations).
        // Preloaded here; WHEN it fires depends on whether the splash will
        // show this launch:
        //   - splash shows (once per version) -> the splash's first frame
        //     triggers it (onBegin), so the guitar rides the tile animation.
        //   - no splash -> play it now as a plain open chime.
        // Decoupling load from play is what lets the two start in lockstep.
        if (savedInstanceState == null) {
            preloadOpenSound()
            val willShowSplash = run {
                val prefs = getSharedPreferences(SPLASH_PREFS, Context.MODE_PRIVATE)
                !prefs.getBoolean("$KEY_SPLASH_SEEN_PREFIX${BuildConfig.VERSION_NAME}", false)
            }
            if (!willShowSplash) triggerOpenSound()
        }
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
                            onBegin = { triggerOpenSound() },
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
