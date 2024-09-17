package com.intellij.support.ide.inspector.action

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.impl.*
import com.intellij.lang.LangBundle
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.ReportingClassSubstitutor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiUtilCore
import com.jetbrains.support.ide.inspector.IntentionDumpDialog
import java.util.*


class IntentionDumpAction : AnAction() {
    private val copyPasteManager = CopyPasteManager.getInstance()

    override fun actionPerformed(e: AnActionEvent) {
        val dataContext = e.dataContext
        val editor = dataContext.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val project = e.project
        if (editor == null || psiFile == null || project == null) {
            return
        }

        showIntentionHint(project, editor, psiFile, true)
    }

    protected fun showIntentionHint(
        project: Project,
        editor: Editor,
        file: PsiFile,
        showFeedbackOnEmptyMenu: Boolean
    ) {
        val cachedIntentions = ShowIntentionActionsHandler.calcCachedIntentions(project, editor, file)
        cachedIntentions.wrapAndUpdateGutters()
        val allActions:List<IntentionActionWithTextCaching> = getAllActions(cachedIntentions)
        if (allActions.isEmpty()) {
            showEmptyMenuFeedback(editor, showFeedbackOnEmptyMenu)
        } else {
            val lines = ArrayList<String>()
            val classLines = ArrayList<String>()
            allActions.forEach {
                lines.add(it.text + " (class = " + ReportingClassSubstitutor.getClassToReport(it.action).name + ")")
                classLines.add(ReportingClassSubstitutor.getClassToReport(it.action).name)
            }

            IntentionDumpDialog(
                project,
                "Dump Intentions",
                "Intentions of current editor position",
                lines,
                classLines
            ).show()
//            editor.scrollingModel.runActionOnScrollingFinished {
//                IntentionHintComponent.showIntentionHint(project, file, editor, true, cachedIntentions)
//            }
        }
    }

    private fun showEmptyMenuFeedback(editor: Editor, showFeedbackOnEmptyMenu: Boolean) {
        if (showFeedbackOnEmptyMenu) {
            HintManager.getInstance()
                .showInformationHint(
                    editor,
                    LangBundle.message("hint.text.no.context.actions.available.at.this.location")
                )
        }
    }

    /**
     * @see CachedIntentions.getAllActions
     */
    fun getAllActions(intentions: CachedIntentions): List<IntentionActionWithTextCaching> {
        var result: ArrayList<IntentionActionWithTextCaching> = ArrayList<IntentionActionWithTextCaching>()
        val myInspectionFixes = intentions.inspectionFixes
        val myIntentions = intentions.intentions
        val myErrorFixes = intentions.errorFixes
        result.addAll(myInspectionFixes)
        for (intention in myIntentions) {
            if (!myErrorFixes.contains(intention) && !myInspectionFixes.contains(intention)) {
                result.add(intention)
            }
        }
//        result.addAll(myGutters)
        result.addAll(intentions.notifications)
        result = DumbService.getInstance(intentions.project).filterByDumbAwareness(result) as ArrayList<IntentionActionWithTextCaching>

        val language = PsiUtilCore.getLanguageAtOffset(intentions.file, intentions.offset)
        val intentionsOrder = IntentionsOrderProvider.EXTENSION.forLanguage(language)
        return intentionsOrder.getSortedIntentions(intentions, result)
    }
}
