package com.zjsf.gps_ant_bms

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
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
    
    private lateinit var locationHelper: LocationHelper
    private lateinit var bleScanner: BleScanner
    private lateinit var bmsBluetoothManager: BmsBluetoothManager
    
    private lateinit var bleDeviceAdapter: BleDeviceAdapter
    private val discoveredDevices = mutableListOf<BleDevice>()
    private var scanDialog: androidx.appcompat.app.AlertDialog? = null

    private val LOCATION_PERMISSION_REQUEST_CODE = 1
    private val BLUETOOTH_PERMISSION_REQUEST_CODE = 2
    private val SCAN_PERIOD: Long = 1000 // 1 second

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
    }

    private fun initViews() {
        gpsSpeedTextView = findViewById(R.id.textViewGpsSpeed)
        bmsDataTextView = findViewById(R.id.textViewBmsData)
        scanButton = findViewById(R.id.buttonScanBle)
        
        bleDeviceAdapter = BleDeviceAdapter(this, discoveredDevices) { device ->
            val bluetoothManager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as BluetoothManager
            val deviceObj = bluetoothManager.adapter.getRemoteDevice(device.address)
            bmsBluetoothManager.connect(deviceObj)
            scanDialog?.dismiss()
        }
        
        scanButton.setOnClickListener {
            showScanDialog()
        }
    }

    private fun initModules() {
        val bluetoothManager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        locationHelper = LocationHelper(this) { location ->
            val speed = location.speed * 3.6
            gpsSpeedTextView.text = "GPS Speed: %.2f km/h".format(speed)
        }

        bleScanner = BleScanner(this, bluetoothAdapter,
            onDeviceFound = { result ->
                val deviceName = try {
                    result.device.name ?: "Unknown Device"
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

    private fun updateBmsUi(data: com.zjsf.gps_ant_bms.model.BmsData) {
        val sb = StringBuilder()
        sb.append("--- BMS Status ---\n")
        sb.append("Total Voltage: %.2f V\n".format(data.totalVoltage))
        sb.append("Current:       %.1f A\n".format(data.current))
        sb.append("SOC:           %d %%\n".format(data.soc))
        sb.append("Capacity:      %.2f Ah\n".format(data.capacity))
        sb.append("Remaining:     %.2f Ah\n".format(data.remainingCharge))
        sb.append("MOS Temp:      %d °C\n".format(data.mosTemp))
        sb.append("Balancer Temp: %d °C\n".format(data.balancerTemp))
        
        sb.append("\n--- Cell Voltages ---\n")
        data.cellVoltages.forEachIndexed { index, voltage ->
            sb.append("Cell %02d: %d mV\n".format(index + 1, voltage))
        }

        runOnUiThread {
            bmsDataTextView.text = sb.toString()
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            locationHelper.startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                locationHelper.startLocationUpdates()
            }
        } else if (requestCode == BLUETOOTH_PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                bleScanner.startScan(SCAN_PERIOD)
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