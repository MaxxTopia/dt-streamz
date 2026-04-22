package com.dt.streamz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.dt.streamz.ui.DtApp
import com.dt.streamz.ui.theme.DtTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DtTheme {
                DtApp()
            }
        }
    }
}
