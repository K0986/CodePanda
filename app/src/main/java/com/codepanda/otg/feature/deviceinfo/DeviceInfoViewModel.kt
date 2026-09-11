package com.codepanda.otg.feature.deviceinfo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codepanda.otg.core.session.SessionManager
import com.codepanda.otg.ui.Async
import kotlinx.coroutines.launch

class DeviceInfoViewModel : ViewModel() {

    var state by mutableStateOf<Async<DeviceInfo>>(Async.Loading)
        private set

    init {
        load()
    }

    fun load() {
        val repo = SessionManager.session?.deviceInfo ?: run {
            state = Async.Failure("Not connected")
            return
        }
        viewModelScope.launch {
            state = Async.Loading
            state = try {
                Async.Success(repo.load())
            } catch (t: Throwable) {
                Async.Failure(t.message ?: "Failed to read device info")
            }
        }
    }
}
