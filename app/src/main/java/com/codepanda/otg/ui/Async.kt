package com.codepanda.otg.ui

/** A tiny three-state async wrapper used by the feature ViewModels. */
sealed interface Async<out T> {
    data object Loading : Async<Nothing>
    data class Success<T>(val data: T) : Async<T>
    data class Failure(val message: String) : Async<Nothing>
}
