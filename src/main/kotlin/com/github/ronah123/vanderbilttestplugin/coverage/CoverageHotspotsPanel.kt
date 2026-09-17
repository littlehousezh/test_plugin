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
import com.intellij.openapi.application.ApplicationManager.getApplication
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

class CoverageHotspotsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val log = Logger.getInstance(CoverageHotspotsPanel::class.java)

    private val model = object : DefaultTableModel(
        arrayOf<Any>("#", "Missed/Total", "%Cov", "Method"), 0
    ) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private var currentRows: List<MethodHit> = emptyList()
    // Button actions and Task.onFinished both run on the event dispatch thread.
    private var generationInProgress = false

    private val generateButton = JButton("Generate recommendations").apply {
        toolTipText = "Generate recommendations and check their accuracy"
        addActionListener { onGenerateRecommendationsClicked() }
    }

    private val table = JBTable(model).apply {
        setShowGrid(false)
        autoCreateRowSorter = true
        emptyText.text = "No data yet - run TestCompass coverage analysis."
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
        add(JBLabel("TestCompass coverage hotspots"), BorderLayout.WEST)

        add(generateButton, BorderLayout.EAST)
        add(JBLabel("<html>Please wait for the current request to finish. " +
            "Repeated requests may use up your account allowance.</html>").apply {
            border = JBUI.Borders.emptyTop(6)
        }, BorderLayout.SOUTH)
    }

    init {
        val scroll = ScrollPaneFactory.createScrollPane(table)
        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
    }

    fun setData(rows: List<MethodHit>) {
        currentRows = rows
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
        if (generationInProgress) return

        val settings = getApplication().getService(CoverageSettings::class.java)
        if (!settings.isConfigured()) {
            if (!TestCompassSetupDialog(project, settings).showAndGet() || !settings.isConfigured()) {
                Messages.showInfoMessage(
                    project,
                    "Enter your Amplify token to generate recommendations.",
                    "TestCompass Setup"
                )
                return
            }
        }

        val total = model.rowCount
        if (total == 0) {
            Messages.showInfoMessage(project, "Run TestCompass coverage analysis first.", "TestCompass")
            return
        }

        val selectedHits = currentRows.asSequence()
            .filter { it.missedLines > 0 }
            .take(CoverageAIConfig.MAX_METHODS_TO_REVIEW)
            .toList()

        if (selectedHits.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No missed production lines were found in the current coverage hotspots. There are no coverage-driven recommendations to generate.",
                "TestCompass"
            )
            return
        }

        val task = object : Task.Backgroundable(project, "Generating test recommendations", true) {
            override fun onFinished() {
                setGenerationInProgress(false)
            }

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Collecting source & tests…"

                // Compute BOTH the method bundles and the single test file inside a ReadAction.
                val (bundles, testFiles) = ReadAction.compute<Pair<List<MethodCoverageBundle>, List<TestFileBundle>>, RuntimeException> {
                    val bs = CodeExtraction.resolveTopBundles(project, selectedHits)
                    val tf = CodeExtraction.resolveRelevantTestFiles(project, bs)
                    bs to tf
                }

                if (bundles.isEmpty()) {
                    info("Could not resolve any methods/test files to analyze.")
                    return
                }

                indicator.text = "Calling Chat API…"
                val prompt = CodeExtraction.buildPrompt(bundles, testFiles)
                val amplifyBase = CoverageAIConfig.getAmplifyBase()
                val modelId = CoverageAIConfig.getModelId()

                val client = AmplifyChatClient(
                    amplifyBase,
                    CoverageAIConfig.getAmplifyBearer(),
                    modelId
                )

                val promptToSend = if (CoverageAIConfig.DEBUG_SIMPLE_PROMPT)
                    CoverageAIConfig.DEBUG_SIMPLE_PROMPT_TEXT
                else
                    prompt

                var error: Throwable? = null
                var verificationPrompt = promptToSend
                val response = try {
                    val result = RecommendationGenerator(client).generate(
                        contextPrompt = promptToSend,
                        beforeVerification = { indicator.text = "Reviewing recommendation accuracy…" },
                        beforeCorrection = { indicator.text = "Correcting inconsistent recommendations…" }
                    )
                    verificationPrompt = result.finalPrompt
                    result.recommendations
                } catch (t: Throwable) {
                    error = t
                    log.warn("Chat API failed", t)
                    "Failed to get recommendations: ${t.message}"
                }

                ApplicationManager.getApplication().invokeLater {
                    // A disk/logging failure must not prevent users from seeing the answer.
                    runCatching {
                        project.getService(AIInteractionLoggerService::class.java)?.logAiInteraction(
                            verificationPrompt,
                            response,
                            client.resolvedModelId ?: modelId.ifBlank { "Amplify account default" },
                            amplifyBase,
                            error
                        )
                    }.onFailure { log.warn("Could not save the recommendation interaction log", it) }
                    RecommendationsDialog(project, response).show()
                }
            }
        }
        setGenerationInProgress(true)
        try {
            task.queue()
        } catch (t: Throwable) {
            setGenerationInProgress(false)
            throw t
        }
    }

    private fun setGenerationInProgress(running: Boolean) {
        generationInProgress = running
        generateButton.isEnabled = !running
        generateButton.text = if (running) "Generating…" else "Generate recommendations"
    }

    private fun info(text: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, text, "TestCompass")
        }
    }
}
