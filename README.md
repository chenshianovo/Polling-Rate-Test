# Polling Rate Test · 安卓手柄回报率测试

原生 Java Android 应用，支持标准手柄输入、自定义 BLE 协议和 USB/OTG 接收测试。最低 Android 8.0（API 26），标准输入事件的精细时间戳推荐 Android 14+。

## 下载与安装

当前版本为 **1.5.0 测试版**（versionCode 6）。2026-11-04 编译成功，53 项单元测试全部通过，Lint 0 错误、5 条警告；真机验证待完成。

本版安装包为 `GamepadRate-1.5.0-debug.apk`。从 [Releases](https://github.com/chenshianovo/Polling-Rate-Test/releases) 取得对应版本后，传到手机打开安装；系统询问时，允许当前文件管理器安装应用。此为**调试签名测试包**，使用与 1.4.0 及先前版本相同的证书，可覆盖同证书旧版。自行构建通常使用自己的调试证书，无法覆盖另一证书签名的版本。

Release 附件提供 APK、SHA-256、源码 ZIP 和构建信息。源码树不包含 SDK、Gradle 缓存、签名密钥、用户采集数据或 APK。

## 1.5.0 新功能

- BLE/USB 可选固定长度报告计数；针对示例手柄选择每报告 5 字节、报告头 `14 01`，一个 10 字节合包计两份报告；同一报告拆成 2+3 字节接收，补齐后计一份。报告头也可跨接收拼接，失配字节单独统计。
- 页面显示完整报告数、报告到达率、合包完成次数、拆包完成数与待拼字节，保留原接收次数。悬浮窗默认只显示 `x Hz`，点击才展开这些统计和操作按钮。一次接收完成至少两份报告计一次“合包完成”，跨接收拼成的报告计入“拆包完成”；这是应用接收层口径。
- 规则只作用于当前通道，切换设备或通道后关闭；修改规则会清空本轮，需先导出。暂停/停止会丢弃未完成残片并记录字节数，继续后不跨缺口拼接。原接收率与间隔曲线仍按包统计，完整报告计在最后片段接收时刻，不伪造采样时间。
- BLE、USB、标准手柄 CSV 的参数名改为中文；蓝牙连接参数名及已知状态也中文化，保留完整 payload、原始时间戳和快照编号。CSV 格式版本为 2，按旧英文列名读取的脚本需更新。

详细规则与示例见 [合包、拆包报告计数与中文 CSV](docs/report-counting-and-csv-zh.md)。本轮已完成编译和 53 项单元测试，实际手柄、手机及后台悬浮行为仍待真机验收。

## 1.4.0 已发布功能

增加当前 BLE 设备常驻显示、可折叠连接信息、RSSI/PHY 读取、手动 ATT MTU 协商、CSV 参数历史，以及 OTA 网络错误分类与发布页备用入口。“管理配对”通过系统设置取消配对。当前 SDK 35 无法通过公开接口取得 LL 长度与连接间隔等参数，显示不可获取；未回调的 MTU 不填默认值冒充协商结果。具体字段、限制和排查思路见 [蓝牙连接信息与 CSV](docs/ble-connection-info.md)。

## 1.3.0 新功能

- 固定底部导航：BLE、USB、我的；默认打开 BLE，标准手柄测试入口移到“我的”。
- 去掉页面常驻操作步骤，统一圆角按钮、主操作高亮；主题、导出目录和更新集中到“我的”。
- 灰色半透明悬浮窗：点击展开“开始/继续、暂停、导出、关闭”，拖动数字区域移动。
- 选定通道并完成连接后自动开窗、开始记录；默认手动时长。暂停保留记录，继续时排除跨暂停间隔。
- 通过系统目录选择器设置 CSV 保存目录，记住授权；悬浮窗导出会先暂停，首次导出可直接选择目录。
- 应用内检查 GitHub Release 更新，校验 APK 后打开系统安装器，由用户确认安装。

## 1.2.0 已发布功能

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

从“我的 → 标准手柄测试”进入。系统设置中连接手柄 → 选择手柄 → 选择时长 → 开始 → 持续转动摇杆 → 停止后导出。按键独立显示，不混入摇杆 Hz；多个轴同一时间点变化只计一次。Android 8–13 使用毫秒时间转换单位，Android 14+ 使用纳秒时间接口。

### 自定义 BLE

先在系统配对，或用设备原应用建立 BLE 连接。打开 BLE 页面，允许“附近设备”连接权限，选择设备。列表优先显示系统 GATT 已连接设备，再显示 BLE/双模/未知类型的配对记录；**已配对不代表已连接**。不扫描附近设备，系统查询也无法列出全部蓝牙连接。

应用建立自己的 GATT 客户端，自动发现服务、特征、实例编号及属性。只有一个可订阅通道时自动选择，多个通道需手动选择。需要标准 CCCD，同时支持 Notify/Indicate 时优先 Notify。首次选择设备会申请悬浮权限；订阅成功、页面回到前台后自动开窗并记录。切换设备或通道会清空本轮，应先导出。

应用不猜测私有协议、不解密应用层数据、不发送私有启动命令、不自动识别按键位。需要鉴权或特殊初始化的设备可能连接成功但无数据。连接、服务发现、订阅均有 15 秒超时保护。

### USB / OTG

使用数据线/OTG 连接手柄，手机需支持 USB Host，并打开 OTG 开关（如系统需要）。选择设备 → 允许悬浮窗与 USB 授权 → 选择 Interrupt IN 端点 → 确认接管接口；连接成功后自动开窗并开始记录。结束后导出并断开；系统手柄输入未恢复时重新插拔。

当前支持第一配置的 Alt 0、单事务 Interrupt IN 端点（包长不超过 1024 字节），会读取 GET_CONFIGURATION 确认配置。暂不支持其他配置、非零 Alt、Bulk、Isochronous、高带宽多事务端点及专用初始化协议。不发送输出报告或厂商命令。

后台线程使用 4 个异步 UsbRequest，每次缓冲容量等于端点最大包长。等待超时和空传输不计数。“完整 payload”指本次接收完成交付的全部字节，CSV 始终逐次保留。在 1.5.0 中配置并启用固定长度报告规则后，可跨接收拼成完整逻辑报告，额外统计合包/拆包；不启用时仍只统计接收次数。bInterval 的换算依赖总线速度，因此只显示描述符原始值，不直接换算 Hz。

接管接口可能分离系统 HID 驱动，不能保证同时在游戏中使用同一 USB 手柄。断开、权限丢失或请求失败会停止测试并清理资源。

### 交互悬浮窗

BLE/USB 通道就绪后自动打开悬浮窗并开始记录，也可使用页面上的悬浮窗按钮。系统通知可返回页面或关闭悬浮窗；未授予通知权限时，系统可能只在运行中应用管理处显示服务。

悬浮背景为灰色半透明，默认紧凑显示 `x Hz`，尽量减少屏幕占位。开启报告计数规则时显示完整报告到达率，否则显示接收率；开始/继续后的首秒显示 `— Hz`。点击数字区域展开详细统计、运行状态和“开始/继续、暂停、导出、关闭”按钮，再次点击收起；拖动不会触发展开。展开后可查看原接收率、接收次数、完整报告数、合包/拆包完成数量及待拼字节。不抢输入焦点，仅自身范围响应触摸。

暂停保留已有记录，继续追加到本轮；导出先暂停并保存独立快照，可随后继续记录。暂停/停止时 Hz 保持冻结，展开可查看状态。关闭悬浮、锁屏或移除任务会停止测试；定时结束/断连后的展开状态显示“已停止”。关闭后本轮数据仍在内存，后台连接会释放；系统终止进程会丢失未导出的数据。

未开启悬浮时，失焦停止测试，离开前台释放连接。开启后由不持有 Activity 的应用级采集对象和前台服务持续接收。不自动开机启动或恢复测试。标准输入模式无法跨应用采集，因此没有后台悬浮入口。

### 导出目录与应用更新

“我的 → 修改导出目录”通过 Android 系统文件选择器授权一个文件夹，后续各模式写入该目录，文件名自动避重。系统可能不允许选择存储根目录、Download 根目录或 Android/data 等位置，可选择自己创建的子目录。撤销授权、删除文件夹或存储不可用会显示失败，应重新选择目录。BLE/USB 未设置目录时，首次导出会打开目录选择器；标准手柄模式保留逐次另存为入口。

“我的 → 检查更新”访问本仓库 GitHub Releases，按 `v主.次.补丁` 数字版本比较，包含标明“测试版”的预发布。下载前由用户确认，下载后核对附件大小、GitHub SHA-256、包名、递增 versionCode、最低系统版本及当前 APK 签名；校验通过才交给系统安装器。首次需要允许此应用安装未知来源软件，并确认安装；不支持静默安装。

1.2.0 没有更新入口，需要先手动安装 1.3.0。同一设备上的后续更新必须使用**与已安装包相同的签名**，同时提高 versionCode；换电脑生成的默认 debug 证书通常不同。发布附件名称需为 `GamepadRate-*.apk`，且 GitHub API 提供有效的 `sha256:` digest；没有摘要或校验失败会拒绝安装。检查范围是最新 100 条 Release，联网失败或受 GitHub 访问限制时显示错误。

## CSV 与统计

1.5.0 导出 UTF-8 BOM 中文 CSV，文件头有 `# CSV格式版本,2` 和 `# 字段语言,中文`。默认六列，BLE 追加“蓝牙连接快照编号”；开启报告计数后再追加“本次完成报告数、累计报告数、本次拆包完成数、接收后待拼字节、本次失配丢弃字节、报告解析状态”。文件头也记录合包/拆包总数与中断残片字节。[完整示例](docs/report-counting-and-csv-zh.md)。

1.4.0 及之前的已发布 APK 使用英文列名（UTF-8 BOM）：

```text
sample,callback_elapsed_realtime_ns,relative_ms,interval_ms,payload_bytes,payload_hex
1,10000000,0.000000,,5,"01 00 FF 10 80"
```

文件头的 # 行保存模式、设备、通道、开始时间、停止原因、系统和平均 Hz。payload_hex 无损记录收到的字节，字节数等于 payload_bytes；空 BLE 通知写入空字段。界面预览前 64 字节，**CSV 不截断**。升级无法补回旧版 CSV 未保存的内容。

1.3.0 增加 `paused_ms`、`active_duration_ms` 元数据；`duration_ms` 为开始至最后停止/暂停的跨度，`paused_ms` 记录其中已结束的暂停段。首次采样和每次继续后的首条采样，其 `interval_ms` 留空，表示不能跨段计算间隔。原始时间戳与 `relative_ms` 保持真实时间轴，包含中间暂停；计时、平均间隔和抖动排除暂停边界。未点击暂停时的设备静默仍计入间隔。

BLE/USB 使用 elapsedRealtimeNanos；标准输入使用事件 uptime，字段为 `sample,event_uptime_ns,relative_ms,interval_ms,historical,callback_uptime_ms`，并声明原始 payload 不可用。不同时间轴不能直接相减；在电子表格中把长整数时间戳作为文本导入，避免精度损失。

| 指标 | 算法 |
| --- | --- |
| 最近 1 秒 Hz | (当前时间−1 秒, 当前时间] 内采样数；开始/继续后首秒等待，暂停/停止后冻结 |
| 全程平均 Hz | 同一记录段内的有效间隔个数 × 10^9 / 这些间隔之和；没有有效跨度显示 —；无暂停时等同 (N−1)/时间跨度 |
| 平均/最小/最大间隔 | 全轮各记录段内相邻采样，保留设备静默和零间隔，排除人工暂停边界 |
| P95/抖动 | 最近最多 2048 个间隔的百分位/总体标准差 |
| 同时间戳/同内容 | 保留，不去重 |
| 逆序时间戳 | 单独计数并忽略，payload 与时间行保持对齐 |

每轮最多 250000 个采样；BLE/USB 完整 payload 最多 16 MiB。达到上限自动停止，不覆盖数据、不存半条 payload；无法完整存下的下一条不纳入统计。导出使用独立快照，重新测试或接收缓冲复用不会更改导出内容。测量数据只存本地内存与用户选定目录，不上传；1.3.0 新增网络权限用于 GitHub 更新检查/下载，APK 临时存于应用缓存。没有定位、扫描或广泛存储权限。

## 构建与验证

JDK 17、Android SDK Platform 35、Build Tools 34.0.0、AGP 8.7.3、Gradle 8.9。Android Studio 打开仓库，或配置 JAVA_HOME、ANDROID_HOME 后运行：

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

Linux/macOS 使用 `bash ./gradlew assembleDebug testDebugUnitTest lintDebug`。产物为 `app/build/outputs/apk/debug/app-debug.apk`。build-apk.ps1 使用开发机忽略目录 .build-tools 中已有的工具链；其他电脑直接使用 Wrapper。Wrapper 下载校验固定在 gradle-wrapper.properties。

1.5.0 于 2026-11-04 完成 `assembleDebug testDebugUnitTest lintDebug`，结果为 **BUILD SUCCESSFUL；53 项单元测试通过，0 失败、0 错误；Android Lint 0 错误、5 条警告**。警告为 `GradleDependency`（依赖新版提示）、`DataExtractionRules`（备份规则建议）、`Overdraw`（重复背景绘制）及两处 `SetTextI18n`（文字拼接）；本轮没有新增或升级依赖。回归测试覆盖合包/拆包流式计数、报告头跨接收、残片中断隔离、中文 CSV、冻结快照、计时和既有连接统计。

APK 签名验证通过，与 1.4.0 相同；证书 SHA-256 为 `719026cd16a38990106bac1ee39fcf590a010771053368d5efbe100b634ed69c`。本轮 ADB 设备仅显示 `offline`，未进行真机验收；**USB、BLE、交互悬浮窗及 OTA 的实际设备行为仍需验证**。以往版本的构建结果保留在 [更新记录](CHANGELOG.md) 中。

另外完成源码静态检查：12 个 XML 格式、Java 资源引用、Activity 控件 ID、Manifest 组件对应文件、括号配对与差异空白检查通过。这些检查不能代替真机验证。

建议真机检查：USB 拔出/拒绝授权/接口占用/释放恢复；BLE Notify/Indicate/多特征/权限撤销；通道就绪自动开窗；快速开始/暂停/继续；导出快照与大于 64 字节的完整 CSV；导出目录失效；底部三页来回切换；悬浮权限取消、后台计时、锁屏、通知关闭、横竖屏拖动与展开；三种主题；OTA 无更新/下载中断/摘要错误/异签名/安装权限拒绝/同签名覆盖安装。

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
| ThemedActivity.java / ProfileActivity.java | 固定底部导航与“我的”设置页面 |
| ExportStore.java / ExportActivity.java | 持久目录授权、导出快照与后台写入 |
| UpdateManager.java / UpdateFileProvider.java | 检查发布、校验下载和系统安装交接 |

1.3.0 排查思路：先看点击是否到达 `PacketRuntime`，再看通道状态是否 ready，最后检查 `PacketRateSession` 的 recording/paused。暂停不是清空或结束：保留数组，继续时通过 `RateStatistics.beginSegment()` 标记新段，避免暂停时间拉低平均 Hz。BLE 开始请求在 GATT 线程执行，暂停/停止会取消尚未执行的开始请求，防止快速点击后又被旧请求开启。导航统一放在 ThemedActivity 的滚动区域外，避免三个页面各写一套而出现位置和样式不一致。

旧版无法区分按下/松开的直接原因是只存了长度。本版接收时复制完整 byte 数组，导出时再编码 hex；高频回调中不进行文字格式化。USB 按非空请求完成计数，不把界面刷新或等待超时算作报告。

## 官方接口资料

[USB Host](https://developer.android.com/develop/connectivity/usb/host) · [UsbRequest](https://developer.android.com/reference/android/hardware/usb/UsbRequest) · [UsbDeviceConnection](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection) · [BLE 数据](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data) · [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device) · [Android 15 悬浮限制](https://developer.android.com/about/versions/15/behavior-changes-15#fgs-background-start) · [MotionEvent](https://developer.android.com/reference/android/view/MotionEvent)
