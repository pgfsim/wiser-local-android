package dev.wiserlocal.app

import java.io.Closeable
import java.net.HttpURLConnection

/**
 * An open connection to the hub's event stream. [forEachEvent] blocks, reading
 * "event:" and "data:" lines and calling back once per event, until the hub closes
 * the stream or [close] is called from another thread.
 */
class HubEventStream(private val conn: HttpURLConnection) : Closeable {

    /** Calls [onEvent] with each event's name ("message" when it has none) and data. */
    fun forEachEvent(onEvent: (name: String, data: String) -> Unit) {
        conn.inputStream.bufferedReader().use { reader ->
            var name: String? = null
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: return
                when {
                    line.isEmpty() -> {
                        if (name != null || data.isNotEmpty()) onEvent(name ?: "message", data.toString())
                        name = null
                        data.setLength(0)
                    }
                    line.startsWith(":") -> Unit // a comment or keep-alive
                    line.startsWith("event:") -> name = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append("\n")
                        data.append(line.removePrefix("data:").removePrefix(" "))
                    }
                }
            }
        }
    }

    override fun close() {
        runCatching { conn.disconnect() }
    }
}
