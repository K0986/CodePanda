package com.codepanda.otg.feature.bloatware

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codepanda.otg.core.session.SessionManager
import com.codepanda.otg.ui.Async
import kotlinx.coroutines.launch

class BloatwareViewModel : ViewModel() {

    var state by mutableStateOf<Async<List<BloatApp>>>(Async.Loading)
        private set
    var query by mutableStateOf("")
        private set
    var knownOnly by mutableStateOf(true)
        private set
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    private val repo get() = SessionManager.session?.bloatware

    init {
        load()
    }

    fun load() {
        val repo = repo ?: run { state = Async.Failure("Not connected"); return }
        viewModelScope.launch {
            state = Async.Loading
            state = try {
                Async.Success(repo.list())
            } catch (t: Throwable) {
                Async.Failure(t.message ?: "Failed to enumerate system apps")
            }
        }
    }

    fun updateQuery(value: String) { query = value }
    fun toggleKnownOnly() { knownOnly = !knownOnly }
    fun consumeMessage() { message = null }

    fun filtered(): List<BloatApp> {
        val list = (state as? Async.Success)?.data ?: return emptyList()
        return list.filter { app ->
            (!knownOnly || app.isKnownBloat) &&
                (query.isBlank() ||
                    app.packageName.contains(query, ignoreCase = true) ||
                    (app.knownName?.contains(query, ignoreCase = true) == true))
        }
    }

    fun disable(app: BloatApp) = runAction { repo?.disableForUser(app.packageName)?.message }
    fun enable(app: BloatApp) = runAction { repo?.enable(app.packageName)?.message }
    fun uninstallForUser(app: BloatApp) = runAction { repo?.uninstallForUser(app.packageName)?.message }
    fun reinstall(app: BloatApp) = runAction { repo?.reinstall(app.packageName)?.message }

    private fun runAction(block: suspend () -> String?) {
        viewModelScope.launch {
            busy = true
            message = try {
                block() ?: "No session"
            } catch (t: Throwable) {
                t.message ?: "Operation failed"
            } finally {
                busy = false
            }
            load()
        }
    }
}
