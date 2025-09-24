package com.github.ronah123.vanderbilttestplugin.coverage

import com.github.ronah123.vanderbilttestplugin.actions.MethodHit
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

@Service(Service.Level.PROJECT)
class CoverageHotspotsService(private val project: Project) {
    var panel: CoverageHotspotsPanel? = null

    fun showInToolWindow(rows: List<MethodHit>) {
        // Everything UI-related must run on EDT
        ApplicationManager.getApplication().invokeLater {
            val tw = ToolWindowManager.getInstance(project)
                .getToolWindow(CoverageHotspotsToolWindowFactory.ID) ?: return@invokeLater

            // Showing the tool window triggers factory.createToolWindowContent (if not created yet),
            // which sets `panel` on this service. Then we can push data.
            tw.show {
                panel?.setData(rows)
            }
        }
    }
}
