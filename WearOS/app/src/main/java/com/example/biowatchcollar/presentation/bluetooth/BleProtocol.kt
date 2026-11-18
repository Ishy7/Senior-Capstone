
package com.example.biowatchcollar.presentation.bluetooth

import java.util.concurrent.atomic.AtomicInteger

object BleProtocol {

    // Command identifiers
    const val CMD_BATTERY_LIFE: Int = 0x01
    const val CMD_SET_VIBRATION_STRENGTH: Int = 0x02
    const val CMD_VIBRATE: Int = 0x03
    const val CMD_HEARTBEAT: Int = 0x04

    // Response status codes
    const val STATUS_SUCCESS: Int = 0x00
    const val STATUS_UNKNOWN_COMMAND: Int = 0x01
    const val STATUS_INVALID_PARAMETERS: Int = 0x02
    const val STATUS_HARDWARE_ERROR: Int = 0x03
    const val STATUS_EXECUTION_FAILED: Int = 0x04

    // Generate unique transaction IDs
    private val transactionCounter = AtomicInteger(0)

    fun nextTransactionId(): Int {
        return transactionCounter.updateAndGet { current ->
            if (current >= 255) 1 else current + 1
        }
    }

    // Build a request packet
    private fun buildRequest(
        command: Int,
        transactionId: Int,
        vararg payload: Int
    ): ByteArray {

        require(command in 0..255)
        require(transactionId in 1..255)
        require(payload.all { it in 0..255 })

        return byteArrayOf(
            command.toByte(),
            transactionId.toByte(),
            *payload.map { it.toByte() }.toByteArray()
        )
    }

    // 1. Request battery percentage
    fun batteryLife(transactionId: Int = nextTransactionId()): ByteArray {
        return buildRequest(
            CMD_BATTERY_LIFE,
            transactionId
        )
    }

    // 2. Set vibration strength
    // Strength 0 means retrieve current strength without changing it.
    fun setVibrationStrength(
        strength: Int,
        transactionId: Int = nextTransactionId()
    ): ByteArray {

        require(strength in 0..10) {
            "Vibration strength must be between 0 and 10."
        }

        return buildRequest(
            CMD_SET_VIBRATION_STRENGTH,
            transactionId,
            strength
        )
    }

    // 3. Activate vibration for a specified duration
    fun vibrate(
        durationMs: Int,
        transactionId: Int = nextTransactionId()
    ): ByteArray {

        require(durationMs in 1..65535) {
            "Duration must be between 1 and 65535 milliseconds."
        }

        val lowByte = durationMs and 0xFF
        val highByte = (durationMs shr 8) and 0xFF

        return buildRequest(
            CMD_VIBRATE,
            transactionId,
            lowByte,
            highByte
        )
    }

    // 4. Check whether the vest is responsive
    fun heartbeat(transactionId: Int = nextTransactionId()): ByteArray {
        return buildRequest(
            CMD_HEARTBEAT,
            transactionId
        )
    }

    // Represents a decoded response from the ESP32
    data class Response(
        val command: Int,
        val transactionId: Int,
        val status: Int,
        val payload: ByteArray
    ) {

        val successful: Boolean
            get() = status == STATUS_SUCCESS

        // Battery percentage, if this is a battery response
        val batteryPercentage: Int?
            get() {
                if (!successful || command != CMD_BATTERY_LIFE) {
                    return null
                }

                return payload.firstOrNull()?.toInt()?.and(0xFF)
            }

        // Vibration strength, if this is a strength response
        val vibrationStrength: Int?
            get() {
                if (!successful ||
                    command != CMD_SET_VIBRATION_STRENGTH
                ) {
                    return null
                }

                return payload.firstOrNull()?.toInt()?.and(0xFF)
            }

        // Human-readable status
        fun statusMessage(): String {
            return when (status) {
                STATUS_SUCCESS -> "Success"
                STATUS_UNKNOWN_COMMAND -> "Unknown command"
                STATUS_INVALID_PARAMETERS -> "Invalid parameters"
                STATUS_HARDWARE_ERROR -> "Hardware error"
                STATUS_EXECUTION_FAILED -> "Execution failed"
                else -> "Unknown error: $status"
            }
        }
    }

    // Decode a response packet received from the ESP32
    fun parseResponse(data: ByteArray): Response {

        require(data.size >= 3) {
            "Response packet is too short."
        }

        val command = data[0].toInt() and 0xFF
        val transactionId = data[1].toInt() and 0xFF
        val status = data[2].toInt() and 0xFF

        val payload = data.copyOfRange(3, data.size)

        val response = Response(
            command = command,
            transactionId = transactionId,
            status = status,
            payload = payload
        )

        if (response.successful) {
            when (command) {

                CMD_BATTERY_LIFE -> {
                    require(payload.size == 1) {
                        "Invalid battery response."
                    }

                    require((payload[0].toInt() and 0xFF) in 0..100) {
                        "Battery percentage must be between 0 and 100."
                    }
                }

                CMD_SET_VIBRATION_STRENGTH -> {
                    require(payload.size == 1) {
                        "Invalid vibration strength response."
                    }

                    require((payload[0].toInt() and 0xFF) in 0..10) {
                        "Vibration strength must be between 0 and 10."
                    }
                }

                CMD_VIBRATE,
                CMD_HEARTBEAT -> {
                    require(payload.isEmpty()) {
                        "Unexpected response data."
                    }
                }
            }
        }

        return response
    }
}
