# MediaParser v0.1.12 — 字幕预览与底部 Key 设置

本版按用户要求仅调整已有 Android 版本，暂停 115 / 实时字幕插件开发。

## 修改

- 默认显示序号、起止时间、分段字幕；预览直接使用导出 SRT 的同一个格式化函数，不另行估算时间。
- 分段时间轴 / 查看全文可切换；全文不再截断到 2600 字。
- 首次显示最多60段，超过后可加载更多，复制SRT始终复制完整文件内容。
- 新增“复制 SRT”，保留“复制全文”，文件入口明确标为“下载目录”。
- Gemini / Groq 的 API Key 设置统一移到整个页面最下面，默认收起。
- 缺少 Key 时展开对应设置，并请求滚动到输入框；所有“上方设置”提示改为“底部”。
- 现有 Gemini / Groq 转写、Key 加密存储和 SRT/TXT 保存逻辑不变。
- 不增加录音、悬浮窗或后台服务权限，仍只有 INTERNET 权限。

截图中“47段”说明代码已经拿到字幕分段，旧页面显示的是 fullText 全文而非 SRT。
新预览直接从相同的 segments 生成带时间轴的文字；TXT 本来就不包含时间轴。

## 验证

- 普通 Java 回归：74/74 字幕测试、24/24 链接提取测试。
- 覆盖47段完整显示、125段分批加载、毫秒时间、预览与完整SRT一致、空内容和无效参数。
- 继续回放此前 Gemini / Groq 英文和中文实际响应记录。
- 静态检查：API区是页面最后一个区块；输入默认收起；缺Key提示与跳转位置正确；没有新增权限。
- 本轮不调用云端 API，不上传任何新音频，不读取或使用 API Key。
- Android APK 真编译、签名、版本、权限和包内容检查。
- 没有连接手机或模拟器进行 UI 触摸测试；需用户安装确认字体、滚动和布局。

## 编译

JDK17、Gradle8.9、Android平台35及build-tools35.0.0；最低Android10。
在完整源码目录执行 `gradle :app:assembleDebug`。
源码测试沿用 `GeminiSubtitleSmokeTest` 与 `LinkExtractorSmokeTest`。
当前74项字幕回归需要依次传入：app/src/test/resources/gemini-live.json、输出前缀、
app/src/test/resources/groq-live-en.json、app/src/test/resources/groq-live-zh.json。
JVM类路径需要org.json:json:20240303与Android35 android.jar，json.jar放在android.jar前。

仓库基线仍为 `lijiangnian/gpt-query` 的 `media-parser-app-build-20260828`（a3713ed）。
工作流先应用http500-fix（v0.1.11双路线），再覆盖subtitle-ui-v0112。
本地修改尚未推送到GitHub；完整源码包可以直接编译，不需要恢复base64历史包。

## 安装

本版versionCode13、versionName0.1.12。保持原包名与Key存储标识。
与本任务之前交付的v0.1.11 APK使用相同debug签名；正常覆盖安装应保留已保存Key。
若你安装的是其他签名来源的旧包，Android可能拒绝覆盖；不要直接卸载旧包，以免丢失设置。
所有文件都不包含用户API Key。
