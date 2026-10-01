"""
PC 端：Android Network Camera 人脸识别 + 画面变化事件录像。

功能：
1. 从 Android 摄像头的 MJPEG /stream 读取视频。
2. YOLOv8 人脸检测。
3. InsightFace/ArcFace 提取人脸特征，建立永久 UUID。
4. 每个 UUID 保存一张 UUID.jpeg，并可通过内置网页填写姓名。
5. 画面变化检测：
   - 始终保留最近 2 秒帧缓存；
   - 变化开始时保留前 2 秒；
   - 变化结束后继续保留 2 秒；
   - 保存原始 MP4；
   - 如果事件期间检测到人脸，再额外保存一份带人脸框和识别结果的 MP4；
   - 文件名为 YYYYMMDDHHMMSS-YYYYMMDDHHMMSS.mp4，
     人脸版本增加 -face 后缀；
   - 无变化时不保存录像。
6. OpenCV 窗口实时显示人脸框和 UUID/姓名。

注意：
- 姿态识别完全没有实现。
- 手机只负责稳定提供 MJPEG；YOLO/人脸特征/录像均在 PC 执行。
"""

from __future__ import annotations

import argparse
import json
import threading
import time
import uuid
from collections import deque
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Optional
from urllib.parse import parse_qs, urlparse

import cv2
import numpy as np
import requests
from ultralytics import YOLO

try:
    from insightface.app import FaceAnalysis
except ImportError as exc:
    raise SystemExit(
        "缺少 insightface。请先安装 pc/requirements.txt"
    ) from exc


BASE_DIR = Path(__file__).resolve().parent
FACES_DIR = BASE_DIR / "faces"
RECORD_DIR = BASE_DIR / "recordings"
MODELS_DIR = BASE_DIR / "models"

FACES_DIR.mkdir(exist_ok=True)
RECORD_DIR.mkdir(exist_ok=True)
MODELS_DIR.mkdir(exist_ok=True)

DEFAULT_YOLO = MODELS_DIR / "yolov8n-face.pt"

PRE_SECONDS = 2.0
POST_SECONDS = 2.0

CHANGE_WIDTH = 320
CHANGE_HEIGHT = 180
CHANGE_PIXEL_THRESHOLD = 18
CHANGE_RATIO_THRESHOLD = 0.015

FACE_CONF = 0.35
YOLO_IMGSZ = 640
FACE_MATCH_THRESHOLD = 0.48


class IdentityStore:
    """永久 UUID 人脸库。"""

    def __init__(self, root: Path):
        self.root = root
        self.lock = threading.RLock()
        self.items: dict[str, dict] = {}
        self.load()

    def load(self) -> None:
        with self.lock:
            self.items.clear()
            for folder in self.root.iterdir():
                if not folder.is_dir():
                    continue

                info_path = folder / "info.json"
                embedding_path = folder / "embedding.npy"
                jpeg_path = folder / f"{folder.name}.jpeg"

                if not info_path.exists() or not embedding_path.exists():
                    continue

                try:
                    info = json.loads(
                        info_path.read_text(encoding="utf-8")
                    )
                    embedding = np.load(
                        embedding_path, allow_pickle=False
                    ).astype(np.float32)
                    embedding = self.normalize(embedding)

                    self.items[folder.name] = {
                        "uuid": folder.name,
                        "name": str(info.get("name", "")).strip(),
                        "embedding": embedding,
                        "jpeg": jpeg_path,
                    }
                except Exception as exc:
                    print(f"[faces] 忽略损坏的人脸库 {folder}: {exc}")

        print(f"[faces] 已加载 {len(self.items)} 个 UUID")

    @staticmethod
    def normalize(embedding: np.ndarray) -> np.ndarray:
        norm = float(np.linalg.norm(embedding))
        if norm <= 1e-8:
            return embedding
        return embedding / norm

    def match(
        self,
        embedding: np.ndarray,
        threshold: float,
    ) -> tuple[Optional[str], float]:
        embedding = self.normalize(embedding)
        best_uuid = None
        best_score = -1.0

        with self.lock:
            for uid, item in self.items.items():
                score = float(np.dot(embedding, item["embedding"]))
                if score > best_score:
                    best_score = score
                    best_uuid = uid

        if best_uuid is not None and best_score >= threshold:
            return best_uuid, best_score

        return None, best_score

    def create(
        self,
        embedding: np.ndarray,
        face_image: np.ndarray,
    ) -> str:
        uid = self.new_uuid()
        folder = self.root / uid
        folder.mkdir(parents=True, exist_ok=False)

        embedding = self.normalize(embedding)
        np.save(folder / "embedding.npy", embedding)

        cv2.imwrite(
            str(folder / f"{uid}.jpeg"),
            face_image,
            [cv2.IMWRITE_JPEG_QUALITY, 92],
        )

        info = {"uuid": uid, "name": ""}
        (folder / "info.json").write_text(
            json.dumps(info, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

        with self.lock:
            self.items[uid] = {
                "uuid": uid,
                "name": "",
                "embedding": embedding,
                "jpeg": folder / f"{uid}.jpeg",
            }

        print(f"[faces] 新建 UUID: {uid}")
        return uid

    @staticmethod
    def new_uuid() -> str:
        return str(uuid.uuid4())

    def update_name(self, uid: str, name: str) -> bool:
        name = name.strip()

        with self.lock:
            item = self.items.get(uid)
            if item is None:
                return False

            info = {"uuid": uid, "name": name}
            (self.root / uid / "info.json").write_text(
                json.dumps(info, ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
            item["name"] = name

        return True

    def get_display_name(self, uid: str) -> str:
        with self.lock:
            item = self.items.get(uid)
            if item is None:
                return uid
            return item["name"] or uid

    def list_items(self) -> list[dict]:
        with self.lock:
            return [
                {
                    "uuid": uid,
                    "name": item["name"],
                    "jpeg": f"/faces/{uid}/{uid}.jpeg",
                }
                for uid, item in self.items.items()
            ]


class EventRecorder:
    """变化事件录像：前 2 秒 + 变化过程 + 后 2 秒。

    每个事件始终保存一份原始视频。
    如果事件期间出现人脸，再额外保存一份带人脸框和识别结果的视频。
    """

    def __init__(
        self,
        output_dir: Path,
        fps: float,
        pre_seconds: float = PRE_SECONDS,
        post_seconds: float = POST_SECONDS,
    ):
        self.output_dir = output_dir
        self.fps = max(1.0, float(fps))
        self.pre_seconds = pre_seconds
        self.post_seconds = post_seconds

        # 原始视频帧。
        self.buffer: deque[tuple[float, np.ndarray]] = deque()
        self.event_frames: list[tuple[float, np.ndarray]] = []

        # 带识别框的视频帧。
        self.face_buffer: deque[tuple[float, np.ndarray]] = deque()
        self.face_event_frames: list[tuple[float, np.ndarray]] = []

        self.recording = False
        self.has_face = False
        self.last_change_time: Optional[float] = None
        self.event_start_time: Optional[float] = None
        self.lock = threading.RLock()

    def _trim_buffer(self, now: float) -> None:
        cutoff = now - self.pre_seconds

        while self.buffer and self.buffer[0][0] < cutoff:
            self.buffer.popleft()

        while self.face_buffer and self.face_buffer[0][0] < cutoff:
            self.face_buffer.popleft()

    def push(
        self,
        timestamp: float,
        frame: np.ndarray,
        changed: bool,
        annotated_frame: Optional[np.ndarray] = None,
        has_face: bool = False,
    ) -> None:
        with self.lock:
            self._trim_buffer(timestamp)

            # 人脸版本需要一直保留最近 2 秒的“已经画好框”的帧。
            # 这样事件开始前 2 秒也能完整进入人脸录像。
            if annotated_frame is None:
                annotated_frame = frame

            if has_face:
                self.has_face = True

            if not self.recording:
                self.buffer.append((timestamp, frame.copy()))
                self.face_buffer.append(
                    (timestamp, annotated_frame.copy())
                )

                if changed:
                    self.recording = True
                    self.event_start_time = (
                        self.buffer[0][0] if self.buffer else timestamp
                    )
                    self.event_frames = list(self.buffer)
                    self.face_event_frames = list(self.face_buffer)
                    self.last_change_time = timestamp

                    # 如果前 2 秒里已经出现过人脸，也需要保存人脸版本。
                    self.has_face = any(
                        self._frame_has_face_for_buffer(
                            item[1]
                        )
                        for item in []
                    ) or self.has_face

                    print("[record] 变化开始")
                return

            self.event_frames.append((timestamp, frame.copy()))
            self.face_event_frames.append(
                (timestamp, annotated_frame.copy())
            )

            if has_face:
                self.has_face = True

            if changed:
                self.last_change_time = timestamp
                return

            if (
                self.last_change_time is not None
                and timestamp - self.last_change_time >= self.post_seconds
            ):
                self._finish_event()

    @staticmethod
    def _frame_has_face_for_buffer(frame: np.ndarray) -> bool:
        # 保留接口，不从图像反推人脸；真正的人脸状态由 has_face 维护。
        return False

    def _finish_event(self) -> None:
        if not self.event_frames:
            self._reset()
            return

        end_timestamp = self.event_frames[-1][0]
        start_timestamp = (
            self.event_start_time or self.event_frames[0][0]
        )

        self._write_mp4(
            self.event_frames,
            start_timestamp,
            end_timestamp,
            suffix="",
        )

        if self.has_face and self.face_event_frames:
            self._write_mp4(
                self.face_event_frames,
                start_timestamp,
                end_timestamp,
                suffix="-face",
            )

        self._reset()

    def _reset(self) -> None:
        self.recording = False
        self.has_face = False
        self.last_change_time = None
        self.event_start_time = None
        self.event_frames.clear()
        self.face_event_frames.clear()
        self.buffer.clear()
        self.face_buffer.clear()

    def _make_unique_path(
        self,
        start_dt: datetime,
        end_dt: datetime,
        suffix: str,
    ) -> Path:
        stem = (
            f"{start_dt:%Y%m%d%H%M%S}-"
            f"{end_dt:%Y%m%d%H%M%S}{suffix}"
        )
        path = self.output_dir / f"{stem}.mp4"

        if path.exists():
            index = 2
            while True:
                candidate = self.output_dir / f"{stem}-{index}.mp4"
                if not candidate.exists():
                    return candidate
                index += 1

        return path

    def _write_mp4(
        self,
        frames: list[tuple[float, np.ndarray]],
        start_ts: float,
        end_ts: float,
        suffix: str,
    ) -> None:
        first_frame = frames[0][1]
        height, width = first_frame.shape[:2]

        start_dt = datetime.fromtimestamp(start_ts)
        end_dt = datetime.fromtimestamp(end_ts)
        path = self._make_unique_path(start_dt, end_dt, suffix)

        writer = cv2.VideoWriter(
            str(path),
            cv2.VideoWriter_fourcc(*"mp4v"),
            self.fps,
            (width, height),
        )

        if not writer.isOpened():
            print(f"[record] 无法创建 MP4: {path}")
            return

        try:
            for _, frame in frames:
                writer.write(frame)
        finally:
            writer.release()

        print(
            f"[record] 已保存 {path.name} "
            f"frames={len(frames)} duration={end_ts - start_ts:.2f}s"
        )


class ChangeDetector:
    """缩小画面后用帧差判断变化。"""

    def __init__(
        self,
        pixel_threshold: int = CHANGE_PIXEL_THRESHOLD,
        ratio_threshold: float = CHANGE_RATIO_THRESHOLD,
    ):
        self.pixel_threshold = pixel_threshold
        self.ratio_threshold = ratio_threshold
        self.previous: Optional[np.ndarray] = None

    def detect(self, frame: np.ndarray) -> tuple[bool, float]:
        small = cv2.resize(
            frame,
            (CHANGE_WIDTH, CHANGE_HEIGHT),
            interpolation=cv2.INTER_AREA,
        )
        gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
        gray = cv2.GaussianBlur(gray, (5, 5), 0)

        if self.previous is None:
            self.previous = gray
            return False, 0.0

        diff = cv2.absdiff(self.previous, gray)
        self.previous = gray

        _, mask = cv2.threshold(
            diff,
            self.pixel_threshold,
            255,
            cv2.THRESH_BINARY,
        )

        changed_ratio = float(cv2.countNonZero(mask) / mask.size)
        return changed_ratio >= self.ratio_threshold, changed_ratio


class MjpegReader:
    """从 multipart MJPEG 流提取 JPEG 帧。"""

    def __init__(
        self,
        url: str,
        username: Optional[str] = None,
        password: Optional[str] = None,
        timeout: float = 10.0,
    ):
        self.url = url
        self.auth = (
            (username, password) if username is not None else None
        )
        self.timeout = timeout

    def frames(self):
        response = requests.get(
            self.url,
            auth=self.auth,
            stream=True,
            timeout=self.timeout,
        )
        response.raise_for_status()

        data = bytearray()
        for chunk in response.iter_content(chunk_size=65536):
            if not chunk:
                continue

            data.extend(chunk)

            while True:
                start = data.find(b"\xff\xd8")
                if start < 0:
                    if len(data) > 2 * 1024 * 1024:
                        del data[:-2]
                    break

                end = data.find(b"\xff\xd9", start + 2)
                if end < 0:
                    if start > 0:
                        del data[:start]
                    break

                jpeg = bytes(data[start:end + 2])
                del data[:end + 2]

                frame = cv2.imdecode(
                    np.frombuffer(jpeg, np.uint8),
                    cv2.IMREAD_COLOR,
                )
                if frame is not None:
                    yield frame


class FaceRecognizer:
    """YOLOv8 人脸检测 + InsightFace/ArcFace embedding。"""

    def __init__(
        self,
        yolo_path: Path,
        threshold: float,
    ):
        print(f"[face] YOLO: {yolo_path}")
        self.detector = YOLO(str(yolo_path))
        self.threshold = threshold

        providers = [
            "CUDAExecutionProvider",
            "CPUExecutionProvider",
        ]

        print("[face] 正在加载 InsightFace buffalo_l...")
        self.app = FaceAnalysis(
            name="buffalo_l",
            providers=providers,
        )
        self.app.prepare(ctx_id=0, det_size=(640, 640))
        print("[face] InsightFace 已加载")

    @staticmethod
    def _crop(
        frame: np.ndarray,
        box: tuple[int, int, int, int],
    ) -> np.ndarray:
        x1, y1, x2, y2 = box
        height, width = frame.shape[:2]

        w = x2 - x1
        h = y2 - y1
        pad_x = int(w * 0.18)
        pad_y = int(h * 0.18)

        x1 = max(0, x1 - pad_x)
        y1 = max(0, y1 - pad_y)
        x2 = min(width, x2 + pad_x)
        y2 = min(height, y2 + pad_y)

        return frame[y1:y2, x1:x2].copy()

    def process(
        self,
        frame: np.ndarray,
        store: IdentityStore,
    ) -> list[dict]:
        results = self.detector.predict(
            source=frame,
            conf=FACE_CONF,
            imgsz=YOLO_IMGSZ,
            verbose=False,
        )

        if not results or results[0].boxes is None:
            return []

        detections = []

        for box in results[0].boxes:
            xyxy = box.xyxy[0].cpu().numpy()
            conf = float(box.conf[0].cpu().item())

            x1, y1, x2, y2 = [
                int(round(value)) for value in xyxy
            ]

            x1 = max(0, x1)
            y1 = max(0, y1)
            x2 = min(frame.shape[1], x2)
            y2 = min(frame.shape[0], y2)

            if x2 <= x1 or y2 <= y1:
                continue

            crop = self._crop(frame, (x1, y1, x2, y2))
            if crop.size == 0:
                continue

            faces = self.app.get(crop)
            if not faces:
                continue

            face = max(
                faces,
                key=lambda item: float(
                    getattr(item, "det_score", 0.0)
                ),
            )

            embedding = getattr(
                face,
                "normed_embedding",
                None,
            )
            if embedding is None:
                embedding = face.embedding

            embedding = np.asarray(embedding, dtype=np.float32)
            uid, score = store.match(
                embedding,
                self.threshold,
            )

            if uid is None:
                uid = store.create(embedding, crop)
                score = 1.0

            detections.append(
                {
                    "bbox": (x1, y1, x2, y2),
                    "confidence": conf,
                    "uuid": uid,
                    "similarity": score,
                }
            )

        return detections


class ManagementHandler(BaseHTTPRequestHandler):
    """本地人脸 UUID → 名字管理页面。"""

    state: Optional[IdentityStore] = None

    def _send_html(self, html: str, status: int = 200):
        data = html.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _redirect(self, location: str):
        self.send_response(303)
        self.send_header("Location", location)
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)

        if parsed.path == "/":
            self._send_html(self.render_index())
            return

        if parsed.path.startswith("/faces/"):
            self.serve_face(parsed.path)
            return

        self._send_html("Not Found", 404)

    def do_POST(self):
        parsed = urlparse(self.path)

        if parsed.path == "/save":
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length)
            form = parse_qs(
                body.decode("utf-8"),
                keep_blank_values=True,
            )

            uid = form.get("uuid", [""])[0].strip()
            name = form.get("name", [""])[0].strip()

            if self.state is not None and self.state.update_name(
                uid, name
            ):
                self._redirect("/")
                return

            self._send_html("UUID 不存在", 404)
            return

        self._send_html("Not Found", 404)

    def serve_face(self, path: str):
        parts = path.strip("/").split("/")
        if len(parts) != 3 or parts[0] != "faces":
            self._send_html("Not Found", 404)
            return

        uid = parts[1]
        filename = parts[2]

        if filename != f"{uid}.jpeg":
            self._send_html("Not Found", 404)
            return

        file_path = FACES_DIR / uid / filename
        if not file_path.exists():
            self._send_html("Not Found", 404)
            return

        data = file_path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def render_index(self) -> str:
        items = self.state.list_items() if self.state else []
        rows = []

        for item in items:
            uid = item["uuid"]
            name = (
                item["name"]
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace('"', "&quot;")
            )

            rows.append(
                f"""
                <div class="card">
                  <img src="{item['jpeg']}" loading="lazy">
                  <div class="uuid">{uid}</div>
                  <form method="post" action="/save">
                    <input type="hidden" name="uuid" value="{uid}">
                    <input name="name" value="{name}" placeholder="输入姓名">
                    <button type="submit">保存</button>
                  </form>
                </div>
                """
            )

        return f"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>人脸 UUID 管理</title>
<style>
body {{
  margin: 0; padding: 20px; background: #111; color: #eee;
  font-family: Arial, sans-serif;
}}
h1 {{ margin-top: 0; }}
.grid {{
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
}}
.card {{
  background: #222; border-radius: 8px; padding: 12px;
}}
.card img {{
  display: block; width: 100%; aspect-ratio: 4 / 3;
  object-fit: contain; background: #000; border-radius: 6px;
}}
.uuid {{
  margin: 10px 0; font: 12px monospace;
  word-break: break-all; color: #aaa;
}}
input {{ box-sizing: border-box; width: calc(100% - 70px); padding: 8px; }}
button {{ width: 62px; padding: 8px 4px; margin-left: 4px; }}
</style>
</head>
<body>
<h1>人脸 UUID 管理</h1>
<p>
新识别的人脸会自动生成永久 UUID。输入姓名后保存，之后画面显示姓名。
留空则显示 UUID。
</p>
<div class="grid">
{''.join(rows)}
</div>
</body>
</html>"""


def start_management_server(
    host: str,
    port: int,
    store: IdentityStore,
):
    handler = ManagementHandler
    handler.state = store

    server = ThreadingHTTPServer((host, port), handler)
    thread = threading.Thread(
        target=server.serve_forever,
        daemon=True,
    )
    thread.start()

    shown_host = "127.0.0.1" if host == "0.0.0.0" else host
    print(f"[web] 人脸管理: http://{shown_host}:{port}/")
    return server


def get_camera_fps(
    stream_url: str,
    username: Optional[str],
    password: Optional[str],
) -> float:
    """从手机 /api/status 获取 FPS；失败时使用 15 FPS。"""
    parsed = urlparse(stream_url)
    base = f"{parsed.scheme}://{parsed.netloc}"

    try:
        response = requests.get(
            f"{base}/api/status",
            auth=(
                (username, password)
                if username is not None
                else None
            ),
            timeout=3,
        )
        response.raise_for_status()
        fps = float(response.json().get("fps", 15))
        if fps > 0:
            print(f"[camera] 手机报告 FPS={fps}")
            return fps
    except Exception as exc:
        print(
            f"[camera] 无法读取 /api/status，录像使用 15 FPS: {exc}"
        )

    return 15.0


def draw_face_results(
    frame: np.ndarray,
    detections: list[dict],
    store: IdentityStore,
) -> None:
    for item in detections:
        x1, y1, x2, y2 = item["bbox"]
        label = store.get_display_name(item["uuid"])

        cv2.rectangle(
            frame,
            (x1, y1),
            (x2, y2),
            (0, 255, 0),
            2,
        )

        (tw, th), _ = cv2.getTextSize(
            label,
            cv2.FONT_HERSHEY_SIMPLEX,
            0.65,
            2,
        )

        top = max(0, y1 - th - 12)

        cv2.rectangle(
            frame,
            (x1, top),
            (x1 + tw + 10, y1),
            (0, 255, 0),
            -1,
        )

        cv2.putText(
            frame,
            label,
            (x1 + 5, y1 - 7),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.65,
            (0, 0, 0),
            2,
            cv2.LINE_AA,
        )


def main():
    parser = argparse.ArgumentParser(
        description="Android Network Camera PC：人脸识别 + 画面变化事件录像"
    )

    parser.add_argument(
        "--url",
        required=True,
        help="手机 MJPEG 地址，例如 http://192.168.1.100:8080/stream",
    )
    parser.add_argument("--user", default=None)
    parser.add_argument("--password", default=None)
    parser.add_argument(
        "--yolo",
        default=str(DEFAULT_YOLO),
        help="YOLOv8 人脸模型路径",
    )
    parser.add_argument(
        "--face-threshold",
        type=float,
        default=FACE_MATCH_THRESHOLD,
        help="ArcFace cosine similarity 阈值",
    )
    parser.add_argument(
        "--web-host",
        default="127.0.0.1",
        help="人脸管理网页监听地址",
    )
    parser.add_argument(
        "--web-port",
        type=int,
        default=8765,
        help="人脸管理网页端口",
    )
    parser.add_argument(
        "--no-face",
        action="store_true",
        help="仅测试画面变化录像，不加载人脸模型",
    )

    args = parser.parse_args()

    yolo_path = Path(args.yolo)
    if not args.no_face and not yolo_path.exists():
        raise SystemExit(
            f"找不到 YOLO 人脸模型：{yolo_path}\n"
            "请把 yolov8n-face.pt 放到 pc/models/，"
            "或使用 --yolo 指定路径。"
        )

    store = IdentityStore(FACES_DIR)

    recognizer = None
    if not args.no_face:
        recognizer = FaceRecognizer(
            yolo_path,
            args.face_threshold,
        )
        start_management_server(
            args.web_host,
            args.web_port,
            store,
        )

    fps = get_camera_fps(
        args.url,
        args.user,
        args.password,
    )

    recorder = EventRecorder(
        RECORD_DIR,
        fps=fps,
        pre_seconds=PRE_SECONDS,
        post_seconds=POST_SECONDS,
    )
    detector = ChangeDetector()

    print(f"[camera] 连接: {args.url}")
    print("[record] 规则：变化前2秒 + 变化过程 + 变化结束后2秒")
    print("[record] 每个事件保存原始视频；检测到人脸时额外保存 -face.mp4")
    print(f"[record] 输出目录：{RECORD_DIR}")

    reader = MjpegReader(
        args.url,
        username=args.user,
        password=args.password,
    )

    last_face_time = 0.0
    face_interval = 1.0 / min(max(fps, 1.0), 10.0)

    # 保留最近一次识别结果，让录像中每一帧都能带上框和识别文字。
    last_detections: list[dict] = []

    try:
        for frame in reader.frames():
            timestamp = time.time()

            changed, change_ratio = detector.detect(frame)

            detections = None
            if (
                recognizer is not None
                and timestamp - last_face_time >= face_interval
            ):
                detections = recognizer.process(frame, store)
                last_detections = detections
                last_face_time = timestamp

            # 使用最近一次识别结果生成带框版本。
            display = frame.copy()
            if last_detections:
                draw_face_results(
                    display,
                    last_detections,
                    store,
                )

            # 只有真正得到人脸检测结果时才触发“本事件有人脸”。
            has_face = bool(detections) if detections is not None else False

            recorder.push(
                timestamp,
                frame,
                changed,
                annotated_frame=display,
                has_face=has_face,
            )

            status = "CHANGE" if changed else "STATIC"

            cv2.putText(
                display,
                f"{status} {change_ratio:.3f}",
                (10, 28),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.7,
                (0, 255, 255),
                2,
                cv2.LINE_AA,
            )

            if recorder.recording:
                cv2.putText(
                    display,
                    "RECORDING",
                    (10, 58),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.7,
                    (0, 0, 255),
                    2,
                    cv2.LINE_AA,
                )

            cv2.imshow(
                "Android Network Camera - PC",
                display,
            )

            key = cv2.waitKey(1) & 0xFF
            if key in (27, ord("q"), ord("Q")):
                break

    except KeyboardInterrupt:
        pass
    finally:
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
