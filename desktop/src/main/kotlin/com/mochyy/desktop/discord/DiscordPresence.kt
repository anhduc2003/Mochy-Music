package com.mochyy.desktop.discord

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Discord Rich Presence over the local IPC socket.
 *
 * This is the one part of Discord support that could not be copied from the phone build at all.
 * Android reaches Discord over its own OAuth app and a remote gateway; on a desktop, Discord
 * exposes a named pipe (`\\?\pipe\discord-ipc-0`) or a Unix socket and speaks a small binary
 * protocol: 8-byte header (opcode + length, little-endian) then a JSON body.
 *
 * Only what the player needs is implemented: handshake, then repeated SET_ACTIVITY. Nothing here
 * needs Discord to be installed — [connect] fails quietly and presence is simply off.
 */
class DiscordPresence(private val clientId: String) {

    private companion object {
        const val OP_HANDSHAKE = 0
        const val OP_FRAME = 1
        const val OP_CLOSE = 2
        const val OP_PING = 3
        const val OP_PONG = 4
    }

    private var pipe: RandomAccessFile? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val isConnected: Boolean get() = pipe != null

    /** tries each IPC endpoint in turn; false means Discord isn't running, which is not an error */
    fun connect(): Boolean {
        if (pipe != null) return true
        for (index in 0..9) {
            val handle = openPipe(index) ?: continue
            pipe = handle
            if (handshake()) return true
            disconnect()
        }
        return false
    }

    private fun openPipe(index: Int): RandomAccessFile? {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        // Discord's flatpak and snap builds move the socket, so the usual places are all tried
        val candidates = if (isWindows) {
            listOf("\\\\?\\pipe\\discord-ipc-$index")
        } else {
            listOf(
                "/tmp/discord-ipc-$index",
                "${System.getenv("XDG_RUNTIME_DIR") ?: "/run/user/${uid()}"}/discord-ipc-$index",
                "${System.getenv("TMPDIR") ?: "/tmp"}/discord-ipc-$index",
                "/run/user/${uid()}/app/com.discordapp.Discord/discord-ipc-$index",
            )
        }
        for (path in candidates) {
            if (!isWindows && !File(path).exists()) continue
            return runCatching { RandomAccessFile(path, "rw") }.getOrNull()
        }
        return null
    }

    private fun uid(): String = runCatching {
        // no Posix API on plain JVM; the env var covers the common cases
        System.getenv("UID") ?: "1000"
    }.getOrDefault("1000")

    private fun handshake(): Boolean {
        val body = buildJsonObject { put("v", 1); put("client_id", clientId) }.toString()
        if (!writeFrame(OP_HANDSHAKE, body)) return false
        // the reply echoes the user's Discord name; reading it also proves the pipe is live
        return readFrame() != null
    }

    /** sets what Discord shows. An empty [details] clears the presence. */
    fun setActivity(details: String, state: String, startedAtEpochMs: Long?, largeText: String? = null): Boolean {
        val handle = pipe ?: return false
        val activity = buildJsonObject {
            if (details.isNotBlank()) put("details", details.take(128))
            if (state.isNotBlank()) put("state", state.take(128))
            if (startedAtEpochMs != null) {
                putJsonObject("timestamps") { put("start", startedAtEpochMs / 1000) }
            }
            putJsonObject("assets") {
                put("large_image", "mochy")
                if (largeText != null) put("large_text", largeText.take(128))
            }
        }
        val frame = buildJsonObject {
            put("cmd", "SET_ACTIVITY")
            putJsonObject("args") {
                put("pid", ProcessHandle.current().pid())
                put("activity", activity)
            }
            put("nonce", java.util.UUID.randomUUID().toString())
        }.toString()
        return writeFrame(OP_FRAME, frame)
    }

    fun clearActivity(): Boolean {
        val frame = buildJsonObject {
            put("cmd", "SET_ACTIVITY")
            putJsonObject("args") {
                put("pid", ProcessHandle.current().pid())
                put("activity", kotlinx.serialization.json.JsonNull)
            }
            put("nonce", java.util.UUID.randomUUID().toString())
        }.toString()
        return writeFrame(OP_FRAME, frame)
    }

    private fun writeFrame(opcode: Int, body: String): Boolean {
        val handle = pipe ?: return false
        return runCatching {
            val payload = body.toByteArray(Charsets.UTF_8)
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(opcode)
                .putInt(payload.size)
                .array()
            synchronized(handle) {
                handle.write(header)
                handle.write(payload)
            }
            true
        }.getOrDefault(false)
    }

    private fun readFrame(): JsonObject? {
        val handle = pipe ?: return null
        return runCatching {
            val header = ByteArray(8)
            handle.readFully(header)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val opcode = buffer.int
            val length = buffer.int
            if (length <= 0) return null
            if (opcode == OP_PING) {
                // keep the pipe alive and answer, so Discord doesn't drop us mid-song
                val ping = ByteArray(length)
                handle.readFully(ping)
                writeFrame(OP_PONG, String(ping, Charsets.UTF_8))
                return null
            }
            val body = ByteArray(length)
            handle.readFully(body)
            json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject
        }.getOrNull()
    }

    fun disconnect() {
        runCatching {
            writeFrame(OP_CLOSE, "{}")
            pipe?.close()
        }
        pipe = null
    }
}
