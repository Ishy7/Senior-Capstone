
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
        fun onCommandWriteComplete(success: Boolean, message: String?)
        fun onConnectionError(message: String)
    }

    private val appContext = context.applicationContext

    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var responseCharacteristic: BluetoothGattCharacteristic? = null

    private var isConnected = false
    private var servicesReady = false

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
                listener.onDisconnected()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true
                    listener.onConnected()

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

            commandCharacteristic = service.getCharacteristic(
                COMMAND_CHARACTERISTIC_UUID
            )

            responseCharacteristic = service.getCharacteristic(
                RESPONSE_CHARACTERISTIC_UUID
            )

            if (commandCharacteristic == null) {
                listener.onConnectionError(
                    "Command characteristic was not found."
                )
                return
            }

            if (responseCharacteristic == null) {
                listener.onConnectionError(
                    "Response characteristic was not found."
                )
                return
            }

            enableNotifications(gatt)
        }

        // Receives notifications from the ESP32.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == RESPONSE_CHARACTERISTIC_UUID) {
                listener.onResponseReceived(value.copyOf())
            }
        }

        // Support for older Android versions.
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

        // Confirms whether a command write succeeded.
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == COMMAND_CHARACTERISTIC_UUID) {
                val success = status == BluetoothGatt.GATT_SUCCESS

                listener.onCommandWriteComplete(
                    success,
                    if (success) null
                    else "Command transmission failed. Error: $status"
                )
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CLIENT_CONFIGURATION_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    servicesReady = true
                    listener.onServicesReady()
                } else {
                    listener.onConnectionError(
                        "Could not enable notifications."
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (bluetoothGatt != null) {
            disconnect()
            close()
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

    // Sends one command to the ESP32.
    @SuppressLint("MissingPermission")
    fun sendCommand(data: ByteArray): Boolean {
        val gatt = bluetoothGatt
        val characteristic = commandCharacteristic

        if (!isConnected || !servicesReady || gatt == null) {
            listener.onConnectionError(
                "Cannot send command: vest is not ready."
            )
            return false
        }

        if (characteristic == null) {
            listener.onConnectionError(
                "Command characteristic is unavailable."
            )
            return false
        }

        if (data.isEmpty()) {
            listener.onConnectionError(
                "Cannot send an empty command."
            )
            return false
        }

        return try {
            characteristic.writeType =
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            if (Build.VERSION.SDK_INT >= 33) {
                val result = gatt.writeCharacteristic(
                    characteristic,
                    data,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )

                if (result != BluetoothGatt.GATT_SUCCESS) {
                    listener.onCommandWriteComplete(
                        false,
                        "Could not start command transmission. Error: $result"
                    )
                    false
                } else {
                    true
                }
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = data

                @Suppress("DEPRECATION")
                val result = gatt.writeCharacteristic(characteristic)

                if (!result) {
                    listener.onCommandWriteComplete(
                        false,
                        "Could not start command transmission."
                    )
                }

                result
            }
        } catch (e: SecurityException) {
            listener.onCommandWriteComplete(
                false,
                "Bluetooth permission missing."
            )
            false
        }
    }

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

    @SuppressLint("MissingPermission")
    fun close() {
        val gatt = bluetoothGatt ?: return

        try {
            gatt.close()
        } catch (_: SecurityException) {
            // Ignore cleanup errors.
        }

        bluetoothGatt = null
        commandCharacteristic = null
        responseCharacteristic = null
        isConnected = false
        servicesReady = false
    }

    private fun cleanup(gatt: BluetoothGatt) {
        if (bluetoothGatt === gatt) {
            close()
        } else {
            try {
                gatt.close()
            } catch (_: SecurityException) {
                // Ignore cleanup errors.
            }
        }
    }

    companion object {
        val COMMAND_CHARACTERISTIC_UUID: UUID =
            UUID.fromString(
                "12345678-1234-5678-1234-56789abcdef1"
            )

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
