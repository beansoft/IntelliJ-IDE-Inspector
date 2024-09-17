package com.intellij.support.ide.inspector.settings

import com.intellij.support.ide.inspector.InspectionLensRefresher
import com.intellij.support.ide.inspector.editor.LensSeverityFilter
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.XMap

@State(
	name = LensApplicationConfigurable.ID,
	storages = [ Storage("chylex.inspectionLens.xml") ],
	category = SettingsCategory.UI
)
class LensSettingsState : SimplePersistentStateComponent<LensSettingsState.State>(State()) {
	class State : BaseState() {
		@get:XMap
		val hiddenSeverities by map<String, com.intellij.support.ide.inspector.settings.StoredSeverity>()
		
		var showUnknownSeverities by property(true)
	}
	
	@get:Synchronized
	@set:Synchronized
	var severityFilter = createSeverityFilter()
		private set
	
	override fun loadState(state: State) {
		super.loadState(state)
		update()
	}
	
	fun update() {
		severityFilter = createSeverityFilter()
		InspectionLensRefresher.scheduleRefresh()
	}
	
	private fun createSeverityFilter(): LensSeverityFilter {
		val state = state
		return LensSeverityFilter(state.hiddenSeverities.keys, state.showUnknownSeverities)
	}
}
