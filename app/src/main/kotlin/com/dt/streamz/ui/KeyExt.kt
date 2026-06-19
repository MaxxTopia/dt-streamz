package com.dt.streamz.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Fires [action] on a D-pad MENU (or F10) key-up while this element is
 * [focused]. The contextual-action gesture used across the app's cards —
 * remove from a row, toggle favorite, cycle a mode. Extracted so the
 * identical handler isn't copy-pasted into every card.
 */
fun Modifier.onMenuKeyUp(focused: Boolean, action: () -> Unit): Modifier =
    this.onKeyEvent { event ->
        val menuKey = event.key == Key.Menu || event.key == Key.F10
        if (focused && menuKey && event.type == KeyEventType.KeyUp) {
            action()
            true
        } else {
            false
        }
    }
