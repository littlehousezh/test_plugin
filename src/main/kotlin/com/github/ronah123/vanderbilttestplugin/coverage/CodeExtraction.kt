package com.github.ronah123.vanderbilttestplugin.coverage

import com.github.ronah123.vanderbilttestplugin.actions.MethodHit
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import kotlin.math.min

data class MethodBundle(
    val classFqn: String,
    val methodName: String,
    val methodText: String,
    val sourceFilePath: String?,
    val startLine: Int?,
    val endLine: Int?
)

data class MethodCoverageBundle(
    val hit: MethodHit,
    val method: MethodBundle
)

data class TestFileBundle(
    val testFilePath: String?, val testFileText: String?
)

object CodeExtraction {

    fun resolveTopBundles(project: Project, hits: List<MethodHit>): List<MethodCoverageBundle> {
        return hits.mapNotNull { hit ->
            resolveMethodBundle(project, hit.classFqn, hit.method)?.let { MethodCoverageBundle(hit, it) }
        }
    }

    /**
     * Resolve the most relevant student test files for the whole prompt.
     *
     * Some assignments split tests across multiple files. We include a small ranked set so
     * recommendations can avoid duplicating behavior that is covered outside the single
     * best-named test file. In one-test-file projects, this still returns just that file.
     */
    fun resolveRelevantTestFiles(project: Project, bundles: List<MethodCoverageBundle>): List<TestFileBundle> {
        val classNames = bundles.mapNotNull { simpleClassName(it.method.classFqn) }.toSet()
        val methodNames = bundles.map { it.method.methodName }.toSet()
        return findRelevantTestFiles(project, classNames, methodNames)
            .take(CoverageAIConfig.MAX_TEST_FILES_TO_INCLUDE)
            .map {
                TestFileBundle(
                    testFilePath = it.virtualFile?.path,
                    testFileText = it.text.take(CoverageAIConfig.MAX_TEST_FILE_CHARS)
                )
            }
    }

    private fun resolveMethodBundle(project: Project, classFqn: String, methodKey: String): MethodBundle? {
        val psiFacade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)

        val psiClass: PsiClass =
            (psiFacade.findClass(classFqn, scope) ?: psiFacade.findClass(classFqn.replace('$', '.'), scope))
                ?: return null

        // ---------- name + descriptor parsing ----------
        val classSimple = psiClass.name
        val parsedName = simpleMethodNameFrom(methodKey, classSimple)
        val targetParamCount = jvmParamCountFromKey(methodKey)
        val isCtor = parsedName == "<init>"

        // ---------- choose the PsiMethod ----------
        val chosen = if (isCtor) {
            val ctors = psiClass.constructors
            when {
                ctors.isEmpty() -> null
                targetParamCount != null -> ctors.firstOrNull { it.parameterList.parametersCount == targetParamCount }
                    ?: ctors.maxByOrNull { it.textLength }

                else -> ctors.maxByOrNull { it.textLength }
            }
        } else {
            val byName = psiClass.findMethodsByName(parsedName, /*checkBases=*/false).toList()
            when {
                byName.isEmpty() -> null
                targetParamCount != null -> byName.firstOrNull { it.parameterList.parametersCount == targetParamCount }
                    ?: byName.maxByOrNull { it.textLength }

                else -> byName.maxByOrNull { it.textLength }
            }
        } ?: return null

        val methodText = chosen.text.take(CoverageAIConfig.MAX_METHOD_CHARS)
        val document = PsiDocumentManager.getInstance(project).getDocument(chosen.containingFile)
        val startLine = document?.getLineNumber(chosen.textRange.startOffset)?.plus(1)
        val endLine = document?.getLineNumber(chosen.textRange.endOffset)?.plus(1)
        return MethodBundle(
            classFqn = classFqn,
            methodName = if (isCtor) "<init>" else parsedName,
            methodText = methodText,
            sourceFilePath = chosen.containingFile?.virtualFile?.path,
            startLine = startLine,
            endLine = endLine
        )
    }

    // ====================== helpers for parsing (unchanged) ======================

    private fun simpleClassName(fqn: String): String? =
        fqn.substringAfterLast('.', fqn).substringAfterLast('$', fqn.substringAfterLast('.', fqn)).ifBlank { null }

    /**
     * Derive a simple method name from a table key.
     */
    private fun simpleMethodNameFrom(key: String, classSimpleName: String?): String {
        val trimmed = key.trim()
        val tail = trimmed.substringAfterLast('.')           // drop package/Class prefix if present
        val head = tail.substringBefore('(')                 // drop JVM descriptor/sig
        val m = Regex("""([A-Za-z_][$\w]*)\s*$""").find(head)
        val token = m?.groupValues?.get(1) ?: head.trim()
        return when {
            token == "<init>" -> "<init>"
            classSimpleName != null && token == classSimpleName -> "<init>"
            else -> token
        }
    }

    /**
     * Parse JVM-style descriptor to a parameter COUNT.
     */
    private fun jvmParamCountFromKey(key: String): Int? {
        val inside =
            key.substringAfter('(', missingDelimiterValue = "").substringBefore(')', missingDelimiterValue = "")
        if (inside.isEmpty()) return if (key.contains('(')) 0 else null
        var i = 0
        var count = 0
        while (i < inside.length) {
            when (inside[i]) {
                'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> {
                    count++; i++
                }

                '[' -> {
                    i++
                }

                'L' -> {
                    val semi = inside.indexOf(';', i)
                    if (semi < 0) return count
                    count++; i = semi + 1
                }

                else -> i++
            }
        }
        return count
    }

    /**
     * Find likely test PsiFiles anywhere in project content.
     * Preference order:
     *  1) Exact "<ClassName>Test.(kt|java)" for any of the involved classes
     *  2) Any *test-ish* file (.kt/.java) whose name mentions a class/method or ends with Spec/IT
     *  3) Highest-scoring candidates by heuristic
     */
    private fun findRelevantTestFiles(
        project: Project, classNames: Set<String>, methodNames: Set<String>
    ): List<PsiFile> {
        val index = ProjectFileIndex.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)

        // 1) Prefer exact "ClassNameTest"
        val exactNames = classNames.flatMap { simple ->
            listOf("${simple}Test.kt", "${simple}Test.java")
        }
        val exactMatches = exactNames.flatMap { nm ->
            FilenameIndex.getFilesByName(project, nm, scope).mapNotNull { psiFile ->
                psiFile.takeIf { it.virtualFile?.let(index::isInContent) == true }
            }
        }

        // 2) Otherwise, rank all .kt/.java that look like tests
        val allByExt = FilenameIndex.getAllFilesByExt(project, "kt", scope) + FilenameIndex.getAllFilesByExt(
            project,
            "java",
            scope
        )

        val candidates = allByExt.asSequence().mapNotNull { vf ->
                val pf = PsiManager.getInstance(project).findFile(vf) ?: return@mapNotNull null
                if (vf.let(index::isInContent) != true) return@mapNotNull null
                pf
            }.map { pf ->
                val text = pf.text
                val nameLc = pf.name.lowercase()
                val hitTestWord =
                    nameLc.contains("test") || nameLc.endsWith("spec.kt") || nameLc.endsWith("spec.java") || nameLc.endsWith(
                        "it.kt"
                    ) || nameLc.endsWith("it.java")
                val mentionsClass = classNames.any { c -> nameLc.contains(c.lowercase()) || text.contains(c) }
                val mentionsMethod = methodNames.any { m -> nameLc.contains(m.lowercase()) || text.contains(m) }
                val importsJunit = text.contains("org.junit") || text.contains("@Test")
                val score =
                    (if (hitTestWord) 60 else 0) +
                        (if (mentionsClass) 40 else 0) +
                        (if (mentionsMethod) 15 else 0) +
                        (if (importsJunit) 20 else 0) +
                        min(text.length, 20_000) / 1000
                Triple(pf, text, score)
            }
            .filter { (_, _, score) -> score >= 80 }
            .sortedWith(compareByDescending<Triple<PsiFile, String, Int>> { it.third }.thenBy { it.first.name })
            .map { it.first }
            .toList()

        return (exactMatches + candidates).distinctBy { it.virtualFile?.path ?: it.name }
    }

    // ====================== Prompt building ======================

    /**
     * Build a prompt that includes:
     *   - Relevant test files at the top if found
     *   - Then each production method to review
     *
     * Global trimming only applies to the combined result (we never trim the method texts).
     */
    fun buildPrompt(bundles: List<MethodCoverageBundle>, testFiles: List<TestFileBundle>): String {
        val header = """
You are helping a student improve unit tests using IDE coverage results.

Use the current test file, production source, and coverage hotspot metadata together. The coverage hotspot metadata is the source of truth for what needs coverage-driven recommendations.

Coverage-specific rules:
- Recommend only conceptual test cases that address missed or partially covered lines/branches shown in the hotspot metadata.
- If a method has 0 missed lines or 100% coverage, say that no coverage-driven recommendation is needed for that method.
- Do not propose implementation validation, robustness, invalid-input, or exception tests unless they correspond to missed coverage, an assignment requirement visible in the tests/source, or a clearly observed bug path.
- Avoid duplicating behavior already covered by the current test file.
- For private methods, recommend tests through public behavior rather than direct private-method calls.
- Do not write test code. Provide names and behavior/assertion outlines only.
- Keep the advice concise and actionable.

Response format:
- Return plain text only. Do not use LaTeX, Markdown, HTML, tables, or code fences.
- Start with the heading "Recommended tests".
- Use a numbered list. For each recommendation, include a short test name followed by indented "Covers", "Action", and "Expected" lines.
- Use ordinary words for operators and conditions instead of mathematical notation.
- End with a short "Already covered" section only when it helps prevent duplicate tests.

If a test file isn't found, say that no existing tests were found and base recommendations only on coverage/source.

Additionally, if any tests are commented out and target a core functionality that should be tested, treat the functionality as untested and note that it was attempted but is currently commented out.

Keep this checklist as quality criteria for each suggested test case:
- Each test case should be executable, meaning it has an @Test annotation and can be run via “Run as JUnit Test.”
- Each test case should include at least one assert statement or assert that an exception is thrown. Example assert statements include assertTrue, assertFalse, and assertEquals. For asserting an exception is thrown, use assertThrows in JUnit 5.
- Each test case should focus on one behavior. It does not need to call only one implementation method when the relevant method is private.
- Each test case could be descriptively named and commented.
- If there is redundant setup code in multiple test cases, suggest extracting it into a common method, such as @BeforeEach.
- If there are too many assert statements in a single test case, such as more than 5, suggest splitting it so each test evaluates one behavior.

Keep this checklist as quality criteria for the test suite, but do not let it override the coverage-specific rules:
- The test suite should have at least one test for each assignment requirement visible from the project context.
- The test suite should appropriately use setup and teardown code, such as @BeforeEach, when it improves clarity.
- The test suite should contain a fault-revealing test for each confirmed or strongly evidenced bug in the code.
- For each requirement, include valid inputs, boundary cases, invalid inputs, and expected exceptions only where those categories are meaningful for that requirement and relevant to missed coverage or assignment behavior.

""".trimIndent()

        val sb = StringBuilder()
        sb.appendLine(header)

        // Relevant test files, once each.
        if (testFiles.any { it.testFileText != null }) {
            sb.appendLine()
            sb.appendLine("===== Current relevant test files =====")
            testFiles.filter { it.testFileText != null }.forEachIndexed { index, testFile ->
                sb.appendLine()
                sb.appendLine("----- Test file ${index + 1}: ${testFile.testFilePath} -----")
                sb.appendLine(fence(testFile.testFileText.orEmpty(), testFile.testFilePath))
            }
        } else {
            sb.appendLine()
            sb.appendLine("===== No relevant test files found in project content =====")
        }

        sb.appendLine()
        sb.appendLine("===== Coverage hotspots selected for recommendation =====")
        bundles.forEachIndexed { i, b ->
            sb.appendLine(
                "${i + 1}. ${b.hit.classFqn}#${b.hit.method}: " +
                    "${b.hit.missedLines}/${b.hit.totalLines} missed, " +
                    "${String.format("%.1f", b.hit.linePct * 100.0)}% covered"
            )
            if (b.hit.missedLineNumbers.isNotEmpty()) {
                sb.appendLine("   Missed lines: ${b.hit.missedLineNumbers.joinToString(", ")}")
            }
        }

        bundles.forEachIndexed { i, b ->
            sb.appendLine()
            sb.appendLine("=== Method ${i + 1}: ${b.hit.classFqn}#${b.hit.method} ===")
            sb.appendLine("----- Coverage hotspot metadata -----")
            sb.appendLine("Missed/Total lines: ${b.hit.missedLines}/${b.hit.totalLines}")
            sb.appendLine("Line coverage: ${String.format("%.1f", b.hit.linePct * 100.0)}%")
            if (b.hit.missedLineNumbers.isNotEmpty()) {
                sb.appendLine("Missed source lines:")
                sb.appendLine(missedLineDetails(b))
            } else {
                sb.appendLine("Missed source lines: not available from the active coverage suite.")
            }
            sb.appendLine("----- Production method -----")
            sb.appendLine(fence(b.method.methodText, b.method.sourceFilePath ?: b.method.classFqn))
        }

        // Enforce a single global budget: if needed, trim ONLY the test file portion.
        return enforceGlobalBudget(sb.toString())
    }

    private fun fence(code: String, hint: String?): String {
        val lang = when {
            hint?.endsWith(".kt") == true || hint?.endsWith(".kts") == true -> "kotlin"
            hint?.endsWith(".java") == true -> "java"
            else -> ""
        }
        return if (lang.isNotEmpty()) "```$lang\n$code\n```" else "```\n$code\n```"
    }

    /**
     * If the prompt exceeds MAX_PROMPT_CHARS, we keep *all* method code intact and
     * shrink only the test file block (from the bottom). This matches your “include once”
     * requirement while still guaranteeing the call will fit.
     */
    private fun enforceGlobalBudget(full: String): String {
        if (full.length <= CoverageAIConfig.MAX_PROMPT_CHARS) return full

        val marker = "===== Current relevant test files ====="
        val nextMarker = "===== Coverage hotspots selected for recommendation ====="
        val start = full.indexOf(marker)
        val end = full.indexOf(nextMarker)
        if (start < 0) return full.take(CoverageAIConfig.MAX_PROMPT_CHARS)
        if (end <= start) return full.take(CoverageAIConfig.MAX_PROMPT_CHARS)

        val before = full.substring(0, start + marker.length)
        val testBody = full.substring(start + marker.length, end)
        val after = full.substring(end)

        // Binary search the largest keep of testBody that fits.
        var lo = 0
        var hi = testBody.length
        fun build(mid: Int) = before + testBody.take(mid) + "\n... [truncated relevant test files]\n" + after
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            val cand = build(mid)
            if (cand.length <= CoverageAIConfig.MAX_PROMPT_CHARS) lo = mid else hi = mid - 1
        }
        return build(lo)
    }

    private fun missedLineDetails(bundle: MethodCoverageBundle): String {
        val startLine = bundle.method.startLine ?: return bundle.hit.missedLineNumbers.joinToString("\n") { "line $it" }
        val sourceLines = bundle.method.methodText.lines()
        return bundle.hit.missedLineNumbers.joinToString("\n") { lineNumber ->
            val offset = lineNumber - startLine
            if (offset in sourceLines.indices) {
                "line $lineNumber: ${sourceLines[offset].trim()}"
            } else {
                "line $lineNumber"
            }
        }
    }

}
