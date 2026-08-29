# v0.3.2 豆包新旧凭证切换修正版

控制台已开通旧版录音文件识别标准版时，用户只要输入 App ID 与 Access Token，App 会主动清除先前保存的新版 X-Api-Key，并明确显示当前鉴权模式。

此版本修复隐藏保存的新 Key 始终优先、导致旧版服务实例持续返回 `45000030 requested resource not granted` 的问题。
