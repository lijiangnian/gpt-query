# MediaParser Android v0.3.8

支持抖音、小红书、快手、B站、微博分享链接解析、媒体保存、音轨提取和多引擎字幕。

## ASR 引擎

- 本地 sherpa-onnx：SenseVoice、Fun-ASR-Nano、Paraformer，可下载后自由切换。
- Gemini 3.5 Transcribe。
- Groq Whisper。
- 阿里云百炼 Paraformer-v2。
- 阿里云百炼 Qwen3-ASR-Flash-Filetrans：异步长文件，保存句级/词级时间戳。
- 豆包 / 火山引擎录音文件识别：保存分句、词级时间戳及置信度。
- 自动选择：主引擎失败后继续已配置备用引擎，并记录实际使用引擎。

所有云端凭证由用户在 App 底部 API 设置中填写，使用 Android Keystore AES-GCM 加密。凭证不会进入 APK、日志、字幕、对齐 JSON 或截图 ZIP。`允许自动使用可能产生费用的云端额度` 默认关闭；服务商不能查询试用余额时，App 要求用户为当前任务确认。

## 网页校对流程

App 不调用 Gemini 或其他大模型做二次文字校对。ASR 只生成初稿、SRT、TXT 和 alignment JSON。用户可在结果页制作硬字幕截图 ZIP：

1. 按 ASR segment 时间轴定位并取三个锚点。
2. 锚点未检测到明显字幕时，在前后 2 秒内每 250ms 搜索。
3. 裁切画面底部 35%，压缩并去重。
4. manifest 保存截图时间、ASR segment ID、原句边界和实际识别引擎。
5. 用户手动把 ZIP 交给网页版视觉模型校正。清晰的画面硬字幕优先于 ASR 初稿。

## 构建

- JDK 17
- Android Gradle Plugin 8.7.3 / Gradle 8.9
- compileSdk / targetSdk 35，minSdk 29
- `gradle :app:assembleDebug`

applicationId 保持 `com.example.mediaparser`，可覆盖旧版安装。

## v0.3.8 本地字幕修正

- 不再把连续音频机械输出为固定 25 秒字幕块。
- 首次本地识别下载并校验约 629KB 的 Silero VAD 分句模型；失败时仍可回退能量分句。
- 直接使用 sherpa-onnx 返回的 token 文本和 token 时间戳：按静音间隔、标点、最长显示时间和字数断句。
- 字/词级时间戳写入 alignment JSON，供硬字幕截图定位使用。
- 检测到持续配乐且 Silero 覆盖明显不足时，自动切换覆盖优先模式，避免漏掉歌词或低声人声。
- 连续长段优先在低能量点切块，减少从词语中间截断。
- Android 音频降采样改为跨 MediaCodec 缓冲区连续的抗混叠重采样，修复每个缓冲区重新计相位造成的丢样与时间漂移。

## v0.3.1 修正

- 豆包“测试连接”不再固定请求极速版 `volc.bigasr.auc_turbo`。
- `volc.bigasr.auc` 按标准版 submit 接口探测，`volc.bigasr.auc_turbo` 按极速版 flash 接口探测。
- 服务返回 `45000030 requested resource not granted` 时，明确提示当前项目未开通对应 Resource ID，不再误报为 Key 无效。

## v0.3.2 修正

- 用户填写旧版 App ID / Access Token 时，自动删除此前加密保存的新版 X-Api-Key，避免隐藏的新 Key 继续被优先使用。
- 用户填写新版 X-Api-Key 时，自动删除旧版凭证；两套凭证不能混填。
- 状态区明确显示当前实际使用“新版 X-Api-Key”还是“旧版 App ID + Access Token”。
- `45000030` 优先解释为凭证与服务实例授权不匹配。
