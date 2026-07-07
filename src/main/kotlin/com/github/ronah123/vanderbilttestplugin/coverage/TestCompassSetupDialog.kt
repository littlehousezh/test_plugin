package com.github.ronah123.vanderbilttestplugin.coverage

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

class TestCompassSetupDialog(
    project: Project?,
    private val settings: CoverageSettings
) : DialogWrapper(project, true) {

    private val studentIdField = JBTextField(settings.getStudentId())
    private val tokenField = JBPasswordField().apply {
        text = settings.getBearerToken()
    }

    init {
        title = "TestCompass Setup"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Student ID:", studentIdField)
            .addLabeledComponent("Amplify token:", tokenField)
            .panel
    }

    override fun doOKAction() {
        settings.setStudentId(studentIdField.text)
        settings.setBearerToken(String(tokenField.password))
        super.doOKAction()
    }
}
