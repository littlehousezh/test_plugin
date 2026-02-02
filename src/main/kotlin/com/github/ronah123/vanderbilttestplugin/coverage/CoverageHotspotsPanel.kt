package com.github.ronah123.vanderbilttestplugin.coverage

import com.github.ronah123.vanderbilttestplugin.actions.MethodHit
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableModel
import kotlin.math.min

class CoverageHotspotsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(CoverageHotspotsPanel::class.java)

    private val model = object : DefaultTableModel(
        arrayOf<Any>("#", "Missed/Total", "%Cov", "Method"), 0
    ) {
        override fun isCellEditable(row: Int, column: Int) = false
    }

    private val table = JBTable(model).apply {
        setShowGrid(false)
        autoCreateRowSorter = true
        emptyText.text = "No data yet — run the action to analyze coverage."
        TableSpeedSearch(this as JTable)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && selectedRow >= 0) {
                    val row = convertRowIndexToModel(selectedRow)
                    val fqnAndMethod = model.getValueAt(row, 3).toString()
                    openClass(fqnAndMethod.substringBefore('#'))
                }
            }
        })
    }

    private val header = JBPanelWithEmptyText().apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(6, 8)
        add(JBLabel("Coverage hotspots in project scope"), BorderLayout.WEST)

        val generateBtn = JButton("Generate recommendations").apply {
            toolTipText = "Use ChatGPT wrapper to suggest better tests for top methods"
            addActionListener { onGenerateRecommendationsClicked() }
        }
        add(generateBtn, BorderLayout.EAST)
    }

    init {
        val scroll = ScrollPaneFactory.createScrollPane(table)
        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
    }

    fun setData(rows: List<MethodHit>) {
        model.rowCount = 0
        rows.forEachIndexed { idx, m ->
            val pct = if (m.totalLines == 0) 100.0 else (m.coveredLines * 100.0 / m.totalLines)
            val methodId = "${m.classFqn}#${m.method}".let { s -> if (s.length <= 180) s else s.take(177) + "..." }
            model.addRow(arrayOf(idx + 1, "${m.missedLines}/${m.totalLines}", String.format("%.1f", pct), methodId))
        }
        if (rows.isNotEmpty()) table.setRowSelectionInterval(0, 0)
    }

    private fun openClass(fqn: String) {
        val scope = GlobalSearchScope.projectScope(project)
        val psiClass = JavaPsiFacade.getInstance(project).findClass(fqn, scope)
            ?: JavaPsiFacade.getInstance(project).findClass(fqn.replace('$', '.'), scope)
            ?: return
        val vFile = psiClass.containingFile?.virtualFile ?: return
        FileEditorManager.getInstance(project).openFile(vFile, true)
    }

    private fun onGenerateRecommendationsClicked() {
        val total = model.rowCount
        if (total == 0) {
            Messages.showInfoMessage(project, "Run coverage analysis first.", "Coverage")
            return
        }

        val count = min(CoverageAIConfig.MAX_METHODS_TO_REVIEW, total)
        val pairs = (0 until count).mapNotNull { rowIdx ->
            val fqnAndMethod = model.getValueAt(rowIdx, 3)?.toString() ?: return@mapNotNull null
            val fqn = fqnAndMethod.substringBefore('#')
            val methodKey = fqnAndMethod.substringAfter('#', "")
            if (fqn.isBlank() || methodKey.isBlank()) null else (fqn to methodKey)
        }

        object : Task.Backgroundable(project, "Generating test recommendations", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Collecting source & tests…"

                // Compute BOTH the method bundles and the single test file inside a ReadAction.
                val (bundles, testFile) = ReadAction.compute<Pair<List<MethodBundle>, TestFileBundle?>, RuntimeException> {
                    val bs = CodeExtraction.resolveTopBundles(project, pairs)
                    val tf = CodeExtraction.resolveSingleTestFile(project, bs)
                    bs to tf
                }

                if (bundles.isEmpty()) {
                    info("Could not resolve any methods/test files to analyze.")
                    return
                }

                indicator.text = "Calling Chat API…"
                // NOTE: buildPrompt now requires the testFile bundle as 2nd param.
                val prompt = CodeExtraction.buildPrompt(bundles, testFile)

                val client: ChatClient = AmplifyChatClient(
                    CoverageAIConfig.AMPLIFY_BASE,
                    CoverageAIConfig.AMPLIFY_BEARER,
                    CoverageAIConfig.MODEL_ID
                )

                val promptToSend = if (CoverageAIConfig.DEBUG_SIMPLE_PROMPT)
                    CoverageAIConfig.DEBUG_SIMPLE_PROMPT_TEXT
                else
                    prompt

                var error: Throwable? = null
                val response = try {
                    client.chatOnce(promptToSend)
                } catch (t: Throwable) {
                    error = t
                    log.warn("Chat API failed", t)
                    "Failed to get recommendations: ${t.message}"
                }

                ApplicationManager.getApplication().invokeLater {
                    project.getService(AIInteractionLoggerService::class.java)
                        ?.logAiInteraction(promptToSend, response, CoverageAIConfig.MODEL_ID, CoverageAIConfig.AMPLIFY_BASE, error)
                    val shownPrompt = if (CoverageAIConfig.DEBUG_SIMPLE_PROMPT)
                        "DEBUG simple prompt:\n${CoverageAIConfig.DEBUG_SIMPLE_PROMPT_TEXT}"
                    else
                        prompt  // show full prompt
                    RecommendationsDialog(project, shownPrompt, response).show()
                }
            }
        }.queue()
    }


    private fun info(text: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, text, "Coverage Recommender")
        }
    }
}
