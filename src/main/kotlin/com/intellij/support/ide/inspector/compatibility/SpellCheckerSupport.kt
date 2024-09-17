package com.intellij.support.ide.inspector.compatibility

import com.intellij.support.ide.inspector.editor.LensSeverity
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.logger
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.spellchecker.SpellCheckerSeveritiesProvider

object SpellCheckerSupport {
	private val log = logger<SpellCheckerSupport>()
	
	fun load() {
		typoSeverity?.let { LensSeverity.registerMapping(it, LensSeverity.TYPO) }
	}
	
	private val typoSeverity: HighlightSeverity?
		get() = try {
			SpellCheckerSeveritiesProvider.TYPO
		} catch (e: NoClassDefFoundError) {
			log.warn("Falling back to registered severity search due to ${e.javaClass.simpleName}: ${e.message}")
			InspectionProfileManager.getInstance().severityRegistrar.getSeverity("TYPO")
		}
}
