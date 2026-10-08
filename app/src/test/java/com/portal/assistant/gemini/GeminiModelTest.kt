package com.portal.assistant.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the model-id → subtitle label. */
class GeminiModelTest {

    @Test fun vendorAndVersionFromFullId() {
        assertEquals("Gemini 2.5", GeminiModel.prettyName("gemini-2.5-flash-native-audio-latest"))
    }

    @Test fun stripsModelsPrefix() {
        assertEquals("Gemini 2.5", GeminiModel.prettyName("models/gemini-2.5-flash"))
    }

    @Test fun vendorOnlyWhenNoNumericVersion() {
        assertEquals("Gemini", GeminiModel.prettyName("gemini-pro"))
        assertEquals("Gemini", GeminiModel.prettyName("gemini"))
    }

    @Test fun worksForOtherVendors() {
        assertEquals("Claude 3", GeminiModel.prettyName("claude-3-5-sonnet"))
    }

    @Test fun emptyReturnsInput() {
        assertEquals("", GeminiModel.prettyName(""))
    }

    @Test fun defaultIsTheConfiguredModel() {
        assertEquals("Gemini 3.8", GeminiModel.prettyName())
    }

    @Test fun modelPickerOffersLiveUpgradeAndLegacyFallback() {
        assertTrue(GeminiModel.AVAILABLE.contains("gemini-3.8-live"))
        assertTrue(GeminiModel.AVAILABLE.contains("gemini-2.5-flash-native-audio-latest"))
    }
}
