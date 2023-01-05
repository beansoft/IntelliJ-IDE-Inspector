package com.chylex.intellij.inspectionlens

import com.intellij.lang.annotation.HighlightSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class EditorInlayLensManagerTest {
	@Nested
	inner class Priority {
		@ParameterizedTest(name = "offsetAndSeverity = {0}")
		@ValueSource(ints = [0, -1, Int.MIN_VALUE])
		fun minimumOffset(offsetAndSeverity: Int) {
			assertEquals(Int.MAX_VALUE, EditorInlayLensManager.getInlayHintPriority(offsetAndSeverity, Int.MAX_VALUE))
		}
		
		@ParameterizedTest(name = "offset = {0}")
		@ValueSource(ints = [8_589_934, Int.MAX_VALUE])
		fun maximumOffset(offset: Int) {
			assertEquals(Int.MIN_VALUE + 1, EditorInlayLensManager.getInlayHintPriority(offset, Int.MIN_VALUE))
		}
		
		@ParameterizedTest(name = "severity = {0}")
		@ValueSource(ints = [0, 1, 250, 499, 500])
		fun firstPriorityBucket(severity: Int) {
			assertEquals(Int.MAX_VALUE - 500 + severity, EditorInlayLensManager.getInlayHintPriority(0, severity))
		}
		
		@ParameterizedTest(name = "severity = {0}")
		@ValueSource(ints = [0, 1, 250, 499, 500])
		fun secondPriorityBucket(severity: Int) {
			assertEquals(Int.MAX_VALUE - 1000 + severity, EditorInlayLensManager.getInlayHintPriority(1, severity))
		}
		
		@ParameterizedTest(name = "severity = {0}")
		@ValueSource(ints = [0, 1, 250, 499, 500])
		fun penultimatePriorityBucket(severity: Int) {
			assertEquals(Int.MIN_VALUE + 295 + severity, EditorInlayLensManager.getInlayHintPriority(8_589_933, severity))
		}
		
		/**
		 * If any of these change, re-evaluate [EditorInlayLensManager.MAXIMUM_SEVERITY] and the priority calculations.
		 */
		@Nested
		inner class IdeaAssumptions {
			@Test
			fun smallestSeverityHasNotChanged() {
				assertEquals(10, HighlightSeverity.DEFAULT_SEVERITIES.minOf(HighlightSeverity::myVal))
			}
			
			@Test
			fun highestSeverityHasNotChanged() {
				assertEquals(400, HighlightSeverity.DEFAULT_SEVERITIES.maxOf(HighlightSeverity::myVal))
			}
		}
	}
}
