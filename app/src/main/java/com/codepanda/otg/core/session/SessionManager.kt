package com.codepanda.otg.core.session

import com.codepanda.otg.core.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide holder for the one active [DeviceSession].
 *
 * The app only ever talks to a single OTG device at a time, so a singleton is
 * the pragmatic choice. ViewModels observe [state] / [session] and read the
 * session's stores to render. [UsbAdbManager] is the only writer.
 */
object SessionManager {

    private const val TAG = "SessionManager"

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _session = MutableStateFlow<DeviceSession?>(null)

    /**
     * The live session, as a flow so screens can react to it appearing and
     * disappearing rather than reading a nullable field at an arbitrary moment.
     */
    val session: StateFlow<DeviceSession?> = _session.asStateFlow()

    val current: DeviceSession? get() = _session.value

    internal fun setConnecting(deviceName: String) {
        AppLog.i(TAG, "Connecting to $deviceName")
        _state.value = ConnectionState.Connecting(deviceName)
    }

    internal fun setConnected(session: DeviceSession, deviceName: String, banner: String) {
        closeSession("replaced by a new connection")
        AppLog.i(TAG, "Session established with $deviceName")

        // A cable that is yanked, or a device that reboots, must not leave every
        // screen spinning: the connection tells us, and we tell the user.
        session.connection.onDisconnected = { error ->
            AppLog.e(TAG, "Connection to $deviceName lost", error)
            closeSession("connection lost")
            _state.value = ConnectionState.Failed(
                error?.message?.let { "Connection lost: $it" }
                    ?: "The device disconnected. Check the cable and reconnect.",
            )
        }

        _session.value = session
        _state.value = ConnectionState.Connected(deviceName, banner)
    }

    internal fun setFailed(message: String) {
        AppLog.e(TAG, "Connection failed: $message")
        closeSession("connection failed")
        _state.value = ConnectionState.Failed(message)
    }

    fun disconnect() {
        AppLog.i(TAG, "Disconnect requested by the user")
        closeSession("disconnected by user")
        _state.value = ConnectionState.Disconnected
    }

    private fun closeSession(reason: String) {
        _session.value?.let { runCatching { it.close(reason) } }
        _session.value = null
    }
}
