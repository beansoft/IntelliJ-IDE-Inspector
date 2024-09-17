package com.intellij.support.ide.inspector.editor

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfo.IntentionActionDescriptor
import com.intellij.codeInsight.daemon.impl.HintRenderer
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.diagnostic.ReportingClassSubstitutor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.psi.PsiElement
import com.jetbrains.support.ide.inspector.IntentionDumpDialog
import java.awt.Graphics
import java.awt.Rectangle
import java.util.ArrayList

/**
 * Renders the text of an inspection lens.
 */
class LensRenderer(private val info: HighlightInfo) : HintRenderer(null) {
	private lateinit var severity: LensSeverity
	
	init {
		setPropertiesFrom(info)
	}
	
	fun setPropertiesFrom(info: HighlightInfo) {
		text = getValidDescriptionText(info.description)

		val nullPsiElement: PsiElement? = null

		val inspectionToolIdString = info.inspectionToolId

		println("inspectionToolId = " + inspectionToolIdString)

		if(inspectionToolIdString != null) {
			val toolWrapper: InspectionToolWrapper<*, *>? =  InspectionProfileManager.getInstance().currentProfile
				.getInspectionTool( inspectionToolIdString, nullPsiElement) // "unused"

			if(toolWrapper != null) {
				val inspectionClass = toolWrapper.extension.implementationClass
				text += " Inspection class: " + StringUtil.getShortName(inspectionClass)
			}
		} else {
			val runnableClass = HighlightInfo::class.java
			val fieldToolId = runnableClass.getDeclaredField("toolId")
			fieldToolId.isAccessible = true
			if (fieldToolId.get(info) !== null && fieldToolId.get(info) is Class<*> ) {
				val inspectionTool = fieldToolId.get(info) as Class<*>
				text += " Inspection class: " + inspectionTool.simpleName
			}

		}

		val quickFixActionRanges = info.quickFixActionRanges
		if (quickFixActionRanges != null) {
			text += " Fixes: " + StringUtil
				.join<Pair<IntentionActionDescriptor, TextRange?>>(
				quickFixActionRanges,
				{ q: Pair<IntentionActionDescriptor, TextRange?> ->
					// q.first.action.text +
					 " (class = " + ReportingClassSubstitutor.getClassToReport(q.first.action)
						.simpleName },
				") ; "
			)
		}
		severity = LensSeverity.from(info.severity)
	}

	fun dumpInspection() {
		val lines = ArrayList<String>()
		val classLines = ArrayList<String>()

		lines.add(info.description)

		val nullPsiElement: PsiElement? = null

		val inspectionToolIdString = info.inspectionToolId

		if(inspectionToolIdString != null) {
			val toolWrapper: InspectionToolWrapper<*, *>? =  InspectionProfileManager.getInstance().currentProfile
				.getInspectionTool( inspectionToolIdString, nullPsiElement) // "unused"

			if(toolWrapper != null) {
				val inspectionClass = toolWrapper.extension.implementationClass
				lines.add("Inspection class =  $inspectionClass")
				classLines.add(inspectionClass)
			}
		} else {
			val runnableClass = HighlightInfo::class.java
			val fieldToolId = runnableClass.getDeclaredField("toolId")
			fieldToolId.isAccessible = true
			if (fieldToolId.get(info) !== null && fieldToolId.get(info) is Class<*> ) {
				val inspectionTool = fieldToolId.get(info) as Class<*>
				lines += "Inspection class = " + inspectionTool.simpleName
				if (inspectionTool != null) {
					classLines += inspectionTool.name
				}
			}
		}

		val quickFixActionRanges = info.quickFixActionRanges

		lines += ""
		lines += "Fixes:"

		quickFixActionRanges.forEach {
			val className = ReportingClassSubstitutor.getClassToReport(it.first.action).name
			lines += it.first.action.text
			lines += " class = $className"
			classLines += className
		}

		IntentionDumpDialog(
			ProjectUtil.getActiveProject(), "Dump Intentions", "Intentions of current editor position", lines,
			classLines
		).show()

//		Toolkit.getDefaultToolkit()
//			.systemClipboard
//			.setContents(StringSelection(text), null)
	}

	
	override fun paint(inlay: Inlay<*>, g: Graphics, r: Rectangle, textAttributes: TextAttributes) {
		fixBaselineForTextRendering(r)
		super.paint(inlay, g, r, textAttributes)
	}
	
	override fun getTextAttributes(editor: Editor): TextAttributes {
		return severity.textAttributes
	}
	
	override fun useEditorFont(): Boolean {
		return true
	}
	
	private companion object {
		private fun getValidDescriptionText(text: String?): String {
			return if (text.isNullOrBlank()) " " else addMissingPeriod(unescapeHtmlEntities(text))
		}
		
		private fun unescapeHtmlEntities(potentialHtml: String): String {
			return potentialHtml.ifContains('&', StringUtil::unescapeXmlEntities)
		}
		
		private fun addMissingPeriod(text: String): String {
			return if (text.endsWith('.')) text else "$text."
		}
		
		private inline fun String.ifContains(charToTest: Char, action: (String) -> String): String {
			return if (this.contains(charToTest)) action(this) else this
		}
		
		private fun fixBaselineForTextRendering(rect: Rectangle) {
			rect.y += 1
		}
	}
}
