# Android Network Camera

将 Android 手机变成局域网网络摄像头。

**支持 Android 4.1（API 16）及以上。**

核心目标不是把摄像头画面转成 JPEG，而是尽可能使用手机硬件能力完成：

```
Camera
  ↓
H.264 硬件编码
  ↓
Fragmented MP4（fMP4）
  ↓
HTTP :8080
  ↓
电脑 / FFmpeg / OpenCV / YOLO
```

手机负责**采集、硬件编码和网络传输**，电脑负责视频解码和 AI 推理。

---

## 目录

- [功能](#功能)
- [系统兼容性](#系统兼容性)
- [视频编码架构](#视频编码架构)
- [网络接口](#网络接口)
- [电脑端取流](#电脑端取流)
- [YOLO 使用](#yolo-使用)
- [手机端控制](#手机端控制)
- [安装与使用](#安装与使用)
- [构建](#构建)
- [项目结构](#项目结构)
- [性能与限制](#性能与限制)

---

## 功能

- Android 手机摄像头作为局域网网络摄像头
- 默认 HTTP 端口：`8080`
- H.264 硬件编码
- Fragmented MP4（fMP4）持续视频流
- HTTP Chunked Streaming
- CORS 支持
- TCP_NODELAY，降低局域网传输等待
- 多客户端连接
- 电脑端可直接使用 FFmpeg / OpenCV / YOLO
- 支持实时调整：
  - 分辨率
  - FPS
  - H.264 码率
  - I-frame 间隔
  - 数字变焦
  - 自动对焦
  - 曝光补偿
- Android 5.0+ 使用 Camera2
- Android 4.1～4.4 使用旧版 Camera API + MediaCodec 兼容路径

---

## 系统兼容性

| Android | API | 摄像头后端 | H.264 编码 |
|---|---:|---|---|
| Android 4.1 | 16 | Legacy Camera | MediaCodec |
| Android 4.2 | 17 | Legacy Camera | MediaCodec |
| Android 4.3 | 18 | Legacy Camera | MediaCodec |
| Android 4.4 | 19 | Legacy Camera | MediaCodec |
| Android 5.0+ | 21+ | Camera2 | MediaCodec Surface |

项目最低：

```
minSdkVersion 16
```

### Android 4.1～4.4

旧版 Android 没有 Camera2，因此使用：

```
Legacy Camera
    ↓
NV21
    ↓
MediaCodec H.264
    ↓
fMP4
```

这一兼容路径不保证所有老设备都具有相同的 H.264 编码能力。不同厂商的 Android 4.x 设备，其 MediaCodec 编码器、支持分辨率和 YUV 色彩格式可能不同。

### Android 5.0+

使用：

```
Camera2
   ↓
Surface
   ↓
MediaCodec
   ↓
H.264
   ↓
fMP4
```

这是当前主要的视频路径，可以避免 Camera2 → CPU Bitmap/JPEG 的额外转换。

---

## 视频编码架构

### Android 5.0+

```
┌──────────────┐
│   Camera2    │
└──────┬───────┘
       │ Surface
       ▼
┌──────────────┐
│   MediaCodec │
│ H.264 Encoder│
└──────┬───────┘
       │ H.264 / AVCC
       ▼
┌──────────────┐
│   Fmp4Muxer  │
└──────┬───────┘
       │ fMP4
       ▼
┌──────────────┐
│  Fmp4Server  │
│    :8080     │
└──────┬───────┘
       │ HTTP
       ▼
 PC / FFmpeg / OpenCV / YOLO
```

### Android 4.1～4.4

```
┌────────────────┐
│ Legacy Camera  │
└───────┬────────┘
        │ NV21
        ▼
┌────────────────┐
│  MediaCodec    │
│ H.264 Encoder  │
└───────┬────────┘
        │ H.264
        ▼
┌────────────────┐
│   Fmp4Muxer    │
└───────┬────────┘
        │ fMP4
        ▼
┌────────────────┐
│  Fmp4Server    │
│     :8080      │
└────────────────┘
```

---

## 网络接口

手机启动后监听：

```
0.0.0.0:8080
```

### 1. 视频流

```
GET /video.mp4
```

返回持续的 **Fragmented MP4（fMP4）HTTP 流**。

例如：

```
http://192.168.1.100:8080/video.mp4
```

注意：

- 这不是一次性 MP4 文件。
- 连接保持打开并持续发送视频数据。
- 新客户端会获得 MP4 初始化数据和后续视频 fragment。
- 适合 FFmpeg 等能够读取持续 fMP4 流的客户端。

### 2. 帮助页面

```
GET /help
```

例如：

```
http://192.168.1.100:8080/help
```

帮助页面会显示当前 HTTP API、编码参数、数据流结构以及电脑端使用示例。

### 3. 其他路径

未实现的 HTTP 路径返回：

```
404 Not Found
```

---

## 电脑端取流

手机和电脑连接同一个局域网。

假设手机 IP：

```
192.168.1.100
```

视频地址：

```
http://192.168.1.100:8080/video.mp4
```

### FFmpeg

查看输入：

```bash
ffmpeg -i http://192.168.1.100:8080/video.mp4
```

保存：

```bash
ffmpeg -i http://192.168.1.100:8080/video.mp4 -c copy output.mp4
```

解码到原始视频：

```bash
ffmpeg -i http://192.168.1.100:8080/video.mp4 -f rawvideo -pix_fmt bgr24 pipe:1
```

### OpenCV

如果 OpenCV 构建环境包含 FFmpeg，可以直接尝试：

```python
import cv2

url = "http://192.168.1.100:8080/video.mp4"

cap = cv2.VideoCapture(url)

while True:
    ok, frame = cap.read()

    if not ok:
        break

    cv2.imshow("Android Network Camera", frame)

    if cv2.waitKey(1) & 0xFF == 27:
        break

cap.release()
cv2.destroyAllWindows()
```

如果当前 OpenCV/FFmpeg 对持续 fMP4 HTTP 流支持不好，建议使用 FFmpeg 解码后再把帧交给 OpenCV/YOLO。

---

## YOLO 使用

典型数据路径：

```
Android Camera
      ↓
MediaCodec H.264
      ↓
fMP4
      ↓
HTTP :8080
      ↓
FFmpeg
      ↓
BGR Frame
      ↓
YOLO
```

例如使用 Ultralytics：

```bash
pip install ultralytics opencv-python numpy
```

电脑需要安装 FFmpeg，并确保：

```
ffmpeg.exe
```

已经加入 PATH。

### 建议的取流方式

让 FFmpeg 负责：

1. HTTP 连接
2. fMP4 解封装
3. H.264 硬件/软件解码
4. 输出 BGR 帧

然后由 Python/OpenCV 将帧交给 YOLO。

这样可以避免：

```
Camera
 ↓
JPEG
 ↓
HTTP MJPEG
 ↓
JPEG 解码
 ↓
YOLO
```

改成：

```
Camera
 ↓
H.264
 ↓
fMP4
 ↓
FFmpeg
 ↓
BGR
 ↓
YOLO
```

可以显著减少网络传输数据量，并避免手机逐帧 JPEG 压缩。

---

## 手机端控制

当前界面提供：

| 控制 | 作用 |
|---|---|
| 变焦- | 减小数字变焦 |
| 变焦+ | 增大数字变焦 |
| 自动对焦 | 触发自动对焦 |
| 亮度- | 减少曝光补偿 |
| 亮度+ | 增加曝光补偿 |

默认编码参数：

```
分辨率：1920 × 1080
FPS：30
码率：12 Mbps
I-frame interval：1 秒
```

实际运行时会根据设备摄像头和编码器能力选择可用参数。

### 关于变焦

当前的变焦属于**数字裁剪/Camera 参数变焦**，并不代表手机一定会切换物理摄像头或光学长焦镜头。

---

## 安装与使用

### 1. 安装 APK

安装构建得到的 APK。

### 2. 连接 Wi-Fi

让手机和电脑处于同一个局域网。

### 3. 查看手机 IP

App 界面会显示：

```
http://手机IP:8080/video.mp4
```

例如：

```
http://192.168.1.100:8080/video.mp4
```

### 4. 电脑访问

浏览器可以先打开：

```
http://192.168.1.100:8080/help
```

查看当前接口说明。

视频程序使用：

```
http://192.168.1.100:8080/video.mp4
```

---

## 构建

项目当前使用：

```
compileSdkVersion 30
minSdkVersion 16
targetSdkVersion 30
Android Gradle Plugin 4.1.x
Gradle 6.5
Java 8
```

### 本地构建

在项目根目录：

```bash
gradlew.bat assembleDebug
```

APK 通常位于：

```
app/build/outputs/apk/debug/app-debug.apk
```

### GitHub Actions

项目配置了 GitHub Actions。

每次 push 到仓库后会自动执行：

```
Checkout
   ↓
Java 8
   ↓
Gradle 6.5
   ↓
assembleDebug
   ↓
上传 APK Artifact
```

也可以在 GitHub Actions 页面手动运行构建。

---

## 项目结构

主要代码：

```
app/
└── src/main/
    ├── AndroidManifest.xml
    └── java/com/mingyue0094/networkcamera/
        ├── MainActivity.java
        ├── Camera2Controller.java
        ├── LegacyCameraController.java
        ├── H264Encoder.java
        ├── LegacyH264Encoder.java
        ├── Fmp4Muxer.java
        ├── Fmp4Server.java
        └── NetworkUtil.java
```

### MainActivity

负责：

- UI
- 摄像头后端选择
- 编码器启动/停止
- 参数控制
- 网络状态显示

### Camera2Controller

Android 5.0+ 摄像头控制。

### LegacyCameraController

Android 4.1～4.4 摄像头控制。

### H264Encoder

Android 5.0+ MediaCodec Surface 输入的 H.264 编码器。

### LegacyH264Encoder

旧 Android 设备的 YUV → MediaCodec H.264 编码器。

### Fmp4Muxer

将 H.264 编码数据封装成 fragmented MP4。

### Fmp4Server

负责：

- HTTP 监听
- fMP4 初始化段
- 视频 fragment
- 多客户端
- HTTP Chunked Streaming

---

## 性能与限制

### 手机端

Android 5.0+ 的主要路径：

```
Camera2 → Surface → MediaCodec
```

避免把每一帧转换成 Bitmap/JPEG。

Android 4.1～4.4 的兼容路径由于旧 Camera API 的限制，需要取得 NV21 预览数据后交给 MediaCodec，因此 CPU 内存带宽和格式转换开销会高于 Android 5.0+ 路径。

### 老设备兼容性

Android 4.1 只是最低 API 兼容目标。

实际能否使用某个：

- 分辨率
- FPS
- H.264 编码码率
- H.264 Profile
- YUV 色彩格式

取决于具体手机厂商提供的 Camera / MediaCodec 实现。

因此不能保证所有 Android 4.1 手机都能稳定使用 1920×1080@30fps。

### 网络

推荐：

- 手机和电脑使用同一个 Wi-Fi AP
- 优先使用 5 GHz Wi-Fi
- 尽量减少手机与 AP 之间的距离
- YOLO 电脑尽量使用有线网络或稳定的 5 GHz Wi-Fi

12 Mbps H.264 码率意味着网络需要持续承载相应的视频数据，同时还存在协议和 MP4 封装开销。

---

## 与 MJPEG 的区别

传统方案：

```
Camera
 ↓
JPEG
 ↓
MJPEG
 ↓
HTTP
 ↓
PC
```

本项目：

```
Camera
 ↓
H.264 Hardware Encoder
 ↓
fMP4
 ↓
HTTP
 ↓
PC / FFmpeg
```

H.264 更适合持续视频传输，可以在保持较高画质的同时降低网络带宽需求。

---

## 注意事项

1. 手机和电脑必须能够互相访问 TCP `8080`。
2. 如果电脑无法访问，先检查手机 IP、Wi-Fi 隔离以及防火墙。
3. Android 4.x 的实际编码能力取决于设备厂商。
4. `/video.mp4` 是持续流，不是普通静态 MP4 文件。
5. 浏览器对持续 fMP4 HTTP 流的直接播放能力取决于浏览器环境；需要程序取流时优先使用 FFmpeg。
6. 当前项目重点是局域网低延迟视频传输和电脑端 AI 推理，并不定位为互联网视频直播服务器。

---

## License

如果仓库没有单独指定 License，请以仓库实际 License 文件为准。
