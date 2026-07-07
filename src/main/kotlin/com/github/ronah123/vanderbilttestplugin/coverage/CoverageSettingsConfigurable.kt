package com.github.ronah123.vanderbilttestplugin.coverage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

class CoverageSettingsConfigurable : Configurable {
    private var studentIdField: JBTextField? = null
    private var tokenField: JBPasswordField? = null

    override fun getDisplayName(): String = "TestCompass"

    override fun createComponent(): JComponent {
        studentIdField = JBTextField()
        tokenField = JBPasswordField()

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Student ID:", studentIdField as JComponent)
            .addLabeledComponent("Amplify token:", tokenField as JComponent)
            .addComponentFillVertically(javax.swing.JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val settings = service()
        val studentId = studentIdField?.text.orEmpty().trim()
        val current = String(tokenField?.password ?: CharArray(0)).trim()
        return studentId != settings.getStudentId() || current != settings.getBearerToken()
    }

    override fun apply() {
        val settings = service()
        settings.setStudentId(studentIdField?.text.orEmpty())
        val current = String(tokenField?.password ?: CharArray(0)).trim()
        settings.setBearerToken(current)
    }

    override fun reset() {
        val settings = service()
        studentIdField?.text = settings.getStudentId()
        tokenField?.text = settings.getBearerToken()
    }

    override fun disposeUIResources() {
        studentIdField = null
        tokenField = null
    }

    private fun service(): CoverageSettings = ApplicationManager.getApplication().getService(CoverageSettings::class.java)
}
