package github.intellij.support.ide.inspector.source

import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.vcs.log.VcsLogFileHistoryProvider
import com.intellij.vcs.log.visible.filters.VcsLogFilterObject
import com.intellij.vcsUtil.VcsUtil
import git4idea.GitUtil
import git4idea.log.showExternalGitLogInToolwindow
import github.intellij.support.ide.inspector.mcp.IdeaInspectorMcpClient
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
    private val LOG = logger<InspectionGitHistoryPopup>()

    fun showExternalGitLog(project: Project, fqn: String, anchor: java.awt.Component?) {
        val settings = service<LensSettingsState>()
        val mcpUrl = settings.state.mcpServerUrl?.trim().orEmpty()
        val mcpProjectPath = settings.state.ideSourceRepoPath?.trim().orEmpty()
        if (settings.state.mcpEnabled && mcpUrl.isNotEmpty() && mcpProjectPath.isNotEmpty()) {
            object : Task.Backgroundable(project, "Calling remote IDE via MCP…", true) {
                private var ok = false
                private var failure: String? = null
                override fun run(indicator: ProgressIndicator) {
                    indicator.text = "POST $mcpUrl → show_file_history_for_fqn"
                    println("POST $mcpUrl → show_file_history_for_fqn")
                    val r = service<IdeaInspectorMcpClient>()
                        .showFileHistoryForFqn(mcpUrl, fqn, mcpProjectPath)
                    when (r) {
                        IdeaInspectorMcpClient.Result.Ok -> ok = true
                        is IdeaInspectorMcpClient.Result.Failed -> failure = r.reason
                    }
                }
                override fun onSuccess() {
                    if (ok) return
                    LOG.info("MCP show_file_history_for_fqn failed, falling back to local: $failure")
                    println("MCP show_file_history_for_fqn failed, falling back to local: $failure")
                    showExternalGitLogLocal(project, fqn, anchor)
                }
            }.queue()
            return
        } else {
            showExternalGitLogLocal(project, fqn, anchor)
        }
    }

    private fun showExternalGitLogLocal(project: Project, fqn: String, anchor: java.awt.Component?) {
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
        if (!GitUtil.isGitRoot(repoRoot)) {
            JOptionPane.showMessageDialog(anchor, "Configured IDEA source repo is not a Git root:\n$repoPath",
                "IDE Inspector", JOptionPane.WARNING_MESSAGE)
            return
        }
        val rootVf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(repoRoot)
        if (rootVf == null) {
            JOptionPane.showMessageDialog(anchor, "Cannot resolve VirtualFile for:\n$repoPath",
                "IDE Inspector", JOptionPane.WARNING_MESSAGE)
            return
        }
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow(ChangesViewContentManager.TOOLWINDOW_ID)
        if (toolWindow == null) {
            JOptionPane.showMessageDialog(anchor, "Version Control tool window unavailable.",
                "IDE Inspector", JOptionPane.WARNING_MESSAGE)
            return
        }

        object : Task.Backgroundable(project, "Resolving $fqn…", true) {
            private var fileVf: VirtualFile? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Locating $fqn in source tree…"
                val resolved = IdeaSourcePathResolver.resolve(repoRoot, fqn) ?: return
                fileVf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolved)
            }

            override fun onSuccess() {
                val vf = fileVf
                if (vf == null) {
                    JOptionPane.showMessageDialog(anchor, "Class not found under $repoPath:\n$fqn",
                        "IDE Inspector", JOptionPane.INFORMATION_MESSAGE)
                    return
                }
                val paths = listOf(VcsUtil.getFilePath(vf))
                val historyProvider = project.getService(VcsLogFileHistoryProvider::class.java)
                if (historyProvider != null && historyProvider.canShowFileHistory(paths, null)) {
                    historyProvider.showFileHistory(paths, null)
                    return
                }
                val roots = listOf(rootVf)
                val tabTitle = "IDEA History: " + fqn.substringAfterLast('.')
                val tabDescription = "$fqn\n${vf.path}"
                val filters = VcsLogFilterObject.collection(
                    VcsLogFilterObject.fromVirtualFiles(setOf(vf))
                )
                val logId = "EXTERNAL " + roots.joinToString(java.io.File.pathSeparator) { it.path }
                showExternalGitLogInToolwindow(
                    project, toolWindow,
                    { createLogUi(logId, filters) },
                    roots, tabTitle, tabDescription,
                )
            }
        }.queue()
    }

    /**
     * Note: This class is not used so far.
     * Shows the inspection details for a given fully qualified name (FQN) of a class.
     *
     * This function first checks if the IDEA source repository path is configured. If not, it prompts the user to configure it.
     * If the repository does not exist at the specified path, it shows a warning message.
     * It then runs a background task to resolve the FQN in the source tree and read the recent git log.
     * Depending on the result, it either displays the inspection details or an error message.
     *
     * @param project The current IntelliJ project.
     * @param fqn The fully qualified name of the class to inspect.
     * @param anchor The component to use as the parent for any dialog boxes that may be displayed.
     */

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
