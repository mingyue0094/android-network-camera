# PC：人脸识别 + 画面变化自动录像

电脑端从 Android Network Camera 的 MJPEG /stream 读取视频。

当前只包含两个功能：

1. 人脸识别
   - YOLOv8 人脸检测
   - InsightFace / ArcFace 人脸特征
   - 同一个人永久使用同一个 UUID
   - 每个 UUID 保存一张 UUID.jpeg
   - 本地网页给 UUID 输入/修改姓名
   - 有姓名显示姓名，没有姓名显示 UUID
2. 画面变化自动录像
   - 始终缓存最近 2 秒
   - 变化开始：保留变化前 2 秒
   - 变化持续：继续录制
   - 变化结束：继续录制 2 秒
   - 只保存完整变化事件
   - 文件名：YYYYMMDDHHMMSS-YYYYMMDDHHMMSS.mp4
   - 没有变化不会生成录像

没有姿态识别。

## 目录

运行后自动生成：

pc/
├── main.py
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
    └── YYYYMMDDHHMMSS-YYYYMMDDHHMMSS.mp4

## 1. 安装

建议使用已有的 Python 3.10.5。

进入：

cd pc

安装：

pip install -r requirements.txt

检查 ONNX Runtime：

python -c "import onnxruntime as ort; print(ort.get_available_providers())"

正常使用 GPU 时应看到 CUDAExecutionProvider。

## 2. YOLOv8 人脸模型

需要把 YOLOv8 人脸模型放到：

pc\models\yolov8n-face.pt

代码默认路径就是这个文件。

可使用 akanametov/yolo-face 项目提供的 yolov8n-face.pt。

## 3. 启动

例如手机 IP 是 192.168.1.100：

python main.py --url http://192.168.1.100:8080/stream

如果手机开启 Basic Auth：

python main.py --url http://192.168.1.100:8080/stream --user admin --password 123456

也可以指定 YOLO 模型：

python main.py --url http://192.168.1.100:8080/stream --yolo models\yolov8n-face.pt

## 4. 人脸 UUID

第一次检测到一个未知人脸：

YOLO
  ↓
人脸框
  ↓
ArcFace embedding
  ↓
人脸库没有匹配
  ↓
生成永久 UUID
  ↓
保存 UUID.jpeg
  ↓
保存 embedding.npy

例如：

faces/
└── 8f31c2a1-xxxx-xxxx/
    ├── 8f31c2a1-xxxx-xxxx.jpeg
    ├── embedding.npy
    └── info.json

以后同一个人的 embedding 匹配成功，会继续使用原 UUID，不会每帧重新生成 UUID。

## 5. 给 UUID 输入名字

程序启动后打开：

http://127.0.0.1:8765/

网页会列出：

- 人脸照片
- UUID
- 姓名输入框
- 保存按钮

例如：

UUID: 8f31c2a1-xxxx-xxxx
姓名：[ 张三 ]
[保存]

保存后：

8f31c2a1-xxxx-xxxx → 张三

画面识别到这个人时显示张三。
如果姓名为空，则显示 UUID。

## 6. 自动录像

程序一直保留最近 2 秒帧。

假设：

21:30:08 变化开始
21:30:09 变化
21:30:10 变化
21:30:11 变化结束

程序最终保存：

20261001213006-20261001213013.mp4

即：

变化开始前 2 秒
+
整个变化过程
+
变化结束后 2 秒

如果画面一直没有明显变化，只使用内存循环缓存，不生成文件。

## 7. 录像目录

pc\recordings\

文件格式：

YYYYMMDDHHMMSS-YYYYMMDDHHMMSS.mp4

## 8. 仅测试自动录像

python main.py --url http://192.168.1.100:8080/stream --no-face

不会加载 YOLO 和 InsightFace。

## 9. OpenCV 窗口

实时窗口显示：

- 视频画面
- 人脸框
- 姓名 / UUID
- 当前变化检测状态
- 当前是否正在录像

按 Q 或 ESC 退出。

## 10. 两个功能独立

手机 /stream
       │
       ├──────────────→ 人脸识别
       │                YOLOv8
       │                  ↓
       │              ArcFace
       │                  ↓
       │                UUID
       │
       └──────────────→ 画面变化检测
                        ↓
                    2 秒循环缓存
                        ↓
                    事件 MP4

录像不依赖人脸识别。
即使画面中没有人脸，只要画面发生变化，也会生成事件录像。
