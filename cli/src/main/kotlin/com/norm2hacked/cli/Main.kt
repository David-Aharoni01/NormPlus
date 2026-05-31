package com.norm2hacked.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.norm2hacked.protocol.Action
import com.norm2hacked.protocol.CommandCode
import com.norm2hacked.protocol.WatchTransport
import com.norm2hacked.protocol.commands.BatteryCommand
import com.norm2hacked.protocol.commands.BrightnessCommand
import com.norm2hacked.protocol.commands.DeviceVersionCommand
import com.norm2hacked.protocol.commands.DoNotDisturbCommand
import com.norm2hacked.protocol.commands.SyncCountCommand
import com.norm2hacked.protocol.commands.SwitchSettingCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// ─── Root command ─────────────────────────────────────────────────────────────

class NormlinkCli : CliktCommand(name = "normlink-cli", help = "Norm 2 watch BLE test tool (Windows CLI)") {
    override fun run() = Unit
}

// ─── Shared options mixin ─────────────────────────────────────────────────────

abstract class WatchCommand(name: String, help: String) : CliktCommand(name = name, help = help) {
    protected val mac by option("--mac", "-m", help = "Watch Bluetooth MAC address (e.g. 4C:59:80:12:44:F1)").required()
    protected val json by option("--json", "-j", help = "Output structured JSON instead of human-readable text").flag()
    protected val timeout by option("--timeout", "-t", help = "Per-command timeout in milliseconds").int().default(10_000)

    protected fun transport(): WatchTransport = PythonBridgeTransport()

    protected fun <T> withWatch(block: suspend CoroutineScope.(WatchTransport) -> T): T = runBlocking {
        val t = transport()
        try {
            t.connect(mac)
            block(t)
        } catch (e: Throwable) {
            // Top-level boundary: report concisely and exit non-zero — no stack trace.
            val msg = when (e) {
                is TimeoutCancellationException -> "timed out waiting for the watch ($mac)"
                else -> e.message ?: e.toString()
            }
            System.err.println("error: $msg")
            throw ProgramResult(1)
        } finally {
            t.disconnect()
        }
    }
}

// ─── battery ─────────────────────────────────────────────────────────────────

class BatteryCmd : WatchCommand("battery", "Query battery level and charging state") {
    override fun run() = withWatch { t ->
        val pkt = t.sendAndAwait(CommandCode.BATTERY_POWER, Action.CHECK, byteArrayOf(0x00), timeout.toLong())
        val state = BatteryCommand.parse(pkt)
        if (json) {
            println("""{"battery":${state.percent},"charging":${state.charging}}""")
        } else {
            println("Battery: ${state.percent}%${if (state.charging) " (charging)" else ""}")
        }
    }
}

// ─── version ─────────────────────────────────────────────────────────────────

class VersionCmd : WatchCommand("version", "Query device version string") {
    private val type by option("--type", help = "Version type byte (default 6 = full info string)").int().default(6)
    override fun run() = withWatch { t ->
        val pkt = t.sendAndAwait(CommandCode.DEVICE_VERSION, Action.CHECK, byteArrayOf(type.toByte()), timeout.toLong())
        val ver = DeviceVersionCommand.parseVersionString(pkt)
        if (json) println("""{"version":"$ver"}""")
        else println("Version: $ver")
    }
}

// ─── sync-count ───────────────────────────────────────────────────────────────

class SyncCountCmd : WatchCommand("sync-count", "Query sport/sleep/heart-rate record counts on watch") {
    override fun run() = withWatch { t ->
        val sportSleepPkt = t.sendAndAwait(CommandCode.TOTAL_SPORT_SLEEP_COUNT, Action.CHECK, byteArrayOf(0x00), timeout.toLong())
        val hrPkt = t.sendAndAwait(CommandCode.TOTAL_HEART_RATE_COUNT, Action.CHECK, byteArrayOf(0x00), timeout.toLong())
        val sport = SyncCountCommand.parseSportCount(sportSleepPkt)
        val sleep = SyncCountCommand.parseSleepCount(sportSleepPkt)
        val hr = SyncCountCommand.parseHrCount(hrPkt)
        if (json) {
            println("""{"sport":$sport,"sleep":$sleep,"hr":$hr}""")
        } else {
            println("Sport sessions : $sport")
            println("Sleep records  : $sleep")
            println("Heart rate recs: $hr")
        }
    }
}

// ─── brightness ───────────────────────────────────────────────────────────────

class BrightnessCmd : WatchCommand("brightness", "Query or set screen brightness (0-100)") {
    private val set by option("--set", "-s", help = "Set brightness level (0-100)").int()
    override fun run() = withWatch { t ->
        if (set != null) {
            t.sendAndAwait(BrightnessCommand.CMD, Action.SET, BrightnessCommand.setPayload(set!!), timeout.toLong())
            if (json) println("""{"brightness":$set,"changed":true}""")
            else println("Brightness set to $set")
        } else {
            val pkt = t.sendAndAwait(BrightnessCommand.CMD, Action.CHECK, BrightnessCommand.queryPayload(), timeout.toLong())
            val level = BrightnessCommand.parse(pkt)
            if (json) println("""{"brightness":$level}""")
            else println("Brightness: $level")
        }
    }
}

// ─── dnd ─────────────────────────────────────────────────────────────────────

class DndCmd : WatchCommand("dnd", "Query do-not-disturb settings") {
    override fun run() = withWatch { t ->
        val pkt = t.sendAndAwait(DoNotDisturbCommand.CMD, Action.CHECK, DoNotDisturbCommand.queryPayload(), timeout.toLong())
        val dnd = DoNotDisturbCommand.parse(pkt)
        if (json) {
            println("""{"enabled":${dnd.enabled},"start":"${dnd.startHour}:${"%02d".format(dnd.startMin)}","end":"${dnd.endHour}:${"%02d".format(dnd.endMin)}"}""")
        } else {
            println("DND enabled : ${dnd.enabled}")
            println("DND hours   : ${dnd.startHour}:${"%02d".format(dnd.startMin)} – ${dnd.endHour}:${"%02d".format(dnd.endMin)}")
        }
    }
}

// ─── switch ──────────────────────────────────────────────────────────────────

class SwitchCmd : WatchCommand("switch", "Query switch-settings bitmask") {
    override fun run() = withWatch { t ->
        val pkt = t.sendAndAwait(SwitchSettingCommand.CMD, Action.CHECK, SwitchSettingCommand.queryPayload(), timeout.toLong())
        val mask = SwitchSettingCommand.parse(pkt)
        if (json) {
            println("""{"mask":$mask,"hex":"0x${"%08X".format(mask)}"}""")
        } else {
            println("Switch mask: 0x${"%08X".format(mask)} ($mask)")
            println("  RAISE_WAKE       : ${mask and SwitchSettingCommand.BIT_RAISE_WAKE != 0}")
            println("  AUTO_SYNC        : ${mask and SwitchSettingCommand.BIT_AUTO_SYNC != 0}")
            println("  SLEEP detection  : ${mask and SwitchSettingCommand.BIT_SLEEP != 0}")
            println("  HR monitoring    : ${mask and SwitchSettingCommand.BIT_HEART_RATE_MONITOR != 0}")
            println("  Notifications/call: ${mask and SwitchSettingCommand.BIT_CALL != 0}")
        }
    }
}

// ─── raw ─────────────────────────────────────────────────────────────────────

class RawCmd : WatchCommand("raw", "Send a raw command byte + action and print the response") {
    private val cmdByte by option("--cmd-byte", "-c", help = "Command byte (hex, e.g. 08)").required()
    private val actionByte by option("--action-byte", "-a", help = "Action byte (hex, e.g. 70)").required()
    private val payload by option("--payload", "-p", help = "Hex payload (e.g. 00 or empty)").default("")

    override fun run() = withWatch { t ->
        val cmd = CommandCode.fromByte(cmdByte.trimStart('0', 'x').toInt(16).toByte())
            ?: run { System.err.println("Unknown command byte: $cmdByte"); return@withWatch }
        val action = Action.fromByte(actionByte.trimStart('0', 'x').toInt(16).toByte())
            ?: run { System.err.println("Unknown action byte: $actionByte"); return@withWatch }
        val payloadBytes = if (payload.isBlank()) byteArrayOf()
            else payload.trim().split("\\s+".toRegex()).map { it.toInt(16).toByte() }.toByteArray()

        val pkt = t.sendAndAwait(cmd, action, payloadBytes, timeout.toLong())
        val hex = pkt.payload.joinToString(" ") { "%02X".format(it) }
        if (json) {
            println("""{"cmd":"${pkt.cmdCode}","action":"${pkt.action}","payload":"$hex","payloadHex":"${pkt.payload.joinToString("") { "%02X".format(it) }}"}""")
        } else {
            println("Response: cmd=${pkt.cmdCode} action=${pkt.action}")
            println("Payload (${pkt.payload.size} bytes): $hex")
        }
    }
}

// ─── watch ────────────────────────────────────────────────────────────────────

class WatchCmd : WatchCommand("watch", "Passively listen for notifications without sending anything") {
    private val duration by option("--duration", "-d", help = "Listen duration in seconds").int().default(10)
    override fun run() = withWatch { t ->
        println("Listening for ${duration}s on ${mac}…")
        val collector = launch(Dispatchers.Default) {
            t.parsedFlow.collect { pkt ->
                val hex = pkt.payload.joinToString(" ") { "%02X".format(it) }
                println("  <- ${pkt.cmdCode}/${pkt.action}  payload=[$hex]")
            }
        }
        delay(duration * 1000L)
        collector.cancel()
        println("Watch period ended.")
    }
}

// ─── Entry point ─────────────────────────────────────────────────────────────

fun main(args: Array<String>) = NormlinkCli()
    .subcommands(
        BatteryCmd(),
        VersionCmd(),
        SyncCountCmd(),
        BrightnessCmd(),
        DndCmd(),
        SwitchCmd(),
        RawCmd(),
        WatchCmd(),
    )
    .main(args)
