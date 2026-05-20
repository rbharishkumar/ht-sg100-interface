package com.example.sg100usb.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PacketLogEntry(
    val timestamp: Long,
    val direction: String,
    val label: String,
    val hex: String,
)

class PacketLogger(private val maxEntries: Int = 250) {
    private val _entries = MutableStateFlow<List<PacketLogEntry>>(emptyList())
    val entries: StateFlow<List<PacketLogEntry>> = _entries.asStateFlow()

    fun tx(label: String, bytes: ByteArray) = add("TX", label, bytes.toHex())

    fun rx(label: String, bytes: ByteArray) = add("RX", label, bytes.toHex())

    fun message(label: String, message: String) = add("INFO", label, message)

    private fun add(direction: String, label: String, hex: String) {
        val entry = PacketLogEntry(System.currentTimeMillis(), direction, label, hex)
        _entries.update { current -> (current + entry).takeLast(maxEntries) }
    }
}

fun PacketLogEntry.render(): String {
    val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
    return "$time  $direction  $label  $hex"
}
