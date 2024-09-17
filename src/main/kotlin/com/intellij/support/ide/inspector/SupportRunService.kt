package com.intellij.support.ide.inspector


import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope

@Service // Default is App level
class SupportRunService(val coroutineScope: CoroutineScope)