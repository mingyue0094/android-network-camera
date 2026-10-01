# Android Network Camera

一个运行在 Android 手机上的轻量局域网网络摄像头。

当前稳定开发基线：camera1-mediacodec。

核心链路：

~~~text
Camera1
  ↓
NV21 PreviewCallback
  ↓
独立 JPEG 编码线程
  ↓
HTTP MJPEG :8080
  ↓
PC OpenCV / YOLO / InsightFace
~~~

## 功能

### Android 手机端

- Camera1 采集
- NV21 预览帧
- JPEG 编码
- HTTP MJPEG 视频流
- 默认 HTTP 端口 8080
- 网页实时查看
- 网页调整分辨率、FPS、Zoom
- HTTP Basic Auth
- WiFi IP 状态显示
- 电量状态 API
- 开机启动
- 可作为默认 Home/Launcher
- Android 设置入口

### PC 端

- 从手机 /stream 读取 MJPEG
- YOLOv8 人脸检测
- InsightFace / ArcFace 人脸特征
- 永久 UUID 身份
- UUID.jpeg 人脸照片
- UUID → 姓名管理
- 画面实时人脸框和识别结果
- 画面变化自动录像
- 变化前 2 秒 + 变化过程 + 结束后 2 秒
- 原始事件录像
- 有人脸时额外保存带框和识别结果的录像
- 已录制 MP4 播放时实时人脸识别

当前版本不包含姿态识别。

---

## 项目结构

~~~text
android-network-camera/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/mingyue0094/networkcamera/
│       │   ├── MainActivity.java
│       │   ├── MjpegServer.java
│       │   ├── BootReceiver.java
│       │   └── NetworkUtil.java
│       └── res/values/
│           ├── strings.xml
│           └── styles.xml
├── pc/
│   ├── main.py
│   ├── play_recording.py
│   ├── requirements.txt
│   ├── README.md
│   ├── models/
│   ├── faces/
│   └── recordings/
├── .github/workflows/build.yml
├── build.gradle
├── settings.gradle
├── gradle.properties
└── README.md
~~~

---

## Android 工作方式

MainActivity 使用 Android Camera1。

采集过程：

~~~text
Camera.open()
   ↓
设置 Preview Size / FPS / Zoom
   ↓
NV21 PreviewCallback
   ↓
JPEG 编码线程
   ↓
MjpegServer.updateFrame()
~~~

JPEG 编码放在独立线程，避免直接在 Camera 回调中进行 JPEG 压缩。

MjpegServer 是项目自己的轻量 HTTP Server，不依赖第三方 HTTP Server。

---

## HTTP 接口

手机默认监听：

~~~text
0.0.0.0:8080
~~~

### 网页

~~~text
GET /
~~~

显示：

- MJPEG 视频
- 当前状态
- 分辨率
- FPS
- Zoom
- 参数设置
- 打开 Android 设置

### MJPEG

~~~text
GET /stream
~~~

类型：

~~~text
multipart/x-mixed-replace; boundary=frame
~~~

PC 端直接读取这个接口。

### 状态

~~~text
GET /api/status
~~~

示例：

~~~json
{
  "ok": true,
  "resolution": "1280x720",
  "fps": 15,
  "zoom": 1,
  "battery": 86
}
~~~

### 修改配置

~~~text
POST /api/config
~~~

参数：

~~~json
{
  "resolution": "1280x720",
  "fps": 15,
  "zoom": 1
}
~~~

### Android 设置

~~~text
GET /setings
GET /settings
~~~

两个路径都支持，作用是打开本应用的 Android 应用详情设置页。

---

## Android 可调参数

当前网页和 App 内部支持：

分辨率：

~~~text
640x480
800x600
1280x720
1280x960
1920x1080
~~~

FPS：

~~~text
5
10
15
20
24
25
30
~~~

Zoom：

~~~text
1x
1.5x
2x
3x
4x
6x
8x
~~~

Camera1 最终使用手机实际支持的 Preview Size，因此请求值与实际值可能存在差异。

---

## HTTP Basic Auth

密码为空时关闭认证。

开启后：

~~~text
用户名：admin
密码：设置的密码
~~~

PC：

~~~bat
python main.py --url http://192.168.1.100:8080/stream --user admin --password 123456
~~~

---

## Android 构建

当前构建配置：

~~~text
compileSdkVersion 30
minSdkVersion 16
targetSdkVersion 16

Android Gradle Plugin 4.1.3
Gradle 6.5
Java 8
~~~

Windows：

~~~bat
gradlew.bat assembleDebug
~~~

APK：

~~~text
app/build/outputs/apk/debug/app-debug.apk
~~~

GitHub Actions：

~~~text
.github/workflows/build.yml
~~~

支持 push 和手动 workflow_dispatch 构建，并上传 debug APK。

---

# PC 端

PC 端详细说明：

~~~text
pc/README.md
~~~

实时启动：

~~~bat
cd pc
python main.py --url http://192.168.1.100:8080/stream
~~~

人脸管理：

~~~text
http://127.0.0.1:8765/
~~~

历史录像播放：

~~~bat
python play_recording.py
~~~

---

## PC 人脸识别流程

~~~text
手机 /stream
    ↓
OpenCV MJPEG 解码
    ↓
YOLOv8 人脸检测
    ↓
裁剪人脸
    ↓
InsightFace / ArcFace
    ↓
embedding
    ↓
cosine similarity
    ↓
已有 UUID / 新 UUID
~~~

YOLO 负责检测位置。

ArcFace 负责身份特征。

同一个人匹配成功后继续使用原 UUID。

---

## 永久 UUID 人脸库

~~~text
pc/faces/
└── UUID/
    ├── UUID.jpeg
    ├── embedding.npy
    └── info.json
~~~

info.json：

~~~json
{
  "uuid": "UUID",
  "name": "姓名"
}
~~~

UUID 是永久身份。

name 只是显示名称。

---

## 自动录像

PC 不会一直写录像文件。

内存循环保留最近 2 秒。

检测到变化：

~~~text
前 2 秒缓存
    +
变化过程
    +
变化结束后 2 秒
    ↓
保存 MP4
~~~

普通事件：

~~~text
YYYYMMDDHHMMSS-YYYYMMDDHHMMSS.mp4
~~~

如果该事件期间检测到人脸，再额外保存：

~~~text
YYYYMMDDHHMMSS-YYYYMMDDHHMMSS-face.mp4
~~~

普通版本保持原始画面。

face 版本包含：

- 人脸框
- 姓名
- UUID

---

## 历史录像实时识别

play_recording.py：

~~~text
已有 MP4
  ↓
正常播放
  ↓
YOLO 人脸检测
  ↓
ArcFace 身份识别
  ↓
实时叠加框和姓名/UUID
~~~

不会修改原始 MP4。

---

## 当前稳定架构

当前稳定分支：

~~~text
camera1-mediacodec
~~~

已经验证的 Camera1 基线提交：

~~~text
fa83f8d5038a84480533222b3f1fb6b0b1962d4b
~~~

当前稳定视频链路明确使用：

~~~text
Camera1 → NV21 → JPEG → MJPEG
~~~

旧的 H.264/fMP4 编码文件不属于当前稳定链路，不应作为当前 PC 输入接口的依据。

---

## 完整运行关系

~~~text
Android 手机
    │
    │ HTTP MJPEG
    ▼
PC main.py
    │
    ├── 人脸识别
    │     ├── YOLO
    │     ├── ArcFace
    │     └── UUID
    │
    └── 画面变化录像
          ├── 原始 MP4
          └── -face MP4

PC play_recording.py
    │
    └── 已录制 MP4 → 实时人脸识别播放
~~~
