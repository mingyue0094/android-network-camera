# Android Network Camera

适用于 Android 4.1（API 16）及以上的简易网络摄像头。

## 功能

- 手机 Camera API 采集视频
- HTTP MJPEG 网络视频流
- 默认端口：8080
- 可作为 Android 默认桌面（HOME Launcher）启动
- 开机接收 BOOT_COMPLETED 后自动启动，双重保险
- 连接 WiFi 后自动在桌面显示 IP 和 8080 端口
- 手机只负责采集和传输，适合电脑端 YOLO 做后续识别

## 使用

安装 APK 后，将本 App 设置为系统默认桌面（Home/Launcher）。

设置完成后，系统正常启动进入默认桌面时就会直接进入网络摄像头界面；同时保留 BOOT_COMPLETED 自启动作为兜底。

连接 WiFi 后，桌面状态栏会自动显示：

http://手机IP:8080/

WiFi 断开时会显示“WiFi未连接”。重新连接 WiFi 后会自动刷新 IP 和端口。

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
