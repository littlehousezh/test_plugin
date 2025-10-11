package com.github.ronah123.vanderbilttestplugin.coverage

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import kotlin.math.max
import kotlin.math.min

data class MethodBundle(
    val classFqn: String,
    val methodName: String,
    val methodText: String,
    val testFilePath: String?,
    val testFileText: String?
)

object CodeExtraction {

    fun resolveTopBundles(project: Project, fqnAndMethodKeys: List<Pair<String, String>>): List<MethodBundle> {
        return fqnAndMethodKeys.mapNotNull { (fqn, methodKey) ->
            resolveMethodBundle(project, fqn, methodKey)
        }.let { bundles ->
            // Enforce a *global* prompt budget by trimming test parts first
            shrinkToGlobalBudget(bundles, CoverageAIConfig.MAX_PROMPT_CHARS)
        }
    }

    fun resolveMethodBundle(project: Project, classFqn: String, methodKey: String): MethodBundle? {
        val psiFacade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)

        val psiClass: PsiClass = (psiFacade.findClass(classFqn, scope)
            ?: psiFacade.findClass(classFqn.replace('$', '.'), scope))
            ?: return null

        val methodName = methodKey.substringBefore('(').ifBlank { methodKey }.substringAfterLast('.')
        val candidates = psiClass.findMethodsByName(methodName, false).toList()
        if (candidates.isEmpty()) return null
        val chosen = candidates.maxByOrNull { it.textLength } ?: candidates.first()

        val methodText = chosen.text.take(CoverageAIConfig.MAX_METHOD_CHARS)

        val testResult = findBestTestFileAndSlice(project, psiClass, methodName)
        return MethodBundle(
            classFqn = classFqn,
            methodName = methodName,
            methodText = methodText,
            testFilePath = testResult?.first?.virtualFile?.path,
            testFileText = testResult?.second
        )
    }

    /**
     * Find a likely test PsiFile anywhere in project content, then slice it to a relevant subset.
     */
    private fun findBestTestFileAndSlice(project: Project, psiClass: PsiClass, methodName: String): Pair<PsiFile, String>? {
        val index = ProjectFileIndex.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val simpleName = psiClass.name ?: return null

        // 1) Prefer exact "ClassNameTest" java/kt anywhere in project content
        val exactNames = listOf("${simpleName}Test.kt", "${simpleName}Test.java")
        val exact = exactNames.asSequence()
            .flatMap { nm -> FilenameIndex.getFilesByName(project, nm, scope).asSequence() }
            .mapNotNull { it.containingFile }
            .firstOrNull { pf -> pf.virtualFile?.let(index::isInContent) == true }
        if (exact != null) {
            val sliced = sliceTestText(exact.text, simpleName, methodName, CoverageAIConfig.MAX_TESTFILE_CHARS)
            return exact to sliced
        }

        // 2) Otherwise, search all .kt/.java files that look like tests by name and mention the class/method
        val allByExt = FilenameIndex.getAllFilesByExt(project, "kt", scope) +
                FilenameIndex.getAllFilesByExt(project, "java", scope)

        val candidates = allByExt.asSequence()
            .mapNotNull { vf ->
                val pf = PsiManager.getInstance(project).findFile(vf) ?: return@mapNotNull null
                if (vf.let(index::isInContent) != true) return@mapNotNull null
                pf
            }
            .filter { pf ->
                val name = pf.name.toLowerCase()
                // filename contains "test" and the class name OR method name (loose heuristic)
                (name.contains("test") && (name.contains(simpleName.toLowerCase()) || name.contains(methodName.toLowerCase())))
                        // or test-ish names like Spec/IT if they mention the class in content
                        || name.endsWith("spec.kt") || name.endsWith("spec.java")
                        || name.endsWith("it.kt") || name.endsWith("it.java")
            }
            .map { pf ->
                val text = pf.text
                val fname = pf.name.toLowerCase()
                val score =
                    (if (fname.contains("test")) 50 else 0) +
                            (if (fname.contains(simpleName.toLowerCase())) 30 else 0) +
                            (if (text.contains(simpleName)) 20 else 0) +
                            (if (text.contains(methodName)) 15 else 0) +
                            min(text.length, 200_000) / 10 // tiny bias for larger files (more tests)
                Triple(pf, text, score)
            }
            .sortedByDescending { it.third }
            .toList()

        val best = candidates.firstOrNull() ?: return null
        val sliced = sliceTestText(best.second, simpleName, methodName, CoverageAIConfig.MAX_TESTFILE_CHARS)
        return best.first to sliced
    }

    /**
     * Slice the test file text to relevant regions:
     *  1) Prefer @Test regions that mention the class or method.
     *  2) Else, take windows around occurrences of class/method.
     *  3) Else, return the file head (imports + first N chars).
     */
    private fun sliceTestText(full: String, className: String, methodName: String, cap: Int): String {
        val lc = full.toLowerCase()
        val needles = listOf("@test", className.toLowerCase(), methodName.toLowerCase())

        // Collect windows around interesting spots
        val windows = mutableListOf<IntRange>()

        // A) windows around @Test annotations
        var idx = lc.indexOf("@test")
        while (idx >= 0) {
            windows += expandToBlock(full, idx)
            idx = lc.indexOf("@test", idx + 5)
        }

        // B) windows around references to class/method
        fun addWindowsFor(term: String) {
            var j = lc.indexOf(term)
            var guard = 0
            while (j >= 0 && guard++ < 200) {
                windows += window(full, j, 900, 1500)
                j = lc.indexOf(term, j + term.length)
            }
        }
        addWindowsFor(className.toLowerCase())
        addWindowsFor(methodName.toLowerCase())

        // Merge overlapping windows and take from most informative first
        val merged = mergeRanges(windows.sortedBy { it.first })
        val sb = StringBuilder(min(cap, full.length))
        for (r in merged) {
            if (sb.length >= cap) break
            val slice = full.substring(max(0, r.first), min(full.length, r.last + 1))
            val remaining = cap - sb.length
            if (remaining <= 0) break
            sb.append(slice.take(remaining))
            if (sb.length < cap) sb.append("\n\n// --- snip ---\n\n")
        }

        if (sb.isNotEmpty()) return sb.toString().take(cap)

        // C) fallback: imports + class header + first cap chars
        // try to keep imports (often needed for context)
        val importsEnd = lc.indexOf("\n\n", lc.indexOf("import").takeIf { it >= 0 } ?: 0).let { if (it < 0) 0 else it + 2 }
        return full.substring(0, min(cap, max(importsEnd, cap)))
    }

    private fun window(text: String, center: Int, left: Int, right: Int): IntRange {
        val start = max(0, center - left)
        val end = min(text.length, center + right)
        return start until end
    }

    /** Roughly expand from an index to a surrounding code block (brace-balanced). */
    private fun expandToBlock(text: String, from: Int): IntRange {
        // Walk backward to a likely function start or annotation start
        var start = from
        while (start > 0 && text[start] != '\n' && text[start] != '{') start--
        start = max(0, start - 200)

        // Walk forward tracking braces to find the block end
        var depth = 0
        var end = min(text.length - 1, from + 4000)
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '{') depth++
            if (c == '}') {
                depth--
                if (depth <= 0) { end = i + 1; break }
            }
            i++
            if (i - from > 12000) break // cap runaway
        }
        return start until min(text.length, end + 1)
    }

    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val out = mutableListOf<IntRange>()
        var cur = ranges.first()
        for (r in ranges.drop(1)) {
            if (r.first <= cur.last + 1) {
                cur = min(cur.first, r.first) .. max(cur.last, r.last)
            } else {
                out += cur
                cur = r
            }
        }
        out += cur
        return out
    }

    fun buildPrompt(bundles: List<MethodBundle>): String {
        val sb = StringBuilder()
        sb.appendLine(
            """
You are helping a student improve unit tests.
For each method below:
  1) Identify gaps in the current tests.
  2) Propose concrete test cases (inputs, setup, assertions, edge/corner cases).
  3) Note any refactors that would make the code more testable.
If a test file isn't found, propose a brand-new test skeleton.
Keep the advice concise and actionable.
""".trimIndent()
        )
        bundles.forEachIndexed { i, b ->
            sb.appendLine()
            sb.appendLine("=== Item ${i + 1}: ${b.classFqn}#${b.methodName} ===")
            sb.appendLine("----- Production method -----")
            sb.appendLine(fence(b.methodText, b.classFqn))
            if (b.testFileText != null) {
                sb.appendLine("----- Current test slices from: ${b.testFilePath} -----")
                sb.appendLine(fence(b.testFileText, b.testFilePath ?: "tests"))
            } else {
                sb.appendLine("----- No test file found in project content -----")
            }
        }
        return sb.toString()
    }

    private fun fence(code: String, hint: String?): String {
        val lang = when {
            hint?.endsWith(".kt") == true || hint?.endsWith(".kts") == true -> "kotlin"
            hint?.endsWith(".java") == true -> "java"
            else -> ""
        }
        return if (lang.isNotEmpty()) "```$lang\n$code\n```" else "```\n$code\n```"
    }

    fun promptPreview(full: String, maxChars: Int = 3000): String =
        if (full.length <= maxChars) full else full.take(maxChars) + "\n\n… [truncated]"
}

/* ---------------------- Global budget enforcement ---------------------- */

private fun shrinkToGlobalBudget(bundles: List<MethodBundle>, maxPromptChars: Int): List<MethodBundle> {
    // Estimate: header + fences ~ a few hundred per bundle. We'll be conservative.
    fun bundleCost(b: MethodBundle): Int {
        val testLen = b.testFileText?.length ?: 0
        return 400 + b.methodText.length + testLen
    }

    val total = bundles.sumOf(::bundleCost)
    if (total <= maxPromptChars) return bundles

    // Trim test slices proportionally until under budget; keep method texts intact.
    val over = total - maxPromptChars
    val mutable = bundles.map {
        it.copy(testFileText = it.testFileText ?: "")
    }.toMutableList()

    var saved = 0
    // Greedy: reduce largest test slices first
    val order = mutable.withIndex().sortedByDescending { (it.value.testFileText?.length ?: 0) }
    for ((idx, b) in order) {
        if (saved >= over) break
        val current = b.testFileText ?: ""
        if (current.isEmpty()) continue
        val target = max(current.length - (over - saved), current.length / 2)
        mutable[idx] = mutable[idx].copy(testFileText = current.take(target))
        saved += (current.length - target)
    }
    return mutable.map { orig ->
        MethodBundle(
            classFqn = orig.classFqn,
            methodName = orig.methodName,
            methodText = orig.methodText,
            testFilePath = if (orig.testFilePath.isNullOrBlank()) null else orig.testFilePath,
            testFileText = if (orig.testFileText.isNullOrBlank()) null else orig.testFileText
        )
    }
}
