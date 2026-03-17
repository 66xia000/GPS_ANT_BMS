package com.zjsf.gps_ant_bms.protocol

import com.zjsf.gps_ant_bms.model.BmsData

object AntProtocol {
    /**
     * 解析 BMS 状态帧数据 (基于 Python 示例 logic)
     * 起始 7E A1, 结束 AA 55
     */
    fun processAntData(data: ByteArray): BmsData? {
        // 数据长度检查 (Python 脚本要求 100 字节以上，根据 message.txt 约 140+ 字节)
        if (data.size < 100) return null
        
        // 帧类型检查: 第三字节通常是 0x11
        if (data[2] != 0x11.toByte()) return null

        try {
            // 小端序解析辅助函数
            fun u16(i: Int) = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
            fun i16(i: Int) = u16(i).toShort().toInt()
            fun u32(i: Int) = (u16(i).toLong() and 0xFFFFL) or 
                             ((u16(i + 2).toLong() and 0xFFFFL) shl 16)
            fun i32(i: Int) = u32(i).toInt()

            // 1. 读取基本配置信息
            val numTemp = data[8].toInt() and 0xFF
            val numCell = data[9].toInt() and 0xFF
            
            // 2. 解析各单体电压 (从 34 字节开始)
            var offset = 34
            val cellVoltages = mutableListOf<Int>()
            for (i in 0 until numCell) {
                cellVoltages.add(u16(offset)) // 保持 mV
                offset += 2
            }

            // 3. 解析传感器温度
            val temperatures = mutableListOf<Int>()
            for (i in 0 until numTemp) {
                temperatures.add(i16(offset))
                offset += 2
            }

            // 4. MOSFET 和均衡器温度
            val mosTemp = i16(offset)
            val balancerTemp = i16(offset + 2)
            offset += 4

            // 5. 总电参数解析
            val totalVoltage = u16(offset) * 0.01 // V
            offset += 2

            val current = i16(offset) * 0.1 // A
            offset += 2

            val soc = u16(offset)
            offset += 2

            // 6. SOH 与 MOS 状态 (如果长度允许)
            var soh = 0
            if (offset + 6 <= data.size) {
                soh = u16(offset)
                offset += 2
                // 跳过 MOS 状态和均衡器状态 (各 2 字节)
                offset += 4 
            }

            // 7. 容量与运行时间 (如果长度允许)
            var capacity = 0.0
            var remainingCharge = 0.0
            var power = 0.0
            var runtime = 0L

            if (offset + 20 <= data.size) {
                capacity = u32(offset) * 0.000001
                offset += 4
                remainingCharge = u32(offset) * 0.000001
                offset += 4
                
                // 跳过累计循环功耗 (4 字节)
                offset += 4
                
                // BMS 反馈的当前功率
                val pRaw = i32(offset)
                power = pRaw.toDouble()
                offset += 4
                
                // 累计运行时间
                runtime = u32(offset)
                offset += 4
            }

            return BmsData(
                totalVoltage = totalVoltage,
                current = current,
                soc = soc,
                capacity = capacity,
                remainingCharge = remainingCharge,
                mosTemp = mosTemp,
                balancerTemp = balancerTemp,
                cellVoltages = cellVoltages,
                temperatures = temperatures,
                soh = soh,
                power = power,
                runtime = runtime
            )
        } catch (e: Exception) {
            return null
        }
    }
}
