
Prompt: 我正在开发一个 Android 应用，需要实现以下功能：

数据源： 异步获取系统的 GPS 速度（使用 FusedLocationProviderClient）以及通过 BLE 读取 BMS 保护板数据（电压、电流、各串压差）。

业务逻辑： 将这两组频率不同的数据进行时间戳对齐，并实时记录到手机本地的 CSV 文件中。

技术要求： > - 请提供一个 Foreground Service (前台服务) 的代码框架，确保灭屏后记录不中断。

使用 Kotlin Coroutines (协程) 或 HandlerThread 处理后台 IO 写入，避免阻塞 UI。

设计一个高效的缓冲区，每秒批量写入一次 CSV 以降低能耗。

请给出 CSV 写入类 CsvLogger 的实现，包括文件创建、追加数据和 flush()。

考虑 GPS 和 BMS 频率不一致（如 1Hz vs 5Hz），在数据结构中如何设计。