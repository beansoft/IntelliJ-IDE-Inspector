package github.intellij.support.ide.inspector

import com.intellij.codeInspection.InspectionEP
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SpeedSearchComparator
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.table.AbstractTableModel

object SuppressWarningsListPanel {

    private val JDK_WARNINGS = listOf(
        "all", "cast", "classfile", "dep-ann", "deprecation", "divzero", "empty",
        "fallthrough", "finally", "module", "opens", "overloads", "overrides", "path",
        "preview", "processing", "rawtypes", "removal", "requires-automatic",
        "requires-transitive-automatic", "serial", "static", "try", "unchecked", "varargs"
    )

    private val COLUMN_NAMES = arrayOf("Warning", "Source")
    private val COLUMN_WIDTHS = intArrayOf(320, 320)

    data class Row(val warning: String, val source: String)

    @JvmStatic
    fun create(project: Project?): JComponent = Panel(project).root

    private class Panel(private val project: Project?) {
        private var allRows: List<Row> = emptyList()
        private var displayRows: List<Row> = emptyList()

        private val tableModel = object : AbstractTableModel() {
            override fun getRowCount() = displayRows.size
            override fun getColumnCount() = COLUMN_NAMES.size
            override fun getColumnName(col: Int) = COLUMN_NAMES[col]
            override fun getColumnClass(col: Int): Class<*> = String::class.java
            override fun isCellEditable(row: Int, col: Int) = false
            override fun getValueAt(row: Int, col: Int): Any {
                val r = displayRows.getOrNull(row) ?: return ""
                return if (col == 0) r.warning else r.source
            }
        }

        private val table = JBTable(tableModel).apply {
            createDefaultColumnsFromModel()
            for ((i, w) in COLUMN_WIDTHS.withIndex())
                columnModel.getColumn(i).preferredWidth = JBUI.scale(w)
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            autoCreateRowSorter = true
            setDefaultRenderer(String::class.java, object : ColoredTableCellRenderer() {
                override fun customizeCellRenderer(
                    table: JTable, value: Any?, selected: Boolean,
                    hasFocus: Boolean, row: Int, column: Int
                ) {
                    if (value != null) append(value.toString())
                    SpeedSearchUtil.applySpeedSearchHighlighting(this@apply, this, true, selected)
                }
            })
        }

        private val filterField = SearchTextField(false).apply {
            textEditor.emptyText.setText("Filter by warning name or source...")
        }

        val root: JPanel

        init {
            TableSpeedSearch.installOn(table).apply { comparator = SpeedSearchComparator(false) }

            filterField.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = applyFilter(filterField.text)
            })

            val copyAction = object : DumbAwareAction("Copy Warning Name", "Copy selected warning name to clipboard", null) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedRow >= 0
                }
                override fun actionPerformed(e: AnActionEvent) {
                    val viewRow = table.selectedRow.takeIf { it >= 0 } ?: return
                    val modelRow = table.convertRowIndexToModel(viewRow)
                    val warning = displayRows.getOrNull(modelRow)?.warning ?: return
                    CopyPasteManager.getInstance().setContents(StringSelection(warning))
                }
            }

            val refreshAction = object : DumbAwareAction("Refresh", "Reload suppress warnings list", null) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) = reload()
            }

            val exportAction = object : DumbAwareAction("Export to Markdown", "Save current rows as a Markdown file", null) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = displayRows.isNotEmpty()
                }
                override fun actionPerformed(e: AnActionEvent) {
                    val descriptor = FileSaverDescriptor("Export SuppressWarnings to Markdown", "Choose file to save", "md")
                    val wrapper = FileChooserFactory.getInstance()
                        .createSaveFileDialog(descriptor, project)
                        .save(null as VirtualFile?, "suppress-warnings.md") ?: return
                    val content = buildMarkdownTable()
                    try {
                        WriteAction.runAndWait<IOException> {
                            val vFile = wrapper.getVirtualFile(true)
                            if (vFile != null) {
                                vFile.setBinaryContent(content.toByteArray(Charsets.UTF_8))
                            } else {
                                wrapper.file.writeText(content)
                            }
                        }
                    } catch (ex: IOException) {
                        com.intellij.openapi.ui.Messages.showErrorDialog(
                            project, ex.message ?: "Unknown error", "Export Failed"
                        )
                    }
                }
            }

            val toolbar = ActionManager.getInstance()
                .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, DefaultActionGroup(refreshAction, copyAction, exportAction), true)
            toolbar.targetComponent = table

            val topPanel = JPanel(BorderLayout()).apply {
                add(toolbar.component, BorderLayout.WEST)
                add(filterField, BorderLayout.CENTER)
            }

            root = JPanel(BorderLayout()).apply {
                add(topPanel, BorderLayout.NORTH)
                add(JBScrollPane(table), BorderLayout.CENTER)
            }

            reload()
        }

        private fun reload() {
            ApplicationManager.getApplication().executeOnPooledThread {
                val rows = buildRows()
                ApplicationManager.getApplication().invokeLater {
                    allRows = rows
                    applyFilter(filterField.text)
                }
            }
        }

        private fun buildMarkdownTable(): String {
            val sb = StringBuilder()
            sb.appendLine("| Warning | Source |")
            sb.appendLine("| --- | --- |")
            for (row in displayRows) {
                sb.appendLine("| `${row.warning}` | ${row.source} |")
            }
            return sb.toString()
        }

        private fun applyFilter(text: String) {
            val lower = text.trim().lowercase()
            displayRows = if (lower.isEmpty()) allRows
            else allRows.filter {
                it.warning.lowercase().contains(lower) || it.source.lowercase().contains(lower)
            }
            tableModel.fireTableDataChanged()
        }
    }

    private fun buildRows(): List<Row> {
        val rows = mutableListOf<Row>()
        val seen = mutableSetOf<String>()

        JDK_WARNINGS.forEach {
            rows.add(Row(it, "JDK"))
            seen.add(it)
        }

        LocalInspectionEP.LOCAL_INSPECTION.extensionList.forEach { ep ->
            val name = ep.shortName?.takeIf { it.isNotBlank() } ?: return@forEach
            if (seen.add(name)) {
                val source = ep.pluginDescriptor.name ?: "Unknown"
                rows.add(Row(name, source))
            }
        }

        InspectionEP.GLOBAL_INSPECTION.extensionList.forEach { ep ->
            val name = ep.shortName?.takeIf { it.isNotBlank() } ?: return@forEach
            if (seen.add(name)) {
                val source = ep.pluginDescriptor.name ?: "Unknown"
                rows.add(Row(name, source))
            }
        }

        return rows.sortedBy { it.warning.lowercase() }
    }
}
