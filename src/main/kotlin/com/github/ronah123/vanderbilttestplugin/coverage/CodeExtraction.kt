package com.github.ronah123.vanderbilttestplugin.coverage

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import kotlin.math.min

data class MethodBundle(
    val classFqn: String, val methodName: String, val methodText: String
)

data class TestFileBundle(
    val testFilePath: String?, val testFileText: String?
)

object CodeExtraction {

    fun resolveTopBundles(project: Project, fqnAndMethodKeys: List<Pair<String, String>>): List<MethodBundle> {
        return fqnAndMethodKeys.mapNotNull { (fqn, methodKey) ->
            resolveMethodBundle(project, fqn, methodKey)
        }
    }

    /**
     * Resolve ONE test file for the whole prompt.
     * If you want a specific preference (e.g., “StringJrTest”), pass method bundles so
     * we can bias by the involved class/method names. Returns full file text (no slicing).
     */
    fun resolveSingleTestFile(project: Project, bundles: List<MethodBundle>): TestFileBundle {
        val classNames = bundles.mapNotNull { simpleClassName(it.classFqn) }.toSet()
        val methodNames = bundles.map { it.methodName }.toSet()
        val result = findBestTestFile(project, classNames, methodNames) ?: return TestFileBundle(null, null)
        return TestFileBundle(
            testFilePath = result.virtualFile?.path, testFileText = result.text // full text, no slicing
        )
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
        return MethodBundle(
            classFqn = classFqn, methodName = if (isCtor) "<init>" else parsedName, methodText = methodText
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
     * Find a single likely test PsiFile anywhere in project content.
     * Preference order:
     *  1) Exact "<ClassName>Test.(kt|java)" for any of the involved classes
     *  2) Any *test-ish* file (.kt/.java) whose name mentions a class/method or ends with Spec/IT
     *  3) Otherwise, highest-scoring candidate by heuristic
     */
    private fun findBestTestFile(
        project: Project, classNames: Set<String>, methodNames: Set<String>
    ): PsiFile? {
        val index = ProjectFileIndex.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)

        // 1) Prefer exact "ClassNameTest"
        val exactNames = classNames.flatMap { simple ->
            listOf("${simple}Test.kt", "${simple}Test.java")
        }
        exactNames.forEach { nm ->
            val pf = FilenameIndex.getFilesByName(project, nm, scope).firstOrNull()?.containingFile
            if (pf != null && pf.virtualFile?.let(index::isInContent) == true) return pf
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
                val nameLc = pf.name.toLowerCase()
                val hitTestWord =
                    nameLc.contains("test") || nameLc.endsWith("spec.kt") || nameLc.endsWith("spec.java") || nameLc.endsWith(
                        "it.kt"
                    ) || nameLc.endsWith("it.java")
                val mentionsClass = classNames.any { c -> nameLc.contains(c.toLowerCase()) || text.contains(c) }
                val mentionsMethod = methodNames.any { m -> nameLc.contains(m.toLowerCase()) || text.contains(m) }
                val score =
                    (if (hitTestWord) 60 else 0) + (if (mentionsClass) 30 else 0) + (if (mentionsMethod) 15 else 0) + min(
                        text.length,
                        200_000
                    ) / 10
                Triple(pf, text, score)
            }.sortedByDescending { it.third }.toList()

        return candidates.firstOrNull()?.first
    }

    // ====================== Prompt building ======================

    /**
     * Build a prompt that includes:
     *   - ONE test file (full text) at the top if found
     *   - Then each production method to review
     *
     * Global trimming only applies to the combined result (we never trim the method texts).
     */
    fun buildPrompt(bundles: List<MethodBundle>, testFile: TestFileBundle?): String {
        val header = """
You are helping a student improve unit tests.

FIRST, here is the current test file for context (if present). Use it to understand what's already covered.
THEN, for each production method below:
  1) Identify gaps in the current tests.
  2) Create a checklist of test cases to add, focusing on edge cases, error conditions, and important scenarios. Note that we do not want to actually give the student test code, but merely provided a conceptual outline for cases that they could test.
If a test file isn't found, simply report that you could not find any existing tests.
Keep the advice concise and actionable.

Additionally, if any tests are commented out and target a core functionality that should be tested, treat the functionality as untested (that is, address it in your response, and note to the user that this functionality has tried to be tested but is currently commented out).

Here are some guidelines to implement as you generate your advice for the student:
Each test case should be executable, meaning it has an @Test annotation and can be run via “Run as JUnit Test.” It should include at least one assert statement or assert that an exception is thrown, and it should evaluate or test only one method. Additionally, each test case could be descriptively named and commented. When a single test case contains too many assert statements—typically more than five—it may be beneficial to split it up so that each test evaluates only one specific behavior.
The test suite as a whole should include at least one test for each requirement. It should also contain a fault-revealing test for each bug in the code—that is, a test that is expected to fail when the bug is present. For every requirement, the test suite should include test cases covering valid inputs, boundary cases, invalid inputs, and expected exceptions. 

As you generate your advice for the student, utilize the above guidelines to develop structure in your response. Each element of your response for a proposed test case should follow these guidelines -- have a name, a concrete behavior it is testing, etc. Try to keep your advice for each method and specific test case as structured and specific as possible without writing the code for the student.

""".trimIndent()

        val sb = StringBuilder()
        sb.appendLine(header)

        // One test file, once.
        if (testFile?.testFileText != null) {
            sb.appendLine()
            sb.appendLine("===== Current test file: ${testFile.testFilePath} =====")
            sb.appendLine(fence(testFile.testFileText, testFile.testFilePath))
        } else {
            sb.appendLine()
            sb.appendLine("===== No test file found in project content =====")
        }

        bundles.forEachIndexed { i, b ->
            sb.appendLine()
            sb.appendLine("=== Method ${i + 1}: ${b.classFqn}#${b.methodName} ===")
            sb.appendLine("----- Production method -----")
            sb.appendLine(fence(b.methodText, b.classFqn))
        }

        // Enforce a single global budget: if needed, trim ONLY the test file portion.
        return enforceGlobalBudget(sb.toString(), testFile)
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
    private fun enforceGlobalBudget(full: String, testFile: TestFileBundle?): String {
        if (full.length <= CoverageAIConfig.MAX_PROMPT_CHARS) return full
        if (testFile?.testFileText.isNullOrEmpty()) return full.take(CoverageAIConfig.MAX_PROMPT_CHARS)

        val marker = "===== Current test file: ${testFile?.testFilePath} ====="
        val start = full.indexOf(marker)
        if (start < 0) return full.take(CoverageAIConfig.MAX_PROMPT_CHARS)

        val fenceStart = full.indexOf("```", start)
        val fenceEnd = full.indexOf("```", fenceStart + 3)
        if (fenceStart < 0 || fenceEnd < 0) return full.take(CoverageAIConfig.MAX_PROMPT_CHARS)

        val before = full.substring(0, fenceStart + 3) // include opening ```
        val testBody = full.substring(fenceStart + 3, fenceEnd)
        val after = full.substring(fenceEnd) // includes closing ```

        // Binary search the largest keep of testBody that fits.
        var lo = 0
        var hi = testBody.length
        fun build(mid: Int) = before + testBody.take(mid) + "\n… [truncated test file]\n" + after
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            val cand = build(mid)
            if (cand.length <= CoverageAIConfig.MAX_PROMPT_CHARS) lo = mid else hi = mid - 1
        }
        return build(lo)
    }

    fun promptPreview(full: String, maxChars: Int = 3000): String =
        if (full.length <= maxChars) full else full.take(maxChars) + "\n\n… [truncated]"
}
