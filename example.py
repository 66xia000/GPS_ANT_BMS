import struct
import os

# ==========================================
# 辅助解析工具（小端序版）
# ==========================================

def get_uint16_le(data, offset):
    """读取 2 字节无符号整数 (小端序)"""
    return struct.unpack('<H', bytes(data[offset:offset+2]))[0]

def get_int16_le(data, offset):
    """读取 2 字节有符号整数 (小端序)"""
    return struct.unpack('<h', bytes(data[offset:offset+2]))[0]

def get_uint32_le(data, offset):
    """读取 4 字节无符号整数 (小端序)"""
    return struct.unpack('<I', bytes(data[offset:offset+4]))[0]

# ==========================================
# 核心解析逻辑
# ==========================================

def process_status_frame(data):
    """解析 BMS 状态帧数据"""

    # 1. 数据长度检查
    if len(data) < 100:
        print(f"错误：数据长度 ({len(data)} 字节) 不足 100 字节，无法完整解析")
        return

    print("=" * 40)
    print("           BMS 数据解析报告")
    print("=" * 40)

    # 2. 读取基本配置信息
    num_temps = data[8]
    num_cells = data[9]
    print(f"温度传感器数量: {num_temps}")
    print(f"电池单体数量:   {num_cells}")
    print("-" * 40)

    # 3. 解析各单体电压 (从 34 字节开始)
    cell_voltages = []
    for i in range(num_cells):
        # 原始数据为 mV，乘以 0.001 转换为 V
        voltage = get_uint16_le(data, 34 + i * 2) * 0.001
        cell_voltages.append(voltage)
        print(f"单体 {i+1:02d} 电压: {voltage:.3f} V")

    # 4. 计算动态偏移并解析传感器温度
    # 偏移量 = 初始位置(34) + (单体数量 * 2 字节)
    offset = 34 + (num_cells * 2)
    print("-" * 40)
    for i in range(num_temps):
        temp = get_int16_le(data, offset + i * 2) * 1.0
        print(f"传感器 {i+1} 温度: {temp} °C")

    offset += num_temps * 2

    # 5. MOSFET 和均衡器温度
    temp_mosfet = get_int16_le(data, offset) * 1.0
    temp_balancer = get_int16_le(data, offset + 2) * 1.0
    print(f"MOSFET  温度: {temp_mosfet} °C")
    print(f"均衡器  温度: {temp_balancer} °C")
    offset += 4

    # 6. 总电参数解析
    total_voltage = get_uint16_le(data, offset) * 0.01
    print(f"电池组总电压: {total_voltage:.2f} V")
    offset += 2

    current = get_int16_le(data, offset) * 0.1
    print(f"实时电流:     {current:.1f} A")
    offset += 2

    soc = get_uint16_le(data, offset)
    print(f"剩余电量(SOC): {soc} %")
    offset += 2

    print(f"计算功率:     {total_voltage * current:.2f} W")

    # 7. SOH 与 MOS 状态
    if offset + 2 <= len(data):
        soh = get_uint16_le(data, offset)
        print(f"健康度 (SOH):  {soh} %")
        offset += 2

        # 状态映射逻辑
        mos_map = {
            0: "关闭", 1: "开启", 2: "关闭 (过压保护)",
            3: "关闭 (过流保护)", 4: "关闭 (欠压保护)",
            5: "关闭 (过温保护)", 15: "待机"
        }
        print(f"充电 MOS 状态: {mos_map.get(data[offset], data[offset])}")
        print(f"放电 MOS 状态: {mos_map.get(data[offset+1], data[offset+1])}")
        offset += 2

        bal_map = {0: "关闭", 1: "开启", 2: "手动模式"}
        print(f"均衡器状态:   {bal_map.get(data[offset], data[offset])}")
        offset += 2 # 跳过均衡状态和隐藏字节

    # 8. 容量与运行时间
    if offset + 20 <= len(data):
        # 容量字段 (原代码系数较小，输出单位为 Ah)
        design_cap = get_uint32_le(data, offset) * 0.000001
        print(f"设计容量:     {design_cap:.2f} Ah")
        offset += 4

        remain_cap = get_uint32_le(data, offset) * 0.000001
        print(f"剩余容量:     {remain_cap:.2f} Ah")
        offset += 4

        # 累计循环功耗
        total_wh = get_uint32_le(data, offset) * 0.001
        print(f"累计功耗:     {total_wh:.2f} Wh")
        offset += 4

        # BMS反馈的当前功率
        p_raw = get_uint32_le(data, offset)
        if p_raw > 0x7FFFFFFF: p_raw -= 0x100000000 # 处理负数功率
        print(f"当前实时功率: {p_raw} W")
        offset += 4

        # 运行时间格式化
        runtime_sec = get_uint32_le(data, offset)
        d = runtime_sec // 86400
        h = (runtime_sec % 86400) // 3600
        m = (runtime_sec % 3600) // 60
        s = runtime_sec % 60
        print(f"累计运行时间: {d}天 {h:02d}:{m:02d}:{s:02d}")
        offset += 4

    # 9. 电压统计总结
    if num_cells > 0:
        max_v = max(cell_voltages)
        min_v = min(cell_voltages)
        avg_v = sum(cell_voltages) / num_cells
        max_idx = cell_voltages.index(max_v) + 1
        min_idx = cell_voltages.index(min_v) + 1

        print("-" * 40)
        print(f"【电压统计汇总】")
        print(f"最高单体: No.{max_idx:02d}  电压: {max_v:.3f} V")
        print(f"最低单体: No.{min_idx:02d}  电压: {min_v:.3f} V")
        print(f"最大压差: {max_v - min_v:.3f} V")
        print(f"平均电压: {avg_v:.3f} V")
    print("=" * 40)

# ==========================================
# 文件读取入口
# ==========================================

def run_main(file_path):
    if not os.path.exists(file_path):
        print(f"错误：找不到文件 '{file_path}'")
        return

    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            # 清洗数据：去掉连字符、空格、换行
            raw_content = f.read().replace('-', '').replace(' ', '').strip()
            data_bytes = bytearray.fromhex(raw_content)

            print(f"文件加载成功: {file_path}")
            print(f"报文总长度: {len(data_bytes)} 字节")

            process_status_frame(data_bytes)

    except Exception as e:
        print(f"程序运行出错: {e}")

if __name__ == "__main__":
    # 请确保 data.txt 与此脚本在同一目录下
    run_main('data.txt')