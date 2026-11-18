
package com.example.biowatchcollar.presentation.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

class BleScanner(
    context: Context,
    private val listener: Listener
) {

    // Other parts of the app can receive scan results
    interface Listener {
        fun onVestFound(device: BluetoothDevice, rssi: Int)
        fun onScanError(message: String)
        fun onScanFailed(errorCode: Int)
    }

    // Keep an application-level context
    private val appContext = context.applicationContext

    // Access the Android Bluetooth system
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager

    private val bluetoothAdapter = bluetoothManager.adapter

    private var isScanning = false

    private val foundDevices = mutableSetOf<String>()

    // Handles incoming Bluetooth scan results
    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            val device = result.device

            // Only report each device once per scan
            if (foundDevices.add(device.address)) {
                listener.onVestFound(device, result.rssi)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            listener.onScanFailed(errorCode)
        }
    }

    // Start looking for the ESP32
    @SuppressLint("MissingPermission")
    fun startScanning() {

        if (isScanning) return

        if (bluetoothAdapter == null) {
            listener.onScanError("Bluetooth is not supported.")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            listener.onScanError("Bluetooth is turned off.")
            return
        }

        val scanner: BluetoothLeScanner? =
            bluetoothAdapter.bluetoothLeScanner

        if (scanner == null) {
            listener.onScanError("Bluetooth scanner unavailable.")
            return
        }

        // Clear previously discovered devices
        foundDevices.clear()

        // Only discover devices advertising our vest service
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(VEST_SERVICE_UUID))
            .build()

        // Use low-latency scanning for initial discovery
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(
                listOf(filter),
                settings,
                scanCallback
            )

            isScanning = true

        } catch (exception: SecurityException) {
            listener.onScanError(
                "Bluetooth permission was not granted."
            )
        }
    }

    // Stop looking for devices
    @SuppressLint("MissingPermission")
    fun stopScanning() {

        if (!isScanning) return

        try {
            bluetoothAdapter?.bluetoothLeScanner
                ?.stopScan(scanCallback)

            isScanning = false

        } catch (exception: SecurityException) {
            listener.onScanError(
                "Bluetooth permission was not granted."
            )
        }
    }

    companion object {

        // Custom UUID shared by the watch and ESP32
        val VEST_SERVICE_UUID: UUID = UUID.fromString(
            "12345678-1234-5678-1234-56789abcdef0"
        )
    }
}
