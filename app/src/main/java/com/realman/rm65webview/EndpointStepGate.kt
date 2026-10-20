package com.realman.rm65webview

import kotlin.math.abs
import kotlin.math.sqrt

/** A step remains busy until its command, arm completion and fresh position agree. */
class EndpointStepGate {
    var active = false
        private set
    private var accepted = false
    private var completed = false
    private var origin = emptyList<Double>()
    private var distanceM = 0.0
    private var startSequence = 0L
    private var completionSequence = 0L
    private var startedAt = 0L

    fun begin(pose: List<Double>, distanceMm: Double, sequence: Long, now: Long) {
        require(!active) { "上一条步进尚未结束" }
        require(pose.size == 6 && pose.all { it.isFinite() }) { "末端位姿数据不完整" }
        require(abs(distanceMm) in setOf(1.0, 5.0, 10.0)) { "步进距离无效" }
        origin = pose.take(3)
        distanceM = abs(distanceMm) / 1_000.0
        startSequence = sequence
        startedAt = now
        completionSequence = sequence
        accepted = false
        completed = false
        active = true
    }

    fun acknowledge(success: Boolean) {
        if (active) accepted = success
    }

    fun complete(device: Int, success: Boolean, sequence: Long) {
        if (!active || !accepted || device != 0) return
        completed = success
        completionSequence = sequence
    }

    fun reached(pose: List<Double>, sequence: Long): Boolean {
        if (!active || !accepted || !completed || sequence <= startSequence ||
            sequence <= completionSequence || pose.size < 3 || pose.take(3).any { !it.isFinite() }) return false
        val travelled = sqrt((0..2).sumOf { (pose[it] - origin[it]) * (pose[it] - origin[it]) })
        // Distance is invariant under work/tool frame rotation. Completion alone is insufficient.
        return abs(travelled - distanceM) <= 0.00025
    }

    fun timedOut(now: Long): Boolean = active && now - startedAt >= 15_000L

    fun reset() {
        active = false
        accepted = false
        completed = false
        origin = emptyList()
    }
}
