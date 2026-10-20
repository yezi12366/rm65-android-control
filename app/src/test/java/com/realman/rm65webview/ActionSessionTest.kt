package com.realman.rm65webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSessionTest {
    @Test
    fun remoteRecordingCapturesFreshWaypointsAndExplicitNodes() {
        val session = ActionSession()
        session.begin("a1", "搬运", RecordingType.REMOTE, 6, 100)

        assertTrue(session.captureWaypoint(List(6) { it.toDouble() }, "A1P1", 6))
        session.addGripper(GripperOperation.CLOSE)
        session.addWait(1_000)

        val action = session.finish(200)
        assertEquals(3, action.nodes.size)
        assertTrue(action.nodes[0] is WaypointNode)
        assertTrue(action.nodes[1] is GripperNode)
        assertTrue(action.nodes[2] is WaitNode)
        assertNull(session.draft)
    }

    @Test
    fun playbackCursorAdvancesInOrder() {
        val action = ActionRecord(
            "a", "测试", RecordingType.REMOTE, 5,
            listOf(WaitNode(100), GripperNode(GripperOperation.OPEN)), 1, 1
        )
        val cursor = PlaybackCursor(action)

        assertTrue(cursor.current is WaitNode)
        assertTrue(cursor.advance() is GripperNode)
        assertNull(cursor.advance())
        assertTrue(cursor.finished)
    }
}
