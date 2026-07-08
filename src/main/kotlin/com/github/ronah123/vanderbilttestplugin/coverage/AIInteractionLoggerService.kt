package com.github.ronah123.vanderbilttestplugin.coverage

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationManager.getApplication
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service(Service.Level.PROJECT)
class AIInteractionLoggerService(private val project: Project) {

    fun requestSetupIfNeeded() {
        val app = ApplicationManager.getApplication()
        if (app.isUnitTestMode || app.isHeadlessEnvironment) return
        app.invokeLater {
            val settings = getApplication().getService(CoverageSettings::class.java)
            if (!settings.isConfigured()) {
                TestCompassSetupDialog(project, settings).show()
            }
        }
    }

    fun logAiInteraction(prompt: String, response: String, modelId: String, baseUrl: String, error: Throwable?) {
        val basePath = project.basePath ?: return
        val dir = Paths.get(basePath, ".testcompass", "logs")
        Files.createDirectories(dir)

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val logFile = uniqueFile(dir, "$timestamp.log")

        val sb = StringBuilder()
        sb.append("timestamp=").append(timestamp).append('\n')
        sb.append("model=").append(modelId).append('\n')
        sb.append("baseUrl=").append(baseUrl).append('\n')
        if (error != null) {
            sb.append("error=").append(error::class.java.name).append(": ").append(error.message).append('\n')
        }
        sb.append('\n')
        sb.append("=== PROMPT ===\n")
        sb.append(prompt).append('\n')
        sb.append("\n=== RESPONSE ===\n")
        sb.append(response).append('\n')

        Files.write(logFile, sb.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun uniqueFile(dir: Path, fileName: String): Path {
        var candidate = dir.resolve(fileName)
        if (!Files.exists(candidate)) return candidate
        val base = fileName.removeSuffix(".log")
        var i = 1
        while (true) {
            candidate = dir.resolve("${base}_$i.log")
            if (!Files.exists(candidate)) return candidate
            i++
        }
    }
}
