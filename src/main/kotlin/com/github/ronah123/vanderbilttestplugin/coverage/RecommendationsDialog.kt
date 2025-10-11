package com.github.ronah123.vanderbilttestplugin.coverage

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ScrollPaneFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import javax.swing.*

class RecommendationsDialog(
    project: Project,
    promptPreview: String,
    response: String
) : DialogWrapper(project, true) {

    private val recommendationsArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        text = response
        border = JBUI.Borders.empty(8)
    }

    private val promptArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        text = promptPreview
        border = JBUI.Borders.empty(8)
    }

    init {
        title = "Test Recommendations"
        init()
        window?.preferredSize = Dimension(900, 700)
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())

        val tabs = JTabbedPane().apply {
            addTab("Recommendations", ScrollPaneFactory.createScrollPane(recommendationsArea))
            addTab("Prompt (preview)", ScrollPaneFactory.createScrollPane(promptArea))
        }
        panel.add(tabs, BorderLayout.CENTER)

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
