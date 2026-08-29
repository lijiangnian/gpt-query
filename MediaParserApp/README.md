# MediaParser Android v0.4.5

采用“直连解析 / 本地处理 / API 设置”三页架构。六引擎横评支持从系统文件选择器导入 TXT/SRT 标准稿，并统一中文、阿拉伯数字写法后计算 CER；人名、专名和真实错字仍会计错。详见 [README-v0.4.5.md](README-v0.4.5.md) 和 [测试报告](TEST-REPORT-v0.4.5.md)。

支持抖音、小红书、快手、B站、微博分享链接解析，并新增本地视频/音频、会议逐字稿、六引擎横评和时间轴编辑器。

## 本地音视频工作台

- 从系统文件选择器导入 MP4 / MOV / MKV / MP3 / M4A / WAV / AAC。
- 字幕模式：ASR → SRT/TXT/统一 JSON → 可编辑时间轴 → 硬字幕截图 ZIP。
- 会议模式：逐字稿、发言人字段、带时间戳全文，并复制提示词到网页版模型生成会议纪要。
- 横评模式：同一文件逐个运行本地、Paraformer-v2、Qwen3-ASR、豆包、Gemini、Groq，分别显示进度、实际引擎和耗时。
- 有人工稿、硬字幕 OCR 或 SRT 参考时计算 CER、漏字率和时间戳误差；没有参考稿时明确显示不可计算，不用模型一致度冒充准确率。
- 阿里云本地文件使用百炼官方临时存储，文件与模型/账号绑定并在 48 小时后自动清理。只有用户勾选本任务云端许可后才上传。

统一 ASR JSON 为 `mediaparser-asr-v2`，每段保存 `segment_id/start_ms/end_ms/text/words/speaker/engine/language/confidence`。

编辑器支持修改文字、播放当前句、合并、拆分、删除，并重新导出 SRT、TXT、带时间戳全文和统一 JSON。App 仍不调用 Gemini 二次校正；“整理字幕/会议纪要”只复制网页提示词或导出整理包。

## ASR 引擎

- 本地 sherpa-onnx：SenseVoice、Fun-ASR-Nano、Paraformer，可下载后自由切换。
- Gemini 3.5 Transcribe。
- Groq Whisper。
- 阿里云百炼 Paraformer-v2。
- 阿里云百炼 Qwen3-ASR-Flash-Filetrans：异步长文件，保存句级/词级时间戳。
- 豆包 / 火山引擎录音文件识别：保存分句、词级时间戳及置信度。
- 自动选择：主引擎失败后继续已配置备用引擎，并记录实际使用引擎。

所有云端凭证由用户在独立的“API 设置”子页面填写，使用 Android Keystore AES-GCM 加密。凭证不会进入 APK、日志、字幕、对齐 JSON 或截图 ZIP。云端任务许可默认关闭；服务商不能查询试用余额时，App 要求用户为当前任务确认。

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


## v0.4.5 云端 ASR 稳定性与速度

- 豆包极速版 `volc.bigasr.auc_turbo` 支持本地音频单请求 Base64 直传，失败自动回退 4 倍速流式；小米 13 上 3 分 48 秒中文视频约 8.6 秒完成。
- Gemini 修复轻微时间戳回退和尾部越界；Groq 词级时间戳异常时自动降级到经过校验的句级时间轴。
- 编辑器缓存改到内部持久目录，修复小米系统旋转或页面重建后提示文件不存在。
- 豆包空音频鉴权探测将参数错误识别为“已到参数校验阶段”，仍以真实音频任务作为最终可用性确认。
- 开发者说明补充各服务商免费额度、参考价格、API 获取方式和两条真实视频的准确率排名。
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
