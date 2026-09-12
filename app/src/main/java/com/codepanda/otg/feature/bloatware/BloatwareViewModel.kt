package com.codepanda.otg.feature.bloatware

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.codepanda.otg.core.session.SessionManager
import com.codepanda.otg.ui.Async
import com.codepanda.otg.ui.SessionViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class BloatwareViewModel : SessionViewModel() {

    val apps: StateFlow<Async<List<BloatApp>>> = SessionManager.session
        .flatMapLatest { it?.bloatwareStore?.state ?: flowOf(Async.Idle) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Async.Idle)

    var query by mutableStateOf("")
        private set
    var knownOnly by mutableStateOf(true)
        private set

    init {
        viewModelScope.launch {
            SessionManager.session.collect { it?.bloatwareStore?.ensureLoaded() }
        }
    }

    fun refresh() {
        session?.bloatwareStore?.refresh()
    }

    fun updateQuery(value: String) {
        query = value
    }

    fun toggleKnownOnly() {
        knownOnly = !knownOnly
    }

    fun filtered(): List<BloatApp> {
        val list = apps.value.dataOrNull ?: return emptyList()
        return list.filter { app ->
            (!knownOnly || app.isKnownBloat) &&
                (
                    query.isBlank() ||
                        app.packageName.contains(query, ignoreCase = true) ||
                        (app.knownName?.contains(query, ignoreCase = true) == true)
                    )
        }
    }

    fun disable(app: BloatApp) = operate("Disable ${app.packageName}") { session ->
        val result = session.bloatware.disableForUser(app.packageName)
        if (result.isSuccess) {
            session.bloatwareStore.update { list ->
                list.map { if (it.packageName == app.packageName) it.copy(enabled = false) else it }
            }
        }
        describe("Disabling ${app.packageName}", result)
    }

    fun enable(app: BloatApp) = operate("Enable ${app.packageName}") { session ->
        val result = session.bloatware.enable(app.packageName)
        if (result.isSuccess) {
            session.bloatwareStore.update { list ->
                list.map {
                    if (it.packageName == app.packageName) {
                        it.copy(enabled = true, removedForUser = false)
                    } else {
                        it
                    }
                }
            }
        }
        describe("Enabling ${app.packageName}", result)
    }

    fun uninstallForUser(app: BloatApp) = operate("Remove ${app.packageName}") { session ->
        val result = session.bloatware.uninstallForUser(app.packageName)
        if (result.isSuccess) {
            session.bloatwareStore.update { list ->
                list.map {
                    if (it.packageName == app.packageName) {
                        it.copy(removedForUser = true, enabled = false)
                    } else {
                        it
                    }
                }
            }
            // A removed package also disappears from the Apps tab.
            session.packageStore.invalidate()
        }
        describe("Removing ${app.packageName} for this user", result)
    }

    fun reinstall(app: BloatApp) = operate("Restore ${app.packageName}") { session ->
        val result = session.bloatware.reinstall(app.packageName)
        if (result.isSuccess) {
            session.bloatwareStore.update { list ->
                list.map {
                    if (it.packageName == app.packageName) {
                        it.copy(removedForUser = false, enabled = true)
                    } else {
                        it
                    }
                }
            }
            session.packageStore.invalidate()
        }
        describe("Restoring ${app.packageName}", result)
    }
}
