package github.intellij.support.ide.inspector.logviewer

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBTabbedPane
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class IdeaLogFileEditor(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val disposable: Disposable = Disposer.newDisposable("IdeaLogFileEditor")
    private val editor: EditorEx
    private val filterPanel: IdeaLogFilterPanel
    private val tabs = JBTabbedPane()

    private var entries: List<IdeaLogEntry> = emptyList()

    init {
        val text = loadText(file)
        val document = EditorFactory.getInstance().createDocument(text)
        document.setReadOnly(true)
        editor = EditorFactory.getInstance().createViewer(document, project) as EditorEx
        editor.settings.apply {
            isLineNumbersShown = true
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isRightMarginShown = false
            isCaretRowShown = true
            additionalColumnsCount = 0
            additionalLinesCount = 0
        }
        editor.highlighter = EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(project, PlainTextFileType.INSTANCE)

        entries = IdeaLogParser.parse(text)
        filterPanel = IdeaLogFilterPanel(entries) { entry -> revealOffset(entry.startOffset) }

        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                entries = IdeaLogParser.parse(document.text)
                filterPanel.updateEntries(entries)
            }
        }, disposable)

        tabs.addTab("Text", editor.component)
        tabs.addTab("Log Filter (${entries.size})", filterPanel)
    }

    private fun loadText(vf: VirtualFile): String {
        val raw = try {
            String(vf.contentsToByteArray(), vf.charset)
        } catch (t: Throwable) {
            return "// Failed to read ${vf.path}: ${t.message}"
        }
        return raw.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun revealOffset(offset: Int) {
        if (offset < 0 || offset > editor.document.textLength) return
        editor.caretModel.moveToOffset(offset)
        val pos: LogicalPosition = editor.offsetToLogicalPosition(offset)
        editor.scrollingModel.scrollTo(pos, ScrollType.CENTER)
        tabs.selectedIndex = 0
    }

    override fun getComponent(): JComponent = tabs
    override fun getPreferredFocusedComponent(): JComponent = editor.contentComponent
    override fun getName(): String = "IDEA Log"
    override fun setState(state: FileEditorState) = Unit
    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun getFile(): VirtualFile = file

    fun getEditor(): Editor = editor

    override fun dispose() {
        try {
            EditorFactory.getInstance().releaseEditor(editor)
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
