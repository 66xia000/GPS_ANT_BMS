package com.zjsf.gps_ant_bms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.zjsf.gps_ant_bms.bluetooth.BmsBluetoothManager
import com.zjsf.gps_ant_bms.data.BmsDataRepository
import com.zjsf.gps_ant_bms.location.LocationHelper
import com.zjsf.gps_ant_bms.protocol.AntProtocol

/**
 * 方案1 + 方案3：
 * 该前台服务是 GPS + BLE 数据的**唯一采集者**（自持数据源），保证退到后台也能持续取数，
 * 同时把最新数据写入 [BmsDataRepository] 共享给 MainActivity；悬浮窗自身也从仓库订阅刷新。
 */
class FloatingWindowService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var locationHelper: LocationHelper? = null
    private var bmsBluetoothManager: BmsBluetoothManager? = null

    private val repository = BmsDataRepository.instance

    private val CHANNEL_ID = "FloatingWindowServiceChannel"
    private val NOTIFICATION_ID = 1
    private val PREFS_NAME = "BmsPrefs"
    private val PREF_FLOATING_WINDOW = "floating_window_enabled"
    private val PREF_LAST_DEVICE_ADDRESS = "last_device_address"

    private val repositoryListener: (BmsDataRepository) -> Unit = {
        updateOverlay()
        maybeStopSelf()
    }

    companion object {
        private var instance: FloatingWindowService? = null

        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"

        /**
         * MainActivity 的“悬浮窗”开关被关闭时通知服务：隐藏悬浮窗，
         * 并在无连接且悬浮窗关闭时停止自身。
         */
        fun onFloatingWindowPrefChanged(enabled: Boolean) {
            instance?.let {
                it.setOverlayPref(enabled)
                it.updateOverlayVisibility()
                it.maybeStopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startAsForeground()

        repository.addListener(repositoryListener)
        initLocationUpdates()
        initBle()

        // 绑定悬浮窗视图（是否显示由偏好决定）
        showFloatingWindow()

        // 进程被回收后由 START_STICKY 恢复时，自动重连上次设备
        getLastDeviceAddress()?.let { connect(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        if (!address.isNullOrEmpty()) {
            saveLastDeviceAddress(address)
            connect(address)
        }
        updateOverlayVisibility()
        // 刚发出连接指令时蓝牙尚未连上（状态仍为 DISCONNECTED），此时不要误停服务；
        // 是否停服交给后续连接状态变化事件去判定。
        if (address.isNullOrEmpty()) {
            maybeStopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        repository.removeListener(repositoryListener)
        locationHelper?.stopLocationUpdates()
        bmsBluetoothManager?.disconnect()
        if (floatingView?.isAttachedToWindow == true) {
            windowManager?.removeView(floatingView)
        }
    }

    // ---------------------------------------------------------------------
    // 前台服务必须件
    // ---------------------------------------------------------------------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "BMS Floating Window Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun startAsForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BMS 正在后台运行")
            .setContentText("悬浮窗服务已开启，实时监控电池状态")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------------
    // 数据源采集（方案1：服务自持）
    // ---------------------------------------------------------------------
    private fun initLocationUpdates() {
        locationHelper = LocationHelper(this) { location ->
            repository.updateSpeed(location.speed * 3.6)
        }
        locationHelper?.startLocationUpdates()
    }

    private fun initBle() {
        bmsBluetoothManager = BmsBluetoothManager(
            this,
            onDataReceived = { data ->
                AntProtocol.processAntData(data)?.let { bms ->
                    repository.updateBms(bms, repository.getSpeedKmh())
                }
            },
            onConnectionStateChanged = { newState ->
                repository.setConnectionState(newState)
            }
        )
    }

    private fun connect(address: String) {
        if (!hasConnectPermission()) return
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = manager.adapter ?: return
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return
        bmsBluetoothManager?.connect(device, true)
    }

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ActivityCompat.checkSelfPermission(
            this, android.Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ---------------------------------------------------------------------
    // 悬浮窗
    // ---------------------------------------------------------------------
    private fun showFloatingWindow() {
        if (floatingView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.layout_floating_window, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        )
        params?.gravity = Gravity.TOP or Gravity.END
        params?.x = 100
        params?.y = 100

        floatingView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0.0f
            private var initialTouchY: Float = 0.0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params?.x ?: 0
                        initialY = params?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params?.x = initialX - (event.rawX - initialTouchX).toInt()
                        params?.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })

        updateOverlayVisibility()
    }

    /** 根据“悬浮窗开关”偏好决定是否把悬浮窗添加到 WindowManager。 */
    private fun updateOverlayVisibility() {
        val wm = windowManager ?: return
        val view = floatingView ?: return
        val enabled = isFloatingWindowEnabledPref()
        val attached = view.isAttachedToWindow
        if (enabled && !attached) {
            wm.addView(view, params)
        } else if (!enabled && attached) {
            wm.removeView(view)
        }
    }

    private fun updateOverlay() {
        floatingView?.let { v ->
            v.findViewById<TextView>(R.id.tv_speed_value)?.text = String.format("%.1f", repository.getSpeedKmh())
            val bms = repository.getBmsData()
            v.findViewById<TextView>(R.id.tv_voltage_value)?.text = String.format("%.3f", bms.totalVoltage)
            v.findViewById<TextView>(R.id.tv_current_value)?.text = String.format("%.2f", bms.current)
            v.findViewById<TextView>(R.id.tv_diff_value)?.text = bms.voltageDiff.toString()
            v.findViewById<TextView>(R.id.tv_soc_value)?.text = bms.soc.toString()
        }
    }

    // ---------------------------------------------------------------------
    // 生命周期收尾
    // ---------------------------------------------------------------------
    /**
     * 悬浮窗关闭且蓝牙已断开时服务没有存在的意义，主动结束自身释放资源。
     */
    private fun maybeStopSelf() {
        val overlayOn = isFloatingWindowEnabledPref()
        val connected = repository.getConnectionState() == BluetoothProfile.STATE_CONNECTED
        if (!overlayOn && !connected) {
            stopSelf()
        }
    }

    // ---------------------------------------------------------------------
    // 偏好
    // ---------------------------------------------------------------------
    private fun getPrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun isFloatingWindowEnabledPref(): Boolean =
        getPrefs().getBoolean(PREF_FLOATING_WINDOW, false)

    private fun setOverlayPref(enabled: Boolean) {
        getPrefs().edit().putBoolean(PREF_FLOATING_WINDOW, enabled).apply()
    }

    private fun saveLastDeviceAddress(address: String) {
        getPrefs().edit().putString(PREF_LAST_DEVICE_ADDRESS, address).apply()
    }

    private fun getLastDeviceAddress(): String? =
        getPrefs().getString(PREF_LAST_DEVICE_ADDRESS, null)
}
