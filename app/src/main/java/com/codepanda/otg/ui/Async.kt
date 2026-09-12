package com.codepanda.otg.ui

/**
 * A four-state async wrapper used by the feature ViewModels.
 *
 * [Idle] exists so a screen can tell "nothing has been requested yet" apart from
 * "a request is in flight". Without it, a load that never completed left the UI
 * pinned on [Loading] with no way to know it should retry — which is exactly how
 * a switched-away-from screen used to come back spinning forever.
 */
sealed interface Async<out T> {
    data object Idle : Async<Nothing>
    data object Loading : Async<Nothing>
    data class Success<T>(val data: T) : Async<T>
    data class Failure(val message: String) : Async<Nothing>

    val dataOrNull: T? get() = (this as? Success)?.data
    val isBusy: Boolean get() = this is Loading
}
