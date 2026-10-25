package com.example.xgestalkble.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TranslationMemoryTest {
    @Test fun saveCompleteTranslationsAndReuseThemWithoutNetwork() = runTest {
        val memory = TranslationMemory()
        var requests = 0
        val phrase = "I do not need help"
        val translated = "എനിക്ക് സഹായം ആവശ്യമില്ല"
        assertEquals(translated, memory.resolve(phrase, "ml") { requests++; translated }.text)
        assertEquals(translated, memory.resolve(phrase, "ml") { error("No internet") }.text)
        assertEquals(1, requests)
        assertNull(memory.get("I need help", "ml"))
        assertNull(memory.get(phrase, "hi"))
    }

    @Test fun duplicateConcurrentRequestsShareOneTranslation() = runTest {
        val memory = TranslationMemory()
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<String>()
        var requests = 0
        val first = async { memory.resolve("New phrase", "ml") { requests++; started.complete(Unit); result.await() } }
        started.await()
        val second = async { memory.resolve("NEW_PHRASE", "ml") { requests++; error("Duplicate request") } }
        result.complete("പുതിയ വാക്യം")
        assertEquals(first.await().text, second.await().text)
        assertEquals(1, requests)
    }

    @Test fun reviewedCorrectionWinsEvenIfMachineRequestFinishesLater() = runTest {
        val memory = TranslationMemory()
        val started = CompletableDeferred<Unit>()
        val result = CompletableDeferred<String>()
        val request = async { memory.resolve("Good evening", "ml") { started.complete(Unit); result.await() } }
        started.await()
        memory.put(SavedTranslation("Good evening", "ml", "ശുഭ സായാഹ്നം", true))
        result.complete("വൈകുന്നേരം")
        assertEquals("ശുഭ സായാഹ്നം", request.await().text)
        assertTrue(request.await().reviewed)
    }

    @Test fun changedEnglishMeaningInvalidatesOldGeneratedAndReviewedTranslations() {
        val memory = TranslationMemory()
        memory.put(SavedTranslation("MY_SIGN", "ml", "ആദ്യ അർത്ഥം", true))
        memory.put(SavedTranslation("MY_SIGN", "hi", "पहला अर्थ", false))
        val oldVersion = memory.version("MY_SIGN")
        memory.remove("MY_SIGN")
        assertNull(memory.get("MY_SIGN", "ml"))
        assertFalse(memory.put(SavedTranslation("MY_SIGN", "ml", "പഴയ അർത്ഥം", false), oldVersion))
        assertNull(memory.get("MY_SIGN", "ml"))
    }

    @Test fun invalidAndFailedTranslationsAreNeverSavedAsMeanings() = runTest {
        val memory = TranslationMemory()
        for (text in listOf("", "<ctrl42>", "<unk>")) {
            assertThrows(IllegalArgumentException::class.java) { memory.put(SavedTranslation("Hello", "ml", text, false)) }
        }
        try { memory.resolve("Unknown", "ml") { error("Service unavailable") }; fail("Expected failure") }
        catch (_: IllegalStateException) { }
        assertNull(memory.get("Unknown", "ml"))
    }

    @Test fun savedEntriesCanBeRestoredAfterRestartAndKeepReviewStatus() {
        val original = TranslationMemory()
        original.put(SavedTranslation("New sign", "ml", "പുതിയ അടയാളം", true))
        val restored = TranslationMemory()
        original.snapshot().forEach { restored.put(it) }
        assertEquals(original.get("New sign", "ml"), restored.get("New sign", "ml"))
    }
}
