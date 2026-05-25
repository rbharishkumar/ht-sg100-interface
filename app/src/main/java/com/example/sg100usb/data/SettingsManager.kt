package com.example.sg100usb.data

import com.example.sg100usb.protocol.RegisterValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class WriteStatus { Idle, Pending, Success, Error }

data class EditableRegister(
    val register: RegisterValue,
    val editedValue: Int = register.raw,
    val dirty: Boolean = false,
    val writeStatus: WriteStatus = WriteStatus.Idle,
    val writeError: String? = null,
)

class SettingsManager {
    private val _edited = MutableStateFlow<Map<Int, EditableRegister>>(emptyMap())
    val edited: StateFlow<Map<Int, EditableRegister>> = _edited.asStateFlow()

    fun loadHoldingRegisters(registers: Map<Int, RegisterValue>) {
        _edited.update { current ->
            registers.mapValues { (address, value) ->
                current[address]?.takeIf { it.dirty } ?: EditableRegister(value)
            }
        }
    }

    fun edit(address: Int, value: Int) {
        _edited.update { current ->
            val existing = current[address] ?: return@update current
            current + (address to existing.copy(
                editedValue = value.coerceIn(existing.register.definition.min, existing.register.definition.max),
                dirty = true,
                writeStatus = WriteStatus.Idle,
                writeError = null,
            ))
        }
    }

    fun markPending(address: Int) {
        _edited.update { current ->
            val existing = current[address] ?: return@update current
            current + (address to existing.copy(writeStatus = WriteStatus.Pending))
        }
    }

    fun markClean(address: Int, actualValue: Int) {
        _edited.update { current ->
            val existing = current[address] ?: return@update current
            current + (address to existing.copy(
                editedValue = actualValue,
                dirty = false,
                writeStatus = WriteStatus.Success,
                writeError = null,
            ))
        }
    }

    fun markError(address: Int, reason: String) {
        _edited.update { current ->
            val existing = current[address] ?: return@update current
            current + (address to existing.copy(writeStatus = WriteStatus.Error, writeError = reason))
        }
    }

    fun clearWriteStatus(address: Int) {
        _edited.update { current ->
            val existing = current[address] ?: return@update current
            if (existing.writeStatus == WriteStatus.Idle) return@update current
            current + (address to existing.copy(writeStatus = WriteStatus.Idle, writeError = null))
        }
    }
}
