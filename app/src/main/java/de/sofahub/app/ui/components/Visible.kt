package de.sofahub.app.ui.components

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProduceStateScope
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/**
 * Wie [produceState], aber [block] läuft nur, solange Sofa Hub sichtbar ist: Läuft Netflix & Co. im Vordergrund,
 * pausieren Abrufschleifen (Kamera, Verlauf, Termine, Animationen) und starten beim Zurückkehren neu.
 * (Den Wert setzt der weitergereichte [block] – das erkennt die Lint-Prüfung nicht, daher die Unterdrückung.)
 */
@SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
fun <T> produceWhileVisible(initial: T, vararg keys: Any?, block: suspend ProduceStateScope<T>.() -> Unit): State<T> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(initial, lifecycle, *keys) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { this@produceState.block() }
    }
}
