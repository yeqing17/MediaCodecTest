# 项目需求文档

## 项目名称

MediaCodecTest

------

# 项目背景

现有 Android 9 PAD 设备播放直播视频时出现严重卡顿。

当前线上播放器基于 IJKPlayer + MediaCodec。

日志分析发现：

- MediaCodec初始化正常
- 无Codec异常
- 无OMX异常
- 视频实际输出帧率仅13~18fps
- 视频数据持续堆积
- A/V同步差值约400~500ms
- 未发生Drop Frame
- 怀疑问题位于IJK同步逻辑或Android显示链路

需要开发独立验证工具。

目标：

排除IJK影响。

直接验证：

- MediaCodec
- ExoPlayer(Media3)
- Android系统
- Amlogic驱动

是否存在问题。

------

# 开发要求

## 技术栈

### 语言

Java

禁止使用 Kotlin

------

### IDE

Android Studio

兼容：

- Hedgehog
- Iguana
- Narwhal

------

### SDK

compileSdk = 34

targetSdk = 34

minSdk = 28

支持：

Android 9+

------

### 播放器

AndroidX Media3 ExoPlayer

依赖：

```gradle
implementation 'androidx.media3:media3-exoplayer:1.8.0'
implementation 'androidx.media3:media3-ui:1.8.0'
implementation 'androidx.media3:media3-common:1.8.0'
```

------

# 功能需求

## 页面

单页面应用

MainActivity

布局：

顶部：

URL输入框

中间：

播放按钮

停止按钮

获取URL按钮

播放器区域

底部：

实时统计信息区域

------

# URL模式

支持两种模式

------

## 模式1

直接输入URL

例如：

```text
http://xxx/live.ts
```

点击播放即可。

------

## 模式2

接口获取URL

接口：

```text
GET
http://httpicc.slave.tsyrmt.cn:14311/playurl
```

参数动态拼接。

需要提供配置区域：

```text
playtype
protocol
verifycode
accesstoken
programid
playtoken
rate
icctrial
tick
```

自动生成最终URL。

------

# 播放功能

点击播放：

```java
player.prepare();
player.play();
```

点击停止：

```java
player.stop();
```

点击释放：

```java
player.release();
```

------

# 播放器配置

默认：

硬解码

使用：

MediaCodec

------

# 软硬解切换

页面增加：

Switch

名称：

Force Software Decode

开启：

尽量关闭硬解

使用软件解码

关闭：

默认硬解

------

# 统计功能

实时显示：

------

## Decoder名称

例如：

```text
OMX.amlogic.avc.decoder.awesome
```

或：

```text
c2.android.avc.decoder
```

------

## MimeType

例如：

```text
video/avc
```

------

## 分辨率

例如：

```text
1920x1080
```

------

## FPS

实时统计

每秒刷新一次

显示：

```text
FPS: 25
```

------

## Dropped Frames

显示：

```text
Dropped: 0
```

来自：

AnalyticsListener

------

## Bitrate

显示：

```text
Bitrate: xxxx kbps
```

------

## Buffer状态

显示：

```text
BufferedPosition
BufferedPercentage
```

------

## 当前播放时间

显示：

```text
Current Position
```

------

# 日志系统

创建：

MediaCodecTest

专用Tag

```java
private static final String TAG = "MCT";
```

------

# 导出日志

按钮：

Export Log

执行：

```bash
logcat -d
```

导出：

```text
/sdcard/MediaCodecTest/
```

文件名：

```text
log_yyyyMMdd_HHmmss.txt
```

------

# 设备信息

显示：

```text
Manufacturer
Model
Android Version
SDK Version
```

例如：

```text
Amlogic
Android 9
SDK 28
```

------

# Codec诊断

启动时枚举：

MediaCodecList

显示：

```text
所有AVC Decoder
所有HEVC Decoder
```

例如：

```text
OMX.amlogic.avc.decoder.awesome
c2.android.avc.decoder
OMX.google.h264.decoder
```

------

# 导出诊断报告

按钮：

Export Report

生成：

```text
report.txt
```

内容：

设备信息

Decoder列表

当前Decoder

FPS

DroppedFrames

分辨率

Bitrate

播放URL

播放时间

------

# UI要求

简单即可

无需Material Design

无需复杂主题

优先排障

------

# 验收标准

同一PAD设备

播放同一直播流

------

如果：

```text
FPS≈25
Dropped≈0
```

则：

MediaCodec正常

Android系统正常

问题大概率位于IJK链路

------

如果：

```text
FPS≈13~18
```

且与线上表现一致

则：

问题大概率位于

Android系统

Amlogic驱动

Surface显示链路

MediaCodec链路

------

# 最终交付

输出完整Android Studio工程：

MediaCodecTest

包含：

- Gradle配置
- Manifest
- Layout
- MainActivity
- Analytics模块
- Codec诊断模块
- Log导出模块
- Report导出模块
- PlayUrlProvider模块

要求：

工程导入Android Studio后可直接编译运行。

# 补充提示词

请按照资深Android播放器开发工程师标准实现。

要求：

1. Java实现，不允许Kotlin
2. Android 9(API28)真机兼容
3. 使用Media3 ExoPlayer
4. 所有代码必须完整可编译
5. 不允许输出伪代码
6. 不允许TODO
7. 生成完整工程目录结构
8. 所有import必须正确
9. 所有权限配置完整
10. 提供README编译说明