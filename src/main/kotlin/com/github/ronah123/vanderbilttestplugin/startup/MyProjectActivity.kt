package com.github.ronah123.vanderbilttestplugin.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.github.ronah123.vanderbilttestplugin.coverage.AIInteractionLoggerService

class MyProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        project.getService(AIInteractionLoggerService::class.java)?.requestStudentIdIfNeeded()
    }
}
