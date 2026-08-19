package github.intellij.support.ide.inspector.logviewer

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Heavy-weight editor provider — instantiates its own Editor + parses the file,
 * so `accept` stays cheap (name check only).
 */
class IdeaLogFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        val name = file.name
        // idea.log, idea.log.1, idea-2026-05-19.log, etc.
        return name == "idea.log" ||
                (name.startsWith("idea") && name.endsWith(".log")) ||
                name.matches(IDEA_LOG_ROLLED)
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        IdeaLogFileEditor(project, file)

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR

    companion object {
        const val EDITOR_TYPE_ID: String = "idea-log-viewer"
        private val IDEA_LOG_ROLLED = Regex("""idea\.log(?:\.\d+)?""")
    }
}
