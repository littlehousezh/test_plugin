package com.github.ronah123.vanderbilttestplugin.coverage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

class CoverageSettingsConfigurable : Configurable {
    private var tokenField: JBPasswordField? = null

    override fun getDisplayName(): String = "VandyTest AI"

    override fun createComponent(): JComponent {
        tokenField = JBPasswordField()

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Amplify bearer token:", tokenField as JComponent)
            .addComponentFillVertically(javax.swing.JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val settings = service()
        val current = String(tokenField?.password ?: CharArray(0)).trim()
        return current != settings.getBearerToken()
    }

    override fun apply() {
        val settings = service()
        val current = String(tokenField?.password ?: CharArray(0)).trim()
        settings.setBearerToken(current)
    }

    override fun reset() {
        val settings = service()
        tokenField?.text = settings.getBearerToken()
    }

    override fun disposeUIResources() {
        tokenField = null
    }

    private fun service(): CoverageSettings = ApplicationManager.getApplication().getService(CoverageSettings::class.java)
}
