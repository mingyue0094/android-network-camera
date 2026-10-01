"""
读取 pc/recordings 中已经录制的 MP4，在播放时实时进行人脸检测/识别，
并把识别框、姓名/UUID 叠加到播放画面上。

用法：
    python play_recording.py
    python play_recording.py --file recordings/20261001213006-20261001213013.mp4
"""

from __future__ import annotations

import argparse
import time
from pathlib import Path

import cv2
import numpy as np

from main import (
    DEFAULT_YOLO,
    FACE_MATCH_THRESHOLD,
    FACES_DIR,
    IdentityStore,
    FaceRecognizer,
    draw_face_results,
)

BASE_DIR = Path(__file__).resolve().parent
RECORD_DIR = BASE_DIR / "recordings"


def list_recordings() -> list[Path]:
    return sorted(
        RECORD_DIR.glob("*.mp4"),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )


def choose_file() -> Path | None:
    files = list_recordings()

    if not files:
        print(f"[play] 没有找到录像：{RECORD_DIR}")
        return None

    print("\n已录制视频：")
    for index, path in enumerate(files, 1):
        size_mb = path.stat().st_size / 1024 / 1024
        print(f"  {index:>2}. {path.name}  ({size_mb:.1f} MB)")

    while True:
        value = input("\n选择编号（直接回车退出）：").strip()
        if not value:
            return None

        try:
            index = int(value)
            if 1 <= index <= len(files):
                return files[index - 1]
        except ValueError:
            pass

        print("编号无效，请重新输入。")


def process_video(
    video_path: Path,
    recognizer: FaceRecognizer,
    store: IdentityStore,
    interval: float,
) -> None:
    cap = cv2.VideoCapture(str(video_path))

    if not cap.isOpened():
        raise RuntimeError(f"无法打开视频：{video_path}")

    fps = cap.get(cv2.CAP_PROP_FPS)
    if not fps or fps <= 0:
        fps = 15.0

    frame_count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    duration = frame_count / fps if frame_count > 0 else 0.0

    print(
        f"[play] {video_path.name}  "
        f"{fps:.2f} FPS, {duration:.2f}s"
    )
    print("[play] Q/ESC：退出；空格：暂停/继续")

    last_recognize = 0.0
    detections: list[dict] = []
    paused = False

    try:
        while True:
            if paused:
                key = cv2.waitKey(30) & 0xFF
                if key == ord(" "):
                    paused = False
                elif key in (27, ord("q"), ord("Q")):
                    break
                continue

            ok, frame = cap.read()
            if not ok:
                break

            now = time.perf_counter()

            # 不需要每一帧都跑 YOLO + ArcFace。
            # 但识别结果会保留在连续播放帧上，因此显示连续稳定。
            if now - last_recognize >= interval:
                detections = recognizer.process(frame, store)
                last_recognize = now

            display = frame.copy()
            if detections:
                draw_face_results(display, detections, store)

            position = cap.get(cv2.CAP_PROP_POS_MSEC) / 1000.0

            cv2.putText(
                display,
                f"{video_path.name}  {position:.1f}/{duration:.1f}s",
                (10, 28),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.65,
                (0, 255, 255),
                2,
                cv2.LINE_AA,
            )

            cv2.imshow("Recorded Video - Face Recognition", display)

            delay = max(1, int(1000.0 / fps))
            key = cv2.waitKey(delay) & 0xFF

            if key == ord(" "):
                paused = True
            elif key in (27, ord("q"), ord("Q")):
                break

    finally:
        cap.release()
        cv2.destroyAllWindows()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="播放已经录制的 MP4，并实时叠加人脸识别结果"
    )
    parser.add_argument(
        "--file",
        type=str,
        default=None,
        help="指定 MP4 文件；不指定则从 recordings 中选择",
    )
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
        "--recognize-fps",
        type=float,
        default=10.0,
        help="人脸识别最高处理 FPS；播放本身仍按原视频 FPS",
    )

    args = parser.parse_args()

    video_path = Path(args.file) if args.file else choose_file()
    if video_path is None:
        return

    if not video_path.is_absolute():
        video_path = (BASE_DIR / video_path).resolve()

    if not video_path.exists():
        raise SystemExit(f"找不到视频：{video_path}")

    yolo_path = Path(args.yolo)
    if not yolo_path.exists():
        raise SystemExit(
            f"找不到 YOLO 人脸模型：{yolo_path}\n"
            "请把 yolov8n-face.pt 放到 pc/models/，"
            "或使用 --yolo 指定路径。"
        )

    store = IdentityStore(FACES_DIR)

    recognizer = FaceRecognizer(
        yolo_path,
        args.face_threshold,
    )

    interval = 1.0 / max(0.1, args.recognize_fps)

    process_video(
        video_path,
        recognizer,
        store,
        interval,
    )


if __name__ == "__main__":
    main()
