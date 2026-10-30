# Polling Rate Test · 安卓手柄回报率测试

原生 Java Android 应用，支持标准手柄输入、自定义 BLE 协议和 USB/OTG 接收测试。最低 Android 8.0（API 26），标准输入事件的精细时间戳推荐 Android 14+。

## 下载与安装

在 [Releases](https://github.com/chenshianovo/Polling-Rate-Test/releases) 下载 `GamepadRate-1.2.0-debug.apk`，传到手机打开安装；系统询问时，允许当前文件管理器安装应用。此为**调试签名测试包**，使用与先前 1.0/1.1 相同的证书，可覆盖同证书旧版。自行构建通常使用自己的调试证书，无法覆盖另一证书签名的版本。

Release 附件提供 APK、SHA-256、源码 ZIP 和构建信息。源码树不包含 SDK、Gradle 缓存、签名密钥、用户采集数据或 APK。

## 1.2.0 新功能

- USB Host：选择设备、接口与 Interrupt IN 端点，测量非空接收完成频率。
- BLE/USB CSV 增加每条完整 `payload_hex`，不再只保存长度。
- 所有测试卡片可独立折叠，自动记住状态，折叠不停止采集。
- 白天、黑夜、跟随系统主题，记住设置，切换时不重建会话。
- BLE/USB 透明悬浮窗：显示模式、Hz、采样数与运行状态；可拖动、关闭，通过前台服务维持后台采集。

## 测量范围

| 模式 | 统计单位 | 原始 payload | 跨应用后台采集 |
| --- | --- | --- | --- |
| 标准手柄 | Android 交付的摇杆采样，包括 MotionEvent 历史采样 | 系统输入接口不提供，不能还原 | 不支持：普通应用不能取得其他应用的输入事件 |
| 自定义 BLE | 选定特征每一次 Notify / Indicate 回调 | 完整保存，包括相同数据和空通知 | 开启透明悬浮窗后支持 |
| USB / OTG | 选定 Interrupt IN 端点每次非空请求完成 | 完整保存该次接收字节，包括设备发送的 Report ID | 开启透明悬浮窗后支持，但独占接口可能影响游戏使用手柄 |

这是**应用侧接收频率**，不是硬件扫描率、空口发包率、USB 所有轮询次数或端到端按键延迟。驱动过滤、系统合并与线程调度会影响结果，纳秒单位不代表纳秒级准确度。只有协议保证一次接收对应一份报告，接收频率才可解释为报告频率。

如果固件只在状态改变时发送，每秒点按 2 次、按下松开各通知一次，就会显示约 4 Hz；这不能说明手柄最高回报率只有 4 Hz，也不能把通知率除以二当作硬件回报率。

## 使用

### 标准手柄

系统设置中连接手柄 → 选择手柄 → 选择时长 → 开始 → 持续转动摇杆 → 停止后导出。按键独立显示，不混入摇杆 Hz；多个轴同一时间点变化只计一次。Android 8–13 使用毫秒时间转换单位，Android 14+ 使用纳秒时间接口。

### 自定义 BLE

先在系统配对，或用设备原应用建立 BLE 连接。打开 BLE 页面，允许“附近设备”连接权限，选择设备。列表优先显示系统 GATT 已连接设备，再显示 BLE/双模/未知类型的配对记录；**已配对不代表已连接**。不扫描附近设备，系统查询也无法列出全部蓝牙连接。

应用建立自己的 GATT 客户端，自动发现服务、特征、实例编号及属性。只有一个可订阅通道时自动选择，多个通道需手动选择。需要标准 CCCD，同时支持 Notify/Indicate 时优先 Notify。订阅成功后开始测试；切换设备或通道会清空本轮，应先导出。

应用不猜测私有协议、不解密应用层数据、不发送私有启动命令、不自动识别按键位。需要鉴权或特殊初始化的设备可能连接成功但无数据。连接、服务发现、订阅均有 15 秒超时保护。

### USB / OTG

使用数据线/OTG 连接手柄，手机需支持 USB Host，并打开 OTG 开关（如系统需要）。选择设备 → 允许 USB 授权 → 选择 Interrupt IN 端点 → 确认接管接口 → 操作手柄并开始测试。结束后导出并断开；系统手柄输入未恢复时重新插拔。

当前支持第一配置的 Alt 0、单事务 Interrupt IN 端点（包长不超过 1024 字节），会读取 GET_CONFIGURATION 确认配置。暂不支持其他配置、非零 Alt、Bulk、Isochronous、高带宽多事务端点及专用初始化协议。不发送输出报告或厂商命令。

后台线程使用 4 个异步 UsbRequest，每次缓冲容量等于端点最大包长。等待超时和空传输不计数。**跨多个 USB 包的逻辑报告不会自动合并**；“完整 payload”指本次接收完成交付的全部字节。bInterval 的换算依赖总线速度，因此只显示描述符原始值，不直接换算 Hz。

接管接口可能分离系统 HID 驱动，不能保证同时在游戏中使用同一 USB 手柄。断开、权限丢失或请求失败会停止测试并清理资源。

### 透明悬浮窗

BLE/USB 页面：允许“显示在其他应用上层” → 返回并重新连接设备 → 开启悬浮窗 → 开始测试 → 切换应用。首次可允许通知权限，方便返回/停止；拒绝通知权限不阻止前台服务，但系统可能只在运行中应用管理处显示服务。

悬浮背景完全透明，文字带阴影。拖动数字区域移动，点击 × 关闭；不抢输入焦点，仅自身小范围响应触摸。关闭悬浮、锁屏或移除任务会停止测试；定时结束/断连后显示“已停止”，Hz 冻结在停止时。返回页面可导出结果。系统终止进程仍会丢失未导出的内存数据。

未开启悬浮时，失焦停止测试，离开前台释放连接。开启后由不持有 Activity 的应用级采集对象和前台服务持续接收。不自动开机启动或恢复测试。标准输入模式无法跨应用采集，因此没有后台悬浮入口。

## CSV 与统计

BLE/USB 的格式（UTF-8 BOM）：

```text
sample,callback_elapsed_realtime_ns,relative_ms,interval_ms,payload_bytes,payload_hex
1,10000000,0.000000,,5,"01 00 FF 10 80"
```

文件头的 # 行保存模式、设备、通道、开始时间、停止原因、系统和平均 Hz。payload_hex 无损记录收到的字节，字节数等于 payload_bytes；空 BLE 通知写入空字段。界面预览前 64 字节，**CSV 不截断**。升级无法补回旧版 CSV 未保存的内容。

BLE/USB 使用 elapsedRealtimeNanos；标准输入使用事件 uptime，字段为 `sample,event_uptime_ns,relative_ms,interval_ms,historical,callback_uptime_ms`，并声明原始 payload 不可用。不同时间轴不能直接相减；在电子表格中把长整数时间戳作为文本导入，避免精度损失。

| 指标 | 算法 |
| --- | --- |
| 最近 1 秒 Hz | (当前时间−1 秒, 当前时间] 内采样数；首秒等待，停止后冻结 |
| 全程平均 Hz | (N−1) × 10^9 / (最后时间戳−首个时间戳)；跨度为 0 或少于两条显示 — |
| 平均/最小/最大间隔 | 全轮相邻采样，保留停顿和零间隔 |
| P95/抖动 | 最近最多 2048 个间隔的百分位/总体标准差 |
| 同时间戳/同内容 | 保留，不去重 |
| 逆序时间戳 | 单独计数并忽略，payload 与时间行保持对齐 |

每轮最多 250000 个采样；BLE/USB 完整 payload 最多 16 MiB。达到上限自动停止，不覆盖数据、不存半条 payload；无法完整存下的下一条不纳入统计。导出使用独立快照，重新测试或接收缓冲复用不会更改导出内容。应用没有网络、定位、扫描或广泛存储权限，只在本地内存和用户选定的 CSV 保存数据。

## 构建与验证

JDK 17、Android SDK Platform 35、Build Tools 34.0.0、AGP 8.7.3、Gradle 8.9。Android Studio 打开仓库，或配置 JAVA_HOME、ANDROID_HOME 后运行：

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

Linux/macOS 使用 `bash ./gradlew assembleDebug testDebugUnitTest lintDebug`。产物为 `app/build/outputs/apk/debug/app-debug.apk`。build-apk.ps1 使用开发机忽略目录 .build-tools 中已有的工具链；其他电脑直接使用 Wrapper。Wrapper 下载校验固定在 gradle-wrapper.properties。

1.2.0 已完成编译，**25 个单元测试通过**，Android Lint 0 错误。测试覆盖统计、时限、内存边界、深复制和 CSV 完整性；非阻断提示见发布说明。**没有实际手机/手柄连接，USB、BLE 和悬浮窗真机验收尚未完成。**

建议真机检查：USB 拔出/拒绝授权/接口占用/释放恢复；BLE Notify/Indicate/多特征/权限撤销；大于 64 字节的完整 CSV；悬浮授权取消、后台计时、锁屏、通知停止、横竖屏拖动；三种主题和全部面板折叠恢复。

## 代码阅读与排查

按“设备/通道 → 接收 → 时间戳 → 保存 → 公式 → 显示”排查，避免只看 Hz 猜原因。

| 文件 | 职责 |
| --- | --- |
| MainActivity.java | 输入设备过滤、历史采样、按键与摇杆计数分离 |
| BleGattClient.java | GATT 发现与串行 CCCD，新旧回调按版本分流防止双计 |
| UsbHostClient.java | 授权、接管接口、异步队列、断开和资源清理 |
| PacketRateSession.java | 完整字节复制、内存上限、时间与 payload 对齐 |
| PacketCsvWriter.java | 后台逐行导出、完整 hex、元数据转义 |
| PacketRuntime.java / RateOverlayService.java | 应用级接收对象与透明前台悬浮窗 |
| RateStatistics.java | 纯 Java 统计、N−1 公式、逆序处理 |
| CollapsibleCard.java / Appearance.java | 折叠与主题，只影响显示 |

旧版无法区分按下/松开的直接原因是只存了长度。本版接收时复制完整 byte 数组，导出时再编码 hex；高频回调中不进行文字格式化。USB 按非空请求完成计数，不把界面刷新或等待超时算作报告。

## 官方接口资料

[USB Host](https://developer.android.com/develop/connectivity/usb/host) · [UsbRequest](https://developer.android.com/reference/android/hardware/usb/UsbRequest) · [UsbDeviceConnection](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection) · [BLE 数据](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data) · [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device) · [Android 15 悬浮限制](https://developer.android.com/about/versions/15/behavior-changes-15#fgs-background-start) · [MotionEvent](https://developer.android.com/reference/android/view/MotionEvent)
