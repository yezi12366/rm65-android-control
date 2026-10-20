package com.realman.rm65webview

import org.junit.Assert.*
import org.junit.Test

class ManualHoldLeaseTest {
    @Test fun missingHeartbeatsExpireEvenWhenNoUiReleaseArrives() {
        val lease=ManualHoldLease();lease.begin(1,1_000)
        assertNull(lease.takeExpired(1_499));assertEquals(1,lease.takeExpired(1_500)!!.id)
        assertNull(lease.takeExpired(1_600));assertFalse(lease.renew(1,1_600))
    }
    @Test fun delayedHeartbeatCannotReviveAnExpiredHoldBeforeWorkerChecksIt() {
        val lease=ManualHoldLease();lease.begin(1,1_000)
        assertFalse(lease.renew(1,1_500));assertEquals(1,lease.takeExpired(1_501)!!.id)
    }
    @Test fun heartbeatMustBelongToTheCurrentGesture() {
        val lease=ManualHoldLease();lease.begin(2,1_000)
        assertFalse(lease.renew(1,1_400));assertEquals(2,lease.takeExpired(1_500)!!.id)
    }
    @Test fun stuckPointerCannotHoldForeverEvenWithContinuousHeartbeats() {
        val lease=ManualHoldLease();lease.begin(1,1_000)
        for(t in 1_150L..5_950L step 150)assertTrue(lease.renew(1,t))
        assertFalse(lease.renew(1,6_000));assertTrue(lease.takeExpired(6_000)!!.reason.contains("5 秒"))
    }
    @Test fun releaseAndNewGestureDoNotInheritOldDeadline() {
        val lease=ManualHoldLease();lease.begin(1,1_000);lease.end();assertNull(lease.takeExpired(9_000))
        lease.begin(2,10_000);assertTrue(lease.renew(2,10_400));assertNull(lease.takeExpired(10_500))
    }
}
