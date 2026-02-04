package com.zjsf.gps_ant_bms.model

data class BleDevice(val name: String, val address: String, val rssi: Int)

data class BmsData(
    val totalVoltage: Double,
    val current: Double,
    val soc: Int,
    val capacity: Double,
    val remainingCharge: Double,
    val mosTemp: Int,
    val balancerTemp: Int,
    val cellVoltages: List<Int>
)
