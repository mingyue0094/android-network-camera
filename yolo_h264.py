# yolo_h264.py
# 电脑端：WebSocket 接收手机 H.264/fMP4，交给 FFmpeg 解码，再逐帧提供给 YOLO/OpenCV。
#
# 安装：
#   pip install websocket-client opencv-python ultralytics
# 电脑需要 ffmpeg.exe 在 PATH 中。
#
# 手机地址：
#   ws://192.168.1.100:8080/video
#
# 如果手机开启网页密码：
#   python yolo_h264.py --url ws://192.168.1.100:8080/video --user admin --password 123456

import argparse
import subprocess
import threading
import time

import cv2
import websocket


class H264Camera:
    def __init__(self, url, user="", password=""):
        self.url = url
        self.user = user
        self.password = password
        self.proc = None
        self.width = 1280
        self.height = 720
        self.running = False

    def connect(self):
        url = self.url
        if self.user:
            # websocket-client 用 header 发送 Basic Auth。
            import base64
            token = base64.b64encode(
                ("%s:%s" % (self.user, self.password)).encode()
            ).decode()
            ws = websocket.create_connection(
                url,
                timeout=10,
                header=["Authorization: Basic " + token],
                origin=None,
            )
        else:
            ws = websocket.create_connection(url, timeout=10, origin=None)

        self.ws = ws

        # FFmpeg 从 stdin 读取连续的 fragmented MP4。
        self.proc = subprocess.Popen(
            [
                "ffmpeg",
                "-loglevel", "error",
                "-fflags", "nobuffer",
                "-flags", "low_delay",
                "-i", "pipe:0",
                "-f", "rawvideo",
                "-pix_fmt", "bgr24",
                "pipe:1",
            ],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0,
        )

        self.running = True
        threading.Thread(
            target=self._receive,
            name="h264-receiver",
            daemon=True,
        ).start()

    def _receive(self):
        try:
            while self.running:
                data = self.ws.recv()
                if data is None:
                    break
                if isinstance(data, str):
                    continue
                self.proc.stdin.write(data)
                self.proc.stdin.flush()
        except Exception as e:
            if self.running:
                print("视频接收断开:", e)
        finally:
            self.running = False
            try:
                self.proc.stdin.close()
            except Exception:
                pass

    def frames(self):
        frame_size = self.width * self.height * 3
        while self.running:
            data = self.proc.stdout.read(frame_size)
            if len(data) != frame_size:
                break
            frame = __import__("numpy").frombuffer(
                data, dtype=__import__("numpy").uint8
            ).reshape((self.height, self.width, 3))
            yield frame

    def close(self):
        self.running = False
        try:
            self.ws.close()
        except Exception:
            pass
        try:
            self.proc.kill()
        except Exception:
            pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--url",
        default="ws://192.168.1.100:8080/video",
    )
    ap.add_argument("--user", default="")
    ap.add_argument("--password", default="")
    ap.add_argument("--width", type=int, default=1280)
    ap.add_argument("--height", type=int, default=720)
    ap.add_argument("--model", default="")
    args = ap.parse_args()

    cam = H264Camera(
        args.url,
        args.user,
        args.password,
    )
    cam.width = args.width
    cam.height = args.height

    cam.connect()

    try:
        if args.model:
            from ultralytics import YOLO
            model = YOLO(args.model)

            for frame in cam.frames():
                results = model.predict(
                    source=frame,
                    stream=True,
                    verbose=False,
                )
                for result in results:
                    view = result.plot()
                    cv2.imshow("YOLO - Android H264", view)
                    if cv2.waitKey(1) & 0xFF == 27:
                        return
        else:
            for frame in cam.frames():
                cv2.imshow("Android H264", frame)
                if cv2.waitKey(1) & 0xFF == 27:
                    break
    finally:
        cam.close()
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
