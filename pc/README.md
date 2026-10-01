# PC：AI 人脸识别 + 画面变化自动录像

PC 目录负责电脑端的全部视频分析工作。

手机只负责稳定提供 HTTP MJPEG：

~~~text
http://手机IP:8080/stream
~~~

PC 负责：

~~~text
MJPEG
 ↓
OpenCV
 ↓
YOLOv8
 ↓
InsightFace / ArcFace
 ↓
永久 UUID
 ↓
画面变化检测
 ↓
事件录像
~~~

当前明确不包含姿态识别。

---

## 文件

~~~text
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
~~~

models、faces、recordings 不存在时，main.py 会自动创建目录。

---

# 一、安装

推荐：

~~~text
Windows
Python 3.10
NVIDIA GPU
CUDA
ONNX Runtime GPU
~~~

安装：

~~~bat
cd pc
pip install -r requirements.txt
~~~

依赖：

~~~text
numpy
opencv-python
requests
ultralytics
insightface
onnxruntime-gpu
~~~

检查 ONNX Runtime：

~~~bat
python -c "import onnxruntime as ort; print(ort.get_available_providers())"
~~~

GPU 环境正常时应看到：

~~~text
CUDAExecutionProvider
~~~

如果只有 CPUExecutionProvider，则 InsightFace 会使用 CPU。

---

# 二、YOLO 人脸模型

默认：

~~~text
pc/models/yolov8n-face.pt
~~~

也可以手动指定：

~~~bat
python main.py --url http://192.168.1.100:8080/stream --yolo models\yolov8n-face.pt
~~~

如果默认模型不存在，main.py 会直接停止并提示模型路径。

---

# 三、实时启动

手机：

~~~text
http://192.168.1.100:8080/stream
~~~

启动：

~~~bat
python main.py --url http://192.168.1.100:8080/stream
~~~

Basic Auth：

~~~bat
python main.py ^
  --url http://192.168.1.100:8080/stream ^
  --user admin ^
  --password 123456
~~~

启动后：

~~~text
人脸管理：
http://127.0.0.1:8765/
~~~

---

# 四、MJPEG 输入

MjpegReader 直接读取 HTTP MJPEG 长连接。

它从网络字节流中寻找：

~~~text
JPEG SOI = FF D8
JPEG EOI = FF D9
~~~

然后：

~~~text
JPEG
 ↓
cv2.imdecode()
 ↓
OpenCV BGR frame
~~~

不依赖本地视频文件。

实时录像的输入就是手机：

~~~text
/stream
~~~

---

# 五、人脸识别

处理链：

~~~text
OpenCV frame
    ↓
YOLOv8
    ↓
人脸框
    ↓
裁剪人脸
    ↓
InsightFace buffalo_l
    ↓
ArcFace embedding
    ↓
cosine similarity
    ↓
UUID
~~~

YOLO 负责：

~~~text
检测人脸位置
~~~

ArcFace 负责：

~~~text
提取人脸身份特征
~~~

两者职责分开。

---

# 六、永久 UUID

第一次检测到无法匹配的人脸：

~~~text
YOLO
 ↓
ArcFace
 ↓
人脸库没有达到匹配阈值
 ↓
uuid.uuid4()
 ↓
创建永久 UUID
~~~

之后再次看到这个人：

~~~text
新的 embedding
 ↓
与本地 embedding.npy 比较
 ↓
达到阈值
 ↓
继续使用原 UUID
~~~

不会每一帧生成新的 UUID。

---

# 七、人脸库

目录：

~~~text
pc/faces/
└── 8f31c2a1-xxxx-xxxx-xxxx/
    ├── 8f31c2a1-xxxx-xxxx-xxxx.jpeg
    ├── embedding.npy
    └── info.json
~~~

### UUID.jpeg

第一次创建身份时保存的人脸照片。

### embedding.npy

ArcFace 人脸特征。

### info.json

~~~json
{
  "uuid": "8f31c2a1-xxxx-xxxx-xxxx",
  "name": "张三"
}
~~~

UUID 是永久身份。

name 是人工设置的显示名称。

---

# 八、姓名管理网页

main.py 启动人脸识别后启动：

~~~text
http://127.0.0.1:8765/
~~~

网页可以：

- 查看人脸照片
- 查看 UUID
- 输入姓名
- 保存姓名

例如：

~~~text
UUID:
8f31c2a1-xxxx-xxxx-xxxx

姓名：
张三

保存
~~~

保存后：

~~~text
UUID → 张三
~~~

画面显示张三。

如果姓名为空，则显示 UUID。

---

# 九、匹配阈值

默认：

~~~text
FACE_MATCH_THRESHOLD = 0.48
~~~

启动时可修改：

~~~bat
python main.py --url http://192.168.1.100:8080/stream --face-threshold 0.48
~~~

历史录像也支持：

~~~bat
python play_recording.py --face-threshold 0.48
~~~

---

# 十、实时识别频率

main.py 默认最高约 10 FPS 做人脸识别。

原因是 YOLO + InsightFace 不需要每个输入帧都完整推理。

中间帧沿用最近一次识别结果。

因此：

~~~text
视频输入 FPS
    ≠
AI 推理 FPS
~~~

录像缓存仍持续接收输入视频帧。

---

# 十一、实时窗口

main.py OpenCV 窗口显示：

- 视频
- 人脸框
- 姓名 / UUID
- 当前变化状态
- 变化比例
- RECORDING 状态

退出：

~~~text
Q
ESC
~~~

---

# 十二、画面变化检测

画面先缩小：

~~~text
320 × 180
~~~

然后：

~~~text
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
~~~

当前参数：

~~~text
CHANGE_WIDTH = 320
CHANGE_HEIGHT = 180

CHANGE_PIXEL_THRESHOLD = 18
CHANGE_RATIO_THRESHOLD = 0.015
~~~

---

# 十三、自动录像

程序不会一直向硬盘写视频。

内存一直保留最近：

~~~text
2 秒
~~~

检测到变化：

~~~text
变化开始
   ↓
取出变化前 2 秒
   ↓
继续记录变化过程
   ↓
变化停止
   ↓
继续记录 2 秒
   ↓
保存
~~~

最终：

~~~text
变化前 2 秒
+
变化过程
+
变化结束后 2 秒
~~~

如果一直没有明显变化：

~~~text
不生成录像文件
~~~

---

# 十四、普通录像

输出目录：

~~~text
pc/recordings/
~~~

文件名：

~~~text
YYYYMMDDHHMMSS-YYYYMMDDHHMMSS.mp4
~~~

例如：

~~~text
20261001213006-20261001213013.mp4
~~~

这个文件保持手机原始画面，不添加 AI 框。

---

# 十五、人脸事件录像

如果一个变化事件期间检测到人脸，会额外保存：

~~~text
20261001213006-20261001213013-face.mp4
~~~

所以同一事件可能产生：

~~~text
20261001213006-20261001213013.mp4
20261001213006-20261001213013-face.mp4
~~~

第一份：

~~~text
原始画面
~~~

第二份：

~~~text
原始画面
+
人脸框
+
姓名 / UUID
~~~

---

# 十六、为什么要两个版本

原始版本保持干净：

~~~text
原始视频
~~~

face 版本用于快速查看：

~~~text
谁出现了
在哪里
~~~

这样 AI 标注不会覆盖原始录像。

---

# 十七、事件前 2 秒的人脸版本

程序维护两套事件缓存：

~~~text
原始帧缓存
带识别结果帧缓存
~~~

两套缓存都保留最近 2 秒。

因此事件开始时：

~~~text
前 2 秒
+
变化过程
+
后 2 秒
~~~

都可以进入对应录像。

---

# 十八、录像 FPS

启动时读取手机：

~~~text
/api/status
~~~

例如：

~~~json
{
  "ok": true,
  "resolution": "1280x720",
  "fps": 15,
  "zoom": 1,
  "battery": 86
}
~~~

录像使用手机报告的 FPS。

读取失败时默认：

~~~text
15 FPS
~~~

---

# 十九、仅测试录像

如果不需要人脸识别：

~~~bat
python main.py --url http://192.168.1.100:8080/stream --no-face
~~~

此模式：

- 不加载 YOLO
- 不加载 InsightFace
- 不创建 UUID
- 仍然进行画面变化检测
- 仍然保存事件录像

---

# 二十、已录制视频实时识别

程序：

~~~text
play_recording.py
~~~

用途：

~~~text
读取已有 MP4
 ↓
按原视频速度播放
 ↓
实时 YOLO
 ↓
实时 ArcFace
 ↓
实时叠加人脸框
 ↓
显示姓名 / UUID
~~~

不会修改原始 MP4。

---

# 二十一、选择录像

直接运行：

~~~bat
python play_recording.py
~~~

会列出 recordings 中的 MP4。

选择编号即可播放。

---

# 二十二、指定录像

~~~bat
python play_recording.py --file recordings\20261001213006-20261001213013.mp4
~~~

也可以播放 face 版本：

~~~bat
python play_recording.py --file recordings\20261001213006-20261001213013-face.mp4
~~~

---

# 二十三、历史录像 AI FPS

默认：

~~~text
10 FPS
~~~

调整：

~~~bat
python play_recording.py --recognize-fps 15
~~~

播放速度仍按照原视频 FPS。

---

# 二十四、播放控制

~~~text
空格  暂停 / 继续
Q     退出
ESC   退出
~~~

---

# 二十五、完整实时数据流

~~~text
Android
   │
   │ /stream
   ▼
MjpegReader
   │
   ▼
OpenCV frame
   │
   ├───────────────┐
   │               │
   ▼               ▼
变化检测          人脸识别
   │               │
   │              YOLO
   │               ↓
   │             人脸框
   │               ↓
   │          InsightFace
   │               ↓
   │           embedding
   │               ↓
   │              UUID
   │               │
   └───────┬───────┘
           ▼
      EventRecorder
           │
      ┌────┴────┐
      ▼         ▼
   原始 MP4   -face MP4
~~~

---

# 二十六、参数

main.py 当前关键参数：

~~~text
PRE_SECONDS = 2.0
POST_SECONDS = 2.0

CHANGE_WIDTH = 320
CHANGE_HEIGHT = 180

CHANGE_PIXEL_THRESHOLD = 18
CHANGE_RATIO_THRESHOLD = 0.015

FACE_CONF = 0.35
YOLO_IMGSZ = 640
FACE_MATCH_THRESHOLD = 0.48
~~~

---

# 二十七、命令速查

正常：

~~~bat
python main.py --url http://192.168.1.100:8080/stream
~~~

Basic Auth：

~~~bat
python main.py --url http://192.168.1.100:8080/stream --user admin --password 123456
~~~

指定 YOLO：

~~~bat
python main.py --url http://192.168.1.100:8080/stream --yolo models\yolov8n-face.pt
~~~

仅录像：

~~~bat
python main.py --url http://192.168.1.100:8080/stream --no-face
~~~

播放录像：

~~~bat
python play_recording.py
~~~

指定录像：

~~~bat
python play_recording.py --file recordings\xxx.mp4
~~~

播放时提高 AI FPS：

~~~bat
python play_recording.py --recognize-fps 15
~~~

---

# 二十八、网页

手机：

~~~text
http://手机IP:8080/
~~~

MJPEG：

~~~text
http://手机IP:8080/stream
~~~

状态：

~~~text
http://手机IP:8080/api/status
~~~

PC 人脸管理：

~~~text
http://127.0.0.1:8765/
~~~

---

# 二十九、功能边界

当前 PC 已实现：

~~~text
✓ MJPEG 输入
✓ OpenCV 解码
✓ YOLOv8 人脸检测
✓ InsightFace / ArcFace
✓ 永久 UUID
✓ UUID.jpeg
✓ embedding.npy
✓ UUID → 姓名
✓ 实时人脸框
✓ 实时识别结果
✓ 画面变化检测
✓ 前 2 秒缓存
✓ 后 2 秒缓存
✓ 原始事件 MP4
✓ 人脸标注事件 MP4
✓ 已录制 MP4 实时识别
~~~

明确没有：

~~~text
✗ 姿态识别
✗ 手机端 YOLO
✗ 手机端 ArcFace
✗ 手机端 AI 推理
~~~

---

# 三十、运行建议

手机和电脑连接同一个局域网。

手机启动：

~~~text
Android Network Camera
~~~

确认浏览器能够访问：

~~~text
http://手机IP:8080/
~~~

然后电脑：

~~~bat
cd pc
python main.py --url http://手机IP:8080/stream
~~~

长期运行 main.py 负责：

~~~text
实时人脸识别
+
画面变化自动录像
~~~

需要查看历史录像时，再运行：

~~~bat
python play_recording.py
~~~

实时采集和历史录像分析相互独立。
