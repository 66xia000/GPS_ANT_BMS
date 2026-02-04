package com.zjsf.gps_ant_bms.bluetooth

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.*

class BmsBluetoothManager(
    private val context: Context,
    private val onDataReceived: (ByteArray) -> Unit,
    private val onConnectionStateChanged: (Int) -> Unit
) {
    private var bluetoothGatt: BluetoothGatt? = null
    private val bmsDataBuffer = mutableListOf<Byte>()

    private val ANT_SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val ANT_CHAR_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            onConnectionStateChanged(newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("BmsBtManager", "Connected to GATT server.")
                if (hasConnectPermission()) {
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        Log.e("BmsBtManager", "SecurityException discovering services: ${e.message}")
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i("BmsBtManager", "Disconnected from GATT server.")
                bluetoothGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(ANT_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(ANT_CHAR_UUID)
                
                if (characteristic != null && hasConnectPermission()) {
                    try {
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    } catch (e: SecurityException) {
                        Log.e("BmsBtManager", "SecurityException enabling notification: ${e.message}")
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == ANT_CHAR_UUID) {
                handleIncomingData(characteristic.value)
            }
        }
    }

    private fun handleIncomingData(data: ByteArray) {
        if (data.size >= 2 && data[0] == 0x7E.toByte() && data[1] == 0xA1.toByte()) {
            bmsDataBuffer.clear()
        }
        
        bmsDataBuffer.addAll(data.toList())

        if (bmsDataBuffer.isNotEmpty() && bmsDataBuffer.last() == 0x55.toByte()) {
            onDataReceived(bmsDataBuffer.toByteArray())
            bmsDataBuffer.clear()
        }
    }

    fun connect(device: BluetoothDevice) {
        if (hasConnectPermission()) {
            try {
                bluetoothGatt = device.connectGatt(context, false, gattCallback)
            } catch (e: SecurityException) {
                Log.e("BmsBtManager", "SecurityException connecting: ${e.message}")
            }
        }
    }

    fun disconnect() {
        if (hasConnectPermission()) {
            try {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            } catch (e: SecurityException) {
                Log.e("BmsBtManager", "SecurityException disconnecting: ${e.message}")
            }
        }
        bluetoothGatt = null
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
