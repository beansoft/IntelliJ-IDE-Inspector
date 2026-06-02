package github.intellij.support.ide.inspector.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import git4idea.GitUtil
import git4idea.GitVcs
import git4idea.log.showExternalGitLogInToolwindow
import github.intellij.support.ide.inspector.IdeInspectorToolWindowFactory
import github.intellij.support.ide.inspector.inspection.SupportRunService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShowExternalGitLogAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val initial = ShowGitLogForClassesAction.getClipboardText()
        val input = ShowGitLogForClassesAction.showMultiLineInputDialog(
            null,
            initial,
            "Please Input Multiline class names, eg: a.b.c:"
        ) ?: return

        show(project, input)
    }

    companion object {
        @JvmStatic
        fun show(project: Project, input: String) {
            service<SupportRunService>().coroutineScope.launch {
                val roots = readActionBlocking { collectGitRoots(project, input) }
                withContext(Dispatchers.EDT) {
                    if (roots.isEmpty()) {
                        Messages.showInfoMessage(
                            project,
                            "No Git roots found for the provided class names.",
                            "Show External Git Log"
                        )
                        return@withContext
                    }
                    openExternalLog(project, roots)
                }
            }
        }

        private fun collectGitRoots(project: Project, input: String): List<VirtualFile> {
            val vcsManager = ProjectLevelVcsManager.getInstance(project)
            val gitKey = GitVcs.getKey()
            val finder = ClassFinderService.getInstance(project)
            val roots = LinkedHashSet<VirtualFile>()
            for (line in input.lines()) {
                val name = line.trim()
                if (name.isEmpty()) continue
                val psiFiles = finder.getPsiFiles(name) ?: continue
                for (psiFile in psiFiles) {
                    val vf = psiFile.virtualFile ?: continue
                    val vcs = vcsManager.getVcsFor(vf) ?: continue
                    if (vcs.keyInstanceMethod != gitKey) continue
                    val root = vcsManager.getVcsRootFor(vf) ?: continue
                    if (!GitUtil.isGitRoot(root.toNioPath())) continue
                    roots.add(root)
                }
            }
            return roots.toList()
        }

        private fun openExternalLog(project: Project, roots: List<VirtualFile>) {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(IdeInspectorToolWindowFactory.TOOLWINDOW_ID) ?: return
            val title = "Git Log (" + roots.first().name + (if (roots.size > 1) "+" else "") + ")"
            val description = roots.joinToString("\n") { it.path }
            showExternalGitLogInToolwindow(project, toolWindow, roots, title, description)
        }
    }
}
