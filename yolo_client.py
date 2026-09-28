# -*- coding: utf-8 -*-
"""
电脑端 YOLO/H.264 取流客户端。

依赖：
    pip install websocket-client numpy opencv-python

另外需要 ffmpeg.exe 在 PATH 中。

输出：
    read() -> OpenCV BGR ndarray
"""

import base64
import json
import os
import queue
import subprocess
import threading
import time

import cv2
import numpy as np
import websocket


class AndroidCamera:
    def __init__(self, ip, port=8080, username="admin", password=""):
        self.ip = ip
        self.port = port
        self.username = username
        self.password = password
        self.ws = None
        self.proc = None
        self.thread = None
        self.frames = queue.Queue(maxsize=1)
        self.running = False
        self.width = 0
        self.height = 0

    @property
    def url(self):
        return "ws://%s:%d/video" % (self.ip, self.port)

    def _auth_header(self):
        if not self.password:
            return None
        raw = ("%s:%s" % (self.username, self.password)).encode("utf-8")
        return "Authorization: Basic " + base64.b64encode(raw).decode()

    def start(self):
        if self.running:
            return

        cmd = [
            "ffmpeg", "-loglevel", "error",
            "-fflags", "nobuffer",
            "-flags", "low_delay",
            "-f", "mp4",
            "-i", "pipe:0",
            "-an",
            "-pix_fmt", "bgr24",
            "-f", "rawvideo",
            "pipe:1",
        ]

        self.proc = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0,
        )

        headers = []
        auth = self._auth_header()
        if auth:
            headers.append(auth)

        self.running = True
        self.thread = threading.Thread(
            target=self._receiver,
            args=(headers,),
            daemon=True,
        )
        self.thread.start()

    def _receiver(self, headers):
        try:
            self.ws = websocket.create_connection(
                self.url,
                header=headers,
                timeout=10,
                enable_multithread=True,
            )

            def feed_ffmpeg():
                try:
                    while self.running:
                        data = self.ws.recv()
                        if data is None:
                            break
                        if isinstance(data, str):
                            continue
                        if self.proc and self.proc.stdin:
                            self.proc.stdin.write(data)
                            self.proc.stdin.flush()
                except Exception:
                    pass
                finally:
                    try:
                        self.proc.stdin.close()
                    except Exception:
                        pass

            t = threading.Thread(target=feed_ffmpeg, daemon=True)
            t.start()

            frame_size = None
            while self.running and self.proc and self.proc.poll() is None:
                if self.width <= 0 or self.height <= 0:
                    # 从 ffprobe 无法可靠地从管道动态获取尺寸。
                    # 使用手机状态接口取得当前分辨率。
                    self._update_size()

                if self.width <= 0 or self.height <= 0:
                    time.sleep(0.05)
                    continue

                frame_size = self.width * self.height * 3
                data = self._read_exact(self.proc.stdout, frame_size)
                if data is None:
                    break

                frame = np.frombuffer(data, dtype=np.uint8).reshape(
                    (self.height, self.width, 3)
                ).copy()

                while not self.frames.empty():
                    try:
                        self.frames.get_nowait()
                    except queue.Empty:
                        break
                try:
                    self.frames.put_nowait(frame)
                except queue.Full:
                    pass

        except Exception as e:
            print("YOLO取流错误:", e)
        finally:
            self.running = False

    def _update_size(self):
        try:
            import urllib.request

            url = "http://%s:%d/api/status" % (self.ip, self.port)
            req = urllib.request.Request(url)
            if self.password:
                raw = ("%s:%s" % (
                    self.username, self.password
                )).encode("utf-8")
                token = base64.b64encode(raw).decode()
                req.add_header("Authorization", "Basic " + token)

            with urllib.request.urlopen(req, timeout=3) as r:
                j = json.loads(r.read().decode("utf-8"))
            s = j.get("resolution", "")
            if "x" in s:
                self.width, self.height = map(int, s.split("x"))
        except Exception:
            pass

    @staticmethod
    def _read_exact(stream, size):
        buf = bytearray()
        while len(buf) < size:
            chunk = stream.read(size - len(buf))
            if not chunk:
                return None
            buf.extend(chunk)
        return bytes(buf)

    def read(self):
        try:
            return self.frames.get(timeout=2)
        except queue.Empty:
            return None

    def release(self):
        self.running = False
        try:
            if self.ws:
                self.ws.close()
        except Exception:
            pass
        try:
            if self.proc:
                self.proc.kill()
        except Exception:
            pass


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("ip")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--password", default="")
    args = parser.parse_args()

    cam = AndroidCamera(
        args.ip,
        args.port,
        password=args.password,
    )
    cam.start()

    print("连接:", cam.url)
    while True:
        frame = cam.read()
        if frame is None:
            continue

        cv2.imshow("Android H.264 Camera", frame)

        # 这里就是 YOLO 的输入：
        # results = model(frame)
        if cv2.waitKey(1) & 0xff == 27:
            break

    cam.release()
    cv2.destroyAllWindows()
