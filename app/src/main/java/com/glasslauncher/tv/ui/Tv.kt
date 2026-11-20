package com.glasslauncher.tv.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

val KeyEvent.isSelect: Boolean
    get() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * Remote-control press handling without any default focus/ripple effect:
 * short press on OK = [onClick]; holding OK (or pressing Menu) = [onLong].
 * [onKey] sees every key first and can swallow it (used by the "move app" mode).
 * Put onFocusChanged / focusRequester BEFORE this modifier in the chain.
 */
@Composable
fun Modifier.tvPress(
    onLong: (() -> Unit)? = null,
    onKey: ((KeyEvent) -> Boolean)? = null,
    onClick: () -> Unit,
): Modifier {
    var longFired by remember { mutableStateOf(false) }
    var downSeen by remember { mutableStateOf(false) }
    return this.onFocusChanged { if (!it.isFocused) { downSeen = false; longFired = false } }.onKeyEvent { ev ->
        if (onKey != null && onKey(ev)) return@onKeyEvent true
        when {
            ev.isSelect -> when (ev.type) {
                KeyEventType.KeyDown -> {
                    if (ev.nativeKeyEvent.repeatCount == 0) { longFired = false; downSeen = true }
                    if (downSeen && onLong != null && ev.nativeKeyEvent.repeatCount >= 1 && !longFired) {
                        longFired = true; onLong()
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    if (downSeen && !longFired) onClick()
                    longFired = false; downSeen = false
                    true
                }
                else -> false
            }
            ev.key == Key.Menu && onLong != null -> {
                if (ev.type == KeyEventType.KeyDown) onLong()
                true
            }
            else -> false
        }
    }.focusable()
}

/**
 * The focus requester kept for [key] in a remembered map: made once per key, then reused on every composition
 * (a requester made in composition without being kept would be a new one each time).
 */
fun <K> MutableMap<K, androidx.compose.ui.focus.FocusRequester>.requester(key: K): androidx.compose.ui.focus.FocusRequester =
    getOrPut(key) { androidx.compose.ui.focus.FocusRequester() }
