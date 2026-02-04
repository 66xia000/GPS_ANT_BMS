package com.zjsf.gps_ant_bms

import android.Manifest
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.pm.PackageManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import java.util.UUID
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var gpsSpeedTextView: TextView
    private lateinit var bmsDataTextView: TextView
    private lateinit var scanButton: android.widget.Button
    private lateinit var bleDeviceAdapter: BleDeviceAdapter
    private val discoveredDevices = mutableListOf<BleDevice>()
    private var scanDialog: androidx.appcompat.app.AlertDialog? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private val bmsDataBuffer = mutableListOf<Byte>()

    private val ANT_SERVICE_UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val ANT_CHAR_UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    private val LOCATION_PERMISSION_REQUEST_CODE = 1
    private val BLUETOOTH_PERMISSION_REQUEST_CODE = 2
    private val SCAN_PERIOD: Long = 1000 // 1 second
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                Log.d("GPS_SPEED", "Location received: Lat=${location.latitude}, Lon=${location.longitude}, Speed=${location.speed}")
                val speed = location.speed * 3.6 // speed in km/h
                gpsSpeedTextView.text = "GPS Speed: %.2f km/h".format(speed)
            } ?: run {
                Log.e("GPS_SPEED", "LocationResult received, but lastLocation is null.")
                gpsSpeedTextView.text = "GPS Speed: N/A"
            }
        }
    }

    private val bleScanCallback: ScanCallback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val deviceName = result.device.name ?: "Unknown Device"
            val deviceAddress = result.device.address
            val rssi = result.rssi
            
            val existingIndex = discoveredDevices.indexOfFirst { it.address == deviceAddress }
            if (existingIndex != -1) {
                discoveredDevices[existingIndex] = discoveredDevices[existingIndex].copy(rssi = rssi)
            } else {
                discoveredDevices.add(BleDevice(deviceName, deviceAddress, rssi))
                Log.i("BLE_SCAN", "Found BLE device: $deviceName - $deviceAddress ($rssi dBm)")
            }
            
            // Sort by RSSI descending
            discoveredDevices.sortByDescending { it.rssi }
            
            runOnUiThread {
                bleDeviceAdapter.notifyDataSetChanged()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("BLE_SCAN", "BLE Scan Failed with error code: $errorCode")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        gpsSpeedTextView = findViewById(R.id.textViewGpsSpeed)
        bmsDataTextView = findViewById(R.id.textViewBmsData)
        
        bleDeviceAdapter = BleDeviceAdapter(discoveredDevices)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val bluetoothManager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        scanButton = findViewById(R.id.buttonScanBle)
        scanButton.setOnClickListener {
            showScanDialog()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates()
            } else {
                gpsSpeedTextView.text = "GPS permission denied."
            }
        } else if (requestCode == BLUETOOTH_PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startBleScan()
            } else {
                Log.e("BLE_SCAN", "Bluetooth permissions denied.")
            }
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(500)
                .build()

            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            Log.d("GPS_SPEED", "Requesting location updates.")
        } else {
            gpsSpeedTextView.text = "Error: GPS permission not granted."
            Log.d("GPS_SPEED", "Permission not granted when trying to start location updates.")
        }
    }

    private fun checkBlePermissions() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e("BLE_SCAN", "Bluetooth not available or disabled.")
            return
        }

        val permissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), BLUETOOTH_PERMISSION_REQUEST_CODE)
        } else {
            startBleScan()
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

        val progressBar = dialogView.findViewById<android.widget.ProgressBar>(R.id.progressBarScanning)

        builder.setNegativeButton("Cancel") { dialog, _ ->
            stopBleScan()
            dialog.dismiss()
        }

        scanDialog = builder.create()
        scanDialog?.setOnDismissListener {
            stopBleScan()
        }
        scanDialog?.show()

        checkBlePermissions()
    }

    private fun startBleScan() {
        if (scanning || bluetoothLeScanner == null) return
        
        discoveredDevices.clear()
        runOnUiThread {
            bleDeviceAdapter.notifyDataSetChanged()
            scanDialog?.findViewById<android.widget.ProgressBar>(R.id.progressBarScanning)?.visibility = View.VISIBLE
        }

        // Stops scanning after a pre-defined scan period.
        handler.postDelayed({
            if (scanning) {
                stopBleScan()
            }
        }, SCAN_PERIOD)

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            bluetoothLeScanner?.startScan(null, settings, bleScanCallback)
            scanning = true
            Log.d("BLE_SCAN", "Scan started")
        } catch (e: SecurityException) {
            Log.e("BLE_SCAN", "SecurityException starting scan: ${e.message}")
        }
    }

    private fun stopBleScan() {
        if (!scanning) return
        try {
            bluetoothLeScanner?.stopScan(bleScanCallback)
            scanning = false
            handler.removeCallbacksAndMessages(null)
            runOnUiThread {
                scanDialog?.findViewById<android.widget.ProgressBar>(R.id.progressBarScanning)?.visibility = View.GONE
            }
            Log.d("BLE_SCAN", "Scan stopped")
        } catch (e: SecurityException) {
            Log.e("BLE_SCAN", "SecurityException stopping scan: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopBleScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        stopBleScan()
    }

    data class BleDevice(val name: String, val address: String, val rssi: Int)

    inner class BleDeviceAdapter(private val devices: List<BleDevice>) :
        RecyclerView.Adapter<BleDeviceAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameTextView: TextView = view.findViewById(R.id.textViewDeviceName)
            val addressTextView: TextView = view.findViewById(R.id.textViewDeviceAddress)
            val rssiTextView: TextView = view.findViewById(R.id.textViewRssi)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ble_device, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices[position]
            holder.nameTextView.text = device.name
            holder.addressTextView.text = device.address
            holder.rssiTextView.text = "RSSI: ${device.rssi} dBm"
            
            holder.itemView.setOnClickListener {
                Toast.makeText(this@MainActivity, "Connecting to: ${device.address}", Toast.LENGTH_SHORT).show()
                Log.d("BLE_SCAN", "User selected device: ${device.address}")
                connectToDevice(device.address)
                scanDialog?.dismiss()
            }
        }

        override fun getItemCount() = devices.size
    }

    private fun connectToDevice(address: String) {
        val device = bluetoothAdapter?.getRemoteDevice(address) ?: return
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            return
        }

        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("ANT_BMS", "Connected to GATT server.")
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ||
                    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i("ANT_BMS", "Disconnected from GATT server.")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(ANT_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(ANT_CHAR_UUID)
                
                if (characteristic != null) {
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ||
                        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                        gatt.setCharacteristicNotification(characteristic, true)
                        
                        // Enable local notifications
                        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == ANT_CHAR_UUID) {
                val data = characteristic.value
                handleIncomingData(data)
            }
        }
    }

    private fun handleIncomingData(data: ByteArray) {
        // Start of frame: 7E A1
        if (data.size >= 2 && data[0] == 0x7E.toByte() && data[1] == 0xA1.toByte()) {
            bmsDataBuffer.clear()
        }
        
        bmsDataBuffer.addAll(data.toList())

        // End of frame: 55
        if (bmsDataBuffer.isNotEmpty() && bmsDataBuffer.last() == 0x55.toByte()) {
            val fullPacket = bmsDataBuffer.toByteArray()
            processAntData(fullPacket)
            bmsDataBuffer.clear()
        }
    }

    private fun processAntData(data: ByteArray) {
        Log.d("ANT_BMS", "Processing packet: ${data.size} bytes")
        
        // 状态数据包功能码通常是 0x11 (ant.py:150)
        if (data.size < 10 || data[2] != 0x11.toByte()) return

        try {
            // 参考 ant.py:152-155 定义取数方法
            fun u16(i: Int) = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
            fun i16(i: Int) = u16(i).toShort().toInt()
            fun u32(i: Int) = (u16(i).toLong() and 0xFFFFFFFFL) or
                             ((u16(i + 2).toLong() and 0xFFFFFFFFL) shl 16)

            val numTemp = data[8].toInt() and 0xFF
            val numCell = data[9].toInt() and 0xFF
            var offset = 34

            // 1. 各节电压 (ant.py:161)
            val cellVoltages = (0 until numCell).map { u16(offset + it * 2) }
            offset += numCell * 2

            // 2. 温度 (ant.py:164)
            val temperatures = (0 until numTemp).map { u16(offset + it * 2) }
            offset += numTemp * 2

            // 3. MOS 温度 (ant.py:168)
            val mosTemp = u16(offset)
            offset += 2

            // 4. 平衡器温度 (ant.py:171)
            val balancerTemp = u16(offset)
            offset += 2

            // 5. 总电压 (ant.py:174)
            val totalVoltage = u16(offset) * 0.01
            offset += 2

            // 6. 电流 (ant.py:177)
            val current = i16(offset) * 0.1
            offset += 2

            // 7. SOC (ant.py:180)
            val soc = u16(offset)
            offset += 2
            
            // 跳过 SOH (2), DSG MOS (1), CHG MOS (1), BAL (1), Reserved (1)
            offset += 6

            // 8. 容量与剩余电量 (ant.py:200-203)
            val capacity = u32(offset) * 0.000001
            offset += 4
            val remainingCharge = u32(offset) * 0.000001
            
            val sb = StringBuilder()
            sb.append("--- BMS Status ---\n")
            sb.append("Total Voltage: %.2f V\n".format(totalVoltage))
            sb.append("Current:       %.1f A\n".format(current))
            sb.append("SOC:           %d %%\n".format(soc))
            sb.append("Capacity:      %.2f Ah\n".format(capacity))
            sb.append("Remaining:     %.2f Ah\n".format(remainingCharge))
            sb.append("MOS Temp:      %d °C\n".format(mosTemp))
            sb.append("Balancer Temp: %d °C\n".format(balancerTemp))
            
            sb.append("\n--- Cell Voltages ---\n")
            cellVoltages.forEachIndexed { index, voltage ->
                sb.append("Cell %02d: %d mV\n".format(index + 1, voltage))
            }

            runOnUiThread {
                bmsDataTextView.text = sb.toString()
            }
        } catch (e: Exception) {
            Log.e("ANT_BMS", "Error parsing data: ${e.message}")
        }
    }
}