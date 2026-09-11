package com.codepanda.otg.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codepanda.otg.core.session.ConnectionState
import com.codepanda.otg.core.session.SessionManager
import com.codepanda.otg.ui.connect.ConnectScreen

/**
 * Top of the Compose tree. Switches between the connect flow and the main
 * tabbed UI purely as a function of the session state — there is no manual
 * navigation between "connected" and "disconnected".
 */
@Composable
fun AppRoot() {
    val state by SessionManager.state.collectAsStateWithLifecycle()

    Surface(modifier = Modifier) {
        when (state) {
            is ConnectionState.Connected -> MainScaffold(
                deviceName = (state as ConnectionState.Connected).deviceName,
            )
            else -> ConnectScreen(state = state)
        }
    }
}
