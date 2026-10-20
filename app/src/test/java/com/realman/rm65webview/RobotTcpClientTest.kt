package com.realman.rm65webview

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** These tests use only a local mock controller at 127.0.0.1, never the robot's configured address. */
class RobotTcpClientTest {
    private class Events(private val release: CountDownLatch? = null) : RobotTcpClient.Listener {
        val connected = LinkedBlockingQueue<Boolean>()
        val disconnected = LinkedBlockingQueue<String>()
        val lines = LinkedBlockingQueue<Pair<String, Long>>()
        val errors = LinkedBlockingQueue<String>()
        override fun onConnected() { connected.put(true); release?.await(3, TimeUnit.SECONDS) }
        override fun onDisconnected(reason: String) { disconnected.put(reason) }
        override fun onLine(line: String, sessionId: Long) { lines.put(line to sessionId) }
        override fun onError(message: String) { errors.put(message) }
    }
    private fun server() = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 3_000 }

    @Test fun framesCommandsWithCrLfAndReadsFragmentedRepliesWithSessionIdentity() {
        server().use { controller ->
            val events = Events()
            val client = RobotTcpClient(events)
            try {
                client.connect("127.0.0.1", controller.localPort)
                controller.accept().use { socket ->
                    socket.soTimeout = 3_000
                    assertEquals(true, events.connected.poll(3, TimeUnit.SECONDS))
                    val id = client.sessionId()
                    client.send(RobotProtocol.currentState())
                    val bytes = mutableListOf<Byte>()
                    val input = socket.getInputStream()
                    while (true) {
                        val byte = input.read()
                        assertTrue(byte >= 0)
                        bytes += byte.toByte()
                        if (byte == 10) break
                    }
                    assertEquals(RobotProtocol.currentState() + "\r\n", bytes.toByteArray().toString(Charsets.UTF_8))
                    val output = socket.getOutputStream()
                    output.write("{\"command\":\"mock\",".toByteArray()); output.flush()
                    output.write("\"state\":true}\r\n".toByteArray()); output.flush()
                    val line = events.lines.poll(3, TimeUnit.SECONDS)!!
                    assertEquals("{\"command\":\"mock\",\"state\":true}", line.first)
                    assertEquals(id, line.second)
                }
            } finally { client.shutdown() }
        }
    }

    @Test fun queryFloodIsCoalescedAndStopSupersedesQueuedMovement() {
        server().use { controller ->
            val release = CountDownLatch(1)
            val events = Events(release)
            val client = RobotTcpClient(events)
            try {
                client.connect("127.0.0.1", controller.localPort)
                controller.accept().use { socket ->
                    assertEquals(true, events.connected.poll(3, TimeUnit.SECONDS))
                    repeat(100) { client.send(RobotProtocol.currentState()) }
                    client.send(RobotProtocol.moveJToZero(1))
                    client.send(RobotProtocol.slowStop())
                    release.countDown()
                    socket.soTimeout = 3_000
                    val reader = socket.getInputStream().bufferedReader()
                    assertEquals(RobotProtocol.slowStop(), reader.readLine())
                    assertEquals(RobotProtocol.currentState(), reader.readLine())
                    socket.soTimeout = 150
                    try { reader.readLine(); fail("A duplicate query or queued movement was sent") }
                    catch (_: SocketTimeoutException) {}
                }
            } finally { release.countDown(); client.shutdown() }
        }
    }

    @Test fun stopSupersedesMotionAndSessionRemainsUsableAfterControllerConfirmation() {
        server().use { controller ->
            val release = CountDownLatch(1)
            val events = Events(release)
            val client = RobotTcpClient(events)
            try {
                client.connect("127.0.0.1", controller.localPort)
                controller.accept().use { socket ->
                    assertEquals(true, events.connected.poll(3, TimeUnit.SECONDS))
                    val id = client.sessionId()
                    client.send(RobotProtocol.moveJToZero(5))
                    client.send(RobotProtocol.stopTeach())
                    client.send(RobotProtocol.slowStop())
                    release.countDown()
                    socket.soTimeout = 3_000
                    val reader = socket.getInputStream().bufferedReader()
                    assertEquals(RobotProtocol.stopTeach(),reader.readLine())
                    assertEquals(RobotProtocol.slowStop(),reader.readLine())
                    val reply = """{"command":"set_arm_slow_stop","arm_slow_stop":true}"""
                    socket.getOutputStream().write((reply + "\r\n").toByteArray())
                    socket.getOutputStream().flush()
                    assertEquals(reply,events.lines.poll(3,TimeUnit.SECONDS)!!.first)
                    assertTrue(client.isConnected());assertEquals(id,client.sessionId());assertTrue(events.disconnected.isEmpty())
                    client.send(RobotProtocol.currentPlanning())
                    assertEquals(RobotProtocol.currentPlanning(),reader.readLine())
                    client.send(RobotProtocol.moveJTo(List(6){1.0},5))
                    assertEquals(RobotProtocol.moveJTo(List(6){1.0},5),reader.readLine())
                }
            } finally { release.countDown();client.shutdown() }
        }
    }

    @Test fun oldQueuedActuationIsNotDeliveredToNewConnection() {
        server().use { first -> server().use { second ->
            val release = CountDownLatch(1)
            val events = Events(release)
            val client = RobotTcpClient(events)
            try {
                client.connect("127.0.0.1", first.localPort)
                first.accept().use { oldSocket ->
                    assertEquals(true, events.connected.poll(3, TimeUnit.SECONDS))
                    val oldId = client.sessionId()
                    client.send(RobotProtocol.gripperClose())
                    client.stopAndDisconnect()
                    client.connect("127.0.0.1", second.localPort)
                    release.countDown()
                    second.accept().use { newSocket ->
                        assertEquals(true, events.connected.poll(3, TimeUnit.SECONDS))
                        assertNotEquals(oldId, client.sessionId())
                        client.send(RobotProtocol.currentState())
                        newSocket.soTimeout = 3_000
                        val reader = newSocket.getInputStream().bufferedReader()
                        assertEquals(RobotProtocol.currentState(), reader.readLine())
                        newSocket.soTimeout = 150
                        try { reader.readLine(); fail("Old gripper actuation crossed a connection boundary") }
                        catch (_: SocketTimeoutException) {}
                    }
                    oldSocket.soTimeout = 3_000
                    assertEquals(-1, oldSocket.getInputStream().read())
                }
            } finally { release.countDown(); client.shutdown() }
        } }
    }

    @Test fun remoteCloseReportsDisconnectionAndInvalidatesSession() {
        server().use { controller ->
            val events = Events()
            val client = RobotTcpClient(events)
            try {
                client.connect("127.0.0.1", controller.localPort)
                val socket = controller.accept()
                assertEquals(true, events.connected.poll(3, TimeUnit.SECONDS))
                val id = client.sessionId()
                socket.close()
                assertNotNull(events.disconnected.poll(3, TimeUnit.SECONDS))
                assertFalse(client.isConnected())
                assertNotEquals(id, client.sessionId())
            } finally { client.shutdown() }
        }
    }

    @Test fun missingOrRejectedStopReceiptKeepsTcpUsableForRetryAndStateQueries() {
        server().use { controller ->
            val events = Events()
            val client = RobotTcpClient(events)
            val gate = ManualStopGate()
            try {
                client.connect("127.0.0.1", controller.localPort)
                controller.accept().use { socket ->
                    socket.soTimeout = 3_000
                    assertEquals(true, events.connected.poll(3, TimeUnit.SECONDS))
                    val session = client.sessionId()
                    val input = socket.getInputStream().bufferedReader()
                    gate.begin(1_000)
                    gate.commandsDue(1_000).forEach(client::send)
                    assertEquals(RobotProtocol.stopTeach(), input.readLine())
                    assertEquals(RobotProtocol.slowStop(), input.readLine())
                    assertNotNull(gate.acknowledge("set_stop_teach", false, 1_100))
                    gate.markUnconfirmed()
                    gate.commandsDue(1_100).forEach(client::send)
                    assertEquals(RobotProtocol.stopTeach(), input.readLine())
                    assertEquals(RobotProtocol.quickStop(), input.readLine())
                    client.send(RobotProtocol.currentState())
                    assertEquals(RobotProtocol.currentState(), input.readLine())
                    assertTrue(gate.pending);assertFalse(gate.ready(2_000))
                    assertTrue(client.isConnected());assertEquals(session,client.sessionId())
                    assertTrue(events.disconnected.isEmpty())
                    gate.acknowledge("set_stop_teach",true,2_100)
                    gate.acknowledge("set_arm_stop",true,2_100)
                    gate.observePlanning("none",2_110,2_120)
                    for(i in 0..6)gate.observeArm(List(6){0.0},i+1L,2_200+i*50L)
                    assertTrue(gate.ready(2_500));assertTrue(client.isConnected())
                }
            } finally {client.shutdown()}
        }
    }

    @Test fun blockedWriteIsClosedByIndependentWatchdog() {
        server().use { controller ->
            val events = Events()
            val client = RobotTcpClient(events, writeTimeoutMs = 250)
            try {
                client.connect("127.0.0.1", controller.localPort)
                controller.accept().use { socket ->
                    socket.receiveBufferSize = 1_024
                    assertEquals(true, events.connected.poll(3, TimeUnit.SECONDS))
                    client.send("{\"command\":\"movej\",\"padding\":\"" + "x".repeat(8 * 1024 * 1024) + "\"}")
                    val reason = events.disconnected.poll(5, TimeUnit.SECONDS)
                    assertNotNull(reason)
                    assertTrue(reason!!.contains("发送超过"))
                    assertFalse(client.isConnected())
                }
            } finally { client.shutdown() }
        }
    }
}
