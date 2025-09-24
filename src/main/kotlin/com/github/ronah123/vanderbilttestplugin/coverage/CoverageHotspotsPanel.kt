package com.github.ronah123.vanderbilttestplugin.coverage

import com.github.ronah123.vanderbilttestplugin.actions.MethodHit
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

class CoverageHotspotsPanel(private val project: Project) : JPanel(BorderLayout()) {

    // ✅ Subclass DefaultTableModel to override editability; provide Array<Any> for column IDs
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
        // Double-click to open class file
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && selectedRow >= 0) {
                    val row = convertRowIndexToModel(selectedRow)
                    val fqnAndMethod = model.getValueAt(row, 3).toString()
                    val fqn = fqnAndMethod.substringBefore('#')
                    openClass(fqn)
                }
            }
        })
    }

    init {
        val header = JBPanelWithEmptyText().apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(6, 8)
            add(JBLabel("Coverage hotspots in project scope"), BorderLayout.WEST)
        }

        // ✅ Use JScrollPane type, not JBScrollPane
        val scroll = ScrollPaneFactory.createScrollPane(table)
        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
    }

    fun setData(rows: List<MethodHit>) {
        // Replace all rows
        model.rowCount = 0
        rows.forEachIndexed { idx, m ->
            val pct = if (m.totalLines == 0) 100.0 else (m.coveredLines * 100.0 / m.totalLines)
            val methodId = "${m.classFqn}#${m.method}".let { s -> if (s.length <= 180) s else s.take(177) + "..." }
            model.addRow(arrayOf(idx + 1, "${m.missedLines}/${m.totalLines}", String.format("%.1f", pct), methodId))
        }
        if (rows.isNotEmpty()) {
            table.setRowSelectionInterval(0, 0)
        }
    }

    private fun openClass(fqn: String) {
        val scope = GlobalSearchScope.projectScope(project)
        val psiClass = JavaPsiFacade.getInstance(project).findClass(fqn, scope)
            ?: JavaPsiFacade.getInstance(project).findClass(fqn.replace('$', '.'), scope)
            ?: return

        val vFile = psiClass.containingFile?.virtualFile ?: return
        FileEditorManager.getInstance(project).openFile(vFile, true)
    }
}
