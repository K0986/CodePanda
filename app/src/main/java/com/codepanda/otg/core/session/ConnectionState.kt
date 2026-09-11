package com.codepanda.otg.core.session

/**
 * High-level state of the single active USB device session, surfaced to the UI.
 */
sealed interface ConnectionState {

    /** No device attached, or the user disconnected. */
    data object Disconnected : ConnectionState

    /** A device is attached but we are still negotiating ADB / awaiting the prompt. */
    data class Connecting(val deviceName: String) : ConnectionState

    /** Fully authenticated and ready. */
    data class Connected(
        val deviceName: String,
        val banner: String,
    ) : ConnectionState

    /** Something went wrong; [message] is user-presentable. */
    data class Failed(val message: String) : ConnectionState
}
