package github.intellij.diagnostic.specialPaths

import com.intellij.diagnostic.DiagnosticBundle
import com.intellij.diagnostic.specialPaths.SpecialPathEntry
import com.intellij.diagnostic.specialPaths.SpecialPathEntry.Kind
import com.intellij.diagnostic.specialPaths.SpecialPathsProvider
import com.intellij.execution.ExecutionBundle
import com.intellij.ide.DataManager
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.SpeedSearchComparator
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.speedSearch.SpeedSearchUtil
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.JTableHeader
import kotlin.io.path.pathString

object SpecialPathsPanel {
  private val columnNames = arrayOf("Description", "Path")
  private val columnPreferredWidths = intArrayOf(200, 900)

  @JvmStatic
  fun create(project: Project?): JComponent {
    val specialPaths = SpecialPathsProvider.EP_NAME.extensionList
      .flatMap { it.collectPaths(project) }
      .sortedBy { it.name }

    val tableModel = object : AbstractTableModel() {
      override fun getRowCount() = specialPaths.size
      override fun getColumnCount() = columnNames.size
      override fun getColumnName(column: Int) = columnNames[column]
      override fun getColumnClass(columnIndex: Int): Class<out Any> = String::class.java
      override fun isCellEditable(rowIndex: Int, columnIndex: Int) = false
      override fun getValueAt(row: Int, column: Int) =
        if (row in 0 until specialPaths.size) specialPaths[row].getColumn(column) else ""
    }

    val table = JBTable(tableModel).apply {
      createDefaultColumnsFromModel()
      for ((i, width) in columnPreferredWidths.withIndex())
        columnModel.getColumn(i).preferredWidth = JBUI.scale(width)
      tableHeader = JTableHeader(columnModel)
      selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
      setDefaultRenderer(String::class.java, object : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(
          table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ) {
          if (value != null) {
            @NlsSafe val stringValue = value.toString()
            append(stringValue)
          }
          SpeedSearchUtil.applySpeedSearchHighlighting(this@apply, this, true, selected)
        }
      })
      addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
          if (e.clickCount == 2) {
            val row = rowAtPoint(e.point)
            if (row in 0 until specialPaths.size) specialPaths[row].open(project)
          }
        }
      })
      addMouseListener(object : PopupHandler() {
        override fun invokePopup(comp: Component?, x: Int, y: Int) {
          val row = rowAtPoint(Point(x, y))
          if (row in 0 until specialPaths.size && comp != null) {
            val popup = createPopup(specialPaths[row], DataManager.getInstance().getDataContext(comp, x, y), project)
            popup.show(RelativePoint(comp, Point(x, y)))
          }
        }
      })
      TableSpeedSearch.installOn(this).apply { comparator = SpeedSearchComparator(false) }
    }

    return JPanel(BorderLayout()).apply {
      add(JBScrollPane(table), BorderLayout.CENTER)
    }
  }

  private fun SpecialPathEntry.getColumn(column: Int) = when (column) {
    0 -> name
    1 -> path
    else -> throw IndexOutOfBoundsException("column")
  }

  private fun createPopup(entry: SpecialPathEntry, dataContext: DataContext, project: Project?) =
    JBPopupFactory.getInstance().createActionGroupPopup(
      DiagnosticBundle.message("popup.title.actions.with.alt.enter", entry.name),
      entry.getContextActionGroup(project),
      dataContext,
      JBPopupFactory.ActionSelectionAid.NUMBERING,
      false
    )

  private fun SpecialPathEntry.openDirectory() {
    val notNullPath = path ?: return
    when (kind) {
      Kind.Folder -> RevealFileAction.openDirectory(notNullPath)
      Kind.File -> RevealFileAction.openFile(notNullPath)
    }
  }

  private fun SpecialPathEntry.openInEditor(project: Project) {
    val notNullPath = path ?: return
    if (kind == Kind.Folder) return
    val file = VfsUtil.findFile(notNullPath, true)
    if (file == null) {
      Notifications.Bus.notify(
        Notification(
          "System Messages",
          ExecutionBundle.message("error.common.title"),
          DiagnosticBundle.message("notification.content.there.no.such.file", path),
          NotificationType.ERROR
        ), project
      )
    } else {
      FileEditorManager.getInstance(project).openFile(file, true, true)
    }
  }

  private fun SpecialPathEntry.open(project: Project?) {
    when (kind) {
      Kind.Folder -> openDirectory()
      Kind.File -> if (project != null) openInEditor(project) else openDirectory()
    }
  }

  private fun SpecialPathEntry.getContextActionGroup(project: Project?) = DefaultActionGroup().apply {
    when (kind) {
      Kind.Folder -> add(object : AnAction(
        DiagnosticBundle.messagePointer("action.open.folder.text"),
        DiagnosticBundle.messagePointer("action.open.folder.description", path),
        { null }
      ) {
        override fun actionPerformed(e: AnActionEvent) = openDirectory()
      })
      Kind.File -> {
        if (project != null) {
          add(object : AnAction(
            DiagnosticBundle.messagePointer("action.open.in.editor.text"),
            DiagnosticBundle.messagePointer("action.open.in.editor.description", path),
            { null }
          ) {
            override fun actionPerformed(e: AnActionEvent) = openInEditor(project)
          })
        }
        add(object : AnAction(
          DiagnosticBundle.messagePointer("action.show.in.folder.text"),
          DiagnosticBundle.messagePointer("action.show.in.folder.description", path?.parent),
          { null }
        ) {
          override fun actionPerformed(e: AnActionEvent) = openDirectory()
        })
      }
    }
    add(object : AnAction(
      DiagnosticBundle.messagePointer("action.copy.path.text"),
      DiagnosticBundle.messagePointer("action.copy.path.description", path),
      { null }
    ) {
      override fun actionPerformed(e: AnActionEvent) {
        val notNullPath = path ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(notNullPath.pathString))
      }
    })
  }
}
