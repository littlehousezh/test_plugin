package com.github.ronah123.vanderbilttestplugin.coverage

import com.github.ronah123.vanderbilttestplugin.actions.MethodHit
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

@Service(Service.Level.PROJECT)
class CoverageHotspotsService(private val project: Project) {
    var panel: CoverageHotspotsPanel? = null

    private var analysisHasBeenRequested = false

    /**
     * Claim an analysis request. A direct Tools-menu request is always allowed,
     * while the tool-window's automatic first-use request runs only if another
     * entry point has not already started the analysis.
     */
    @Synchronized
    fun beginAnalysis(initialActivationOnly: Boolean): Boolean {
        if (initialActivationOnly && analysisHasBeenRequested) return false
        analysisHasBeenRequested = true
        return true
    }

    fun showInToolWindow(rows: List<MethodHit>) {
        // Everything UI-related must run on EDT
        ApplicationManager.getApplication().invokeLater {
            val tw = ToolWindowManager.getInstance(project)
                .getToolWindow(CoverageHotspotsToolWindowFactory.ID) ?: return@invokeLater

            tw.show {
                CoverageHotspotsToolWindowFactory.ensureContent(project, tw).setData(rows)
            }
        }
    }
}
