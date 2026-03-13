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

    // 常量定义
    private val ANT_SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val ANT_CHAR_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Python 示例中的查询指令: 5A 5A 00 00 01 01
    private val QUERY_COMMAND = byteArrayOf(0x5A.toByte(), 0x5A.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x01.toByte())

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            onConnectionStateChanged(newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("BmsBtManager", "已连接到 GATT 服务器，开始发现服务...")
                if (hasConnectPermission()) {
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        Log.e("BmsBtManager", "发现服务时权限异常: ${e.message}")
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i("BmsBtManager", "断开连接")
                bluetoothGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(ANT_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(ANT_CHAR_UUID)

                if (characteristic != null && hasConnectPermission()) {
                    try {
                        // 1. 开启通知 (类似 Python 的 start_notify)
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
                Log.i("BmsBtManager", "通知已开启，发送初始化查询指令...")
                // 2. 通知开启成功后，发送 Python 示例中的查询指令
                sendBmsCommand(QUERY_COMMAND)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == ANT_CHAR_UUID) {
                // 处理接收到的原始数据
                handleIncomingData(characteristic.value)
            }
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            // Android 13+ 回调
            if (characteristic.uuid == ANT_CHAR_UUID) {
                handleIncomingData(value)
            }
        }
    }

    /**
     * 发送指令到 BMS (对应 Python 的 write_gatt_char)
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
        } else {
            Log.e("BmsBtManager", "无法发送指令：特征值未找到或无权限")
        }
    }

    private fun handleIncomingData(data: ByteArray) {
        // 如果数据以 7E A1 开头，说明是新包起始
        if (data.size >= 2 && data[0] == 0x7E.toByte() && data[1] == 0xA1.toByte()) {
            bmsDataBuffer.clear()
        }

        bmsDataBuffer.addAll(data.toList())

        // 假设 0x55 是结束符（根据你原有逻辑）
        if (bmsDataBuffer.isNotEmpty() && bmsDataBuffer.last() == 0x55.toByte()) {
            onDataReceived(bmsDataBuffer.toByteArray())
            // 某些 BMS 可能需要循环查询，如果数据不是自动推送的，可以在这里按需再次调用 sendBmsCommand
            bmsDataBuffer.clear()
        }
    }

    fun connect(device: BluetoothDevice) {
        if (hasConnectPermission()) {
            try {
                // 使用 TRANSPORT_LE 强制指定 BLE 连接，提高成功率
                bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                Log.e("BmsBtManager", "连接时权限异常: ${e.message}")
            }
        }
    }

    fun disconnect() {
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