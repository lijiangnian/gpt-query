# MediaParser v0.2.1 · 阿里云鉴权状态版

本版修正 v0.2.0 阿里云设置卡片只保存、不验证的问题。

## 阿里云设置流程

* “保存并验证”会先调用北京业务空间的只读 `/api/v1/quotas` 接口。
* 验证 Key 与 Workspace ID 是否匹配，并确认 `paraformer-v2` 对当前业务空间可见。
* 鉴权验证不上传视频或音频、不创建转写任务，因此不消耗语音识别时长。
* 已保存的旧设置初始显示“已保存，未验证”，可点“测试当前设置”重新验证。
* 成功后明确显示“鉴权通过”；401、403、404、429 与网络失败会显示对应原因。
* 配额接口只能读取模型限流，不能读取免费语音时长余额；“查看免费额度”按钮直达阿里云北京地域免费额度页面。

## 安全与兼容性

API Key 仍由 Android Keystore AES-GCM 加密保存在本机，不写入 APK、源码或日志。新版沿用包名及 Keystore 别名，可覆盖 v0.2.0，并保留已保存的 Gemini、Groq、阿里云设置和本地模型。

* applicationId：`com.example.mediaparser`
* versionCode：21
* versionName：0.2.1
* minSdk 29 / targetSdk 35

## 验证

* Android SDK 35 / Build Tools 35.0.0 / JDK 17 / Gradle 8.9 真编译通过。
* 新增的 `AliyunKeyValidator` 已纳入 APK；只读鉴权接口可区分有效与无效 Key。
* 发布前执行源码及 APK 密钥特征扫描，确保未包含测试 Key。
