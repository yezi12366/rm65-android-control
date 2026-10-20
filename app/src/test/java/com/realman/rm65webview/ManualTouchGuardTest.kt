package com.realman.rm65webview

import org.junit.Assert.*
import org.junit.Test

class ManualTouchGuardTest {
    @Test fun noMotionWithoutPhysicalHold() {
        assertFalse(ManualTouchGuard().beginHold())
    }

    @Test fun physicalReleaseStopsExactlyOnceWithoutAnyDomEvent() {
        val guard = ManualTouchGuard()
        guard.pointerDown(0)
        assertTrue(guard.beginHold())
        assertTrue(guard.pointerUp(0))
        assertFalse(guard.pointerUp(0))
    }

    @Test fun delayedBridgeStartAfterPhysicalReleaseIsRejected() {
        val guard = ManualTouchGuard()
        guard.pointerDown(0)
        assertFalse(guard.pointerUp(0))
        assertFalse(guard.beginHold())
    }

    @Test fun secondFingerCannotReleaseTheOwner() {
        val guard = ManualTouchGuard()
        guard.pointerDown(0)
        assertTrue(guard.beginHold())
        guard.pointerDown(1)
        assertFalse(guard.pointerUp(1))
        assertTrue(guard.pointerUp(0))
    }

    @Test fun secondFingerDoesNotInheritOwnershipAfterPrimaryRelease() {
        val guard = ManualTouchGuard()
        guard.pointerDown(0)
        assertTrue(guard.beginHold())
        guard.pointerDown(1)
        assertTrue(guard.pointerUp(0))
        assertFalse(guard.beginHold())
        guard.pointerUp(1)
        guard.pointerDown(2)
        assertTrue(guard.beginHold())
    }

    @Test fun stoppedGestureCannotRestartUntilLiftAndNewPress() {
        val guard = ManualTouchGuard()
        guard.pointerDown(0)
        assertTrue(guard.beginHold())
        assertFalse(guard.beginHold())
        guard.endHold()
        assertFalse(guard.beginHold())
        guard.pointerUp(0)
        guard.pointerDown(0)
        assertTrue(guard.beginHold())
    }

    @Test fun cancellationOrFocusLossInvalidatesPendingBridgeCalls() {
        val guard = ManualTouchGuard()
        guard.pointerDown(0)
        assertTrue(guard.beginHold())
        assertTrue(guard.cancelAll())
        assertFalse(guard.beginHold())
        assertFalse(guard.cancelAll())
    }
}
