package com.github.ronah123.vanderbilttestplugin.coverage

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "VandyTestSettings",
    storages = [Storage("vandytest.xml")]
)
@Service(Service.Level.APP)
class CoverageSettings : PersistentStateComponent<CoverageSettings.State> {
    data class State(
        var amplifyBearer: String = ""
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun getBearerToken(): String = state.amplifyBearer.trim()

    fun setBearerToken(token: String) {
        state.amplifyBearer = token.trim()
    }
}
