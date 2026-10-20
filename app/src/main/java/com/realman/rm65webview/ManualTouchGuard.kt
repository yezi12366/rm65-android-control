package com.realman.rm65webview

/** Native touch ownership is independent of WebView telemetry and DOM pointer delivery. Main thread only. */
class ManualTouchGuard {
    private val down = mutableSetOf<Int>()
    private var primary: Int? = null
    private var owner: Int? = null
    private var consumed = false

    fun pointerDown(id: Int) {
        if (down.isEmpty()) {
            primary = id
            consumed = false
        }
        down.add(id)
    }

    fun beginHold(): Boolean {
        if (down.size != 1 || primary == null || consumed) return false
        owner = primary
        consumed = true
        return true
    }

    /** True means the physical finger that owned a manual hold has been released. */
    fun pointerUp(id: Int): Boolean {
        down.remove(id)
        val released = owner == id
        if (released) owner = null
        if (primary == id) primary = null
        if (down.isEmpty()) consumed = false
        return released
    }

    fun endHold() { owner = null }

    fun cancelAll(): Boolean {
        val released = owner != null
        down.clear()
        primary = null
        owner = null
        consumed = false
        return released
    }
}
