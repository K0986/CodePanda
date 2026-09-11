package com.codepanda.otg

import android.app.Application
import com.codepanda.otg.core.AppGraph
import com.codepanda.otg.core.session.ConnectionState
import com.codepanda.otg.core.session.SessionManager
import com.codepanda.otg.core.session.SessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class CodePandaApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)

        // Keep the foreground service in lockstep with the connection lifecycle:
        // running while a device is attached, gone otherwise.
        SessionManager.state
            .onEach { state ->
                when (state) {
                    is ConnectionState.Connected -> SessionService.start(this)
                    is ConnectionState.Disconnected,
                    is ConnectionState.Failed -> SessionService.stop(this)
                    else -> Unit
                }
            }
            .launchIn(scope)
    }
}
