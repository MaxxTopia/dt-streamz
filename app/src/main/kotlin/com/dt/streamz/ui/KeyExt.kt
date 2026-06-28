package com.dt.streamz.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput

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

/**
 * Adds mouse / air-mouse / touch tap support to a card that's clicked via a
 * TV `Surface(onClick = …)`. tv-material's clickable Surface only fires on the
 * D-pad CENTER key — a pointer (the box's air-mouse cursor) click never
 * triggers it, so those cards feel dead in mouse-cursor mode. [detectTapGestures]
 * fires on a POINTER tap only (never on key events), so this composes with the
 * Surface's own D-pad handling without double-firing the click. Apply it to the
 * card's outer container (the Surface ignores — doesn't consume — the pointer
 * tap, so it bubbles up to here).
 */
fun Modifier.pointerClickable(onClick: () -> Unit): Modifier =
    this.pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) }
