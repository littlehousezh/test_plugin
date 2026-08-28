package com.github.ronah123.vanderbilttestplugin.coverage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecommendationTextFormatterTest {
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
