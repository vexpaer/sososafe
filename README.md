# sososafe

Android 本地隐私审计、安全分析与实时网络元数据监控。Kotlin + Jetpack Compose + Material Design 3。应用 ID：`app.sososafe.mobile`。最低 Android 8.0（API 26），编译/目标 API 37（Android 17）；支持 arm64、ARMv7 与 x86_64。

## 获取 APK

打开 [Android CI](https://github.com/vexpaer/sososafe/actions/workflows/android-ci.yml)，选择**成功**的运行，下载 **sososafe-debug**，解压得到 `sososafe.apk` 与 SHA256。用户无需 Android Studio、SDK、JDK、Gradle 或 ADB。Debug APK 使用构建机的调试签名；跨构建更新可能需要卸载旧的调试版本，请先导出报告。正式版本使用你自己的稳定发布密钥。

CI 自动响应 Push、PR、手动运行。包括固定 JDK / Gradle / SDK / NDK、原生转发库构建、JVM 单元测试、Android Lint、Debug APK 与模拟器测试。`sososafe-verification`、`sososafe-device-verification` 保存测试报告、构建日志与手机/平板截图。模拟器测试覆盖真实 PackageManager/APK 扫描、导入、导出、受限命令执行、本地 VPN TCP/UDP 真实转发及停止；JVM 测试覆盖 IPv4/IPv6 转发和畸形网络输入。

## 使用

1. 概览页点击扫描。默认包含用户应用；在设置中可包含系统应用或关闭 DEX 深度扫描。
2. 应用页支持搜索、风险/系统/导入筛选和排序。点进应用查看权限、端点、SDK、Manifest、DEX、建议和证据。无法读取的 APK 明确显示限制；右上角通过系统文件选择器导入 APK。
3. 设置中的自动模式优先使用**本次会话已明确授权**的 Root，然后 Shizuku，否则普通模式。探测 su 文件不会自动请求 Root。
4. Shizuku 需要单独安装并启动。Android 11+ 可在设备端使用无线调试；授权按钮调用 Shizuku 官方 API。停止服务/撤销授权会更新界面，扫描将记录访问错误。Android 8–10 无 Root 时无法承诺脱离电脑启动 Shizuku。
5. 网络页启动本地 VPN，阅读说明并通过 Android VPN 授权。Android 13+ 请求通知权限；Android 17+ 请求局域网访问权限以维持 LAN 转发。通知/页面随时停止。仅能有一个 Android VPN；启动本应用会替换其他 VPN。
6. 在应用建议页打开系统权限设置，或确认 AppOps/停用/卸载。处置页保存原始状态、执行结果和回滚入口。停用/卸载仅允许用户应用，核心/系统组件受保护。
7. 设置页导出 JSON / Markdown / TXT，默认写入应用私有文件目录；检查敏感信息后再另存或使用系统分享。

## 模式与访问边界

| 能力 | Standard | Shizuku | Root |
|---|---|---|---|
| 可见清单、版本、UID、权限声明与可读取授权状态 | PackageManager | PM + shell 清单 / dumpsys | PM + su 清单 / dumpsys |
| APK、split APK、Manifest、DEX | 能读取时分析，否则导入 | shell 文件描述符读取，受 SELinux/OEM 约束 | 经授权 su 读取，仍报告错误 |
| AppOps | 不伪造状态 | 查询与确认后修改 | 查询与确认后修改 |
| 共享 UID | PackageManager 可见范围 | 补充 Dumpsys | 补充 Dumpsys |
| 停用及精确恢复启用状态 | 打开设置 | 用户应用 | 用户应用 |
| VPN 元数据 | Android VPN 授权 | 同左，另可查询 ss | 同左，另可查询 ss |

普通应用无法任意读取其他应用的私有目录。部分安装 APK 的只读代码路径可由普通模式读取；必须实际打开成功才显示分析结果。权限声明、运行时授权、特殊访问及 AppOps 是不同事实。Accessibility/通知监听/设备管理采用 Manifest 和可读取的系统设置；被限制时显示 Unknown。MediaProjection 声明和代码引用不等于当前正在录屏。QUERY_ALL_PACKAGES 服务于核心审计用途；Google Play 分发需满足其政策和声明要求。

## 分析与证据

基于 [NOIR](https://github.com/Verlintas/NOIR) MIT 源码研究并迁移纯 Kotlin 分类、规则、Dumpsys 与 AXML 逻辑。没有迁入桌面 ADB、JADX 或工具安装代码。Android 静态引擎用固定 `dexlib2 2.5.2` 解析所有受限大小内的 `classes*.dex`，检查真实方法调用与类继承，识别广告/分析/崩溃 SDK、URL/裸域名和敏感 API。离线域名数据库在 `core/src/main/resources/domain-rules.tsv`，按最具体、点边界后缀优先匹配。

四级证据：`CAPABILITY`（能力）、`STATIC_REFERENCE`（静态引用）、`OBSERVED_CONNECTION`（观测连接）、`CONFIRMED_CONTENT`（确认内容）。本应用不解密 HTTPS，不生成“已确认内容泄露”结论。权限与端点关联只表示能力和引用共存。

风险分数为 0–100 的检查优先级。权限最多 35 分、Manifest 30 分、DEX 20 分、其他线索 15 分。每条发现记录原始证据、等级、建议与贡献分；较长 Manifest 不会无限叠加分数。首页安全评分为保留结果平均风险分的补数，不是恶意软件检测或安全保证。未扫描时不显示虚构评分。

受安全和设备内存约束：APK 输入最多 512 MiB；Manifest 最多 8 MiB；单 DEX 最多 128 MiB 且不超过进程最大堆的四分之一；最多 100 个 DEX/每 APK、5000 个端点/每应用、500 条去重代码特征；关联列表最多 500 对，原始权限和端点保留。限制或失败会写入报告。原生库、反射、加密字符串、下载代码不属于静态覆盖范围。

## 本地 VPN

采用 [HevSocks5Tunnel](https://github.com/heiher/hev-socks5-tunnel)，固定提交 `21bb3d77e64ad5a413d4d851b9f4cacf2098cd77`（2026-11-04，上游维护活跃，MIT）。递归子模块固定 lwIP、Hev task system、SOCKS core 和 YAML。GitHub Runner 使用 NDK 28.2 构建支持 16 KiB 页的三种 ABI。源码许可证和修改点保留于仓库，`scripts/build-native.sh` 的断言确保补丁目标准确。

TUN → lwIP TCP/UDP → 经过随机会话口令认证的本机 SOCKS5 → 保护并绑定底层网络的 TCP/UDP socket → 原目的地址。实现双向 TCP、UDP ASSOCIATE、IPv4/IPv6，无外部 VPN/代理。应用自身被排除，每个转发 socket 再调用 VpnService.protect，避免循环。网络变化更新底层网络；已有 TCP 连接可能断开重连。没有可用网络 DNS 时拒绝启动，不静默替换 DNS 服务商。ICMP、组播/广播、非 TCP/UDP 协议不保证转发。

元数据从 TUN 实际收发包提取；不保留载荷，仅保留地址、端口、时间、计数、流量和可见 DNS 名称。Android 10+ 用 active VPN 的 `getConnectionOwnerUid()` 查询原始五元组；未确认时 Unknown。共享 UID 不能唯一定位到其中一个包。Android 8–9 显示设备级/Unknown。增强模式另外提供真实 `ss` 查询，不凭域名猜应用。

DNS 问题与 DNS 响应的 IP 关联分别标识；共享 IP/CDN 意味着域名关联可能有歧义。DoH/DoT、ECH、已有缓存、IPv6 扩展/分片或其他 VPN 都会限制可见范围。UDP DNS 包解析有边界/指针循环保护；TCP DNS 不重组。总字节为保留记录中的 IP 包字节（包含头和重传），不是运营商计费字节；最多保留 20,000 个近期连接元组，内存最多 10,000 个，超载遗漏计数显示在网络页。

## 处置与回滚

安全命令采用封闭 allowlist，校验包名、用户 ID、AppOp、目标模式与 APK 路径。UI 不执行 Shell。Shizuku 使用官方独立 user service，Root 使用 su；进程超时、输出和文件输入大小有上限。

修改前准备原状态，确认后再次检查，持久化 PENDING 后才执行。AppOps 与停用的结果均再次读取验证；回滚恢复原 AppOp 或 `DEFAULT/ENABLED/DISABLED/DISABLED_USER/DISABLED_UNTIL_USED` 精确状态。处置期间进程异常的 PENDING 不会被当成成功。

卸载走 Android 系统确认。卸载前在私有目录保留 base/split APK；无法保留则中止。回滚用 PackageInstaller 多 APK session，仍需 Android 安装授权/确认。**卸载删除的私有应用数据不可恢复**。历史明确区分 APK 恢复与私有数据恢复，不自动卸载、改系统分区或恢复未确认的设置。

## 发布签名

在仓库 Settings → Secrets and variables → Actions 设置：

- `RELEASE_KEYSTORE_BASE64`：你的 keystore 的 Base64
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

推送 `v1.0.0` 形式的 Tag 或手动运行 Android Release。工作流先测试/Lint，再签名、验证签名、生成 SHA256、上传 `sososafe-release` 并创建 GitHub Release。没有这些 Secrets 时发布明确失败，Debug CI 不受影响。生产私钥绝不提交。不要用调试签名替代正式签名。

## 工程

`core` 为不依赖 Android UI 的模型、分析、协议、命令策略与报告模块；`app/data` 负责 Room/DataStore/仓库；`app/platform` 统一三种扫描模式和处置，`app/platform/vpn` 独立前台服务；`app/ui` 为 Compose 五个页面和详情。数据库操作和分析在 IO dispatcher，扫描可取消；未完成扫描在进程重启后标记 INTERRUPTED。

本地只需 Git 编辑/提交。全部 Android 构建和运行验证由 Actions 完成。不要将模拟器通过等同于 Root 管理器、Shizuku/OEM 或实体设备的实测认证。具体成功运行与验收证据见 `docs/VALIDATION.md`。

## 许可

MIT。保留 NOIR © 2026 Verlintas 的版权与许可证；详见 `third_party/licenses`、`THIRD_PARTY_NOTICES.md` 与应用设置中的开源许可。
