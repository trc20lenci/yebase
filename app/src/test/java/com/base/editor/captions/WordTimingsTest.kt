package com.base.editor.captions

import com.base.editor.captions.asr.WordTimings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTimingsTest {
    @Test fun wordsAndPunctuationFollowTokenTimestamps() {
        val w = WordTimings.build("Привет мир!", arrayOf(" При", "вет", " мир", "!"), floatArrayOf(0.10f, 0.30f, 0.80f, 1.10f), 3000)
        assertEquals(listOf("Привет", "мир!"), w.map { it.word })
        assertEquals(100L, w[0].startMs); assertEquals(800L, w[1].startMs)
        assertTrue(w[0].endMs <= 800L)             // слово заканчивается не позже начала следующего
        assertTrue(w[1].endMs <= 3000L)
    }

    @Test fun specialTokensAreIgnored() {
        val w = WordTimings.build("hi", arrayOf("<|ru|>", " hi"), floatArrayOf(0f, 0.2f), 1000)
        assertEquals(listOf("hi"), w.map { it.word })
    }

    @Test fun mismatchedArraysFallBackToProportionalSpread() {
        val w = WordTimings.build("один два", arrayOf(" один"), floatArrayOf(0f, 1f), 2000)
        assertEquals(2, w.size)
        assertEquals(0L, w[0].startMs); assertTrue(w[1].endMs in 1900L..2000L)
    }

    @Test fun brokenUtf8TokensFallBack() {
        val w = WordTimings.build("слово ещё", arrayOf(" сл\uFFFD", "ово"), floatArrayOf(0f, 0.1f), 1000)
        assertEquals(listOf("слово", "ещё"), w.map { it.word })
    }
}
