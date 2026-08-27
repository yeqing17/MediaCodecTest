# MediaCodecTest

独立验证工具：用 **Media3 ExoPlayer** 直接播放直播流，绕开 IJKPlayer，
实时观察 MediaCodec 解码 / Surface 显示链路的真实表现，定位卡顿来源。

目标设备：Android 9（Amlogic 平台）PAD，直播流卡顿排障。

## 技术栈

- 语言：Java（无 Kotlin）
- compileSdk 35 / targetSdk 34 / minSdk 28（Android 9+）
- androidx.media3:media3-exoplayer / ui / common `1.3.1`
  （1.8.0 内部 API 重构过，1.3.1 API 稳定且诊断功能等价；详见后文"踩坑记录"）
- AGP 8.7.3 + Gradle 8.11.1 + JDK 17

## 更新记录

- **v1.4.5**：按返回键退出前增加确认弹框（电视上连按返回键极易误退）。
  弹框默认焦点在「取消」上，连按返回只会开/关弹框，必须显式选「退出」才会离开；
  播放中的流确认后停止。

- **v1.4.4**：修复遥控器焦点跳到 playurl Config 面板时选不中 chnlid 输入框：
  三行是 `[标签][输入框]` 结构而标签宽度不同，框的左边缘不在一条线上，
  系统的几何光束式焦点搜索会跳过错位的行。现改为显式 nextFocus 链
  （toggle→account→deviceno→chnlid→GET URL 往返焊死，不再依赖启发式）；
  展开面板后焦点自动落到第一个框、收起后回到开关。

- **v1.4.3**：修复点「GET URL → 拼播放地址」必闪退：URL 编码用了
  `URLEncoder.encode(String, Charset)` 重载（**API 33+ 才有**，Android 9 盒子直接抛
  `NoSuchMethodError`，且它是 Error 不是 Exception，穿透了原有的 catch 而杀死进程；
  v1.1.0 引入该流程时埋下，只在老系统真机上触发）。改用全版本通用的字符串形式。
  另外新增全局崩溃记录器：任何闪退都会在 `/sdcard/MediaCodecTest/crash_*.txt`
  留下完整堆栈；playurl 链路异常捕获扩为 Throwable，OOM 等 Error 也转为提示。

- **v1.4.2**：修复遥控器（D-pad）操作看不出焦点的问题——上一版重做 UI 后，
  按钮 selector 只有 pressed/enabled 没有 focused 状态，电视上移动焦点毫无反馈。
  现在所有按钮聚焦时显示描边高亮（Play/Stop 白环、次级按钮填充+蓝边）、
  文字提亮；「PLAYURL CONFIG [+]」改为可聚焦（原来是普通 TextView，遥控器到不了）；
  复选框加焦点外框；URL 输入框/预设下拉聚焦时蓝边；统计文本不再可选中，
  避免吸走焦点。

- **v1.4.1**：默认组播地址落到 `udp://238.1.1.4:5000`——启动时自动填入 URL 框，
  预设下拉同步提供「UDP组播 238.1.1.4:5000」，打开即 Play；CI 每次
  构建（含手动 Run workflow）都会把 APK 上传为运行产物 `MediaCodecTest-apk`。

- **v1.4.0**：新增 **UDP 组播 / 单播播放**（`udp://`、`rtp://`，ExoPlayer 无内置 UDP 数据源，
  自研 `UdpMulticastDataSource`；自动选网卡加入组播组 + WiFi MulticastLock、RTP 头自动剥离
  与丢包统计、SO_RCVBUF 可调）；新增「Auto Reconnect」错误自动重连开关、「静音」按钮、
  首帧耗时（FirstFrame ms）统计、屏幕常亮；**UI 全面重做**：暗色诊断主题、语义化配色按钮
  （Play 蓝 / Stop 红 / 次级描边）、圆角输入框与面板卡片、统计面板彩色化
  （FPS≥23 绿 / ≥18 黄 / 其余红；丢帧与 UDP 丢包标红；状态点跟随播放状态）。
- **v1.3.0**：新增本地文件播放（「文件」按钮 + SAF 选择器，支持 U盘 / sdcard / tmp 任意文件；「本地 media.ts」预设自动扫盘）；修复本地构建（gradle wrapper 8.2 → 8.11.1，对齐 AGP 8.7.3）；给 playurl Config 折叠区加空值兜底，杜绝因布局不一致导致的点击闪退。
- **v1.2.0**：横屏左右分栏布局（左：控件+视频，右：实时统计），统计每秒刷新时保持滚动位置。
- **v1.1.x**：三步 playurl 流程（登录 → 频道信息 → 拼播放地址）；VLC UA 绕过直播服务器对 ExoPlayer UA 的限速；live 流按未知长度读取，避免被 10TB 占位长度卡住。
- **v1.0.0**：基础播放工具 + 自动构建发布（tag 触发 Actions 出 APK）。

## 获取 APK

打 tag（`v1.0.0` 这类 `v` 开头）会触发 Actions 编译并直接把 APK 挂到
[Releases](https://github.com/yeqing17/MediaCodecTest/releases) 页，下载即用。
本地操作：

```
git tag v1.0.0
git push origin v1.0.0
```

APK 用 debug 签名（内部诊断工具，无需正式签名），可直接安装。

## 本地编译

1. Android Studio（Hedgehog / Iguana / Narwhal 均可）Open 本目录。
2. 配置 local.properties 中的 sdk.dir（AS 一般自动生成）。
3. gradle-wrapper.properties 已就位（Gradle 8.11.1）。若提示缺少 gradlew 二进制，
   执行 `gradle wrapper` 生成，或直接 Sync。
4. 连真机 Run。

## 界面布局

横屏锁定，左右分栏，暗色诊断主题，核心是让**视频画面与实时统计同屏可见**：

- **左栏**：预设下拉（输入框样式外壳）→ URL 输入 + 文件/静音(描边)、Stop(红)、Play(蓝主色)
  → 软解 / 自动重连 复选行 → 视频画面（细边框包裹，主体）→ `PLAYURL CONFIG [+]`
  （折叠 chip，点开是 account / deviceno / chnlid 三暗色输入框 + GET URL 蓝字按钮）
  → Export Log / Export Report（描边按钮）。
- **右栏**：`LIVE STATS · 实时统计` 卡片面板；右上角**状态点**跟随播放状态
  （播放绿 / 缓冲黄 / 停止红）；内容按秒刷新并保留滚动位置，
  关键指标带颜色：FPS ≥23 绿、18~22 黄、更低或丢帧时红；UDP 丢包数非 0 标红。
- 全部样式为纯 XML drawable/style 实现（`res/drawable/btn_*.xml` 等），零新增依赖。

## 用法

- **模式1（预设）**：顶部下拉选已内置的播放地址，直接 Play。
- **模式2（手输）**：URL 输入框粘贴地址，Play。
- **模式3（playurl 拉取）**：展开底部 playurl Config，填 account / deviceno / chnlid，点 Get URL 自动走「登录 → 频道信息 → 拼出播放地址」三步，再 Play。
- **模式4（本地文件）**：点 URL 框右侧「文件」按钮，用系统选择器选 U盘 / sdcard / tmp 上任意文件（ts/mp4 等）直接播放；选中即播，无需填地址。也可在下拉里选「本地 media.ts (U盘/sdcard)」自动扫盘。
- **模式5（UDP 组播）**：直接在 URL 框输入 `udp://@组播IP:端口`（如 `udp://@239.1.1.5:1234`），或选「UDP组播示例」预设后改 IP/端口，Play。支持 RTP 组播（`rtp://@...`）与单播 UDP（IP 为非组播地址时自动按单播监听）。
- `Soft Decode` 勾选走纯软解（c2.android.* / OMX.google.*），不勾默认硬解。
- `Auto Reconnect` 勾选后播放出错自动按 2s 间隔重连，最多 5 次；点 Stop 或重新 Play 会重置计数。
- `静音` 按钮切换音频输出（现场测量不想吵时可静音）。

### UDP 组播细节

- **网卡选择**：多网卡设备（Ethernet + WiFi）自动按 eth* > wlan* > 其他 的顺序尝试加入
  组播组，日志打印实际使用的 iface；指定网卡可在 URL 后加参数：
  `udp://@239.1.1.5:1234?ifname=eth0`。
- **WiFi 收包前提**：App 在播放 UDP 时自动持有 WifiManager MulticastLock
  （manifest 已加 `CHANGE_WIFI_MULTICAST_STATE`），否则 WiFi 驱动默认过滤组播帧，
  一个包都收不到。某些路由器/AP 还需开启「IGMP Snooping/组播转发」。
- **可选参数**（拼在 URL 后，`&` 连接）：`ifname=网卡名`、
  `rcvbuf=接收缓冲字节数`（默认 4MB，OS 有上限）、`rtp=auto|on|off`
  （RTP 头剥离模式，auto 按 PT=33 自动识别）。
- **丢包观测**：RTP 流按序列号统计丢包数；纯 UDP 无序列号无法计数，
  靠面板 UDP RX 码率是否低于流码率判断。
- **超时**：15 秒收不到任何 UDP 数据报或中途断流 15 秒会报错；
  勾选 Auto Reconnect 可自动恢复。
- **本机自测**：局域网另一台电脑用 ffmpeg 发流即可验证：
  ```
  ffmpeg -re -i test.ts -c copy -f mpegts udp://239.1.1.5:1234?ttl=2
  # RTP 版： -f rtp_mpegts
  ```
- Export Log / Export Report 输出到 `/sdcard/MediaCodecTest/`（10+ 分区存储自动改写到 App 专属目录）。

## 预设地址

预设下拉的地址在 [strings.xml](app/src/main/res/values/strings.xml) 的
`preset_labels` / `preset_urls` 两个数组里（顺序一一对应）。加新预设往里各加一行即可，
URL 里的 `&` 写成 `&amp;`。当前内置：

- 「UDP组播 238.1.1.4:5000」（默认地址，启动时已直接填入 URL 框）；
- ICC 抓包直播流；
- 「本地 media.ts」预设，值为 `local://media.ts`：选中后自动扫 sdcard 及每个挂载的可移动卷（U盘/SD）根目录，命中即填入 `file://` 路径；读不到会提示用「文件」按钮手选。

其中 UDP 默认地址定义在 strings.xml 的 `udp_default_url`（preset_urls 数组用
`@string/udp_default_url` 引用同一份）。

## 本地文件播放

播放管线复用 ExoPlayer 的 `DefaultDataSource`：`file://` 走 `FileDataSource`、SAF 选择器返回的 `content://` 走 `ContentDataSource`，本地文件不会经过只包 HTTP 的 `HttpTraceDataSource`，长度/跳转行为正常。

- 「文件」按钮用 `ACTION_OPEN_DOCUMENT`（SAF），选任意目录（含 U盘 / 外置 sdcard / `/data/local/tmp` 等可读路径）的文件，并取可持久化读权限，回放时地址仍有效。
- 「本地 media.ts」预设按 `local://media.ts` 约定解析，自动定位 `media.ts`。
- API ≤ 32 直读 `file://` 需要 `READ_EXTERNAL_STORAGE` 权限（已在 manifest 申明，maxSdk 32）；`content://` 选择器路径无需权限。

## 统计面板

- `Transport`：当前传输通道（HTTP / 文件 / UDP 组播+网卡名；UDP 播放中持续刷新）。
- `FPS`：VideoFrameMetadataListener 每帧计数，每秒归零 —— 真正上屏帧率，与源流 25fps 直接对比；同时显示峰值。
- `FirstFr`：从按下 Play 到首帧上屏的毫秒数（起播耗时观测）。
- `Dropped`：累计丢帧（AnalyticsListener）。
- `UDP RX` / `UDPPkt`：仅 UDP 播放时显示 —— 实测接收码率、累计包数、RTP 序列号丢包数。用于区分「网络没收到包」和「解码链路丢帧」。
- `Decoder`：通过 ExoPlayer 的 MediaCodecUtil 取首选解码器（软解模式优先取软件解码器）。
- 另含 MimeType、分辨率、码率、缓冲、当前播放位置、播放状态。

## 工程结构

```
app/src/main/java/com/mediacodectest/
├── MainActivity.java              # 单页：UI、ExoPlayer 编排、统计刷新、预设、折叠区
├── analytics/
│   ├── FpsCounter.java            # VideoFrameMetadataListener，每帧计数→真实渲染FPS
│   ├── StatsCollector.java        # AnalyticsListener：格式/丢帧/解码器
│   └── SoftwareCodecSelector.java # 仅软件解码器的 MediaCodecSelector
├── diag/
│   ├── CodecDiagnostor.java       # 启动枚举 AVC/HEVC 解码器
│   └── DeviceInfo.java            # 厂商/型号/版本
├── export/
│   ├── LogExporter.java           # logcat -d → /sdcard/MediaCodecTest/log_*.txt
│   ├── ReportExporter.java        # report.txt 快照
│   └── OutputDirs.java            # /sdcard 写入，scoped-storage 兜底
└── net/
    ├── PlayUrlProvider.java       # 三步获取 playurl：登录取 token → 频道信息取 play_token → 拼播放地址
    ├── SchemeRoutingDataSource.java # 按 scheme 分发数据源（http→trace 栈 / udp|rtp→组播 / 其他→Default）
    ├── UdpMulticastDataSource.java  # UDP 组播/单播 + RTP 剥头 DataSource（加入组播组、RCVBUF、丢包计数）
    └── UdpStreamStats.java          # UDP 收包/码率/丢包计数器（数据源写、面板读）
```

## 验收判读

同流同机：

- `FPS≈25` 且 `Dropped≈0` → MediaCodec/Android 正常，问题大概率在 IJK。
- `FPS≈13~18` 与线上一致 → 问题在 MediaCodec / Amlogic / Surface 链路。

## 踩坑记录

- **media3 1.8.0 内部 API 重构**：`AnalyticsListener` / `VideoFrameMetadataListener` 换了子包，
  `MediaCodecSelector.getDecoderInfo` 改名 `getDecoderInfos`，`MediaCodecInfo` 的
  `getName()`/`isHardwareAccelerated()` 改成公开字段 `name`/`hardwareAccelerated`。
  本项目钉在 1.3.1，API 与代码吻合；升 1.8.0 需同步改这几个类。
- **compileSdk**：media3 全系要求 compileSdk ≥ 35（AAR metadata 强制），AGP 需 ≥ 8.7。
- **Spinner prompt** 属性只接受 `@string` 引用，不能写字面值；普通下拉模式用不上，已移除。
- **UDP 组播 WiFi 收不到包**：Android 的 WiFi 驱动默认丢弃组播帧，必须持有
  `WifiManager.MulticastLock`（需 `CHANGE_WIFI_MULTICAST_STATE` 权限）才能收到；
  本 App 在播放 udp/rtp 时自动获取、停止后释放。多网卡设备组播从哪个口出去/进来由路由表定，
  加错网段时用 `?ifname=` 指定网卡重试。
- **media3 1.3.1 BaseDataSource**：UDP 数据源继承 `BaseDataSource` 复用 TransferListener 管道，
  这样 AnalyticsListener 的 load 事件（bytesLoaded）对 UDP 同样生效。

## 需确认/补充的点

1. playurl 三步流程：登录取 `access_token` / `device_id`（作 verifycode）→ 频道信息取 `play_token` → 拼最终播放地址。
   字段解析在 `PlayUrlProvider.fetch()`。登录的固定参数（accounttype / devicetype / grouptype / pwd 等）按抓包硬编码，
   用户只填 account / deviceno / chnlid；换密码不同的账号需改 `LOGIN_FIXED_PARAMS` 里的 pwd。
2. READ_LOGS：导出全局 logcat（含 SurfaceFlinger）需 root/系统签名；普通设备仅能拿到本进程日志（含 ExoPlayer）。
3. playurl 三字段预填了文档示例（account 760053843406 / deviceno …21802000017 / chnlid 4200000953），换账号频道直接改输入框即可。
