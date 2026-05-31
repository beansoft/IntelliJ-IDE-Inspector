package github.intellij.support.ide.inspector.source

import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import github.intellij.support.ide.inspector.settings.LensApplicationConfigurable
import github.intellij.support.ide.inspector.settings.LensSettingsState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableColumn
import kotlin.io.path.exists

object InspectionGitHistoryPopup {

    private const val GITHUB_BASE = "https://github.com/JetBrains/intellij-community/commit/"

    fun show(project: Project, fqn: String, anchor: java.awt.Component?) {
        val settings = service<LensSettingsState>()
        val repoPath = settings.state.ideSourceRepoPath?.trim().orEmpty()
        if (repoPath.isEmpty()) {
            promptConfigure(project)
            return
        }
        val repoRoot = Path.of(repoPath)
        if (!repoRoot.exists()) {
            JOptionPane.showMessageDialog(anchor, "Configured IDEA source repo does not exist:\n$repoPath",
                "IDE Inspector", JOptionPane.WARNING_MESSAGE)
            return
        }

        object : Task.Backgroundable(project, "Resolving inspection class…", true) {
            private var file: Path? = null
            private var result: GitHistoryReader.Result? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Locating $fqn in source tree…"
                val resolved = IdeaSourcePathResolver.resolve(repoRoot, fqn) ?: return
                file = resolved
                indicator.text = "Reading git log…"
                result = GitHistoryReader.recentCommits(repoRoot, resolved)
            }

            override fun onSuccess() {
                val f = file
                if (f == null) {
                    JOptionPane.showMessageDialog(anchor, "Class not found under $repoPath:\n$fqn",
                        "IDE Inspector", JOptionPane.INFORMATION_MESSAGE)
                    return
                }
                when (val r = result) {
                    is GitHistoryReader.Result.Ok -> showPopup(project, fqn, f, r, anchor)
                    is GitHistoryReader.Result.Error -> JOptionPane.showMessageDialog(anchor,
                        r.message, "IDE Inspector", JOptionPane.ERROR_MESSAGE)
                    null -> {}
                }
            }
        }.queue()
    }

    private fun promptConfigure(project: Project) {
        val choice = JOptionPane.showConfirmDialog(
            null,
            "IDEA source repo not configured.\nOpen Settings | Tools | IDE Inspector?",
            "IDE Inspector",
            JOptionPane.YES_NO_OPTION,
        )
        if (choice == JOptionPane.YES_OPTION) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, LensApplicationConfigurable::class.java)
        }
    }

    private fun showPopup(project: Project, fqn: String, file: Path, ok: GitHistoryReader.Result.Ok, anchor: java.awt.Component?) {
        val model = CommitsTableModel(ok.commits)
        val table = JBTable(model)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        intArrayOf(90, 160, 90, 600).forEachIndexed { i, w ->
            if (i < table.columnCount) {
                val col: TableColumn = table.columnModel.getColumn(i)
                col.preferredWidth = w
            }
        }

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    val row = table.selectedRow.takeIf { it >= 0 } ?: return
                    val c = model.rows[row]
                    CopyPasteManager.getInstance().setContents(StringSelection(c.hash))
                }
            }
        })

        val menu = JPopupMenu()
        menu.add(javax.swing.JMenuItem("Copy Hash").apply {
            addActionListener {
                val row = table.selectedRow.takeIf { it >= 0 } ?: return@addActionListener
                CopyPasteManager.getInstance().setContents(StringSelection(model.rows[row].hash))
            }
        })
        menu.add(javax.swing.JMenuItem("Open in GitHub").apply {
            addActionListener {
                val row = table.selectedRow.takeIf { it >= 0 } ?: return@addActionListener
                BrowserUtil.browse(GITHUB_BASE + model.rows[row].hash)
            }
        })
        table.addMouseListener(object : PopupHandler() {
            override fun invokePopup(comp: java.awt.Component, x: Int, y: Int) {
                val row = table.rowAtPoint(java.awt.Point(x, y))
                if (row >= 0) table.setRowSelectionInterval(row, row)
                menu.show(comp, x, y)
            }
        })

        val openFileBtn = JButton("Open File").apply {
            addActionListener {
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) ?: return@addActionListener
                ApplicationManager.getApplication().invokeLater {
                    FileEditorManager.getInstance(project).openFile(vf, true)
                }
            }
        }
        val copyPathBtn = JButton("Copy Path").apply {
            addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(file.toString())) }
        }

        val south = JPanel().apply {
            layout = java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 4)
            add(copyPathBtn)
            add(openFileBtn)
        }

        val header = JBLabel("<html><b>$fqn</b><br>${ok.relativePath} &middot; ${ok.commits.size} commits</html>")
            .apply { border = JBUI.Borders.empty(6, 8) }

        val content = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(south, BorderLayout.SOUTH)
            preferredSize = Dimension(900, 480)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, table)
            .setTitle("Git History")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()

        if (anchor != null) {
            val ctx = DataManager.getInstance().getDataContext(anchor)
            popup.showInBestPositionFor(ctx)
        } else {
            popup.showInFocusCenter()
        }
    }

    private class CommitsTableModel(val rows: List<CommitInfo>) : AbstractTableModel() {
        private val cols = arrayOf("Date", "Author", "Hash", "Subject")
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = cols.size
        override fun getColumnName(c: Int): String = cols[c]
        override fun isCellEditable(r: Int, c: Int): Boolean = false
        override fun getColumnClass(c: Int): Class<*> = String::class.java
        override fun getValueAt(r: Int, c: Int): Any {
            val row = rows[r]
            return when (c) {
                0 -> row.date
                1 -> row.author
                2 -> row.shortHash
                3 -> row.subject
                else -> ""
            }
        }
    }
}
