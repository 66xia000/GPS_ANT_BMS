package com.zjsf.gps_ant_bms.protocol

import com.zjsf.gps_ant_bms.model.BmsData

object AntProtocol {
    fun processAntData(data: ByteArray): BmsData? {
        if (data.size < 10 || data[2] != 0x11.toByte()) return null

        try {
            fun u16(i: Int) = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
            fun i16(i: Int) = u16(i).toShort().toInt()
            fun u32(i: Int) = (u16(i).toLong() and 0xFFFFFFFFL) or 
                             ((u16(i + 2).toLong() and 0xFFFFFFFFL) shl 16)

            val numTemp = data[8].toInt() and 0xFF
            val numCell = data[9].toInt() and 0xFF
            var offset = 34

            val cellVoltages = (0 until numCell).map { u16(offset + it * 2) }
            offset += numCell * 2

            val temperatures = (0 until numTemp).map { u16(offset + it * 2) }
            offset += numTemp * 2

            val mosTemp = u16(offset)
            offset += 2

            val balancerTemp = u16(offset)
            offset += 2

            val totalVoltage = u16(offset) * 0.01
            offset += 2

            val current = i16(offset) * 0.1
            offset += 2

            val soc = u16(offset)
            offset += 2
            
            offset += 6

            val capacity = u32(offset) * 0.000001
            offset += 4
            val remainingCharge = u32(offset) * 0.000001
            
            return BmsData(
                totalVoltage = totalVoltage,
                current = current,
                soc = soc,
                capacity = capacity,
                remainingCharge = remainingCharge,
                mosTemp = mosTemp,
                balancerTemp = balancerTemp,
                cellVoltages = cellVoltages
            )
        } catch (e: Exception) {
            return null
        }
    }
}
