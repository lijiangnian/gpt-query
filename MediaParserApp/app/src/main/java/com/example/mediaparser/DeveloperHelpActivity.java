package com.example.mediaparser;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** In-app usage, credential acquisition, privacy and troubleshooting guide. */
public final class DeveloperHelpActivity extends Activity {
    private static final int BLUE=Color.rgb(39,100,231),TEXT=Color.rgb(23,27,35),MUTED=Color.rgb(91,101,117),BORDER=Color.rgb(224,228,236),PANEL=Color.rgb(247,249,252);

    @Override protected void onCreate(Bundle state){super.onCreate(state);setContentView(buildUi());}

    private View buildUi(){
        ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(24),dp(16),dp(36));
        root.setOnApplyWindowInsetsListener((v,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());v.setPadding(dp(16),dp(24)+bars.top,dp(16),dp(36)+bars.bottom);}return insets;});
        scroll.addView(root);
        Button back=secondary("← 返回 API 设置");back.setOnClickListener(v->finish());root.addView(back,new LinearLayout.LayoutParams(-1,dp(46)));
        TextView title=text("开发者说明",27,TEXT,true);title.setPadding(0,dp(18),0,0);root.addView(title);
        TextView lead=text("MediaParser 是音视频解析、转写、字幕与会议整理工具。凭证由用户自行配置，云端和本地识别方式可以自由切换。",14,MUTED,false);lead.setPadding(0,dp(6),0,dp(4));root.addView(lead);

        section(root,"四个主要入口",
                "直连解析：粘贴受支持的社媒链接。知乎无需固定复制格式，可粘贴完整分享文案、文章/回答/视频链接或直接接收系统“分享/处理文本”；纯文字也能收进 App。知乎视频可提取媒体，文章和回答可复制标题、作者、正文、封面与原链接。\n\n"+
                "网盘直链：连接用户自己的 OpenList / AList，把已授权网盘文件转换为临时源站直链，再保存、提取音频或生成字幕。\n\n"+
                "本地处理：导入手机里的视频、音频或会议录音，选择字幕、逐字稿、会议或多引擎横评模式。\n\n"+
                "API 设置：配置云端识别服务、测试鉴权，并查看分页诊断报告。设置页面不会占用主页面空间。");
        section(root,"推荐使用流程",
                "普通视频：导入或解析媒体 → 选择 ASR → 生成 SRT / TXT → 在结果编辑器校正。\n\n"+
                "硬字幕视频：ASR 提供时间轴 → 定位字幕画面 → 生成截图 ZIP → 手动发送给网页版高端模型校正。App 不会自动上传截图包，也不会再调用 Gemini 做二次校对。\n\n"+
                "会议录音：本地或云端转写 → 保留原始逐字稿 → 再生成摘要、决策和待办。AI 整理稿不得覆盖原始记录。");

        LinearLayout gemini=section(root,"Gemini API 获取",
                "进入 Google AI Studio 的 API Keys 页面，登录后选择或创建项目，再点 Create API key。复制后只粘贴到本 App 的 API 设置。\n\n"+
                "Gemini 3.5 Transcribe 免费层输入、输出均免费，但 Google 不承诺统一的固定分钟数；RPM/TPM/RPD 必须以该项目的 AI Studio 限额页为准。付费层估算约 0.005 美元/分钟（音频输入约 0.003、文字输出约 0.002），约 0.30 美元/小时。Google AI 会员订阅与 Developer API 配额是两套体系。免费层提交的数据可能按 Google 当前条款用于改进产品。");
        link(gemini,"打开 Google AI Studio","https://aistudio.google.com/api-keys");
        link(gemini,"Gemini 官方价格","https://ai.google.dev/gemini-api/docs/pricing");

        LinearLayout groq=section(root,"Groq Whisper API 获取",
                "进入 GroqCloud Console → API Keys → Create API Key。App 使用 Whisper Large V3 / Turbo 进行带时间戳转写。\n\n"+
                "当前 Free Plan：Whisper V3 / Turbo 每天 28,800 音频秒（8 小时）、每小时 7,200 秒（2 小时）、20 RPM、2,000 RPD；免费层单文件 25MB。付费 Developer：Turbo 0.04 美元/小时，Large V3 0.111 美元/小时，单文件上限 100MB。额度按组织/项目限制，遇到 429 时等待后重试。");
        link(groq,"打开 Groq API Keys","https://console.groq.com/keys");
        link(groq,"Groq 官方限额与价格","https://console.groq.com/docs/rate-limits");

        LinearLayout aliyun=section(root,"阿里云百炼 API 获取",
                "进入阿里云百炼控制台，选择北京地域的业务空间，在 API Key 页面创建 Key，并同时复制该业务空间的 Workspace ID。Key 与 Workspace 必须属于同一个空间。\n\n"+
                "北京地域 Paraformer-v2：每月 10 小时免费额度，每月 1 日发放、当月有效；超额 0.00008 元/秒，约 0.288 元/小时。Qwen3-ASR file transcription：首次/新人总额度 10 小时，90 天内有效，不是每月刷新；超额 0.00022 元/秒，约 0.792 元/小时。接口不能查询余额时，本 App 会明确提示去控制台查看。文件转写使用阿里官方临时上传空间。");
        link(aliyun,"打开阿里云百炼控制台","https://bailian.console.aliyun.com/");
        link(aliyun,"阿里百炼官方模型价格","https://help.aliyun.com/zh/model-studio/model-pricing");

        LinearLayout doubao=section(root,"豆包 / 火山引擎 ASR 获取",
                "进入火山引擎语音技术控制台，开通“录音文件识别大模型”或对应流式服务。在服务详情复制 App ID、Access Token 和该实例对应的 Resource ID；若账号已开通新版接口，也可只填写 X-Api-Key。\n\n"+
                "Resource ID 必须与已开通的标准版、极速版或流式服务一致，不能互换。当前公开试用：流式 ASR 20 小时、录音文件 ASR 20 小时，属于首次试用总额度，不是每月刷新。公开按量价：大模型流式约 4.5 元/小时，录音文件识别 2.0 约 0.8 元/小时。官网另有 30/1000 小时资源包，活动价会变化。错误 45000030 通常表示凭证没有获准访问填写的 Resource ID。\n\n"+
                "小米13真实样本：3分48秒中文动物解说，极速版约8.6秒完成，45段、788个词级时间戳，按人工稿计算文字准确率约97.1%；27.8秒英文采访约3.7秒完成，文字准确率约93.9%。这说明豆包对普通中文口播很稳定，但只是两条样本；歌曲、混响、强背景音乐、方言和多人重叠说话要单独测试，不能直接套用97.1%。");
        link(doubao,"打开火山引擎语音控制台","https://console.volcengine.com/speech/service/17");
        link(doubao,"豆包语音官方价格","https://www.volcengine.com/product/asr");

        LinearLayout openlist=section(root,"国内网盘直链 · OpenList",
                "推荐使用开源 OpenList（AList 社区延续版）统一挂载 115、阿里云盘、夸克、百度、天翼、移动云盘、123 云盘等。MediaParser 连接你自己的 HTTPS OpenList，通过 /api/fs/get 获取 raw_url，并保留服务返回的 User-Agent / Referer，避免 115 等源站防盗链导致 403。\n\n"+
                "临时源站直链是主路线：通常不用再次打开网盘网页，可直接下载、提音频和转字幕，但可能过期。/d/ 地址较稳定，私有站点仍可能要求 OpenList 登录、路径密码或签名。普通网盘分享页不能直接绕过登录、会员和提取码；应先在官方页面保存/授权，再从已挂载路径取直链。\n\n"+
                "OpenList Token、用户名和密码使用 Android Keystore 加密。App 只允许 HTTPS 服务器，不接来路不明的公共解析站。");
        link(openlist,"OpenList 开源项目","https://github.com/OpenListTeam/OpenList");
        link(openlist,"OpenList 国内网盘驱动文档","https://doc.oplist.org/guide/drivers/115_open");

        section(root,"本地模型",
                "本地模型无需 API Key，下载并校验一次后可以离线转写。小米 13 推荐优先使用 Fun-ASR-Nano INT8；干净短语音可尝试 SenseVoice Small，中文、英语、粤语和标点可对比 Paraformer。\n\n"+
                "模型会占用数百 MB 到约 1 GB 存储。下载时可离开页面，但系统可能限制后台网络；回到“本地模型管理”查看实时进度、校验状态和已安装版本。长文件应分块处理并保留断点。");
        section(root,"付费保护与额度",
                "价格核对日期：2026-08-30。服务商可以随时调整价格、活动和限额，App 显示的是公开参考值，最终以你的控制台账单为准。\n\n"+
                "“允许使用付费额度”默认关闭。自动选择只会在用户允许的范围内调用云端引擎；某个接口失败时会记录原因并尝试备用引擎。\n\n"+
                "服务商无法通过公开接口返回余额时，App 不猜测剩余次数，只提示到控制台查看。开启付费调用前，请先确认项目、地域、计费方式和欠费保护。");
        section(root,"凭证与隐私",
                "API Key、Token 等敏感信息使用 Android Keystore 加密保存在本机，不会硬编码进 APK，也不会写入日志、诊断报告、字幕、导出文件或截图 ZIP。报告只显示脱敏状态。\n\n"+
                "不要把完整 Key 发到聊天、截图或公开仓库。密钥一旦公开，应立即到服务商控制台删除并重新创建。换机或清除 App 数据后需要重新填写。上传媒体前请确认你拥有下载、转写和使用该内容的权利。");
        section(root,"常见错误",
                "HTTP 401：Key/Token 无效、过期或填写错误。\n"+
                "HTTP 403：模型权限、地域、Workspace 或 Resource ID 不匹配。\n"+
                "HTTP 429：额度用完或触发速率限制。\n"+
                "HTTP 500：服务商临时异常，保留原稿，稍后重试或换备用引擎。\n"+
                "Invalid audio URI：服务商无法下载音频地址；请改用本地文件上传或可公开访问的直链。\n"+
                "转写缺句：先确认音轨是否完整，再换更适合人声/歌曲的模型；歌曲、混响和背景音乐本来就更难识别。");
        section(root,"六引擎真机测评与排序",
                "测试设备：小米 13。真实样本一为 27.8 秒英文采访，样本二为 3 分 48 秒中文动物解说；使用人工复核稿计算 CER。数字样式统一后再评分，但人名、专名和真实错字仍计错。\n\n"+
                "中文样本准确率：1. 豆包极速版 97.1%；2. Qwen3-ASR 96.1%；3. Paraformer-v2 93.2%；4. Gemini 90.2%；5. Groq 89.6%；6. 本地 Fun-ASR-Nano 87.7%。中文解说中豆包准确率第一，Qwen 接近且同样适合长文件。\n\n"+
                "英文样本准确率：Groq 100.0%；Qwen3-ASR 100.0%；Paraformer-v2 98.8%；Gemini 96.3%；本地 94.1%；豆包极速版 93.9%。Gemini 修复后得到 16 段有效时间轴；Groq 词级时间戳乱序时自动降级到 8 段有效句级时间轴，不再把有效全文误算成 0%。\n\n"+
                "中文样本端到端耗时：豆包极速版本地直传约 8.6 秒、Qwen 11.6 秒、Paraformer 15.6 秒、Gemini 19.9 秒、Groq 20.4 秒、本地 25.0 秒。豆包标准版遇到防盗链时的4倍速流式回退约 68.7 秒；极速版开通后不再依赖公网直链，官方支持最长2小时，App 对 Base64 本地直传采用20MB稳定上限，超限自动走流式回退。\n\n"+
                "当前建议：中文高精度优先豆包；兼顾速度与精度优先 Qwen3-ASR；英语优先 Qwen/Groq；隐私和零费用优先本地。样本仍只有两条，歌曲、多人会议、方言和强噪声要分开排名。\n\n"+
                "准确率测评：在全面测试或本地六引擎横评中粘贴或导入同一素材的人工稿、硬字幕 OCR 或 SRT。报告会显示文字准确率、CER、漏字率；SRT 还会计算平均时间戳误差，并分别生成文字准确率排名与综合排名。没有标准答案时只保留速度和稳定性数据，不生成准确率名次。");
        section(root,"如何自检",
                "在 API 设置点击“一键全面测试 · 分页报告”：先做全部鉴权，再选择测试素材跑真实转写。检查每个引擎的分句、时间轴、耗时、漏句和错误原文。\n\n"+
                "横评应使用同一份音频；有清晰硬字幕时可作为参考文本。文字准确率与时间戳质量需要分别判断。诊断报告可以复制或保存，但不会包含完整密钥。");

        LinearLayout signature=section(root,"关于",
                "开发者：lijiangnian\n抖音昵称：ljn\n抖音号：70258976876\n版本：MediaParser v0.4.7\n\n感谢测试与反馈。功能、服务商条款和额度都可能变化，实际可用性以 App 当次测试结果及各服务商官方控制台为准。");
        TextView sig=text("lijiangnian",18,BLUE,true);sig.setPadding(0,dp(10),0,dp(2));signature.addView(sig);
        link(signature,"打开我的抖音主页 · ljn","https://v.douyin.com/7qpitHslLT0/");
        return scroll;
    }

    private LinearLayout section(LinearLayout root,String title,String body){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(14),dp(14),dp(14),dp(14));card.setBackground(bg(PANEL,14,BORDER));
        card.addView(text(title,17,TEXT,true));TextView content=text(body,14,MUTED,false);content.setLineSpacing(0,1.18f);content.setPadding(0,dp(8),0,0);card.addView(content);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(12);root.addView(card,p);return card;
    }
    private void link(LinearLayout card,String label,String url){Button b=secondary(label);b.setOnClickListener(v->open(url));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(46));p.topMargin=dp(10);card.addView(b,p);}
    private void open(String url){try{startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));}catch(Exception e){Toast.makeText(this,"无法打开浏览器，请手动访问服务商官网",Toast.LENGTH_LONG).show();}}
    private Button secondary(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(TEXT);b.setBackground(bg(Color.WHITE,12,BORDER));return b;}
    private TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(v.getTypeface(),Typeface.BOLD);return v;}
    private GradientDrawable bg(int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));d.setStroke(dp(1),stroke);return d;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
