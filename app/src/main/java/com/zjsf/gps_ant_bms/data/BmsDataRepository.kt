package com.zjsf.gps_ant_bms.data

import android.bluetooth.BluetoothProfile
import android.os.Handler
import android.os.Looper
import com.zjsf.gps_ant_bms.model.BmsData
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App 进程级的共享数据缓存（方案3）。
 *
 * 由 foreground service（方案1）负责写入最新 GPS 速度、BMS 数据与连接状态；
 * MainActivity 与悬浮窗服务共同订阅，界面总是展示同一份最新值。
 *
 * 所有变更都会在主线程通知监听者，因此监听者可以直接刷新 UI。
 */
class BmsDataRepository {

    @Volatile
    private var speedKmh: Double = 0.0

    @Volatile
    private var bmsData: BmsData = BmsData()

    @Volatile
    private var connectionState: Int = BluetoothProfile.STATE_DISCONNECTED

    private val listeners = CopyOnWriteArrayList<(BmsDataRepository) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 最新 GPS 速度（km/h）。 */
    fun getSpeedKmh(): Double = speedKmh

    /** 最新 BMS 状态。 */
    fun getBmsData(): BmsData = bmsData

    /** 当前蓝牙连接状态（[BluetoothProfile] 的状态常量）。 */
    fun getConnectionState(): Int = connectionState

    fun isConnected(): Boolean = connectionState == BluetoothProfile.STATE_CONNECTED

    /** 仅更新速度（GPS 定位回调）。 */
    fun updateSpeed(speed: Double) {
        speedKmh = speed
        notifyChange()
    }

    /** 更新 BMS 数据，并携带创建回调当时的 GPS 速度，保证悬浮窗速度也刷新。 */
    fun updateBms(data: BmsData, speed: Double) {
        bmsData = data
        speedKmh = speed
        notifyChange()
    }

    fun setConnectionState(state: Int) {
        connectionState = state
        notifyChange()
    }

    /** 注册变化监听。返回的 lambda 可移除该监听。 */
    fun addListener(listener: (BmsDataRepository) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (BmsDataRepository) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChange() {
        mainHandler.post { listeners.forEach { it(this) } }
    }

    companion object {
        /** App 级单例。 */
        val instance: BmsDataRepository by lazy { BmsDataRepository() }
    }
}
