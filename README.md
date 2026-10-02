# Android Network Camera

把一台旧 Android 手机变成一个**局域网网络摄像头 + 简易 Home/Launcher**。

当前默认分支：

`camera1-mediacodec`

> 当前实际视频链路是 **Camera1 → NV21 → JPEG → HTTP MJPEG**。分支名称保留了历史命名；当前代码不是 H.264 / MediaCodec 视频流。

## 功能

### Android

- Camera1 摄像头采集，兼容 Android 4.1 / API 16
- HTTP MJPEG，默认端口 `8080`
- 浏览器实时预览
- 分辨率 / FPS / Zoom
- 连续自动对焦、单次自动对焦、锁定
- **点击画面，对点击位置进行对焦**
- 摄像头开关
- 摄像头曝光补偿（网页显示为画面亮度）
- HTTP Basic Auth
- Wi-Fi IP、电量状态
- 开机启动
- 可作为 Android 默认 Home / Launcher
- `/setings` / `/settings` 进入 Android 应用设置

### PC

- 从手机 `/stream` 获取 MJPEG
- OpenCV 解码
- YOLO 人脸检测
- InsightFace / ArcFace 特征识别
- 永久 UUID 身份
- UUID → 姓名
- 实时人脸框、姓名 / UUID
- 画面变化自动录像
- 变化前 2 秒 + 变化过程 + 结束后 2 秒
- 原始 MP4 + 人脸标注 MP4
- 历史 MP4 实时人脸识别

**当前不包含姿态识别。**

## 架构

```text
Android
  Camera1
    ↓
  NV21 PreviewCallback
    ↓
  JPEG 编码线程
    ↓
  MjpegServer :8080
    ↓ HTTP MJPEG
    ├──────────────→ 浏览器：预览 / 控制
    │
    └──────────────→ PC
                      ├─ OpenCV
                      ├─ YOLO + ArcFace → UUID / 姓名
                      └─ 变化检测 → EventRecorder
                                      ├─ 原始 MP4
                                      └─ -face MP4
```

手机只负责采集、JPEG 编码和网络服务；AI 全部运行在 PC。

## Android

核心文件：

```text
app/src/main/java/com/mingyue0094/networkcamera/
├── MainActivity.java
├── MjpegServer.java
├── BootReceiver.java
└── NetworkUtil.java
```

Camera1 流程：

```text
Camera.open()
  ↓
Preview Size / FPS / Zoom / Focus / Exposure
  ↓
PreviewCallback → NV21
  ↓
独立 JPEG 编码线程
  ↓
MjpegServer.updateFrame()
```

请求分辨率不一定等于实际分辨率；程序会从设备支持的 Preview Size 中选择最接近值，并在状态接口返回实际值。

### Launcher / Home

MainActivity 同时声明：

```text
CATEGORY_LAUNCHER
CATEGORY_HOME
CATEGORY_DEFAULT
```

并使用：

```text
singleTask
stateNotNeeded
clearTaskOnLaunch
```

作为默认 Home 后，`BootReceiver` 会避免重复启动第二个 Activity，防止多个实例同时抢占 `8080`。

### 点击位置对焦

网页点击视频后：

```text
点击位置 x/y
  ↓
/api/focus
  ↓
Camera1 Focus Area + Metering Area
  ↓
AUTO + autoFocus()
```

设备不支持 Focus Area 时，会退化为普通 `autoFocus()`。

### 摄像头参数

分辨率：

```text
640x480
800x600
1280x720
1280x960
1920x1080
```

FPS：

```text
5 / 10 / 15 / 20 / 24 / 25 / 30
```

Zoom：

```text
1x / 1.5x / 2x / 3x / 4x / 6x / 8x
```

Focus：

```text
continuous  连续自动
single      单次自动
lock        锁定
```

网页“画面亮度”实际控制的是 Camera1 **Exposure Compensation**，不是手机屏幕亮度。

## HTTP API

手机默认：

```text
http://手机IP:8080/
```

### 视频

```http
GET /stream
```

Content-Type：

```text
multipart/x-mixed-replace; boundary=frame
```

### 状态

```http
GET /api/status
```

示例：

```json
{
  "ok": true,
  "camera": true,
  "resolution": "1280x720",
  "fps": 15,
  "zoom": 1,
  "focus": "continuous",
  "battery": 86,
  "brightness": 0,
  "brightnessMin": -6,
  "brightnessMax": 6
}
```

曝光范围由具体 Camera1 驱动决定。

### 修改配置

```http
POST /api/config
Content-Type: application/json
```

```json
{
  "resolution": "1280x720",
  "fps": 15,
  "zoom": 2,
  "focus": "continuous"
}
```

### 点击对焦

```http
GET /api/focus?x=0.5&y=0.5
```

`x/y` 为 `0.0 ~ 1.0` 的归一化坐标。

### 曝光补偿

```http
GET /api/brightness?value=0
```

### 摄像头开关

```http
GET /api/camera?enabled=1
GET /api/camera?enabled=0
```

### Android 设置

```http
GET /setings
GET /settings
```

`/setings` 是历史拼写，继续兼容。

### Basic Auth

默认关闭。设置密码后用户名固定为：

```text
admin
```

PC：

```bat
python main.py --url http://192.168.1.100:8080/stream --user admin --password 123456
```

## PC 目录

```text
pc/
├── main.py
├── play_recording.py
├── requirements.txt
├── README.md
├── models/
│   └── yolov8n-face.pt
├── faces/
│   └── UUID/
│       ├── UUID.jpeg
│       ├── embedding.npy
│       └── info.json
└── recordings/
    ├── YYYYMMDDHHMMSS-YYYYMMDDHHMMSS.mp4
    └── YYYYMMDDHHMMSS-YYYYMMDDHHMMSS-face.mp4
```

## PC 安装

推荐 Windows + Python 3.10 + NVIDIA GPU + CUDA + ONNX Runtime GPU。

```bat
cd pc
pip install -r requirements.txt
```

依赖：

```text
numpy
opencv-python
requests
ultralytics
insightface
onnxruntime-gpu
```

检查 GPU Provider：

```bat
python -c "import onnxruntime as ort; print(ort.get_available_providers())"
```

正常应包含：

```text
CUDAExecutionProvider
```

默认 YOLO 人脸模型：

```text
pc/models/yolov8n-face.pt
```

也可以：

```bat
python main.py --url http://192.168.1.100:8080/stream --yolo models\yolov8n-face.pt
```

## PC 实时运行

```bat
cd pc
python main.py --url http://192.168.1.100:8080/stream
```

人脸管理：

```text
http://127.0.0.1:8765/
```

## 人脸识别

```text
MJPEG
  ↓
OpenCV
  ↓
YOLOv8 人脸检测
  ↓
人脸裁剪
  ↓
InsightFace buffalo_l / ArcFace
  ↓
embedding
  ↓
cosine similarity
  ↓
UUID
```

YOLO 负责定位；ArcFace 负责身份特征。

手机不运行 AI。

### 永久 UUID

无法匹配的新身份会生成 UUID，并保存：

```text
pc/faces/<UUID>/
├── <UUID>.jpeg
├── embedding.npy
└── info.json
```

例如：

```json
{
  "uuid": "8f31c2a1-xxxx-xxxx-xxxx",
  "name": "张三"
}
```

UUID 是身份标识，`name` 是人工设置的显示名称。姓名为空时显示 UUID。

默认匹配阈值：

```text
0.48
```

可通过：

```bat
python main.py --url http://192.168.1.100:8080/stream --face-threshold 0.48
```

调整。

实时识别最高约 10 FPS；视频输入 FPS 与 AI 推理 FPS 独立，中间帧沿用最近一次识别结果。

## 自动录像

变化检测在缩小后的 `320 × 180` 画面上进行：

```text
BGR
 ↓
Gray
 ↓
GaussianBlur
 ↓
absdiff
 ↓
threshold
 ↓
变化像素比例
```

当前参数：

```text
CHANGE_WIDTH = 320
CHANGE_HEIGHT = 180
CHANGE_PIXEL_THRESHOLD = 18
CHANGE_RATIO_THRESHOLD = 0.015
```

程序内存循环保留最近 2 秒。

检测到事件：

```text
前 2 秒
 + 变化过程
 + 结束后 2 秒
       ↓
      MP4
```

输出：

```text
YYYYMMDDHHMMSS-YYYYMMDDHHMMSS.mp4
```

如果事件期间检测到人脸，再生成：

```text
YYYYMMDDHHMMSS-YYYYMMDDHHMMSS-face.mp4
```

普通 MP4 保持原始画面；`-face.mp4` 增加人脸框、姓名 / UUID。

## 历史录像

```bat
cd pc
python play_recording.py
```

也可以：

```bat
python play_recording.py --file recordings\20261001213006-20261001213013.mp4
python play_recording.py --recognize-fps 15
```

控制：

```text
空格  暂停 / 继续
Q     退出
ESC   退出
```

原始 MP4 不会被修改。

## 仅录像模式

不加载 AI：

```bat
python main.py --url http://192.168.1.100:8080/stream --no-face
```

仍然执行画面变化检测和事件录像。

## Android 构建

```text
minSdkVersion     16
targetSdkVersion  16
compileSdkVersion 30

Android Gradle Plugin 4.1.3
Gradle 6.5
Java 8
```

Debug APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions：

```text
.github/workflows/build.yml
```

普通 push / 手动执行：

```text
assembleDebug
  ↓
GitHub Actions Artifact
```

Tag：

```text
assembleDebug
  ↓
GitHub Release
  ↓
android-network-camera-<tag>.apk
```

当前 Release 使用的是构建出的 **Debug APK**，并非单独的 Release 签名构建。

## 项目边界

### Android

```text
✓ Camera1
✓ Android 4.1 / API 16
✓ NV21 → JPEG
✓ HTTP MJPEG
✓ 网页控制
✓ 分辨率 / FPS / Zoom
✓ 连续 / 单次 / 锁定对焦
✓ 点击位置对焦
✓ Exposure Compensation
✓ 摄像头开关
✓ Basic Auth
✓ 电量 / Wi-Fi IP
✓ 开机启动
✓ Home / Launcher
✓ Android 设置入口
```

### PC

```text
✓ MJPEG
✓ OpenCV
✓ YOLO 人脸检测
✓ InsightFace / ArcFace
✓ 永久 UUID
✓ UUID.jpeg
✓ embedding.npy
✓ UUID → 姓名
✓ 实时人脸框
✓ 画面变化检测
✓ 前后 2 秒事件缓存
✓ 原始事件 MP4
✓ 人脸标注 MP4
✓ 历史 MP4 实时识别
```

明确没有：

```text
✗ 姿态识别
✗ 手机端 YOLO
✗ 手机端 ArcFace
✗ 手机端 AI 推理
✗ 当前稳定链路中的 H.264 / RTSP / fMP4
```

## 快速开始

### 1. 手机

启动 Android Network Camera。

确保手机和电脑在同一局域网，然后访问：

```text
http://手机IP:8080/
```

### 2. PC

```bat
cd pc
python main.py --url http://手机IP:8080/stream
```

### 3. 人脸管理

```text
http://127.0.0.1:8765/
```

### 4. 历史录像

```bat
cd pc
python play_recording.py
```

## 当前稳定基线

默认分支：

```text
camera1-mediacodec
```

已确认通过的历史基线：

```text
fa83f8d5038a84480533222b3f1fb6b0b1962d4b
```

Launcher、点击位置对焦、曝光补偿、摄像头控制等后续功能已经合并到默认分支。

当前合并提交：

```text
2f051ed9f50aeb0f852809f51c1d18e96fd888c8
```
