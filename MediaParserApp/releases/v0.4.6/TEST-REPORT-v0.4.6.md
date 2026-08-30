# MediaParser v0.4.6 测试报告

测试日期：2026-08-30

主要设备：Android 16 / API 36 开发模拟器，1080×2400。

辅助确认：Xiaomi 13（2211133C / fuxi）仅用于一次性本机加密配置迁移；操作后恢复 v0.4.5 正式版，未清除数据。

## 构建与单元测试

- `testDebugUnitTest assembleDebug assembleRelease`：通过。
- 单元测试：44 个，0 failure，0 error。
- 覆盖新增项目：知乎分享文案/裸链接、知乎通用介绍过滤、网盘平台与提取码识别、OpenList 路径规范化、`/d/` 编码、`raw_url` 和 115 请求头解析、Authorization 过滤。
- Release APK：17,638,226 字节；SHA-256：`a16c007ad673d829ebba8def1d82e086443986f48b939df6bb673e2c35afa879`。

## Android 16 模拟器验收

| 项目 | 结果 |
|---|---|
| 四主页 1080×2400 布局 | 通过；直连解析 / 网盘直链 / 本地处理 / API 设置均可见 |
| 百度网盘分享路由 | 通过；进入网盘页并明确提示官方授权/OpenList 路径 |
| OpenList 设置折叠 | 通过；未配置时不占用主流程，提示只接受 HTTPS |
| 知乎回答失效样本 | 通过；不再返回知乎通用介绍，进入本机 WebView 并等待真实正文 |
| 知乎问题页 | 通过；公开标题可以提取，通用站点描述被过滤 |
| 任意分享文案/裸知乎链接 | 单元测试通过；Android SEND/PROCESS_TEXT 路线保留 |
| API 配置迁移 | 通过；模拟器检测到 4 组已配置云端服务，临时文件已删除 |

## API 鉴权

模拟器中的“一键全面测试”保持“真实转写”未勾选，只检查鉴权和模型可见性，不上传素材、不触发付费转写。

| 服务 | 结果 |
|---|---|
| Gemini | 通过；Key 验证和转写模型可见 |
| Groq Whisper | 通过；鉴权和 Whisper Large V3 可见 |
| 阿里 Paraformer-v2 | 通过；Key、Workspace 匹配，模型可见 |
| 阿里 Qwen3-ASR | 通过；Key、Workspace 匹配，filetrans 模型可见 |
| 豆包 / 火山 | 通过到音频参数校验层；服务与 Resource ID 权限已到达，真实音频仍由主动测试确认 |
| 本地模型 | 未安装；没有在本轮自动下载数百 MB 模型 |

## OpenList 测试范围

本轮没有用户自己的 OpenList 服务器地址，因此没有伪造“在线连接成功”。已完成：

- API 请求/响应、错误码、路径和返回头的单元测试；
- 国内网盘分享文案在模拟器的真实路由；
- 临时源站直链、`/d/` 备用地址和媒体处理交接逻辑；
- 非 HTTPS 服务/源站阻止、路径穿越阻止、Authorization 过滤。

用户配置自己的 HTTPS OpenList 后，仍需在“网盘直链 → OpenList 服务器设置 → 测试连接”执行一次真实服务器验收。不同网盘驱动生成的临时地址、过期时间和 User-Agent 绑定由对应驱动决定。

## 安全检查

- 真机 API 配置通过一次性 AES-GCM 文件在本机迁移，传输中无明文输出；迁移密钥和加密包已删除。
- 真机已恢复 v0.4.5 正式版；模拟器保留 v0.4.6 调试包用于测试。
- 正式清单不包含 `CredentialMigrationActivity`。
- 源码、发布包和报告不得命中完整 API Key / Token / Secret。
- OpenList 服务端返回的 Authorization 不进入播放、下载或导出。

## 已知边界

- 知乎登录、验证码、已删除回答和仅 App 可见内容仍受知乎账号权限控制。
- 网盘分享页不是媒体直链，OpenList 必须已经挂载并授权对应网盘。
- OpenList `raw_url` 可能过期，115 等地址可能绑定 User-Agent。
- 服务商价格、试用额度、模型名和限额会变化，以控制台为准。
