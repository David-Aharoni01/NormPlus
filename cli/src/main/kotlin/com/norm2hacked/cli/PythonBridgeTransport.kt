package com.norm2hacked.cli

import com.norm2hacked.protocol.Action
import com.norm2hacked.protocol.CommandCode
import com.norm2hacked.protocol.Packet
import com.norm2hacked.protocol.PacketBuilder
import com.norm2hacked.protocol.PacketDeframer
import com.norm2hacked.protocol.WatchTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * [WatchTransport] backed by the bundled Python (bleak) BLE bridge.
 *
 * The watch's BLE stack on Windows is owned by a child process
 * (`python -m norm2_probe bridge <MAC>`), which connects, performs the one-time
 * Just Works bonding, subscribes to notifications, and exchanges raw bytes over a
 * tiny stdio line protocol:
 *
 *   stdout:  READY | N <hex> | ERR <msg>
 *   stdin :  W <hex> | QUIT
 *
 * **All protocol logic stays here in Kotlin/`:protocol`** — this class builds frames
 * with [PacketBuilder] and reassembles notifications with [PacketDeframer], exactly
 * like the Android app. The process boundary isolates the JVM from any
 * bleak/WinRT instability.
 */
class PythonBridgeTransport(
    private val pythonExe: String = System.getenv("NORMLINK_PYTHON")?.takeIf { it.isNotBlank() } ?: "python",
    pythonDirOverride: String? = System.getenv("NORMLINK_PYTHON_DIR"),
) : WatchTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _parsedFlow = MutableSharedFlow<Packet>(extraBufferCapacity = 64)
    override val parsedFlow: SharedFlow<Packet> = _parsedFlow.asSharedFlow()

    private val deframer = PacketDeframer()
    private val writeMutex = Mutex()

    @Volatile private var process: Process? = null
    @Volatile private var stdin: OutputStreamWriter? = null
    @Volatile private var ready = false

    private val pythonDir: File = resolvePythonDir(pythonDirOverride)

    override val isConnected: Boolean get() = ready && process?.isAlive == true

    // ── connect ─────────────────────────────────────────────────────────────

    override suspend fun connect(mac: String): Unit = withContext(Dispatchers.IO) {
        if (!pythonDir.isDirectory) {
            error("Python bridge package not found at ${pythonDir.absolutePath}. " +
                "Set NORMLINK_PYTHON_DIR or run from the repo root.")
        }
        val pb = ProcessBuilder(pythonExe, "-m", "norm2_probe", "bridge", mac).apply {
            environment()["PYTHONPATH"] = pythonDir.absolutePath
            environment()["PYTHONUTF8"] = "1"
            environment()["PYTHONUNBUFFERED"] = "1"
            redirectErrorStream(false)
        }
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            error("Failed to launch Python bridge ('$pythonExe'). Is Python on PATH? ${e.message}")
        }
        process = proc
        stdin = OutputStreamWriter(proc.outputStream, Charsets.UTF_8)

        // Forward bridge stderr (human/debug logs) to our stderr.
        scope.launch {
            runCatching {
                proc.errorStream.bufferedReader().lineSequence().forEach { System.err.println("[bridge] $it") }
            }
        }

        // Read the stdout protocol stream: READY / N <hex> / ERR <msg>.
        val readyDeferred = CompletableDeferred<Unit>()
        scope.launch {
            runCatching {
                proc.inputStream.bufferedReader().lineSequence().forEach { line ->
                    when {
                        line == "READY" -> { ready = true; readyDeferred.complete(Unit) }
                        line.startsWith("N ") -> {
                            val bytes = line.substring(2).hexToBytes()
                            val pkt = deframer.feed(bytes)
                            if (pkt != null) _parsedFlow.emit(pkt)
                        }
                        line.startsWith("ERR ") -> {
                            val msg = line.substring(4)
                            if (!readyDeferred.isCompleted) {
                                readyDeferred.completeExceptionally(IllegalStateException("bridge error: $msg"))
                            } else {
                                System.err.println("[bridge] ERR $msg")
                            }
                        }
                    }
                }
            }
            ready = false
            if (!readyDeferred.isCompleted) {
                readyDeferred.completeExceptionally(IllegalStateException("bridge exited before READY (exit=${runCatching { proc.exitValue() }.getOrNull()})"))
            }
        }

        withTimeout(45_000) { readyDeferred.await() }
    }

    // ── sendAndAwait ────────────────────────────────────────────────────────

    override suspend fun sendAndAwait(
        cmd: CommandCode,
        action: Action,
        payload: ByteArray,
        timeoutMs: Long,
    ): Packet {
        check(isConnected) { "Not connected" }
        val expectedAction = when (action) {
            Action.CHECK -> Action.CHECK_RESPONSE
            Action.SET -> Action.SET_RESPONSE
            else -> action
        }
        val frame = PacketBuilder.build(cmd, action, payload)
        return withTimeout(timeoutMs) {
            coroutineScope {
                // Subscribe BEFORE writing: onSubscription fires once the collector is
                // registered, so we never miss a response that arrives immediately.
                val subscribed = CompletableDeferred<Unit>()
                val deferred = async(Dispatchers.Default) {
                    parsedFlow
                        .onSubscription { subscribed.complete(Unit) }
                        .filter { it.cmdCode == cmd && it.action == expectedAction }
                        .first()
                }
                subscribed.await()
                writeFrame(frame)
                deferred.await()
            }
        }
    }

    // ── writeToChar (fire-and-forget) ───────────────────────────────────────

    override fun writeToChar(bytes: ByteArray, charUuid: UUID) {
        // The bridge always writes to its configured write characteristic; the
        // specific UUID is not selectable from the line protocol (not needed for CLI tests).
        scope.launch { runCatching { writeFrame(bytes) } }
    }

    private suspend fun writeFrame(bytes: ByteArray) {
        val w = stdin ?: error("Not connected")
        val line = "W " + bytes.toHex() + "\n"
        writeMutex.withLock {
            withContext(Dispatchers.IO) { w.write(line); w.flush() }
        }
    }

    // ── disconnect ──────────────────────────────────────────────────────────

    override fun disconnect() {
        runCatching {
            stdin?.let { w -> synchronized(w) { w.write("QUIT\n"); w.flush() } }
        }
        process?.let { proc ->
            if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
        ready = false
        scope.cancel()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        val s = trim()
        return ByteArray(s.length / 2) {
            ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
        }
    }

    /** Resolve the dir that contains the `norm2_probe` package. */
    private fun resolvePythonDir(override: String?): File {
        override?.takeIf { it.isNotBlank() }?.let { return File(it) }
        // Bundled in the installDist image at <APP_HOME>/app/python.
        System.getenv("APP_HOME")?.let { home ->
            val bundled = File(home, "app/python")
            if (bundled.isDirectory) return bundled
        }
        // Dev fallback: walk up from the working dir looking for cli/src/main/python.
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val d = dir ?: return@repeat
            val cand = File(d, "cli/src/main/python")
            if (cand.isDirectory) return cand
            dir = d.parentFile
        }
        return File("cli/src/main/python")
    }
}
