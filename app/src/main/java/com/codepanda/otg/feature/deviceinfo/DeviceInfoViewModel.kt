package com.codepanda.otg.feature.deviceinfo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codepanda.otg.core.session.SessionManager
import com.codepanda.otg.ui.Async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceInfoViewModel : ViewModel() {

    val info: StateFlow<Async<DeviceInfo>> = SessionManager.session
        .flatMapLatest { it?.deviceInfoStore?.state ?: flowOf(Async.Idle) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Async.Idle)

    init {
        viewModelScope.launch {
            SessionManager.session.collect { it?.deviceInfoStore?.ensureLoaded() }
        }
    }

    fun refresh() {
        SessionManager.current?.deviceInfoStore?.refresh()
    }
}
