
package com.example.biowatchcollar.presentation.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Handler
import android.os.Looper

class BleManager(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onStatusChanged(status: String)
        fun onVestFound(device: BluetoothDevice, rssi: Int)
        fun onConnected()
        fun onDisconnected()
        fun onServicesReady()

        fun onBatteryReceived(percentage: Int)
        fun onVibrationStrengthReceived(strength: Int)
        fun onCommandCompleted(command: Int)

        fun onError(message: String)
    }

    private val appContext = context.applicationContext

    private val mainHandler = Handler(Looper.getMainLooper())

    private var connection: BleConnection? = null

    private var autoConnect = true
    private var isConnecting = false
    private var isReady = false

    // Only one request may be waiting for a response at a time.
    private data class PendingRequest(
        val command: Int,
        val transactionId: Int,
        val timeoutRunnable: Runnable
    )

    private var pendingRequest: PendingRequest? = null

    private val requestTimeoutMs = 5000L

    // Handles finding nearby ESP32 devices.
    private val scanner = BleScanner(
        appContext,
        object : BleScanner.Listener {

            override fun onVestFound(
                device: BluetoothDevice,
                rssi: Int
            ) {
                listener.onVestFound(device, rssi)

                // Automatically connect to the first discovered vest.
                if (autoConnect && !isConnecting && !isReady) {
                    listener.onStatusChanged(
                        "Vest found. Connecting..."
                    )

                    connect(device)
                }
            }

            override fun onScanError(message: String) {
                listener.onError(message)
            }

            override fun onScanFailed(errorCode: Int) {
                listener.onError(
                    "Bluetooth scanning failed. Error: $errorCode"
                )
            }
        }
    )

    // Starts looking for the ESP32 vest.
    fun startScanning(autoConnect: Boolean = true) {
        this.autoConnect = autoConnect

        listener.onStatusChanged("Searching for vest...")

        try {
            scanner.startScanning()
        } catch (e: SecurityException) {
            listener.onError(
                "Bluetooth scan permission is missing."
            )
        }
    }

    // Stops looking for nearby devices.
    fun stopScanning() {
        try {
            scanner.stopScanning()
            listener.onStatusChanged("Scanning stopped.")
        } catch (e: SecurityException) {
            listener.onError(
                "Bluetooth scan permission is missing."
            )
        }
    }

    // Connects to a discovered ESP32.
    fun connect(device: BluetoothDevice) {
        stopScanning()

        connection?.close()
        connection = null

        isConnecting = true
        isReady = false

        listener.onStatusChanged("Connecting to vest...")

        connection = BleConnection(
            appContext,
            object : BleConnection.Listener {

                override fun onConnected() {
                    listener.onStatusChanged(
                        "Connected. Discovering services..."
                    )
                }

                override fun onDisconnected() {
                    isConnecting = false
                    isReady = false

                    failPendingRequest(
                        "Vest disconnected before responding."
                    )

                    listener.onDisconnected()
                    listener.onStatusChanged("Disconnected.")
                }

                override fun onServicesReady() {
                    isConnecting = false
                    isReady = true

                    listener.onConnected()
                    listener.onServicesReady()

                    listener.onStatusChanged(
                        "Vest connected and ready."
                    )
                }

                override fun onResponseReceived(data: ByteArray) {
                    handleResponse(data)
                }

                override fun onCommandWriteComplete(
                    success: Boolean,
                    message: String?
                ) {
                    if (!success) {
                        failPendingRequest(
                            message ?: "Command transmission failed."
                        )
                    }
                }

                override fun onConnectionError(message: String) {
                    listener.onError(message)
                }
            }
        )

        connection?.connect(device)
    }

    // Requests the vest's battery percentage.
    fun getBattery(): Boolean {
        val packet = BleProtocol.batteryLife()
        return sendRequest(packet)
    }

    // Sets the vibration strength from 0 to 10.
    // A strength of 0 queries the current setting.
    fun setVibrationStrength(strength: Int): Boolean {
        return try {
            val packet = BleProtocol.setVibrationStrength(strength)
            sendRequest(packet)
        } catch (e: IllegalArgumentException) {
            listener.onError(
                e.message ?: "Invalid vibration strength."
            )
            false
        }
    }

    // Activates the vest's vibration motor.
    // Duration is measured in milliseconds.
    fun vibrate(durationMs: Int): Boolean {
        return try {
            val packet = BleProtocol.vibrate(durationMs)
            sendRequest(packet)
        } catch (e: IllegalArgumentException) {
            listener.onError(
                e.message ?: "Invalid vibration duration."
            )
            false
        }
    }

    // Checks whether the vest is still responding.
    fun sendHeartbeat(): Boolean {
        val packet = BleProtocol.heartbeat()
        return sendRequest(packet)
    }

    // Sends a packet and waits for its matching response.
    private fun sendRequest(packet: ByteArray): Boolean {

        if (!isReady || connection == null) {
            listener.onError(
                "Cannot send command: vest is not connected and ready."
            )
            return false
        }

        if (pendingRequest != null) {
            listener.onError(
                "Another command is still waiting for a response."
            )
            return false
        }

        if (packet.size < 2) {
            listener.onError("Invalid command packet.")
            return false
        }

        val command = packet[0].toInt() and 0xFF
        val transactionId = packet[1].toInt() and 0xFF

        val timeoutRunnable = Runnable {
            val pending = pendingRequest

            if (pending?.transactionId == transactionId) {
                pendingRequest = null

                listener.onError(
                    "The vest did not respond to command $command."
                )
            }
        }

        pendingRequest = PendingRequest(
            command = command,
            transactionId = transactionId,
            timeoutRunnable = timeoutRunnable
        )

        mainHandler.postDelayed(
            timeoutRunnable,
            requestTimeoutMs
        )

        val sent = connection?.sendCommand(packet) ?: false

        if (!sent) {
            failPendingRequest(
                "Could not send command to the vest."
            )
            return false
        }

        listener.onStatusChanged(
            "Command $command sent. Waiting for response..."
        )

        return true
    }

    // Interprets an incoming ESP32 response.
    private fun handleResponse(data: ByteArray) {
        val response = try {
            BleProtocol.parseResponse(data)
        } catch (e: IllegalArgumentException) {
            listener.onError(
                "Received an invalid response from the vest: ${e.message}"
            )
            return
        }

        val pending = pendingRequest

        if (pending == null) {
            listener.onError(
                "Received an unexpected response from the vest."
            )
            return
        }

        // Make sure this response belongs to our current request.
        if (response.command != pending.command ||
            response.transactionId != pending.transactionId
        ) {
            listener.onError(
                "Received a response that does not match the pending command."
            )
            return
        }

        mainHandler.removeCallbacks(pending.timeoutRunnable)
        pendingRequest = null

        if (!response.successful) {
            listener.onError(
                "Vest rejected command: ${response.statusMessage()}"
            )
            return
        }

        when (response.command) {
            0x01 -> {
                response.batteryPercentage?.let {
                    listener.onBatteryReceived(it)
                }
            }

            0x02 -> {
                response.vibrationStrength?.let {
                    listener.onVibrationStrengthReceived(it)
                }
            }

            0x03, 0x04 -> {
                // Vibrate and heartbeat have no response payload.
            }

            else -> {
                listener.onError(
                    "Received an unknown command response."
                )
                return
            }
        }

        listener.onCommandCompleted(response.command)
        listener.onStatusChanged(
            "Command ${response.command} completed successfully."
        )
    }

    private fun failPendingRequest(message: String) {
        val pending = pendingRequest ?: return

        mainHandler.removeCallbacks(pending.timeoutRunnable)
        pendingRequest = null

        listener.onError(message)
    }

    // Disconnects from the vest.
    fun disconnect() {
        failPendingRequest("Disconnected from vest.")

        isReady = false
        isConnecting = false

        connection?.disconnect()
        listener.onStatusChanged("Disconnecting...")
    }

    // Releases Bluetooth resources when the manager is no longer needed.
    fun close() {
        stopScanning()

        failPendingRequest("BLE manager closed.")

        connection?.close()
        connection = null

        isReady = false
        isConnecting = false

        listener.onStatusChanged("BLE manager closed.")
    }
}
