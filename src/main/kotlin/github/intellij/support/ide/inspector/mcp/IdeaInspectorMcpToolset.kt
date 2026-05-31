package github.intellij.support.ide.inspector.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.VcsLogFileHistoryProvider
import com.intellij.vcsUtil.VcsUtil
import github.intellij.support.ide.inspector.source.IdeaSourcePathResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.exists

class IdeaInspectorMcpToolset : McpToolset {

    @McpTool
    @McpDescription(
        """
        Opens the JetBrains IDE 'Show History' (Vcs.ShowTabbedFileHistory) view for the given file.
        Use this in the IDE instance that has the target Git repository open as the project.
        Accepts an absolute path or a project-relative path.
        """
    )
    suspend fun show_file_history(
        @McpDescription("Absolute path or project-relative path to the file inside the opened Git repo")
        filePath: String,
    ): String {
        val project = currentCoroutineContext().project
        val vf = resolveFile(project, filePath) ?: mcpFail("File not found: $filePath")
        return openHistory(project, vf)
    }

    @McpTool
    @McpDescription(
        """
        Resolves a fully qualified Java/Kotlin class name to a file inside the currently opened IntelliJ
        Community source repo (the project root) and opens 'Show History' for that file.
        Use this in the IDE instance that has intellij-community checked out as the project.
        """
    )
    suspend fun show_file_history_for_fqn(
        @McpDescription("Fully qualified class name, e.g. com.intellij.foo.Bar")
        fqn: String,
    ): String {
        val project = currentCoroutineContext().project
        val basePath = project.basePath ?: mcpFail("Project has no base path")
        val repoRoot = Path.of(basePath)
        if (!repoRoot.exists()) mcpFail("Project base path does not exist: $basePath")

        val resolved = readAction { IdeaSourcePathResolver.resolve(repoRoot, fqn) }
            ?: mcpFail("Class not found under $basePath: $fqn")

        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(resolved)
            ?: mcpFail("Cannot resolve VirtualFile for: $resolved")

        return openHistory(project, vf)
    }

    private fun resolveFile(project: Project, filePath: String): VirtualFile? {
        val nio = Path.of(filePath)
        val abs = if (nio.isAbsolute) nio else Path.of(project.basePath ?: return null).resolve(filePath)
        if (!abs.exists()) return null
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(abs)
    }

    private suspend fun openHistory(project: Project, vf: VirtualFile): String {
        val paths = listOf(VcsUtil.getFilePath(vf))
        val historyProvider = project.getService(VcsLogFileHistoryProvider::class.java)
            ?: mcpFail("VcsLogFileHistoryProvider unavailable")
        if (!historyProvider.canShowFileHistory(paths, null)) {
            mcpFail("Cannot show file history for: ${vf.path}. The file may not be under a project VCS root.")
        }
        withContext(Dispatchers.EDT) {
            historyProvider.showFileHistory(paths, null)
        }
        return "Opened file history for ${vf.path}"
    }
}
