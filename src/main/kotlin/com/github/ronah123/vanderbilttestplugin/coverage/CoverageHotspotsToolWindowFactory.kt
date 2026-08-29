package com.github.ronah123.vanderbilttestplugin.coverage

import com.github.ronah123.vanderbilttestplugin.actions.AnalyzeCoverageAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class CoverageHotspotsToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        ensureContent(project, toolWindow)

        // Tool-window factories are initialized lazily on first activation. Run
        // the same workflow as Tools -> TestCompass after IntelliJ finishes
        // making the window visible. The launch gate prevents a duplicate run
        // when the Tools action itself caused the window to be initialized.
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                AnalyzeCoverageAction().analyze(project, initialActivationOnly = true)
            }
        }
    }

    companion object {
        const val ID = "TestCompass"

        /**
         * Return the visible panel, recreating its content if IntelliJ or an older
         * plugin version left the tool window initialized without any tabs.
         * Must be called on the EDT.
         */
        fun ensureContent(project: Project, toolWindow: ToolWindow): CoverageHotspotsPanel {
            val service = project.getService(CoverageHotspotsService::class.java)
            val registeredPanel = service.panel
            val registeredContent = registeredPanel?.let { panel ->
                toolWindow.contentManager.contents.firstOrNull { it.component === panel }
            }
            if (registeredPanel != null && registeredContent != null) {
                return registeredPanel
            }

            val panel = CoverageHotspotsPanel(project)
            val content = ContentFactory.getInstance().createContent(panel, "", false).apply {
                isCloseable = false
            }
            toolWindow.contentManager.addContent(content)
            service.panel = panel
            return panel
        }
    }
}
