package com.github.ronah123.vanderbilttestplugin

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.components.service
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.PsiErrorElementUtil
import com.github.ronah123.vanderbilttestplugin.services.MyProjectService
import com.github.ronah123.vanderbilttestplugin.coverage.AmplifyChatClient
import com.github.ronah123.vanderbilttestplugin.startup.CoverageCalculatedListener
import com.github.ronah123.vanderbilttestplugin.startup.CoverageRunTracker
import com.intellij.coverage.CoverageSuite
import com.intellij.coverage.CoverageSuitesBundle
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong

@TestDataPath("\$CONTENT_ROOT/src/test/testData")
class MyPluginTest : BasePlatformTestCase() {

    fun testXMLFile() {
        val psiFile = myFixture.configureByText(XmlFileType.INSTANCE, "<foo>bar</foo>")
        val xmlFile = assertInstanceOf(psiFile, XmlFile::class.java)

        assertFalse(PsiErrorElementUtil.hasErrors(project, xmlFile.virtualFile))

        assertNotNull(xmlFile.rootTag)

        xmlFile.rootTag?.let {
            assertEquals("foo", it.name)
            assertEquals("bar", it.value.text)
        }
    }

    fun testRename() {
        myFixture.testRename("foo.xml", "foo_after.xml", "a2")
    }

    fun testProjectService() {
        val projectService = project.service<MyProjectService>()

        assertNotSame(projectService.getRandomNumber(), projectService.getRandomNumber())
    }

    fun testAmplifyUsesPreferredModelOnlyWhenAvailable() {
        val response = """
            {"data":{"models":[{"id":"gpt-5.2"},{"id":"account-default"}],"default":{"id":"account-default"}}}
        """.trimIndent()

        assertEquals("gpt-5.2", AmplifyChatClient.selectAvailableModel(response, "gpt-5.2"))
        assertEquals("account-default", AmplifyChatClient.selectAvailableModel(response, "gpt-5"))
    }

    fun testAmplifyFallsBackToFirstAvailableModelWithoutDefault() {
        val response = """
            {"data":{"models":[{"id":"first-model"},{"id":"second-model"}]}}
        """.trimIndent()

        assertEquals("first-model", AmplifyChatClient.selectAvailableModel(response, ""))
    }

    fun testAmplifyExtractsCurrentChatResponse() {
        assertEquals(
            "Recommendation text",
            AmplifyChatClient.extractContentSmart(
                """{"success":true,"message":"ok","data":"Recommendation text"}"""
            )
        )
    }

    fun testCoverageRunTrackerAcceptsEachNewCoverageResultOnce() {
        val tracker = CoverageRunTracker()
        val firstBundle = Any()
        val secondBundle = Any()

        assertTrue(tracker.claim(firstBundle, 100L))
        assertFalse(tracker.claim(firstBundle, 100L))
        assertTrue(tracker.claim(firstBundle, 101L))
        assertTrue(tracker.claim(secondBundle, 101L))
    }

    fun testCalculatedCoverageAutomaticallyStartsOneAnalysisPerResult() {
        val timestamp = AtomicLong(100L)
        val bundle = coverageBundle(timestamp)
        var analysisCount = 0
        var analyzedBundle: CoverageSuitesBundle? = null
        val listener = CoverageCalculatedListener(project) { _, calculatedBundle ->
            analysisCount++
            analyzedBundle = calculatedBundle
        }

        listener.coverageDataCalculated(bundle)
        listener.coverageDataCalculated(bundle)
        assertEquals(1, analysisCount)
        assertSame(bundle, analyzedBundle)

        timestamp.incrementAndGet()
        listener.coverageDataCalculated(bundle)
        assertEquals(2, analysisCount)
    }

    private fun coverageBundle(timestamp: AtomicLong): CoverageSuitesBundle {
        val suite = Proxy.newProxyInstance(
            CoverageSuite::class.java.classLoader,
            arrayOf(CoverageSuite::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "getLastCoverageTimeStamp" -> timestamp.get()
                "getCoverageEngine", "getProject", "getRunner", "getCoverageDataFileProvider", "getCoverageData" -> null
                "getCoverageDataFileName", "getPresentableName" -> "test-coverage"
                "isValid" -> true
                "isTrackTestFolders", "isBranchCoverage", "isCoverageByTestEnabled" -> false
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "TestCoverageSuite"
                else -> null
            }
        } as CoverageSuite

        return CoverageSuitesBundle(suite)
    }

    override fun getTestDataPath() = "src/test/testData/rename"
}
