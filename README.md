# Android Network Camera

适用于 Android 4.1（API 16）及以上的简易网络摄像头。

## 功能

- 手机 Camera API 采集视频
- HTTP MJPEG 网络视频流
- 默认端口：8080
- 开机接收 BOOT_COMPLETED 后自动启动
- 手机只负责采集和传输，适合电脑端 YOLO 做后续识别

## 使用

安装 APK 后启动一次，并确保系统允许该 App 自启动。

手机和电脑连接同一个局域网，在电脑浏览器访问：

http://手机IP:8080/

例如：

http://192.168.1.100:8080/

也可以把该 MJPEG 地址交给电脑端 OpenCV / YOLO 程序。

## 构建

项目使用：

- compileSdkVersion 30
- minSdkVersion 16
- targetSdkVersion 16
- Android Gradle Plugin 4.1.3
- Gradle 6.5
- Java 8

GitHub Actions 会在 push 或手动运行 workflow 时构建 APK。
