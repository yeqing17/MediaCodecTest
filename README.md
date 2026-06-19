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

横屏锁定，左右分栏，核心是让**视频画面与实时统计同屏可见**：

- **左栏**：预设下拉 → URL 输入 + Play/Stop/文件 → 软解开关 → 视频画面（16:9，主体）→ `playurl Config [+]`（折叠，点开是 account / deviceno / chnlid 三输入框 + Get URL）→ Export Log / Export Report。
- **右栏**：实时统计面板（放大字号），与视频同屏，对照看 FPS / 丢帧 / 解码器 / 缓冲水位。

## 用法

- **模式1（预设）**：顶部下拉选已内置的播放地址，直接 Play。
- **模式2（手输）**：URL 输入框粘贴地址，Play。
- **模式3（playurl 拉取）**：展开底部 playurl Config，填 account / deviceno / chnlid，点 Get URL 自动走「登录 → 频道信息 → 拼出播放地址」三步，再 Play。
- **模式4（本地文件）**：点 URL 框右侧「文件」按钮，用系统选择器选 U盘 / sdcard / tmp 上任意文件（ts/mp4 等）直接播放；选中即播，无需填地址。也可在下拉里选「本地 media.ts (U盘/sdcard)」自动扫盘。
- `Soft Decode` 勾选走纯软解（c2.android.* / OMX.google.*），不勾默认硬解。
- Export Log / Export Report 输出到 `/sdcard/MediaCodecTest/`（10+ 分区存储自动改写到 App 专属目录）。

## 预设地址

预设下拉的地址在 [strings.xml](app/src/main/res/values/strings.xml) 的
`preset_labels` / `preset_urls` 两个数组里（顺序一一对应）。加新预设往里各加一行即可，
URL 里的 `&` 写成 `&amp;`。当前内置：

- ICC 抓包直播流；
- 「本地 media.ts」预设，值为 `local://media.ts`：选中后自动扫 sdcard 及每个挂载的可移动卷（U盘/SD）根目录，命中即填入 `file://` 路径；读不到会提示用「文件」按钮手选。

## 本地文件播放

播放管线复用 ExoPlayer 的 `DefaultDataSource`：`file://` 走 `FileDataSource`、SAF 选择器返回的 `content://` 走 `ContentDataSource`，本地文件不会经过只包 HTTP 的 `HttpTraceDataSource`，长度/跳转行为正常。

- 「文件」按钮用 `ACTION_OPEN_DOCUMENT`（SAF），选任意目录（含 U盘 / 外置 sdcard / `/data/local/tmp` 等可读路径）的文件，并取可持久化读权限，回放时地址仍有效。
- 「本地 media.ts」预设按 `local://media.ts` 约定解析，自动定位 `media.ts`。
- API ≤ 32 直读 `file://` 需要 `READ_EXTERNAL_STORAGE` 权限（已在 manifest 申明，maxSdk 32）；`content://` 选择器路径无需权限。

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
    └── PlayUrlProvider.java       # 三步获取 playurl：登录取 token → 频道信息取 play_token → 拼播放地址
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

1. playurl 三步流程：登录取 `access_token` / `device_id`（作 verifycode）→ 频道信息取 `play_token` → 拼最终播放地址。
   字段解析在 `PlayUrlProvider.fetch()`。登录的固定参数（accounttype / devicetype / grouptype / pwd 等）按抓包硬编码，
   用户只填 account / deviceno / chnlid；换密码不同的账号需改 `LOGIN_FIXED_PARAMS` 里的 pwd。
2. READ_LOGS：导出全局 logcat（含 SurfaceFlinger）需 root/系统签名；普通设备仅能拿到本进程日志（含 ExoPlayer）。
3. playurl 三字段预填了文档示例（account 760053843406 / deviceno …21802000017 / chnlid 4200000953），换账号频道直接改输入框即可。
