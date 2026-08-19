package github.intellij.support.ide.inspector.logviewer

import com.intellij.openapi.ui.Splitter
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.event.DocumentEvent
import javax.swing.event.ListSelectionEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

/**
 * Left: filterable table of parsed entries.
 * Right: full body / stack trace of the selected entry.
 * onEntrySelected fires with the entry's start offset so a paired text viewer can scroll to it.
 */
class IdeaLogFilterPanel(
    private var entries: List<IdeaLogEntry>,
    private val onEntrySelected: (IdeaLogEntry) -> Unit,
) : JPanel(BorderLayout()) {

    private val tableModel = LogTableModel(entries)
    private val table = JBTable(tableModel)
    private val sorter = TableRowSorter(tableModel)
    private val searchField = SearchTextField()
    private val onlyExceptionsBox = JCheckBox("Only exceptions", false)
    private val levelFilter = JCheckBox("Only ERROR/WARN", false)
    private val summary = JLabel()
    private val detail = JTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = false
        border = JBUI.Borders.empty(4)
    }

    init {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.rowSorter = sorter
        table.rowHeight = JBUI.scale(22)
        table.autoResizeMode = JBTable.AUTO_RESIZE_LAST_COLUMN
        applyColumnWidths()
        table.setDefaultRenderer(Any::class.java, LogCellRenderer())
        table.selectionModel.addListSelectionListener(::handleSelection)

        searchField.textEditor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = applyFilter()
        })
        onlyExceptionsBox.addActionListener { applyFilter() }
        levelFilter.addActionListener { applyFilter() }

        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(searchField, BorderLayout.CENTER)
            val right = JPanel().apply {
                add(onlyExceptionsBox)
                add(levelFilter)
            }
            add(right, BorderLayout.EAST)
        }

        val left = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(summary.apply {
                border = JBUI.Borders.empty(2, 6)
                foreground = JBColor.GRAY
            }, BorderLayout.SOUTH)
        }

        val splitter = Splitter(true, 0.55f).apply {
            dividerWidth = JBUI.scale(1)
            firstComponent = left
            secondComponent = JBScrollPane(detail).apply {
                border = BorderFactory.createEmptyBorder()
            }
        }
        add(splitter, BorderLayout.CENTER)

        applyFilter()
    }

    fun updateEntries(newEntries: List<IdeaLogEntry>) {
        entries = newEntries
        tableModel.replace(newEntries)
        applyFilter()
    }

    private fun applyColumnWidths() {
        val cm = table.columnModel
        cm.getColumn(0).preferredWidth = JBUI.scale(40)
        cm.getColumn(1).preferredWidth = JBUI.scale(160)
        cm.getColumn(2).preferredWidth = JBUI.scale(60)
        cm.getColumn(3).preferredWidth = JBUI.scale(300)
        cm.getColumn(4).preferredWidth = JBUI.scale(500)
    }

    private fun applyFilter() {
        val query = searchField.text?.trim().orEmpty().lowercase()
        val onlyExc = onlyExceptionsBox.isSelected
        val onlyErr = levelFilter.isSelected
        sorter.rowFilter = object : RowFilter<LogTableModel, Int>() {
            override fun include(entry: Entry<out LogTableModel, out Int>): Boolean {
                val row = entry.identifier as Int
                val e = tableModel.entryAt(row)
                if (onlyExc && !e.hasException) return false
                if (onlyErr && e.level != "ERROR" && e.level != "WARN") return false
                if (query.isEmpty()) return true
                return e.timestamp.lowercase().contains(query) ||
                        e.level.lowercase().contains(query) ||
                        e.logger.lowercase().contains(query) ||
                        e.message.lowercase().contains(query) ||
                        e.body.lowercase().contains(query)
            }
        }
        summary.text = "Showing ${table.rowCount} of ${entries.size} entries" +
                " · exceptions: ${entries.count { it.hasException }}"
    }

    private fun handleSelection(e: ListSelectionEvent) {
        if (e.valueIsAdjusting) return
        val viewRow = table.selectedRow
        if (viewRow < 0) {
            detail.text = ""
            return
        }
        val modelRow = table.convertRowIndexToModel(viewRow)
        val entry = tableModel.entryAt(modelRow)
        detail.text = buildDetail(entry)
        detail.caretPosition = 0
        onEntrySelected(entry)
    }

    private fun buildDetail(entry: IdeaLogEntry): String = buildString {
        append(entry.timestamp).append("  [").append(entry.elapsedMs).append("ms]  ")
            .append(entry.level).append("  ").append(entry.logger).append('\n')
        append(entry.message).append('\n')
        if (entry.body.isNotBlank()) {
            append('\n').append(entry.body)
        }
        if (entry.exceptions.isNotEmpty()) {
            append("\n\n--- Parsed exceptions (").append(entry.exceptions.size).append(") ---\n")
            for ((i, ex) in entry.exceptions.withIndex()) {
                append('\n').append(i + 1).append(". ")
                if (ex.causedBy) append("[caused by] ")
                append(ex.type)
                if (ex.message.isNotBlank()) append(": ").append(ex.message)
                append('\n')
                for (s in ex.stackLines.take(3)) append("    ").append(s.trim()).append('\n')
                if (ex.stackLines.size > 3) {
                    append("    ... ").append(ex.stackLines.size - 3).append(" more frames\n")
                }
            }
        }
    }

    private class LogTableModel(private var data: List<IdeaLogEntry>) : AbstractTableModel() {
        private val cols = arrayOf("#", "Time", "Level", "Logger", "Message")
        fun entryAt(row: Int): IdeaLogEntry = data[row]
        fun replace(newData: List<IdeaLogEntry>) {
            data = newData
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = data.size
        override fun getColumnCount(): Int = cols.size
        override fun getColumnName(column: Int): String = cols[column]
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) Integer::class.javaObjectType else String::class.java

        override fun getValueAt(row: Int, col: Int): Any {
            val e = data[row]
            return when (col) {
                0 -> e.index + 1
                1 -> e.timestamp
                2 -> e.level
                3 -> shortLogger(e.logger)
                else -> summaryOf(e)
            }
        }

        private fun shortLogger(fqn: String): String {
            val dot = fqn.lastIndexOf('.')
            return if (dot < 0) fqn else fqn.substring(dot + 1)
        }

        private fun summaryOf(e: IdeaLogEntry): String {
            val head = e.message.trim()
            val exc = e.primaryExceptionType?.let { t ->
                val msg = e.primaryExceptionMessage.orEmpty()
                if (msg.isBlank()) "  ↳ $t" else "  ↳ $t: $msg"
            }.orEmpty()
            return head + exc
        }
    }

    private class LogCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: javax.swing.JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (column == 2 && !isSelected) {
                foreground = when (value?.toString()) {
                    "ERROR" -> JBColor.RED
                    "WARN" -> JBColor.ORANGE
                    "INFO" -> JBColor.foreground()
                    "DEBUG", "TRACE" -> JBColor.GRAY
                    else -> JBColor.foreground()
                }
            } else if (!isSelected) {
                foreground = JBColor.foreground()
            }
            return c
        }
    }
}

@Suppress("unused")
private val simpleAttrs = SimpleTextAttributes.REGULAR_ATTRIBUTES
