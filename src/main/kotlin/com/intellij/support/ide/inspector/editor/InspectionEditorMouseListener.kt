package com.intellij.support.ide.inspector.editor

import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.codeInsight.hint.HintUtil
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.project.DumbModeBlockedFunctionality
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint

class InspectionEditorMouseListener: EditorMouseListener {
    override fun mouseClicked(event: EditorMouseEvent) {
        if (event.isConsumed) return
        val inlay = event.inlay
        if (inlay != null && inlay.renderer is LensRenderer) {
            val renderer = inlay.renderer as LensRenderer

//            if(event.mouseEvent.isPopupTrigger) {
//
//            } else {
//                event.editor.project?.let { showCopyHint(it, RelativePoint(event.mouseEvent) ) }
//            }

            renderer.dumpInspection()
//            event.editor.project?.let { showCopied (it, RelativePoint(event.mouseEvent) ) }

//            (inlay.renderer as InlineDebugRenderer).onClick(inlay, event)
            event.consume()
        }
    }

    override fun mouseEntered(event: EditorMouseEvent) {
        if (event.isConsumed) return
        val inlay = event.inlay
        if (inlay != null && inlay.renderer is LensRenderer) {
            event.editor.project?.let { showCopyHint(it, RelativePoint(event.mouseEvent) ) }
        }

    }

    fun showCopyHint(project: Project, hyperlinkLocationPoint: RelativePoint?) {
        if (hyperlinkLocationPoint == null) return
        if (DumbService.isDumb(project)) {
            DumbService.getInstance(project).showDumbModeNotificationForFunctionality(
                CodeInsightBundle.message("message.navigation.is.not.available.here.during.index.update"),
                DumbModeBlockedFunctionality.GotoClass
            )
            return
        }
        val message = "Please right click the message to copy inspection and intention class names copied to clipboard"
        val label = HintUtil.createSuccessLabel(message)
        JBPopupFactory.getInstance().createBalloonBuilder(label)
            .setFadeoutTime(5000)
            .setFillColor(HintUtil.getWarningColor())
            .createBalloon()
            .show(RelativePoint(hyperlinkLocationPoint.screenPoint), Balloon.Position.above)
    }

     fun showCopied(project: Project, hyperlinkLocationPoint: RelativePoint?) {
        if (hyperlinkLocationPoint == null) return
        if (DumbService.isDumb(project)) {
            DumbService.getInstance(project).showDumbModeNotificationForFunctionality(
                CodeInsightBundle.message("message.navigation.is.not.available.here.during.index.update"),
                DumbModeBlockedFunctionality.GotoClass
            )
            return
        }
        val message = "Class names copied to clipboard, please analyze it via <b>Help | Show Git Log For Classes.." +
                ".</b> in the IDEA source repo "
        val label = HintUtil.createSuccessLabel(message)
        JBPopupFactory.getInstance().createBalloonBuilder(label)
            .setFadeoutTime(5000)
            .setFillColor(HintUtil.getWarningColor())
            .createBalloon()
            .show(RelativePoint(hyperlinkLocationPoint.screenPoint), Balloon.Position.above)
    }
}