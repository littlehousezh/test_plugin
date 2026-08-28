package com.github.ronah123.vanderbilttestplugin

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.components.service
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.PsiErrorElementUtil
import com.github.ronah123.vanderbilttestplugin.services.MyProjectService
import com.github.ronah123.vanderbilttestplugin.coverage.AmplifyChatClient

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

    override fun getTestDataPath() = "src/test/testData/rename"
}
