package com.base.editor.domain

import com.base.editor.core.ClipTransform
import com.base.editor.core.Keyframe
import com.base.editor.core.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeTrackTest {

    private fun k(ms: Long, x: Float = 0f, scale: Float = 1f) = Keyframe(ms, x, 0f, scale, 0f)

    @Test fun emptyTrackIsIdentity() {
        assertEquals(ClipTransform(), KeyframeTrack.at(emptyList(), 500))
    }

    @Test fun beforeFirstAndAfterLastKeyAreClamped() {
        val keys = listOf(k(1_000, x = 0.5f), k(3_000, x = -0.5f))
        assertEquals(0.5f, KeyframeTrack.at(keys, 0).x, 1e-6f)
        assertEquals(-0.5f, KeyframeTrack.at(keys, 9_999).x, 1e-6f)
    }

    @Test fun linearInterpolationBetweenNeighbours() {
        // value = V1 + (V2 - V1) * t — как в Lottie BaseKeyframeAnimation
        val keys = listOf(k(0, x = 0f, scale = 1f), k(1_000, x = 1f, scale = 2f))
        val mid = KeyframeTrack.at(keys, 250)
        assertEquals(0.25f, mid.x, 1e-6f)
        assertEquals(1.25f, mid.scale, 1e-6f)
        val threeQuarter = KeyframeTrack.at(keys, 750)
        assertEquals(0.75f, threeQuarter.x, 1e-6f)
    }

    @Test fun unsortedKeysAreHandled() {
        val keys = listOf(k(2_000, x = 2f), k(0, x = 0f))
        assertEquals(1f, KeyframeTrack.at(keys, 1_000).x, 1e-6f)
    }

    @Test fun upsertReplacesKeyAtSameTime() {
        val keys = KeyframeTrack.upsert(listOf(k(1_000, x = 0.1f)), k(1_000, x = 0.9f))
        assertEquals(1, keys.size)
        assertEquals(0.9f, keys.single().x, 1e-6f)
    }

    @Test fun removeAtFindsKeyWithTolerance() {
        val (rest, removed) = KeyframeTrack.removeAt(listOf(k(1_000), k(2_000)), 1_010)
        assertTrue(removed)
        assertEquals(listOf(k(2_000)), rest)
        assertFalse(KeyframeTrack.removeAt(listOf(k(1_000)), 1_500).second)
    }
}

class KeyframeModelTest {

    @Test fun toggleAddsThenRemovesKey() {
        val m = TimelineModel()
        val a = m.addClip(0, MediaType.VIDEO, "a", 10_000, 10_000)
        assertTrue(m.toggleKeyframe(a, 2_000))
        assertEquals(1, m.state().keyframesOf(a).size)
        assertEquals(2_000, m.state().keyframesOf(a).single().timeMs)
        assertFalse(m.toggleKeyframe(a, 2_010))            // курсор на ключе (±40 мс) — удаление
        assertTrue(m.state().keyframesOf(a).isEmpty())
    }

    @Test fun autoKeyFixesGestureCoordinates() {
        val m = TimelineModel()
        val a = m.addClip(0, MediaType.VIDEO, "a", 10_000, 10_000)
        m.toggleKeyframe(a, 0)
        m.checkpoint()
        m.setKeyframe(a, 5_000, ClipTransform(x = 0.4f, scale = 1.6f))
        val t = m.state().transformAt(a, 2_500)
        assertEquals(0.2f, t.x, 1e-6f)
        assertEquals(1.3f, t.scale, 1e-6f)
    }

    @Test fun splitDistributesKeysBetweenHalves() {
        val m = TimelineModel()
        val a = m.addClip(0, MediaType.VIDEO, "a", 10_000, 10_000)
        m.toggleKeyframe(a, 1_000)
        m.toggleKeyframe(a, 7_000)
        val right = m.split(a, 4_000)
        assertEquals(listOf(1_000L), m.state().keyframesOf(a).map { it.timeMs })
        assertEquals(listOf(3_000L), m.state().keyframesOf(right).map { it.timeMs })
    }

    @Test fun keysSurviveSerializeRoundTrip() {
        val m = TimelineModel()
        val a = m.addClip(0, MediaType.VIDEO, "a", 10_000, 10_000)
        m.toggleKeyframe(a, 500)
        m.setKeyframe(a, 500, ClipTransform(x = 0.3f, y = -0.1f, scale = 1.4f, rotationDeg = 15f))
        m.toggleKeyframe(a, 8_000)
        val copy = TimelineModel()
        assertTrue(copy.load(m.serialize()))
        assertEquals(m.serialize(), copy.serialize())
        val t = copy.state().keyframesOf(a).first { it.timeMs == 500L }
        assertEquals(0.3f, t.x, 1e-6f); assertEquals(15f, t.rotationDeg, 1e-6f)
    }

    @Test fun keysAreClampedAfterTrim() {
        val m = TimelineModel()
        val a = m.addClip(0, MediaType.VIDEO, "a", 10_000, 10_000)
        m.toggleKeyframe(a, 9_000)
        m.checkpoint(); m.trimEnd(a, 5_000)
        assertEquals(listOf(5_000L), m.state().keyframesOf(a).map { it.timeMs })
    }

    @Test fun undoRestoresKeys() {
        val m = TimelineModel()
        val a = m.addClip(0, MediaType.VIDEO, "a", 10_000, 10_000)
        m.checkpoint(); m.toggleKeyframe(a, 1_000)
        assertEquals(1, m.state().keyframesOf(a).size)
        assertTrue(m.undo())
        assertTrue(m.state().keyframesOf(a).isEmpty())
        assertTrue(m.redo())
        assertEquals(1, m.state().keyframesOf(a).size)
    }
}

class AudioTrackModelTest {

    @Test fun audioClipLivesOnOwnRowAndMovesFreely() {
        val m = TimelineModel()
        m.addClip(0, MediaType.VIDEO, "v", 8_000, 8_000)
        val a = m.addClip(1, MediaType.AUDIO, "music", 60_000, 60_000)
        m.checkpoint(); assertTrue(m.moveClip(a, 2_000, 0, -1))
        assertEquals(2_000, m.state().clips.first { it.id == a }.startMs)
        // основная дорожка не пострадала
        assertEquals(0, m.state().clips.first { it.row == 0 }.startMs)
    }

    @Test fun audioClipTrimsWithinSourceDuration() {
        val m = TimelineModel()
        val a = m.addClip(1, MediaType.AUDIO, "music", 10_000, 10_000)
        m.checkpoint(); assertTrue(m.trimEnd(a, 60_000))   // нельзя длиннее исходника
        assertEquals(10_000, m.state().clips.first { it.id == a }.lengthMs)
        m.checkpoint(); assertTrue(m.trimStart(a, 2_000))
        val c = m.state().clips.first { it.id == a }
        assertEquals(2_000, c.startMs); assertEquals(2_000, c.srcInMs); assertEquals(8_000, c.lengthMs)
    }

    @Test fun audioClipExtendsProjectDuration() {
        val m = TimelineModel()
        m.addClip(0, MediaType.VIDEO, "v", 5_000, 5_000)
        m.addClip(1, MediaType.AUDIO, "music", 20_000, 20_000)
        assertEquals(20_000, m.totalMs)
    }

    @Test fun audioSurvivesSerializeRoundTrip() {
        val m = TimelineModel()
        m.addClip(0, MediaType.VIDEO, "v", 5_000, 5_000)
        m.addClip(1, MediaType.AUDIO, "content://music/1", 30_000, 12_000)
        val copy = TimelineModel()
        assertTrue(copy.load(m.serialize()))
        val a = copy.state().clips.single { it.type == MediaType.AUDIO }
        assertEquals(1, a.row); assertEquals(30_000, a.srcDurMs); assertEquals(12_000, a.lengthMs)
    }

    @Test fun removingAudioLeavesMainTrackUntouched() {
        val m = TimelineModel()
        val v = m.addClip(0, MediaType.VIDEO, "v", 5_000, 5_000)
        val a = m.addClip(1, MediaType.AUDIO, "music", 20_000, 20_000)
        m.checkpoint(); m.remove(a)
        assertNull(m.state().clips.firstOrNull { it.type == MediaType.AUDIO })
        assertEquals(5_000, m.state().clips.first { it.id == v }.lengthMs)
    }
}
