package com.example.sg100usb.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class PacketLogEntry(
    val id: Long,
    val timestamp: Long,
    val direction: String,
    val label: String,
    val hex: String,
    val displayText: String,
)

class PacketLogger(
    private val maxEntries: Int = 250,
    private val batchIntervalMs: Long = 50L,
) {
    private val _entries = MutableStateFlow<List<PacketLogEntry>>(emptyList())
    val entries: StateFlow<List<PacketLogEntry>> = _entries.asStateFlow()
    private val nextId = AtomicLong()
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val buffer = ArrayDeque<PacketLogEntry>(maxEntries)
    private var lastEmitMs = 0L

    fun tx(label: String, bytes: ByteArray) = add("TX", label, bytes.toHex())

    fun rx(label: String, bytes: ByteArray) = add("RX", label, bytes.toHex())

    fun message(label: String, message: String) = add("INFO", label, message)

    private fun add(direction: String, label: String, hex: String) {
        val now = System.currentTimeMillis()
        val entry = PacketLogEntry(
            id = nextId.incrementAndGet(),
            timestamp = now,
            direction = direction,
            label = label,
            hex = hex,
            displayText = render(now, direction, label, hex),
        )
        synchronized(this) {
            buffer.addFirst(entry)
            while (buffer.size > maxEntries) buffer.removeLast()
            if (now - lastEmitMs >= batchIntervalMs) {
                publishLocked(now)
            }
        }
    }

    private fun publishLocked(now: Long) {
        lastEmitMs = now
        _entries.value = buffer.toList()
    }

    private fun render(timestamp: Long, direction: String, label: String, hex: String): String {
        val time = synchronized(formatter) { formatter.format(Date(timestamp)) }
        return "$time  $direction  $label  $hex"
    }
}
