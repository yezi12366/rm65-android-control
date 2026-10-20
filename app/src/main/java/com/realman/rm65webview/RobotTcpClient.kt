package com.realman.rm65webview

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

class RobotTcpClient(private val listener: Listener, private val writeTimeoutMs: Long = 3_000L) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onLine(line: String, sessionId: Long)
        fun onError(message: String)
    }
    private val epoch = AtomicLong()
    private val sequence = AtomicLong()
    private val connected = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val queuedQueries = ConcurrentHashMap.newKeySet<String>()
    private class Task(val priority: Int, val order: Long, val body: () -> Unit) : Runnable {
        override fun run() = body()
    }
    private val writerExecutor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        PriorityBlockingQueue<Runnable>(16, Comparator { a, b ->
            a as Task; b as Task
            val priority = a.priority.compareTo(b.priority)
            if (priority != 0) priority else a.order.compareTo(b.order)
        }), { runnable -> Thread(runnable, "rm65-json-writer").apply { isDaemon = true } })
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "rm65-write-watchdog").apply { isDaemon = true }
    }
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var writingSocket: Socket? = null
    @Volatile private var writeStartedAt = 0L

    init {
        watchdog.scheduleAtFixedRate({
            val current = writingSocket
            if (current != null && current === socket &&
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - writeStartedAt) >= writeTimeoutMs) {
                fail(current, "TCP 发送超过 ${writeTimeoutMs}ms 未完成，连接已关闭；运动指令不会在重连后自动重发。")
            }
        }, 50, 50, TimeUnit.MILLISECONDS)
    }
    fun isConnected(): Boolean = connected.get()
    fun sessionId(): Long = epoch.get()

    fun connect(host: String, port: Int) {
        if (closed.get()) return
        val id = epoch.incrementAndGet()
        closeSocket("正在重新连接", invalidate = false, notify = false)
        submit(0) {
            if (epoch.get() != id) return@submit
            val newSocket = Socket()
            socket = newSocket
            try {
                newSocket.tcpNoDelay = true
                newSocket.keepAlive = true
                newSocket.connect(InetSocketAddress(host, port), 3_000)
                if (epoch.get() != id || socket !== newSocket) {
                    newSocket.close()
                    return@submit
                }
                writer = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8))
                connected.set(true)
                startReader(newSocket, id)
                listener.onConnected()
            } catch (error: Exception) {
                try { newSocket.close() } catch (_: Exception) {}
                if (epoch.get() == id) {
                    listener.onError("连接失败：${error.message ?: error.javaClass.simpleName}")
                    closeSocket("连接失败", expected = newSocket)
                }
            }
        }
    }

    fun send(json: String) {
        if (closed.get()) return
        val command = JSONObject(json).getString("command")
        val current = socket
        if (!connected.get() || current == null) {
            if (!command.startsWith("get_") && command !in STOP_COMMANDS) {
                submit(0) { listener.onError("机械臂未连接，指令未发送。") }
            }
            return
        }
        val id = epoch.get()
        val query = command.startsWith("get_")
        val key = "$id:$command"
        if (query && !queuedQueries.add(key)) return
        val stopping = command in STOP_COMMANDS
        if (stopping) writerExecutor.queue.removeIf { (it as Task).priority == 1 }
        if (!stopping && writerExecutor.queue.size >= MAX_QUEUED_WRITES) {
            if (query) queuedQueries.remove(key)
            fail(current, "TCP 待发送队列过长，连接已关闭，避免延迟指令继续执行。")
            return
        }
        submit(if (stopping) 0 else if (query) 2 else 1) {
            try {
                if (epoch.get() == id && socket === current && connected.get()) writeDirect(current, json)
            } catch (error: Exception) {
                fail(current, "发送失败：${error.message}")
            } finally {
                if (query) queuedQueries.remove(key)
            }
        }
    }

    fun stopAndDisconnect(reason: String = "连接已关闭") {
        if (closed.get()) return
        val id = epoch.incrementAndGet()
        writerExecutor.queue.clear()
        queuedQueries.clear()
        val current = socket
        if (!connected.get() || current == null) {
            closeSocket("连接已取消", expected = current, invalidate = false)
            return
        }
        submit(0) {
            if (epoch.get() != id || socket !== current) return@submit
            try {
                listOf(RobotProtocol.stopDragTeach(), RobotProtocol.stopDragTrajectory(),
                    RobotProtocol.stopTeach(), RobotProtocol.slowStop()).forEach { writeDirect(current, it) }
            } catch (_: Exception) {
                // Transport failure cannot guarantee a physical stop.
            } finally {
                closeSocket(reason, expected = current)
            }
        }
    }

    fun shutdown() {
        if (closed.get()) return
        stopAndDisconnect()
        closed.set(true)
        writerExecutor.shutdown()
        watchdog.schedule({ closeSocket("程序已关闭"); watchdog.shutdown() }, writeTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun submit(priority: Int, body: () -> Unit) {
        if (writerExecutor.isShutdown) return
        try { writerExecutor.execute(Task(priority, sequence.incrementAndGet(), body)) }
        catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun writeDirect(current: Socket, json: String) {
        if (socket !== current || !connected.get()) return
        val output = writer ?: return
        writeStartedAt = System.nanoTime()
        writingSocket = current
        try {
            output.write(json)
            output.write("\r\n")
            output.flush()
        } finally { writingSocket = null }
    }

    private fun startReader(readerSocket: Socket, id: Long) {
        Thread({
            try {
                BufferedReader(InputStreamReader(readerSocket.getInputStream(), StandardCharsets.UTF_8)).use { input ->
                    while (readerSocket === socket) {
                        val line = input.readLine() ?: break
                        if (line.isNotBlank() && epoch.get() == id) listener.onLine(line, id)
                    }
                }
            } catch (error: Exception) {
                if (readerSocket === socket && connected.get()) listener.onError("接收中断：${error.message}")
            } finally { closeSocket("控制器关闭了 TCP 连接", expected = readerSocket) }
        }, "rm65-json-reader").apply { isDaemon = true; start() }
    }

    private fun fail(current: Socket, message: String) {
        if (current !== socket) return
        listener.onError(message)
        closeSocket(message, expected = current)
    }

    @Synchronized
    private fun closeSocket(reason: String, expected: Socket? = null, invalidate: Boolean = true, notify: Boolean = true) {
        if (expected != null && socket !== expected) return
        val current = socket
        val wasConnected = connected.getAndSet(false)
        socket = null
        writer = null
        if (invalidate) epoch.incrementAndGet()
        // Socket.close interrupts an in-flight write; BufferedWriter.close could wait behind that write.
        try { current?.close() } catch (_: Exception) {}
        if (wasConnected && notify) listener.onDisconnected(reason)
    }

    companion object {
        private const val MAX_QUEUED_WRITES = 32
        private val STOP_COMMANDS = setOf("set_stop_teach", "set_arm_stop", "set_arm_slow_stop",
            "stop_drag_teach", "stop_drag_trajectory", "set_arm_pause", "pause_drag_trajectory")
    }
}
