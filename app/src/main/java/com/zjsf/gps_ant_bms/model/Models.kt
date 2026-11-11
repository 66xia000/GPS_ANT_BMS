package com.zjsf.gps_ant_bms.model

data class BleDevice(val name: String, val address: String, val rssi: Int)

data class BmsData(
    val totalVoltage: Double = 0.0,
    val current: Double = 0.0,
    val soc: Int = 0,
    val capacity: Double = 0.0,
    val remainingCharge: Double = 0.0,
    val mosTemp: Int = 0,
    val balancerTemp: Int = 0,
    val cellVoltages: List<Int> = emptyList(),
    val temperatures: List<Int> = emptyList(),
    val soh: Int = 0,
    val power: Double = 0.0,
    val runtime: Long = 0L,
    val voltageDiff: Int = 0
)
