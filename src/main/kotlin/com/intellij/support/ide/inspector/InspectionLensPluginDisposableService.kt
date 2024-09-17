package com.intellij.support.ide.inspector

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service

/**
 * Gets automatically disposed when the plugin is unloaded.
 */
@Service
class InspectionLensPluginDisposableService : Disposable {
	override fun dispose() {}
}
