package com.zjsf.gps_ant_bms.bluetooth

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    
    private val handler = Handler(Looper.getMainLooper())
    private var pollingInterval: Long = 1000 // 默认 1 秒
    private var isPolling = false
    
    private var lastConnectedDevice: BluetoothDevice? = null
    private var isAutoReconnectEnabled = true
    private val RECONNECT_DELAY: Long = 2000 // 2 seconds

    // 常量定义
    private val ANT_SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val ANT_CHAR_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // 查询指令: 7E A1 01 00 00 BE 18 55 AA 55
    private val QUERY_COMMAND = byteArrayOf(
        0x7E.toByte(), 0xA1.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(),
        0xBE.toByte(), 0x18.toByte(), 0x55.toByte(), 0xAA.toByte(), 0x55.toByte()
    )

    private val pollingRunnable = object : Runnable {
        override fun run() {
            if (isPolling) {
                sendBmsCommand(QUERY_COMMAND)
                handler.postDelayed(this, pollingInterval)
            }
        }
    }
    
    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (isAutoReconnectEnabled && bluetoothGatt == null) {
                lastConnectedDevice?.let {
                    Log.i("BmsBtManager", "尝试自动重连至: ${it.address}")
                    connect(it, true)
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            onConnectionStateChanged(newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("BmsBtManager", "已连接到 GATT 服务器，开始发现服务...")
                handler.removeCallbacks(reconnectRunnable)
                if (hasConnectPermission()) {
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        Log.e("BmsBtManager", "发现服务时权限异常: ${e.message}")
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i("BmsBtManager", "断开连接")
                stopPolling()
                bluetoothGatt?.close()
                bluetoothGatt = null
                
                if (isAutoReconnectEnabled) {
                    Log.i("BmsBtManager", "2秒后将尝试重连...")
                    handler.postDelayed(reconnectRunnable, RECONNECT_DELAY)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(ANT_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(ANT_CHAR_UUID)

                if (characteristic != null && hasConnectPermission()) {
                    try {
                        // 1. 开启通知
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                        if (descriptor != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                    } catch (e: SecurityException) {
                        Log.e("BmsBtManager", "设置通知时权限异常: ${e.message}")
                    }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("BmsBtManager", "通知已开启，启动定时轮询...")
                startPolling()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == ANT_CHAR_UUID) {
                handleIncomingData(characteristic.value)
            }
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == ANT_CHAR_UUID) {
                handleIncomingData(value)
            }
        }
    }

    fun startPolling(intervalMs: Long = 1000) {
        pollingInterval = intervalMs
        if (!isPolling) {
            isPolling = true
            handler.post(pollingRunnable)
            Log.i("BmsBtManager", "轮询已启动，间隔: ${pollingInterval}ms")
        }
    }

    fun stopPolling() {
        isPolling = false
        handler.removeCallbacks(pollingRunnable)
        Log.i("BmsBtManager", "轮询已停止")
    }

    /**
     * 发送指令到 BMS
     */
    fun sendBmsCommand(command: ByteArray) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(ANT_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(ANT_CHAR_UUID)

        if (characteristic != null && hasConnectPermission()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(characteristic, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                } else {
                    characteristic.value = command
                    gatt.writeCharacteristic(characteristic)
                }
                Log.d("BmsBtManager", "指令发送成功: ${command.joinToString("") { "%02X".format(it) }}")
            } catch (e: SecurityException) {
                Log.e("BmsBtManager", "发送指令时权限异常: ${e.message}")
            }
        }
    }

    private fun handleIncomingData(data: ByteArray) {
        if (data.size >= 2 && data[0] == 0x7E.toByte() && data[1] == 0xA1.toByte()) {
            bmsDataBuffer.clear()
        }

        bmsDataBuffer.addAll(data.toList())

        if (bmsDataBuffer.size >= 2 && 
            bmsDataBuffer[bmsDataBuffer.size - 2] == 0xAA.toByte() && 
            bmsDataBuffer.last() == 0x55.toByte()) {
            
            onDataReceived(bmsDataBuffer.toByteArray())
            bmsDataBuffer.clear()
        }
    }

    fun connect(device: BluetoothDevice, autoReconnect: Boolean = true) {
        isAutoReconnectEnabled = autoReconnect
        lastConnectedDevice = device
        if (hasConnectPermission()) {
            try {
                bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                Log.e("BmsBtManager", "连接时权限异常: ${e.message}")
            }
        }
    }

    fun disconnect() {
        isAutoReconnectEnabled = false
        handler.removeCallbacks(reconnectRunnable)
        stopPolling()
        if (hasConnectPermission()) {
            try {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            } catch (e: SecurityException) {
                Log.e("BmsBtManager", "断开连接时权限异常: ${e.message}")
            }
        }
        bluetoothGatt = null
        bmsDataBuffer.clear()
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}