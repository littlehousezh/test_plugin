package com.github.ronah123.vanderbilttestplugin.coverage

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class CoverageHotspotsToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CoverageHotspotsPanel(project)
        // register the panel in the project service so the action can push data into it
        val svc = project.getService(CoverageHotspotsService::class.java)
        svc.panel = panel

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.removeAllContents(true)
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        const val ID = "Coverage Hotspots"
    }
}
