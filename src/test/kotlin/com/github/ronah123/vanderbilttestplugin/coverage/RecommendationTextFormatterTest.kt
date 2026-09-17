package com.github.ronah123.vanderbilttestplugin.coverage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationTextFormatterTest {
    @Test
    fun `empty and presentation-only responses always have a readable explanation`() {
        for (raw in listOf("", " \n\t", "```\n```", "** **", "{}", "[]", "\u200B\u0000")) {
            val text = RecommendationTextFormatter.toDisplayText(raw)
            assertTrue("Blank display for $raw", text.isNotBlank())
            assertTrue(text.contains("no readable text"))
        }
    }

    @Test
    fun `structured model output is displayed with readable labels`() {
        val raw = """{"recommendations":[{"name":"Addition","action":"Use 2 and 3","expected":"The result is 5"}],"alreadyCovered":"Zero inputs"}"""
        val text = RecommendationTextFormatter.toDisplayText("```json\n$raw\n```")
        assertTrue(text.contains("Action: Use 2 and 3"))
        assertTrue(text.contains("Expected: The result is 5"))
        assertTrue(text.contains("Already covered: Zero inputs"))
        assertFalse(text.contains("\"recommendations\""))
    }

    @Test
    fun `formats common LaTeX recommendation wrappers as plain text`() {
        val response = """
            \section*{Recommended tests}
            \begin{itemize}
            \item \textbf{emptyInput} \textit{covers the empty branch}
            \item Expected: result \_ is empty
            \end{itemize}
        """.trimIndent()

        val formatted = RecommendationTextFormatter.toDisplayText(response)

        assertEquals(
            "Recommended tests\n\n• emptyInput covers the empty branch\n• Expected: result _ is empty",
            formatted
        )
    }

    @Test
    fun `removes Markdown presentation syntax`() {
        val formatted = RecommendationTextFormatter.toDisplayText(
            "## Recommended tests\n\n1. **emptyInput**\n   Expected: [an empty result](https://example.test)"
        )

        assertFalse(formatted.contains("#"))
        assertFalse(formatted.contains("**"))
        assertEquals(
            "Recommended tests\n\n1. emptyInput\n   Expected: an empty result",
            formatted
        )
    }
}
