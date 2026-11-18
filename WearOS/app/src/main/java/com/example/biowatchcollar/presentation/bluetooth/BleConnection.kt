
package com.example.biowatchcollar.presentation.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.UUID

class BleConnection(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onServicesReady()
        fun onResponseReceived(data: ByteArray)
        fun onConnectionError(message: String)
    }

    private val appContext = context.applicationContext

    private var bluetoothGatt: BluetoothGatt? = null

    private var responseCharacteristic:
            BluetoothGattCharacteristic? = null

    private var isConnected = false

    // Handles Bluetooth connection events
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onConnectionError(
                    "Bluetooth connection failed. Error: $status"
                )
                cleanup(gatt)
                return
            }

            when (newState) {

                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true
                    listener.onConnected()

                    // Look for our custom BLE service
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        listener.onConnectionError(
                            "Bluetooth permission missing."
                        )
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    cleanup(gatt)
                    listener.onDisconnected()
                }
            }
        }

        // Called when ESP32 services have been discovered
        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onConnectionError(
                    "Could not discover ESP32 services."
                )
                return
            }

            val service = gatt.getService(
                BleScanner.VEST_SERVICE_UUID
            )

            if (service == null) {
                listener.onConnectionError(
                    "Vest BLE service was not found."
                )
                return
            }

            // Find the response characteristic
            responseCharacteristic = service.getCharacteristic(
                RESPONSE_CHARACTERISTIC_UUID
            )

            if (responseCharacteristic == null) {
                listener.onConnectionError(
                    "Response characteristic was not found."
                )
                return
            }

            // Subscribe to ESP32 notifications
            enableNotifications(gatt)
        }

        // Receives notifications from the ESP32
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == RESPONSE_CHARACTERISTIC_UUID) {
                listener.onResponseReceived(value)
            }
        }

        // Support for older Android versions
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == RESPONSE_CHARACTERISTIC_UUID) {
                characteristic.value?.let {
                    listener.onResponseReceived(it.copyOf())
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CLIENT_CONFIGURATION_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    listener.onServicesReady()
                } else {
                    listener.onConnectionError(
                        "Could not enable notifications."
                    )
                }
            }
        }
    }

    // Connect to the discovered ESP32
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {

        if (bluetoothGatt != null) {
            disconnect()
        }

        try {
            bluetoothGatt = device.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } catch (e: SecurityException) {
            listener.onConnectionError(
                "Bluetooth connection permission missing."
            )
        }
    }

    // Enable notifications from the ESP32
    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt) {

        val characteristic = responseCharacteristic ?: return

        try {
            val notificationEnabled =
                gatt.setCharacteristicNotification(
                    characteristic,
                    true
                )

            if (!notificationEnabled) {
                listener.onConnectionError(
                    "Could not enable characteristic notifications."
                )
                return
            }

            val descriptor = characteristic.getDescriptor(
                CLIENT_CONFIGURATION_UUID
            )

            if (descriptor == null) {
                listener.onConnectionError(
                    "Notification descriptor not found."
                )
                return
            }

            if (Build.VERSION.SDK_INT >= 33) {
                val result = gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                )

                if (result != BluetoothGatt.GATT_SUCCESS) {
                    listener.onConnectionError(
                        "Could not subscribe to notifications."
                    )
                }

            } else {
                @Suppress("DEPRECATION")
                descriptor.value =
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                @Suppress("DEPRECATION")
                val result = gatt.writeDescriptor(descriptor)

                if (!result) {
                    listener.onConnectionError(
                        "Could not subscribe to notifications."
                    )
                }
            }

        } catch (e: SecurityException) {
            listener.onConnectionError(
                "Bluetooth permission missing."
            )
        }
    }

    // Disconnect from the ESP32
    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            bluetoothGatt?.disconnect()
        } catch (e: SecurityException) {
            listener.onConnectionError(
                "Bluetooth permission missing."
            )
            close()
        }
    }

    // Release Bluetooth resources
    @SuppressLint("MissingPermission")
    fun close() {
        val gatt = bluetoothGatt ?: return

        try {
            gatt.close()
        } catch (_: SecurityException) {
            // Bluetooth resources could not be closed
        }

        bluetoothGatt = null
        responseCharacteristic = null
        isConnected = false
    }

    private fun cleanup(gatt: BluetoothGatt) {
        if (bluetoothGatt === gatt) {
            close()
        } else {
            try {
                gatt.close()
            } catch (_: SecurityException) {
                // Ignore cleanup errors
            }
        }
    }

    companion object {

        val RESPONSE_CHARACTERISTIC_UUID: UUID =
            UUID.fromString(
                "12345678-1234-5678-1234-56789abcdef2"
            )

        private val CLIENT_CONFIGURATION_UUID: UUID =
            UUID.fromString(
                "00002902-0000-1000-8000-00805f9b34fb"
            )
    }
}
