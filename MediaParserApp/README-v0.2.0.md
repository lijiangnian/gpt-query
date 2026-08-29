# MediaParser v0.2.0 · 多引擎字幕版

## 新增路线

字幕路线现在可以明确选择，绝不在失败后偷偷切到另一家服务：

1. 本地离线（sherpa-onnx 1.13.4，arm64-v8a）
2. 阿里云百炼 Paraformer-v2
3. Gemini 3.5 Transcribe
4. Groq Whisper
5. Gemini + Groq 双原稿，再由 Gemini 校对

## 本地模型

模型不在 APK 中，用户在 App 内按需下载。下载支持断点续传，并校验固定字节数及 SHA-256；通过后才写入 `.verified` 安装标记。已安装模型可以自由切换，不会重复下载，也不会自动回退。

| 模型 | 下载 | 安装后约 | 用途 |
|---|---:|---:|---|
| SenseVoice Small INT8 | 155 MiB | 230 MB | 速度优先，中英日韩粤 |
| Fun-ASR-Nano 轻量转换版 INT8 | 179 MiB | 253 MB | 小米 13 默认推荐，质量与速度折中 |
| Paraformer 三语 INT8 | 1,010 MiB | 约245 MB（只保留 INT8） | 中文、英文、粤语及标点增强 |

三个下载包均来自 k2-fsa/sherpa-onnx 官方 GitHub Release。Paraformer 原模型不提供内部词级时间戳，因此本 App 的本地 SRT 时间来自音频 VAD 语音片段边界，需要复听微调。

本地长视频采用固定内存流程：Android MediaCodec 流式解码为 16 kHz 单声道临时 PCM、VAD 切句、最长 25 秒一段送入本地模型。处理完删除临时音频。

## 硬字幕截图包

对画面已有烧录字幕的视频，可点“制作硬字幕截图 ZIP”：

* 有当前字幕原稿时，在每个时间段的起点后、中点、终点前取帧；没有原稿时每 500 ms 取帧。
* 使用精确时间附近帧（不是只取关键帧），裁切画面底部 35%，压到最大 960 px、JPEG 68%，感知哈希去重。
* 每 100 张拆一个 ZIP，最多 3,600 张；ZIP 含时间命名截图、manifest、提示词以及已有原稿/校对记录。
* ZIP 只写到系统 Download，App 不自动上传。用户可手动交给网页版高端视觉模型。

## 阿里云百炼

页面最底部新增阿里云 Key 与北京地域 Workspace ID。Key 使用 Android Keystore AES-GCM 加密保存。文件转写接口只接受公网可访问 URL，因此 App 直接提交当前解析出来的 HTTPS 媒体直链；有特殊 Referer/Cookie 或已过期的链接可能被阿里服务器拒绝。时间戳对齐开启时，阿里官方接口同时启用敏感词过滤。

## 构建与限制

* applicationId: `com.example.mediaparser`
* minSdk 29 / targetSdk 35
* versionCode 20 / versionName 0.2.0
* APK 只带 arm64-v8a sherpa runtime，不带 ONNX 模型和 tokens 文件。
* 与 v0.1.16 使用同一调试签名，可直接覆盖安装。
* 当前没有连接 Android 真机，无法在本次构建机上执行 arm64 JNI 模型推理、MediaCodec 真机取帧和用户阿里云账户调用。这三项需在小米 13 上做最终验收。

