package com.zjsf.gps_ant_bms

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zjsf.gps_ant_bms.bluetooth.BleScanner
import com.zjsf.gps_ant_bms.data.BmsDataRepository
import com.zjsf.gps_ant_bms.model.BleDevice
import com.zjsf.gps_ant_bms.ui.BleDeviceAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var gpsSpeedTextView: TextView
    private lateinit var textConnectionStatus: TextView
    private lateinit var textTotalVoltage: TextView
    private lateinit var textSoc: TextView
    private lateinit var textCurrent: TextView
    private lateinit var textPower: TextView
    private lateinit var textVoltageDiff: TextView
    private lateinit var textCapacity: TextView
    private lateinit var textRemaining: TextView
    private lateinit var textRuntime: TextView
    private lateinit var textMosTemp: TextView
    private lateinit var textBalancerTemp: TextView
    private lateinit var textTemps: TextView
    private lateinit var textCellVoltages: TextView
    private lateinit var progressSoc: com.google.android.material.progressindicator.LinearProgressIndicator
    private lateinit var scanButton: android.widget.Button
    private lateinit var floatingWindowSwitch: android.widget.Switch
    private lateinit var hideFromRecentsSwitch: android.widget.Switch

    private lateinit var bleScanner: BleScanner
    private lateinit var bleDeviceAdapter: BleDeviceAdapter
    private val discoveredDevices = mutableListOf<BleDevice>()
    private var scanDialog: androidx.appcompat.app.AlertDialog? = null

    private val repository = BmsDataRepository.instance

    private val overlayPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (android.provider.Settings.canDrawOverlays(this)) {
                if (isFloatingWindowEnabled()) {
                    startFloatingWindowService()
                }
            } else {
                // Permission denied, uncheck the switch
                floatingWindowSwitch.isChecked = false
                setFloatingWindowEnabled(false)
            }
        }
    }

    private val PERMISSION_REQUEST_CODE = 100
    private val SCAN_PERIOD: Long = 5000 // 5 seconds
    private val PREFS_NAME = "BmsPrefs"
    private val PREF_FLOATING_WINDOW = "floating_window_enabled"
    private val PREF_HIDE_FROM_RECENTS = "hide_from_recents"
    private val PREF_LAST_DEVICE_ADDRESS = "last_device_address"

    private val repositoryListener: (BmsDataRepository) -> Unit = { applyRepositoryState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
        initModules()
        checkPermissions()

        if (isFloatingWindowEnabled()) {
            checkOverlayPermission()
        }

        applyHideFromRecents(isHideFromRecentsEnabled())
    }

    override fun onStart() {
        super.onStart()
        repository.addListener(repositoryListener)
        applyRepositoryState()
    }

    override fun onStop() {
        repository.removeListener(repositoryListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 数据采集由前台服务承担，这里只负责确保服务按需运行：
        // 悬浮窗开着 → 保证服务在跑；否则有上次设备时也启动服务以便自动重连并刷新主界面。
        if (isFloatingWindowEnabled()) {
            checkOverlayPermission()
        } else if (getLastDeviceAddress() != null) {
            startFloatingWindowService()
        }
    }

    override fun onPause() {
        super.onPause()
        bleScanner.stopScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleScanner.stopScan()
        // 数据源与连接由前台服务持有，此处不主动 disconnect，
        // 悬浮窗开启时服务会在后台继续监控。
    }

    // ---------------------------------------------------------------------
    // 偏好
    // ---------------------------------------------------------------------
    private fun isFloatingWindowEnabled(): Boolean = getPrefs().getBoolean(PREF_FLOATING_WINDOW, false)

    private fun setFloatingWindowEnabled(enabled: Boolean) {
        getPrefs().edit().putBoolean(PREF_FLOATING_WINDOW, enabled).apply()
    }

    private fun isHideFromRecentsEnabled(): Boolean = getPrefs().getBoolean(PREF_HIDE_FROM_RECENTS, false)

    private fun setHideFromRecentsEnabled(enabled: Boolean) {
        getPrefs().edit().putBoolean(PREF_HIDE_FROM_RECENTS, enabled).apply()
    }

    private fun getPrefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun saveLastDeviceAddress(address: String) {
        getPrefs().edit().putString(PREF_LAST_DEVICE_ADDRESS, address).apply()
    }

    private fun getLastDeviceAddress(): String? = getPrefs().getString(PREF_LAST_DEVICE_ADDRESS, null)

    // ---------------------------------------------------------------------
    // 悬浮窗服务控制
    // ---------------------------------------------------------------------
    private fun applyHideFromRecents(exclude: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = am.appTasks
            if (tasks.isNotEmpty()) {
                tasks[0].setExcludeFromRecents(exclude)
            }
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            } else {
                startFloatingWindowService()
            }
        } else {
            startFloatingWindowService()
        }
    }

    /** 启动（或通知已运行的）前台服务来承担数据采集/监控。 */
    private fun startFloatingWindowService(address: String? = getLastDeviceAddress()) {
        val intent = Intent(this, FloatingWindowService::class.java)
        if (address != null) {
            intent.putExtra(FloatingWindowService.EXTRA_DEVICE_ADDRESS, address)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    // ---------------------------------------------------------------------
    // 视图与模块
    // ---------------------------------------------------------------------
    private fun initViews() {
        gpsSpeedTextView = findViewById(R.id.textViewGpsSpeed)
        textConnectionStatus = findViewById(R.id.textConnectionStatus)
        textTotalVoltage = findViewById(R.id.textTotalVoltage)
        textSoc = findViewById(R.id.textSoc)
        textCurrent = findViewById(R.id.textCurrent)
        textPower = findViewById(R.id.textPower)
        textVoltageDiff = findViewById(R.id.textVoltageDiff)
        textCapacity = findViewById(R.id.textCapacity)
        textRemaining = findViewById(R.id.textRemaining)
        textRuntime = findViewById(R.id.textRuntime)
        textMosTemp = findViewById(R.id.textMosTemp)
        textBalancerTemp = findViewById(R.id.textBalancerTemp)
        textTemps = findViewById(R.id.textTemps)
        textCellVoltages = findViewById(R.id.textCellVoltages)
        progressSoc = findViewById(R.id.progressSoc)
        scanButton = findViewById(R.id.buttonScanBle)
        floatingWindowSwitch = findViewById(R.id.switchFloatingWindow)
        hideFromRecentsSwitch = findViewById(R.id.switchHideFromRecents)

        floatingWindowSwitch.isChecked = isFloatingWindowEnabled()
        floatingWindowSwitch.setOnCheckedChangeListener { _, isChecked ->
            setFloatingWindowEnabled(isChecked)
            if (isChecked) {
                checkOverlayPermission()
            } else {
                FloatingWindowService.onFloatingWindowPrefChanged(false)
            }
        }

        hideFromRecentsSwitch.isChecked = isHideFromRecentsEnabled()
        hideFromRecentsSwitch.setOnCheckedChangeListener { _, isChecked ->
            setHideFromRecentsEnabled(isChecked)
            applyHideFromRecents(isChecked)
        }

        bleDeviceAdapter = BleDeviceAdapter(this, discoveredDevices) { device ->
            saveLastDeviceAddress(device.address)
            // 通知（或启动）前台服务连接所选设备，服务持有 BLE 连接。
            startFloatingWindowService(device.address)
            scanDialog?.dismiss()
        }

        scanButton.setOnClickListener { showScanDialog() }
    }

    private fun initModules() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        bleScanner = BleScanner(this, bluetoothAdapter,
            onDeviceFound = { result ->
                val deviceName = try {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        result.device.name ?: "Unknown Device"
                    } else {
                        "Unknown (No Permission)"
                    }
                } catch (e: SecurityException) {
                    "Unknown Device (No Permission)"
                }
                val deviceAddress = result.device.address
                val rssi = result.rssi

                val existingIndex = discoveredDevices.indexOfFirst { it.address == deviceAddress }
                if (existingIndex != -1) {
                    discoveredDevices[existingIndex] = discoveredDevices[existingIndex].copy(rssi = rssi)
                } else {
                    discoveredDevices.add(BleDevice(deviceName, deviceAddress, rssi))
                }
                discoveredDevices.sortByDescending { it.rssi }
                runOnUiThread { bleDeviceAdapter.notifyDataSetChanged() }
            },
            onScanStarted = {
                discoveredDevices.clear()
                runOnUiThread {
                    bleDeviceAdapter.notifyDataSetChanged()
                    scanDialog?.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressBarScanning)?.visibility = View.VISIBLE
                }
            },
            onScanStopped = {
                runOnUiThread {
                    scanDialog?.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressBarScanning)?.visibility = View.GONE
                }
            }
        )
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            afterPermissionsGranted()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                afterPermissionsGranted()
            }
        }
    }

    private fun afterPermissionsGranted() {
        if (isFloatingWindowEnabled()) {
            checkOverlayPermission()
        } else if (getLastDeviceAddress() != null) {
            startFloatingWindowService()
        }
    }

    private fun showScanDialog() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        val inflater = layoutInflater
        val dialogView = inflater.inflate(R.layout.dialog_ble_scan, null)
        builder.setView(dialogView)

        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerViewBleDevicesDialog)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = bleDeviceAdapter

        builder.setNegativeButton("Cancel") { dialog, _ ->
            bleScanner.stopScan()
            dialog.dismiss()
        }

        scanDialog = builder.create()
        scanDialog?.setOnDismissListener { bleScanner.stopScan() }
        scanDialog?.show()

        bleScanner.startScan(SCAN_PERIOD)
    }

    // ---------------------------------------------------------------------
    // 渲染（数据来自共享仓库）
    // ---------------------------------------------------------------------
    private fun applyRepositoryState() {
        gpsSpeedTextView.text = "%.1f".format(repository.getSpeedKmh())
        renderConnectionStatus(repository.getConnectionState())

        val data = repository.getBmsData()
        textTotalVoltage.text = "%.2f".format(data.totalVoltage)
        textSoc.text = data.soc.toString()
        progressSoc.progress = data.soc.coerceIn(0, 100)
        textCurrent.text = "%.1f A".format(data.current)
        textPower.text = "%.1f W".format(data.power)
        textVoltageDiff.text = "%d mV".format(data.voltageDiff)
        textCapacity.text = "%.2f Ah".format(data.capacity)
        textRemaining.text = "%.2f Ah".format(data.remainingCharge)

        val d = data.runtime / 86400
        val h = (data.runtime % 86400) / 3600
        val m = (data.runtime % 3600) / 60
        val s = data.runtime % 60
        textRuntime.text = "%d天 %02d:%02d:%02d".format(d, h, m, s)

        textMosTemp.text = "%d °C".format(data.mosTemp)
        textBalancerTemp.text = "%d °C".format(data.balancerTemp)
        textTemps.text = if (data.temperatures.isNotEmpty()) {
            data.temperatures.joinToString(", ") { "$it °C" }
        } else {
            "--"
        }

        textCellVoltages.text = buildCellVoltages(data.cellVoltages)
    }

    private fun renderConnectionStatus(state: Int) {
        val (chipText, color) = when (state) {
            BluetoothProfile.STATE_CONNECTED -> "已连接" to Color.rgb(0x2E, 0x7D, 0x32)
            BluetoothProfile.STATE_CONNECTING -> "连接中" to Color.rgb(0x61, 0x61, 0x61)
            BluetoothProfile.STATE_DISCONNECTED -> "未连接" to Color.rgb(0x9E, 0x9E, 0x9E)
            else -> "未连接" to Color.rgb(0x9E, 0x9E, 0x9E)
        }
        textConnectionStatus.text = chipText
        textConnectionStatus.setTextColor(color)
    }

    private fun buildCellVoltages(cellVoltages: List<Int>): Spannable {
        val sb = StringBuilder()

        // 计算电压最高的 3 个和最低的 3 个电池单体下标
        val highest3 = cellVoltages.indices
            .sortedByDescending { cellVoltages[it] }
            .take(3)
            .toSet()
        val lowest3 = cellVoltages.indices
            .sortedBy { cellVoltages[it] }
            .take(3)
            .toSet()

        // 记录需要着色的文本范围
        val colorSpans = mutableListOf<Pair<IntRange, Int>>()
        cellVoltages.forEachIndexed { index, voltage ->
            val start = sb.length
            sb.append("Cell %02d  %d mV\n".format(index + 1, voltage))
            val end = sb.length
            val color = when {
                index in highest3 -> Color.RED
                index in lowest3 -> Color.GREEN
                else -> null
            }
            if (color != null) {
                colorSpans.add(start until end to color)
            }
        }

        val spannable = SpannableString(sb.toString())
        colorSpans.forEach { (range, color) ->
            spannable.setSpan(
                ForegroundColorSpan(color),
                range.first,
                range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }
}
