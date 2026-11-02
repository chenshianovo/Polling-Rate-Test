# 蓝牙连接信息与 CSV（1.4.0）

本说明对应 1.4.0 测试版。编译、35 项单元测试及 Lint 已通过，真机验证待完成；1.3.0 APK 没有这些字段。

## 读取范围

| 参数 | 来源与含义 |
| --- | --- |
| 设备名称、地址、类型、配对状态 | 当前选择的 BluetoothDevice；连接和手动刷新时读取。BLE 隐私地址可能变化，不能当作永久身份。 |
| GATT 连接状态 | 本 App 自己建立的 GATT 连接，不等同于系统全部蓝牙连接。 |
| ATT MTU | 仅使用成功的 onMtuChanged 回调。没有回调就显示未上报，CSV 数值为空。 |
| PHY TX / RX | readPhy 的回调或 onPhyUpdate；方向以手机为基准，显示 LE 1M / LE 2M / LE Coded。 |
| RSSI | 最近一次 readRemoteRssi 的结果，单位 dBm，有观测时间，不代表每个通知包的 RSSI。 |
| 服务、特征、实例、属性、Notify/Indicate | 实际订阅的数据通道；只统计这个通道的接收回调。 |
| LL TX/RX 长度、连接间隔、peripheral latency、supervision timeout | 当前项目 SDK 35 的普通应用公开接口不可获取，数值为空，并写入 unavailable_public_api_sdk35。 |
| 链路加密状态 | 未获取。已配对不等于本次链路已加密。 |

ATT MTU 是 ATT 层 PDU 的上限，LL 数据长度是更低一层的数据包限制；两者不能互换，一个较长的 ATT PDU 可能由多个 LL 包承载。`att_notification_pdu_value_space_bytes` 是 ATT MTU 减 3 得到的通知 PDU 值字段空间，不是实际 payload 长度，也不是 LL 包长；GATT 属性长度仍有自身限制。实际接收到的完整字节数以每行 `payload_bytes` 为准。

首次服务发现后，App 串行读取一次 RSSI 和 PHY，再继续自动选择唯一通道；每一步最多等待 3 秒，失败不把未知值伪装成成功。测量期间不主动轮询这些诊断参数。暂停后可以点“刷新信息”；需要实际协商 ATT MTU 时点“协商 MTU”。这会改变连接条件，不是只读查询。Android 14 起系统首次 MTU 请求按 517 发起，后续请求可能被忽略，始终以成功回调确认值为准。

当前项目使用的 android-35/android.jar 已核对：有 onMtuChanged、onPhyRead、onPhyUpdate、onReadRemoteRssi；没有公开 onConnectionUpdated 或 removeBond。较新的 Android 文档列出 37.2 的 onConnectionUpdated，并不使 API 35 SDK 自动具备该接口。若要在现有手机上得到真实连接间隔与 LL 长度，可由手柄固件通过独立诊断特征上报，或使用蓝牙 HCI/空口抓包；尚未提供私有协议定义，因此此版不猜测 payload 中哪些字节表示这些参数。

## CSV 格式

BLE CSV 保留已有 UTF-8 BOM、注释元数据、真实时间戳和完整 payload，在六个采样列后追加一列：

```csv
sample,callback_elapsed_realtime_ns,relative_ms,interval_ms,payload_bytes,payload_hex,ble_connection_info_id
```

文件头保存参数快照，每项是一行；`id` 是采样行引用的编号，从 0 开始：

```csv
# ble_info_columns,id,observed_elapsed_realtime_ns,key,value
# ble_info,0,1000000000,"device_name","手柄 A"
# ble_info,0,1000000000,"att_mtu_bytes","23"
# ble_info,0,1000000000,"att_mtu_status","confirmed_callback"
# ble_info,0,1000000000,"connection_interval_ms",""
# ble_info,0,1000000000,"connection_parameters_status","unavailable_public_api_sdk35"
# ble_info,1,2000000000,"att_mtu_bytes","247"
```

上述只是格式片段，真实文件包含每个快照的完整键值。设备信息、UUID、实例号、订阅方式以及 MTU、PHY、RSSI 的来源、状态和各自观测时间都在快照中。`observed_elapsed_realtime_ns` 为整组信息被 App 保存的时间；`att_mtu_observed_ns`、`phy_observed_ns`、`rssi_observed_ns` 是对应成功回调的时间，使用与数据接收相同的 elapsedRealtimeNanos 时间轴。长整数在表格中按文本导入，以免丢精度。

参数变化只影响之后采样所引用的快照；之前的行仍引用原编号。错误状态可能和最后一次成功值同时存在，分析时要同时看状态与观测时间。断开、切换设备或再次测试不会重写已经导出的快照。暂停期间刷新得到的新观测可出现在快照表里，继续后的采样使用新编号。最多保存 1024 份快照；达到上限后停止本轮，避免漏记参数后还继续采样。USB CSV 保持原六列，不填入 BLE 信息。

## 配对与 OTA

“管理配对”显示当前选择设备，确认后停止本 App 的连接并打开系统蓝牙设置，取消配对由用户在系统页面完成。普通应用没有跨 Android 版本可靠的公开一键删除配对接口；这里没有使用隐藏 API 或把“断开连接”当成“删除配对”。

截图中的 `Unable to resolve host api.github.com` 来自检查阶段的 DNS 解析失败，发生在读取 Release 信息之前。排查链路是 `check()` → `read()` → `open()` → `getResponseCode()`，不能把这种错误当成“没有新版本”。1.4.0 按 DNS、超时、连接失败、TLS、HTTP 状态分别显示中文提示，失败时出现“打开发布页”；原始异常保留在本地 Logcat 的 `GamepadRateUpdate` 标签。备用入口仍访问官方 GitHub，并不保证手机当前网络能访问，也不修复私人 DNS、VPN 或运营商网络配置。

## 代码与验证

- `BleGattClient`：所有连接参数与 GATT 操作在原工作线程处理；旧连接回调按 gatt 对象过滤，信息刷新与订阅串行。
- `ConnectionMetadata`、`PacketRateSession`：复制不可变快照，按采样索引保存快照编号；结束后拒绝给旧记录换参数。
- `PacketCsvWriter`：写快照表及每行编号，继续执行 CSV 引号转义和公式前缀防护。
- `UpdateErrors`、`UpdateManager`：区分网络故障与 APK 验证失败，不修改原签名、摘要或包名检查。

新增 5 项回归测试，覆盖参数变化与旧行关联、导出后快照冻结、未知字段、历史上限与 USB 隔离，以及 DNS/TLS/HTTP 错误分类。已执行 assembleDebug、testDebugUnitTest、lintDebug：35 项测试全部通过，Lint 0 错误、4 条警告。后续真机验收需检查真实 BLE 的 MTU/PHY 回调、暂停续录后的 CSV 编号、信息读取超时、系统取消配对、DNS 失败后的重试及手动下载入口。

官方资料：[BluetoothGatt](https://developer.android.com/reference/android/bluetooth/BluetoothGatt)、[BluetoothGattCallback](https://developer.android.com/reference/android/bluetooth/BluetoothGattCallback)、[BluetoothDevice](https://developer.android.com/reference/android/bluetooth/BluetoothDevice)、[Bluetooth ATT 规范](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core-61/out/en/host/attribute-protocol--att-.html)。
