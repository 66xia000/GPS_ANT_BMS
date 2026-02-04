package com.zjsf.gps_ant_bms.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

class BleScanner(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val onDeviceFound: (ScanResult) -> Unit,
    private val onScanStarted: () -> Unit,
    private val onScanStopped: () -> Unit
) {
    private val scanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (hasPermissions()) {
                onDeviceFound(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BleScanner", "Scan failed: $errorCode")
            stopScan()
        }
    }

    fun startScan(scanPeriod: Long) {
        if (isScanning || scanner == null) return

        if (!hasPermissions()) {
            Log.e("BleScanner", "Missing permissions for scanning")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
            isScanning = true
            onScanStarted()

            handler.postDelayed({
                stopScan()
            }, scanPeriod)
        } catch (e: SecurityException) {
            Log.e("BleScanner", "SecurityException starting scan: ${e.message}")
            isScanning = false
            onScanStopped()
        }
    }

    fun stopScan() {
        if (!isScanning) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e("BleScanner", "SecurityException stopping scan: ${e.message}")
        } finally {
            isScanning = false
            handler.removeCallbacksAndMessages(null)
            onScanStopped()
        }
    }

    private fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
}
