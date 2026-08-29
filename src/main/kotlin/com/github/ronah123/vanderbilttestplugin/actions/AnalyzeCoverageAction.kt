package com.github.ronah123.vanderbilttestplugin.actions

import com.intellij.coverage.CoverageDataManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.Messages
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

class AnalyzeCoverageAction : AnAction("TestCompass") {

    companion object {
        // Set to a positive number to cap the list, or null to show all rows.
        private val TOP_N: Int? = 25
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        analyze(project)
    }

    /**
     * Run the same coverage analysis from either the Tools action or the
     * TestCompass tool-window icon.
     *
     * Tool-window content can also be created as a consequence of the Tools
     * action showing its results. In that case [initialActivationOnly] keeps
     * the factory from starting a duplicate background analysis.
     */
    fun analyze(project: Project, initialActivationOnly: Boolean = false) {
        val hotspotsService = project.getService(
            com.github.ronah123.vanderbilttestplugin.coverage.CoverageHotspotsService::class.java
        )
        if (!hotspotsService.beginAnalysis(initialActivationOnly)) return

        object : Task.Backgroundable(project, "Analyzing IDE Coverage", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Reading current coverage suite…"

                val bundle = CoverageDataManager.getInstance(project).currentSuitesBundle
                if (bundle == null) {
                    info(
                        project,
                        """
                        No active coverage suite.

                        Run All Tests with Coverage, then run TestCompass again.
                        """.trimIndent()
                    )
                    return
                }

                val coverageData: Any = bundle.coverageData ?: run {
                    info(project, "Coverage data not available for this suite type.")
                    return
                }

                indicator.text = "Aggregating per-method coverage…"
                val allMethods = aggregateByMethod(coverageData)

                // Filter to classes that belong to THIS project (exclude libs) and exclude tests
                val projectMethods = filterMethodsToProject(project, allMethods)
                if (projectMethods.isEmpty()) {
                    info(
                        project,
                        "No production (non-test) project classes found in coverage."
                    )
                    return
                }

                // Rank: most missed lines first, then lowest percentage
                val sorted = projectMethods.sortedWith(
                    compareByDescending<MethodHit> { it.missedLines }.thenBy { it.linePct }
                )
                val top = TOP_N?.let { n -> sorted.take(n) } ?: sorted

                // Show in the tool window
                hotspotsService.showInToolWindow(top)
            }
        }.queue()
    }

    private fun info(project: Project, text: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, text, "TestCompass")
        }
    }

    // -------------------------- Project-scope filter --------------------------

    private fun filterMethodsToProject(project: Project, methods: List<MethodHit>): List<MethodHit> {
        return ReadAction.compute<List<MethodHit>, RuntimeException> {
            val scope = GlobalSearchScope.projectScope(project)
            val psi = JavaPsiFacade.getInstance(project)
            val index = ProjectFileIndex.getInstance(project)

            methods.asSequence()
                // Fast name-based guard (cheap)
                .filter { m -> !looksLikeTestFqn(m.classFqn) }
                .filter { m ->
                    val fqn = m.classFqn
                    val psiClass =
                        psi.findClass(fqn, scope)
                            ?: psi.findClass(fqn.replace('$', '.'), scope)
                    if (psiClass == null) {
                        false
                    } else {
                        val vFile = psiClass.containingFile?.virtualFile
                        // keep only project *production* source files
                        vFile != null &&
                                index.isInContent(vFile) &&
                                !index.isInTestSourceContent(vFile)
                    }
                }
                .toList()
        }
    }

    /** Heuristics to identify test classes by name. */
    private fun looksLikeTestFqn(fqn: String): Boolean {
        val simple = fqn.substringAfterLast('.')
        return simple.contains("test", ignoreCase = true) ||
                simple.endsWith("IT", ignoreCase = true) ||
                simple.endsWith("Spec", ignoreCase = true)
    }

    // -------------------------- Pretty report (unused in panel) --------------------------

    @Suppress("unused")
    private fun formatReport(rows: List<MethodHit>): String {
        if (rows.isEmpty()) return "No class/method coverage found in the current suite."

        val header = String.format("%-4s  %-12s  %-6s  %s", "#", "Missed/Total", "%Cov", "Method")
        val body = buildString {
            rows.forEachIndexed { idx, m ->
                val pct = if (m.totalLines == 0) 100.0 else (m.coveredLines * 100.0 / m.totalLines)
                val methodId = "${m.classFqn}#${m.method}".let { s ->
                    if (s.length <= 120) s else s.take(117) + "..."
                }
                appendLine(
                    String.format(
                        "%-4d  %-12s  %6.1f  %s",
                        idx + 1,
                        "${m.missedLines}/${m.totalLines}",
                        pct,
                        methodId
                    )
                )
            }
        }

        return buildString {
            appendLine("Top methods needing tests (by missed lines):")
            appendLine(header)
            appendLine("----  ------------  ------  ------------------------------------------------------------")
            append(body)
            appendLine()
            appendLine("Hint: re-run tests with coverage (IntelliJ runner) to refresh.")
        }
    }

    // -------------------------- Aggregation --------------------------
    // Try a typed path (public API via class lookups), fall back to generic reflection.

    private fun aggregateByMethod(projectDataAny: Any): List<MethodHit> {
        return try {
            aggregateByMethodTypedIfAvailable(projectDataAny)
        } catch (_: Throwable) {
            aggregateByMethodReflective(projectDataAny)
        }
    }

    /**
     * "Typed-if-available" path via public getters (no HashMap$Node reflection).
     * Classes:
     *   com.intellij.rt.coverage.data.ProjectData
     *   com.intellij.rt.coverage.data.ClassData
     *   com.intellij.rt.coverage.data.LineData
     */
    private fun aggregateByMethodTypedIfAvailable(projectDataAny: Any): List<MethodHit> {
        val projectDataClass = Class.forName("com.intellij.rt.coverage.data.ProjectData")
        if (!projectDataClass.isInstance(projectDataAny)) {
            throw IllegalStateException("Not ProjectData")
        }

        val classDataClass = Class.forName("com.intellij.rt.coverage.data.ClassData")
        val lineDataClass = Class.forName("com.intellij.rt.coverage.data.LineData")

        val getClasses = projectDataClass.getMethod("getClasses")
        val classesObj = getClasses.invoke(projectDataAny) ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        val classesMap = classesObj as? Map<*, *> ?: return emptyList()

        val getLines = classDataClass.getMethod("getLines")
        val getHits = lineDataClass.getMethod("getHits")
        val getMethodSignature = try { lineDataClass.getMethod("getMethodSignature") } catch (_: Throwable) { null }
        val getMethodName = try { lineDataClass.getMethod("getMethodName") } catch (_: Throwable) { null }

        val out = mutableListOf<MethodHit>()
        for ((rawKey, classDataAny) in classesMap) {
            val key = rawKey as? String ?: continue
            val classData = classDataAny ?: continue

            val fqn = key.replace('/', '.')
            // Early skip for obvious tests (cheap)
            if (looksLikeTestFqn(fqn)) continue

            @Suppress("UNCHECKED_CAST")
            val linesArray: Array<Any?> =
                (getLines.invoke(classData) as? Array<Any?>) ?: emptyArray()
            if (linesArray.isEmpty()) continue

            val byMethod = linesArray.mapIndexedNotNull { index, ld ->
                ld ?: return@mapIndexedNotNull null
                val sig = try { getMethodSignature?.invoke(ld) as? String } catch (_: Throwable) { null }
                val name = try { getMethodName?.invoke(ld) as? String } catch (_: Throwable) { null }
                val methodId = when {
                    !sig.isNullOrBlank() -> sig
                    !name.isNullOrBlank() -> name
                    else -> "<unknown>"
                }
                val hits = try { (getHits.invoke(ld) as? Int) ?: 0 } catch (_: Throwable) { 0 }
                val lineNumber = extractLineNumber(ld) ?: index.takeIf { it > 0 }
                CoveredLine(methodId, hits, lineNumber)
            }.groupBy { it.methodId }

            for ((mName, mLines) in byMethod) {
                val total = mLines.size
                val covered = mLines.count { it.hits > 0 }
                val missed = total - covered
                val pct = if (total == 0) 1.0 else covered.toDouble() / total
                val missedLineNumbers = mLines.asSequence()
                    .filter { it.hits == 0 }
                    .mapNotNull { it.lineNumber }
                    .distinct()
                    .sorted()
                    .toList()

                out += MethodHit(
                    classFqn = fqn,
                    method = mName,
                    totalLines = total,
                    coveredLines = covered,
                    missedLines = missed,
                    linePct = pct,
                    missedLineNumbers = missedLineNumbers
                )
            }
        }
        return out
    }

    /**
     * Fallback reflective walk (no Map-entry reflection).
     */
    private fun aggregateByMethodReflective(projectData: Any): List<MethodHit> {
        val out = mutableListOf<MethodHit>()

        val classesObj: Any = try {
            projectData.javaClass.getMethod("getClasses").invoke(projectData)
        } catch (_: Throwable) {
            val f = projectData.javaClass.getDeclaredField("classes").apply { isAccessible = true }
            f.get(projectData)
        } ?: return emptyList()

        @Suppress("UNCHECKED_CAST")
        val classesMap = classesObj as? Map<*, *> ?: return emptyList()

        for ((rawKey, classDataAny) in classesMap) {
            val key = rawKey as? String ?: continue
            val classData = classDataAny ?: continue

            val fqn = key.replace('/', '.')
            // Early skip for obvious tests (cheap)
            if (looksLikeTestFqn(fqn)) continue

            val linesArray: Array<Any?> = try {
                @Suppress("UNCHECKED_CAST")
                (classData.javaClass.getMethod("getLines").invoke(classData) as? Array<Any?>)
                    ?: emptyArray()
            } catch (_: Throwable) {
                try {
                    val f = classData.javaClass.getDeclaredField("lines").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    (f.get(classData) as? Array<Any?>) ?: emptyArray()
                } catch (_: Throwable) {
                    emptyArray()
                }
            }
            if (linesArray.isEmpty()) continue

            val byMethod = linesArray.mapIndexedNotNull { index, ld ->
                ld ?: return@mapIndexedNotNull null
                val hits = try {
                    (ld.javaClass.getMethod("getHits").invoke(ld) as? Int) ?: 0
                } catch (_: Throwable) {
                    0
                }
                val lineNumber = extractLineNumber(ld) ?: index.takeIf { it > 0 }
                CoveredLine(extractMethodId(ld), hits, lineNumber)
            }.groupBy { it.methodId }

            for ((mName, mLines) in byMethod) {
                val total = mLines.size
                val covered = mLines.count { it.hits > 0 }
                val missed = total - covered
                val pct = if (total == 0) 1.0 else covered.toDouble() / total
                val missedLineNumbers = mLines.asSequence()
                    .filter { it.hits == 0 }
                    .mapNotNull { it.lineNumber }
                    .distinct()
                    .sorted()
                    .toList()

                out += MethodHit(
                    classFqn = fqn,
                    method = mName,
                    totalLines = total,
                    coveredLines = covered,
                    missedLines = missed,
                    linePct = pct,
                    missedLineNumbers = missedLineNumbers
                )
            }
        }
        return out
    }

    /** Prefer method signature; fall back to name or a placeholder. */
    private fun extractMethodId(ld: Any): String {
        try {
            val m = ld.javaClass.getMethod("getMethodSignature")
            ((m.invoke(ld) as? String)?.takeIf { it.isNotBlank() })?.let { return it }
        } catch (_: Throwable) {}

        try {
            val m = ld.javaClass.getMethod("getMethodName")
            ((m.invoke(ld) as? String)?.takeIf { it.isNotBlank() })?.let { return it }
        } catch (_: Throwable) {}

        try {
            val f = ld.javaClass.getDeclaredField("methodSignature")
            f.isAccessible = true
            ((f.get(ld) as? String)?.takeIf { it.isNotBlank() })?.let { return it }
        } catch (_: Throwable) {}

        try {
            val f = ld.javaClass.getDeclaredField("name")
            f.isAccessible = true
            ((f.get(ld) as? String)?.takeIf { it.isNotBlank() })?.let { return it }
        } catch (_: Throwable) {}

        return "<unknown>"
    }

    private fun extractLineNumber(ld: Any): Int? {
        listOf("getLineNumber", "getLine").forEach { methodName ->
            try {
                val value = ld.javaClass.getMethod(methodName).invoke(ld) as? Int
                if (value != null && value > 0) return value
            } catch (_: Throwable) {}
        }

        listOf("lineNumber", "line").forEach { fieldName ->
            try {
                val value = ld.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(ld) as? Int
                if (value != null && value > 0) return value
            } catch (_: Throwable) {}
        }

        return null
    }

    private data class CoveredLine(
        val methodId: String,
        val hits: Int,
        val lineNumber: Int?
    )
}

// Simple DTO for ranked output
data class MethodHit(
    val classFqn: String,
    val method: String,
    val totalLines: Int,
    val coveredLines: Int,
    val missedLines: Int,
    val linePct: Double,
    val missedLineNumbers: List<Int> = emptyList()
)
