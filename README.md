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

## 获取 APK

两种方式：

- **Releases（正式发版）**：打 tag（`v1.0.0` 这类 `v` 开头）会触发 Actions 编译并直接把 APK 挂到
  [Releases](https://github.com/yeqing17/MediaCodecTest/releases) 页，下载即用。
  本地操作：`git tag v1.0.0 && git push origin v1.0.0`。
- **Actions（每次提交验证）**：普通 push 到 main/master 会编译并把 APK 放进 Actions 运行结果的
  Artifacts（名为 `MediaCodecTest-debug-apk`）。
  仓库：https://github.com/yeqing17/MediaCodecTest/actions

APK 用 debug 签名（内部诊断工具，无需正式签名），可直接安装。

## 本地编译

1. Android Studio（Hedgehog / Iguana / Narwhal 均可）Open 本目录。
2. 配置 local.properties 中的 sdk.dir（AS 一般自动生成）。
3. gradle-wrapper.properties 已就位（Gradle 8.11.1）。若提示缺少 gradlew 二进制，
   执行 `gradle wrapper` 生成，或直接 Sync。
4. 连真机 Run。

## 界面布局

单页面，分上下两区，核心是让**视频画面与实时统计同屏可见**：

- **顶部区（常驻）**：预设下拉 → URL 输入 → 软解开关 + Play/Stop → 视频画面（16:9）→ 实时统计面板。
  播放时视频与统计面板同屏，可对照看 FPS / 丢帧 / 解码器。
- **底部区（折叠）**：`playurl Config [+]` 标题条点一下展开 9 个参数输入框和 Get URL 按钮；
  不展开时一行都不占。Export Log / Export Report 常驻底部。

## 用法

- **模式1（预设）**：顶部下拉选已内置的播放地址，直接 Play。
- **模式2（手输）**：URL 输入框粘贴地址，Play。
- **模式3（playurl 拉取）**：展开底部 playurl Config，填 9 个参数，点 Get URL 拉回地址，再 Play。
- `Soft Decode` 勾选走纯软解（c2.android.* / OMX.google.*），不勾默认硬解。
- Export Log / Export Report 输出到 `/sdcard/MediaCodecTest/`（10+ 分区存储自动改写到 App 专属目录）。

## 预设地址

预设下拉的地址在 [strings.xml](app/src/main/res/values/strings.xml) 的
`preset_labels` / `preset_urls` 两个数组里（顺序一一对应）。加新预设往里各加一行即可，
URL 里的 `&` 写成 `&amp;`。当前内置一条 ICC 抓包直播流。

## 统计面板

- `FPS`：VideoFrameMetadataListener 每帧计数，每秒归零 —— 真正上屏帧率，与源流 25fps 直接对比；同时显示峰值。
- `Dropped`：累计丢帧（AnalyticsListener）。
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
    └── PlayUrlProvider.java       # GET playurl，灵活解析出播放地址
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

## 需确认/补充的点

1. playurl 返回格式未定义：当前按优先级解析 playurl/playUrl/url，再查 data.*，再兜底纯文本 URL。
   拿到真实响应后可在 `PlayUrlProvider.extractUrl()` 对齐字段。
2. READ_LOGS：导出全局 logcat（含 SurfaceFlinger）需 root/系统签名；普通设备仅能拿到本进程日志（含 ExoPlayer）。
3. playurl 默认参数值为占位（`PlayUrlProvider.defaultParamKeys()`），已按抓包日志设了
   `playtype=live` / `protocol=http` / `rate=icc@@udp://238.1.1.1:5000`，其余按实际接口填。
