package com.github.ronah123.vanderbilttestplugin.coverage

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import javax.swing.*
import org.json.JSONArray
import org.json.JSONObject

class RecommendationsDialog(
    project: Project,
    response: String
) : DialogWrapper(project, true) {

    private val recommendationsArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        text = RecommendationTextFormatter.toDisplayText(response)
        caretPosition = 0
        border = JBUI.Borders.empty(8)
    }

    init {
        title = "Test Recommendations"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(900, 650)
        }
        panel.add(ScrollPaneFactory.createScrollPane(recommendationsArea), BorderLayout.CENTER)

        val copyBtn = JButton("Copy recommendations").apply {
            addActionListener {
                val sel = recommendationsArea.selectedText ?: recommendationsArea.text
                Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(java.awt.datatransfer.StringSelection(sel), null)
            }
        }
        val south = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 8)
            add(copyBtn, BorderLayout.EAST)
        }
        panel.add(south, BorderLayout.SOUTH)
        return panel
    }
}

internal object RecommendationTextFormatter {
    const val NO_OUTPUT_MESSAGE = "Amplify returned no readable text. There is no model answer to display. Please try again or choose another available model."
    private val heading = Regex("(?m)^\\s{0,3}#{1,6}\\s+")
    private val fencedCodeMarker = Regex("(?m)^\\s*```[A-Za-z0-9_-]*\\s*$")
    private val latexEnvironment = Regex("\\\\(?:begin|end)\\{(?:document|enumerate|itemize|description|center|minipage)\\}(?:\\{[^}]*\\})?")
    private val latexHeading = Regex("\\\\(?:section|subsection|subsubsection|paragraph)\\*?\\{([^{}]*)\\}")
    private val latexTextCommand = Regex("\\\\(?:text|textrm|textsf|texttt|textbf|textit|emph|underline)\\{([^{}]*)\\}")
    private val markdownLink = Regex("\\[([^]]+)]\\([^)]+\\)")

    fun toDisplayText(response: String): String {
        return readableContent(response).ifBlank { NO_OUTPUT_MESSAGE }
    }

    /** Empty means no actual model text; callers can then preserve an earlier answer. */
    fun readableContent(response: String): String {
        var text = response.replace("\r\n", "\n").replace('\r', '\n').trim()
        text = text.filter { it == '\n' || it == '\t' ||
            (!it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt()) }
        val unfenced = text.replace(fencedCodeMarker, "").trim()
        val structured = runCatching {
            when {
                unfenced.startsWith("{") -> renderJson(JSONObject(unfenced))
                unfenced.startsWith("[") -> renderJson(JSONArray(unfenced))
                else -> null
            }
        }.getOrNull()
        if (structured != null) text = structured

        text = text
            .replace(fencedCodeMarker, "")
            .replace(latexEnvironment, "")
            .replace(latexHeading) { "\n${it.groupValues[1]}\n" }
            .replace(latexTextCommand) { it.groupValues[1] }
            .replace(Regex("\\\\item(?:\\s*\\[[^]]*])?\\s*"), "• ")
            .replace("\\newline", "\n")
            .replace("\\\\", "\n")
            .replace("\\(", "")
            .replace("\\)", "")
            .replace("\\[", "")
            .replace("\\]", "")
            .replace("$$", "")
            .replace(heading, "")
            .replace(markdownLink) { it.groupValues[1] }
            .replace("\\_", "_")
            .replace("\\%", "%")
            .replace("\\&", "&")
            .replace("**", "")
            .replace("__", "")
            .replace(Regex("(?<!\\w)[*_](?=\\S)|(?<=\\S)[*_](?!\\w)"), "")

        return text
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun renderJson(value: Any?, depth: Int = 0): String {
        if (value == null || value == JSONObject.NULL) return ""
        if (depth >= 12) return value.toString()
        return when (value) {
            is JSONObject -> value.keySet().sortedWith(compareBy<String> {
                listOf("recommendations", "name", "covers", "action", "expected", "alreadyCovered")
                    .indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index }
            }.thenBy { it }).mapNotNull { key ->
                val content = renderJson(value.opt(key), depth + 1)
                if (content.isBlank()) null else {
                    val label = key.replace(Regex("([a-z])([A-Z])"), "$1 $2")
                        .replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() }
                    "$label:${if ('\n' in content) "\n" else " "}$content"
                }
            }.joinToString("\n")
            is JSONArray -> (0 until value.length()).mapNotNull { index ->
                renderJson(value.opt(index), depth + 1).takeIf { it.isNotBlank() }
            }.mapIndexed { index, content -> "${index + 1}. $content" }.joinToString("\n\n")
            else -> value.toString()
        }
    }
}
