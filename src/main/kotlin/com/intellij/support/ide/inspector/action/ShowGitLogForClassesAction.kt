package com.intellij.support.ide.inspector.action

import com.intellij.support.ide.inspector.SupportRunService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ObjectUtils
import com.intellij.vcsUtil.VcsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.datatransfer.DataFlavor
import java.util.*
import javax.swing.JOptionPane
import javax.swing.JScrollPane


class ShowGitLogForClassesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val dataContext = e.dataContext
//        val editor = dataContext.getData(CommonDataKeys.EDITOR)
//        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val project = e.project ?: return

        var text = getClipboardText()
        if (text.isEmpty()) text = ""
        //                    ProjectUtil.focusProjectWindow(project, true)
        //                    val packageName = Messages.showInputDialog(e.getData(PlatformDataKeys.PROJECT),
        //                        "Input class names",
        //                        "Please Input Multiline class names, eg: a.b.c",
        //                        EmptyIcon.ICON_16,
        //                        text,
        //                        object : InputValidator {
        //                            override fun checkInput(input: String): Boolean {
        //                                return input.trim { it <= ' ' }.isNotEmpty()
        //                            }
        //
        //                            override fun canClose(s: String): Boolean {
        //                                return true
        //                            }
        //                        })

        val packageName = showMultiLineInputDialog(
            null,
            text, "Please Input Multiline class names, eg: a.b.c:"
        )

        if (packageName != null) {
            showFileAndVcsHistory(project, packageName)
        }

    }



    companion object {
        private fun getCopyPasteManager() = CopyPasteManager.getInstance()

        @JvmStatic
        private fun showFileAndVcsHistory(project: Project, packageName: String) {
            service<SupportRunService>().coroutineScope.launch {
                val fileContent = withContext(Dispatchers.IO) {}
                withContext(Dispatchers.EDT) {
                    packageName?.lines()?.forEach {
                        val file = it
                        readActionBlocking {
                            //                            val javaPsiFacade = JavaPsiFacade.getInstance(project)
                            val psiClasses = ClassFinderService.getInstance(project).getPsiFiles(file)
                            //                                javaPsiFacade.findClasses(file, GlobalSearchScope.projectScope(project))
                            //                            if (psiClasses.isEmpty()) {
                            //                                psiClasses =
                            //                                    javaPsiFacade.findClasses(file, GlobalSearchScope.allScope(project))
                            //                            }

                            if (psiClasses != null) {
                                if (psiClasses.isNotEmpty()) {
                                    val psiClass = psiClasses[0]

                                    val fileElement: PsiElement = psiClass.containingFile.navigationElement
                                    if (fileElement is PsiFile) {
                                        //                                    val virtualFile = VirtualFileFinder.findFile(file, project)
                                        val virtualFile = fileElement.virtualFile

                                        if (virtualFile != null) {
                                            if (project.isInitialized) {
                                                launch(Dispatchers.EDT) {
                                                    navigateTo(virtualFile, project, 0, 0)
                                                }

                                                val path = VcsUtil.getFilePath(virtualFile)
                                                val fileOrParent: VirtualFile =
                                                    getExistingFileOrParent(
                                                        path
                                                    )

                                                val vcs = ChangesUtil.getVcsForFile(fileOrParent!!, project)
                                                    ?: return@readActionBlocking

                                                launch(Dispatchers.EDT) {
                                                    showOldFileHistory(
                                                        project,
                                                        vcs,
                                                        path
                                                    )
                                                }

                                            }
                                        }
                                    }
                                }
                            }

                            //                            val virtualFile = VirtualFileFinder.findFile(file, project)


                        }
                    }
                }
            }
        }

        @JvmStatic
        fun showMultiLineInputDialog(parent: Component?, initValue: String, title: String?): String? {
            val textArea = JBTextArea()
            textArea.rows = 10
            textArea.columns = 80 // 设置默认行数
            textArea.wrapStyleWord = true // 自动换行
            textArea.lineWrap = true // 激活自动换行功能
            textArea.text = initValue

            val scrollPane = JBScrollPane(textArea)
            scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED

            val result = JOptionPane.showConfirmDialog(
                parent,
                scrollPane,
                title,
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
            )
            return if (result == JOptionPane.OK_OPTION) {
                textArea.text
            } else {
                null
            }
        }

        @JvmStatic
        fun getClipboardText(): String {
            return if (getCopyPasteManager().areDataFlavorsAvailable(DataFlavor.stringFlavor)) {
                getCopyPasteManager().getContents(DataFlavor.stringFlavor) ?: ""
            }
            else {
                ""
            }
        }

        @JvmStatic
        // can work only on EDT
        fun navigateTo(virtualFile: VirtualFile, project: Project, line: Int?, offset: Int?): Boolean {
            val editorProviderManager = FileEditorProviderManager.getInstance()
            if (editorProviderManager.getProviderList(project, virtualFile).isEmpty()) {
                return false
            } else {
                val descriptor = OpenFileDescriptor(project, virtualFile)
                // has to be opened on EDT
                val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
                if (editor != null) {
                    val caretModel = editor.caretModel
                    if (line != null) {
                        caretModel.moveToLogicalPosition(LogicalPosition(0, offset ?: 0))
                        // has to be opened on EDT
                        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                    }
                }
                return true
            }
        }

        @JvmStatic
        fun getExistingFileOrParent(selectedPath: FilePath): VirtualFile {
            return ObjectUtils.chooseNotNull(selectedPath.virtualFile, selectedPath.virtualFileParent)
        }

//    private fun showNewFileHistory(project: Project, paths: Collection<FilePath>) {
//        val historyProvider = project.getService(
//            VcsLogFileHistoryProvider::class.java
//        )
//        historyProvider.showFileHistory(paths, null)
//    }

        @JvmStatic
        fun showOldFileHistory(project: Project, vcs: AbstractVcs, path: FilePath) {
            val provider = Objects.requireNonNull(vcs.vcsHistoryProvider)
            AbstractVcsHelper.getInstance(project).showFileHistory(provider!!, vcs.annotationProvider, path, vcs)
        }
    }
}
