package com.zjsf.gps_ant_bms

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.zjsf.gps_ant_bms.bluetooth.BmsBluetoothManager
import com.zjsf.gps_ant_bms.location.LocationHelper
import com.zjsf.gps_ant_bms.model.BleDevice
import com.zjsf.gps_ant_bms.protocol.AntProtocol
import com.zjsf.gps_ant_bms.ui.BleDeviceAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var gpsSpeedTextView: TextView
    private lateinit var bmsDataTextView: TextView
    private lateinit var scanButton: android.widget.Button
    private lateinit var floatingWindowSwitch: android.widget.Switch
    private lateinit var hideFromRecentsSwitch: android.widget.Switch
    
    private lateinit var locationHelper: LocationHelper
    private lateinit var bleScanner: BleScanner
    private lateinit var bmsBluetoothManager: BmsBluetoothManager
    
    private var currentSpeed: Double = 0.0
    private var currentVoltage: Double = 0.000
    private var currentCurrent: Double = 0.0
    private var currentVoltageDiff: Int = 0

    private lateinit var bleDeviceAdapter: BleDeviceAdapter
    private val discoveredDevices = mutableListOf<BleDevice>()
    private var scanDialog: androidx.appcompat.app.AlertDialog? = null

    private val PERMISSION_REQUEST_CODE = 100
    private val OVERLAY_PERMISSION_REQUEST_CODE = 101
    private val SCAN_PERIOD: Long = 5000 // 5 seconds
    private val PREFS_NAME = "BmsPrefs"
    private val PREF_FLOATING_WINDOW = "floating_window_enabled"
    private val PREF_HIDE_FROM_RECENTS = "hide_from_recents"
    private val PREF_LAST_DEVICE_ADDRESS = "last_device_address"

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

    private fun isFloatingWindowEnabled(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_FLOATING_WINDOW, false)
    }

    private fun setFloatingWindowEnabled(enabled: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_FLOATING_WINDOW, enabled).apply()
    }

    private fun isHideFromRecentsEnabled(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_HIDE_FROM_RECENTS, false)
    }

    private fun setHideFromRecentsEnabled(enabled: Boolean) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_HIDE_FROM_RECENTS, enabled).apply()
    }

    private fun applyHideFromRecents(exclude: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = am.appTasks
            if (tasks.isNotEmpty()) {
                tasks[0].setExcludeFromRecents(exclude)
            }
        }
    }

    private fun saveLastDeviceAddress(address: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_LAST_DEVICE_ADDRESS, address).apply()
    }

    private fun getLastDeviceAddress(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_LAST_DEVICE_ADDRESS, null)
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
            } else {
                startFloatingWindowService()
            }
        } else {
            startFloatingWindowService()
        }
    }

    private fun startFloatingWindowService() {
        val intent = android.content.Intent(this, FloatingWindowService::class.java)
        startService(intent)
    }

    private fun stopFloatingWindowService() {
        val intent = android.content.Intent(this, FloatingWindowService::class.java)
        stopService(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
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
    }

    private fun initViews() {
        gpsSpeedTextView = findViewById(R.id.textViewGpsSpeed)
        bmsDataTextView = findViewById(R.id.textViewBmsData)
        scanButton = findViewById(R.id.buttonScanBle)
        floatingWindowSwitch = findViewById(R.id.switchFloatingWindow)
        hideFromRecentsSwitch = findViewById(R.id.switchHideFromRecents)
        
        floatingWindowSwitch.isChecked = isFloatingWindowEnabled()
        floatingWindowSwitch.setOnCheckedChangeListener { _, isChecked ->
            setFloatingWindowEnabled(isChecked)
            if (isChecked) {
                checkOverlayPermission()
            } else {
                stopFloatingWindowService()
            }
        }

        hideFromRecentsSwitch.isChecked = isHideFromRecentsEnabled()
        hideFromRecentsSwitch.setOnCheckedChangeListener { _, isChecked ->
            setHideFromRecentsEnabled(isChecked)
            applyHideFromRecents(isChecked)
        }
        
        bleDeviceAdapter = BleDeviceAdapter(this, discoveredDevices) { device ->
            saveLastDeviceAddress(device.address)
            val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val deviceObj = bluetoothManager.adapter.getRemoteDevice(device.address)
            bmsBluetoothManager.connect(deviceObj)
            scanDialog?.dismiss()
        }
        
        scanButton.setOnClickListener {
            showScanDialog()
        }
    }

    private fun initModules() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        locationHelper = LocationHelper(this) { location ->
            currentSpeed = location.speed * 3.6
            gpsSpeedTextView.text = "GPS Speed: %.2f km/h".format(currentSpeed)
            FloatingWindowService.updateData(currentSpeed, currentVoltage, currentCurrent, currentVoltageDiff)
        }

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
                    scanDialog?.findViewById<android.widget.ProgressBar>(R.id.progressBarScanning)?.visibility = View.VISIBLE
                }
            },
            onScanStopped = {
                runOnUiThread {
                    scanDialog?.findViewById<android.widget.ProgressBar>(R.id.progressBarScanning)?.visibility = View.GONE
                }
            }
        )

        bmsBluetoothManager = BmsBluetoothManager(this,
            onDataReceived = { data ->
                val bmsData = AntProtocol.processAntData(data)
                bmsData?.let { updateBmsUi(it) }
            },
            onConnectionStateChanged = { newState ->
                // Handle connection state if needed
            }
        )
    }

    private fun reconnectLastDevice() {
        val lastAddress = getLastDeviceAddress()
        if (lastAddress != null) {
            val bluetoothManager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as BluetoothManager
            val bluetoothAdapter = bluetoothManager.adapter
            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled) {
                try {
                    val device = bluetoothAdapter.getRemoteDevice(lastAddress)
                    android.util.Log.i("MainActivity", "自动连接上次设备: $lastAddress")
                    bmsBluetoothManager.connect(device)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "自动连接失败: ${e.message}")
                }
            }
        }
    }

    private fun updateBmsUi(data: com.zjsf.gps_ant_bms.model.BmsData) {
        currentVoltage = data.totalVoltage
        currentCurrent = data.current
        currentVoltageDiff = data.voltageDiff
        FloatingWindowService.updateData(currentSpeed, currentVoltage, currentCurrent, currentVoltageDiff)

        val sb = StringBuilder()
        sb.append("--- BMS Status ---\n")
        sb.append("Total Voltage: %.2f V\n".format(data.totalVoltage))
        sb.append("Voltage Diff:  %d mV\n".format(data.voltageDiff))
        sb.append("Current:       %.1f A\n".format(data.current))
        sb.append("Power:         %.1f W\n".format(data.power))
        sb.append("SOC:           %d %%\n".format(data.soc))
        sb.append("SOH:           %d %%\n".format(data.soh))
        sb.append("Capacity:      %.2f Ah\n".format(data.capacity))
        sb.append("Remaining:     %.2f Ah\n".format(data.remainingCharge))
        sb.append("MOS Temp:      %d °C\n".format(data.mosTemp))
        sb.append("Balancer Temp: %d °C\n".format(data.balancerTemp))
        
        if (data.temperatures.isNotEmpty()) {
            sb.append("Sensor Temps:  ${data.temperatures.joinToString(", ")} °C\n")
        }
        
        val d = data.runtime / 86400
        val h = (data.runtime % 86400) / 3600
        val m = (data.runtime % 3600) / 60
        val s = data.runtime % 60
        sb.append("Runtime:       %d天 %02d:%02d:%02d\n".format(d, h, m, s))

        sb.append("\n--- Cell Voltages ---\n")
        data.cellVoltages.forEachIndexed { index, voltage ->
            sb.append("Cell %02d: %d mV\n".format(index + 1, voltage))
        }

        runOnUiThread {
            bmsDataTextView.text = sb.toString()
        }
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
            locationHelper.startLocationUpdates()
            reconnectLastDevice()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                locationHelper.startLocationUpdates()
                reconnectLastDevice()
            }
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

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationHelper.startLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        locationHelper.stopLocationUpdates()
        bleScanner.stopScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        locationHelper.stopLocationUpdates()
        bleScanner.stopScan()
        bmsBluetoothManager.disconnect()
    }

}