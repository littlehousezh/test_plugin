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
    private val heading = Regex("(?m)^\\s{0,3}#{1,6}\\s+")
    private val fencedCodeMarker = Regex("(?m)^\\s*```[A-Za-z0-9_-]*\\s*$")
    private val latexEnvironment = Regex("\\\\(?:begin|end)\\{(?:document|enumerate|itemize|description|center|minipage)\\}(?:\\{[^}]*\\})?")
    private val latexHeading = Regex("\\\\(?:section|subsection|subsubsection|paragraph)\\*?\\{([^{}]*)\\}")
    private val latexTextCommand = Regex("\\\\(?:text|textrm|textsf|texttt|textbf|textit|emph|underline)\\{([^{}]*)\\}")
    private val markdownLink = Regex("\\[([^]]+)]\\([^)]+\\)")

    fun toDisplayText(response: String): String {
        var text = response.replace("\r\n", "\n").replace('\r', '\n').trim()

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
            .replace("{", "")
            .replace("}", "")

        return text
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
