# OTrans Android

OTrans 的 Android 原生客户端，使用 Kotlin、Jetpack Compose 和 Material 3。

## 当前功能

- 设置独立页面：内网/外网地址、设备名称、配对与断开
- 内网优先、外网自动回退，并校验同一台飞牛的 `serverId`
- 设备令牌通过 Android Keystore 加密保存
- 进入前台自动拉取，前台 WebSocket 收到变化后刷新，也可手动刷新
- 发送文字、图片和多文件混合内容
- 按钮单次读取剪贴板中的文字或文件 URI，不持续监听剪贴板
- 查看全部中转记录、复制文字、点击保存文件、全局同步删除
- 下载文件做大小和 SHA-256 完整性校验

## 构建

使用 Android Studio 打开本目录，或在 PowerShell 中运行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.9.10-hotspot'
.\gradlew.bat assembleDebug
```

调试 APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。

## 后台行为

Android 会限制应用在后台长期持有网络连接，因此当前版本只在应用前台保持 WebSocket；重新进入应用会立即重连并拉取列表。文件不会自动下载，必须由用户点击保存。

## 许可证

本项目采用 [MIT License](LICENSE)。
