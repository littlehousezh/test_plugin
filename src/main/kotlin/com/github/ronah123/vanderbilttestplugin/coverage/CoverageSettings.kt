package com.github.ronah123.vanderbilttestplugin.coverage

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "TestCompassSettings",
    storages = [Storage("testcompass.xml")]
)
@Service(Service.Level.APP)
class CoverageSettings : PersistentStateComponent<CoverageSettings.State> {
    data class State(
        var studentId: String = "",
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

    fun getStudentId(): String = state.studentId.trim()

    fun setStudentId(studentId: String) {
        state.studentId = studentId.trim()
    }

    fun isConfigured(): Boolean = getStudentId().isNotBlank() && getBearerToken().isNotBlank()
}
