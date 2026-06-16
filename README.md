# MediaCodecTest

独立验证工具：用 **Media3 ExoPlayer** 直接播放直播流，绕开 IJKPlayer，
实时观察 MediaCodec 解码 / Surface 显示链路的真实表现，定位卡顿来源。

## 技术栈

- 语言：Java（无 Kotlin）
- compileSdk / targetSdk = 34，minSdk = 28（Android 9+）
- androidx.media3:media3-exoplayer / ui / common `1.8.0`
- AGP 8.2.2 + Gradle 8.2，JDK 17

## 编译运行

1. Android Studio（Hedgehog / Iguana / Narwhal 均可）Open 本目录。
2. 首次打开若提示缺少 gradle-wrapper.jar：菜单 File -> Sync Project with Gradle Files，
   或在已装 Gradle 的环境下于本目录执行 gradle wrapper 生成。gradle-wrapper.properties 已就位（Gradle 8.2）。
3. 配置 local.properties 中的 sdk.dir（AS 一般自动生成）。
4. 连真机（目标 Android 9 PAD）Run。

备注：media3 1.8.0 较新，若同步报 compileSdk/依赖版本错误，可把
app/build.gradle 的 compileSdk/targetSdk 提到 35（PRD 默认 34，已尽量兼容）。

## 工程结构

- MainActivity.java — 单页：UI、ExoPlayer 编排、统计刷新、权限
- analytics/FpsCounter.java — VideoFrameMetadataListener，每帧计数为真实渲染FPS
- analytics/StatsCollector.java — AnalyticsListener：格式/丢帧/解码器
- analytics/SoftwareCodecSelector.java — 仅软件解码器的 MediaCodecSelector
- diag/CodecDiagnostor.java — 启动枚举 AVC/HEVC 解码器
- diag/DeviceInfo.java — 厂商/型号/版本
- export/LogExporter.java — logcat -d 写到 /sdcard/MediaCodecTest/log_*.txt
- export/ReportExporter.java — report.txt 快照
- export/OutputDirs.java — /sdcard 写入，scoped-storage 自动兜底
- net/PlayUrlProvider.java — GET playurl，灵活解析出播放地址

## 用法

- 模式1：在 Play URL 输入框直接粘贴 http://xxx/live.ts，点 Play。
- 模式2：在 playurl Config 填好 9 个参数，点 Get URL 拉取地址回填，再 Play。
- Force Software Decode 勾选走纯软解（c2.android.* / OMX.google.*），不勾默认硬解。
- Export Log / Export Report 输出到 /sdcard/MediaCodecTest/（10+ 分区存储自动改写到 App 专属目录）。

## 统计面板

FPS 为每帧渲染计数（每秒归零），即真正上屏帧率，与源流 25fps 直接对比；
Dropped 为累计丢帧（AnalyticsListener）；Decoder 通过 ExoPlayer 的 MediaCodecUtil 取首选解码器。

## 验收判读

同流同机：FPS 约 25 且 Dropped 约 0 -> MediaCodec/Android 正常，问题大概率在 IJK；
FPS 约 13~18 与线上一致 -> 问题在 MediaCodec/Amlogic/Surface 链路。

## 需确认/补充的点

1. playurl 返回格式未定义：当前按优先级解析 playurl/playUrl/url，再查 data.*，再兜底纯文本 URL。拿到真实响应后可在 PlayUrlProvider.extractUrl() 对齐字段。
2. READ_LOGS：导出全局 logcat（含 SurfaceFlinger）需 root/系统签名；普通设备仅能拿到本进程日志（含 ExoPlayer）。
3. playurl 默认参数值为占位（PlayUrlProvider.defaultParamKeys()），需按实际接口填默认值。
4. media3 1.8.0 与 compileSdk：见上文编译运行说明。