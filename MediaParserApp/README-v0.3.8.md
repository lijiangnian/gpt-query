# MediaParser v0.3.8

本版继续修复本地字幕的准确率、断句和时间轴。

- 实测 Fun-ASR-Nano 的 `tokens` 与 `timestamps` 一一对应；现在直接按 token 时间切分，不再按全文长度猜测句界。
- token 相邻起点相隔 800ms 以上时断为两条字幕；同时限制单条约 22 字、约 5.5 秒。
- 本地字/词级时间戳写入 alignment JSON。
- 持续配乐场景中，Silero VAD 覆盖比能量检测少 20% 以上时启用覆盖优先，降低歌词和低声人声漏识别。
- 超长连续人声在 9–15 秒范围内寻找低能量点切块，降低截断词语的概率。
- 44.1/48kHz 音频到 16kHz 的处理改为连续流式箱式低通重采样，保持跨 MediaCodec 缓冲区的相位和时长。
- 保留 v0.3.7 的模型下载进度、断点续传、SHA-256 校验和 Silero VAD 回退。
- 费用保护关闭时，自动模式直接运行本地引擎并跳过云端，不再因为用户不授权云端而阻止本地任务。

验证包括 9 项 JVM 单元测试、真实 Fun-ASR-Nano 1.13.4 模型 token/时间戳样本、Silero VAD 45 秒真实视频音频测试、Android Release 编译和 lintVitalRelease。
