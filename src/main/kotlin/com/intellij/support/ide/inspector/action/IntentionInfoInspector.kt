package com.intellij.support.ide.inspector.action

import com.intellij.support.ide.inspector.SupportRunService
import com.intellij.codeInsight.daemon.impl.ShowIntentionsPass
import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import kotlinx.coroutines.launch

@Deprecated("Not work as a intention action")
class IntentionInfoInspectorAction : BaseIntentionAction() {

    override fun getFamilyName(): String {
        return "Dump intentions"
    }

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        text = "Dump intentions"
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if(editor == null || file == null) return

        service<SupportRunService>().coroutineScope.launch {
            readAction {
                val intentions : ShowIntentionsPass.IntentionsInfo =  ShowIntentionsPass.getActionsToShow(editor, file)

                intentions.intentionsToShow.forEach {
                    println(it.displayName + "class=" + it.action.javaClass)
                }
            }
        }

    }
}