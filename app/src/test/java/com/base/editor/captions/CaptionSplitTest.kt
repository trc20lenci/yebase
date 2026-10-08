package com.base.editor.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionSplitTest {
    private fun item() = CaptionItem("a", 0, 4000, "раз два три четыре",
        listOf(WordTimestamp("раз", 0, 800), WordTimestamp("два", 1000, 1800), WordTimestamp("три", 2200, 3000), WordTimestamp("четыре", 3200, 4000)))

    @Test fun splitDividesWordsByMidpoint() {
        val (l, r) = CaptionOps.split(item(), 2000)!!
        assertEquals("раз два", l.text); assertEquals("три четыре", r.text)
        assertEquals(2000L, l.endMs); assertEquals(2000L, r.startMs)
        assertTrue(l.id != r.id)
    }

    @Test fun splitNearEdgeIsRejected() { assertEquals(null, CaptionOps.split(item(), 100)) }

    @Test fun trimKeepsWordTimings() {
        val t = CaptionOps.trim(item(), 500, 3500)
        assertEquals(500L, t.startMs); assertEquals(1000L, t.words[1].startMs)
    }
}
