package com.codepanda.otg.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide holder for the one active [DeviceSession].
 *
 * The app only ever talks to a single OTG device at a time, so a singleton is
 * the pragmatic choice. ViewModels observe [state] and read [session] to issue
 * commands. [UsbAdbManager] is the only writer.
 */
object SessionManager {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    @Volatile
    var session: DeviceSession? = null
        private set

    internal fun setConnecting(deviceName: String) {
        _state.value = ConnectionState.Connecting(deviceName)
    }

    internal fun setConnected(session: DeviceSession, deviceName: String, banner: String) {
        this.session = session
        _state.value = ConnectionState.Connected(deviceName, banner)
    }

    internal fun setFailed(message: String) {
        closeSession()
        _state.value = ConnectionState.Failed(message)
    }

    fun disconnect() {
        closeSession()
        _state.value = ConnectionState.Disconnected
    }

    private fun closeSession() {
        session?.let { runCatching { it.close() } }
        session = null
    }
}
