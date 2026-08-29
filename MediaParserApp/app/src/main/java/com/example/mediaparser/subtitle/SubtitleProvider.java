package com.example.mediaparser.subtitle;

/** Persisted ASR selection. AUTO is the only route that may fall back. */
public enum SubtitleProvider {
    AUTO("自动选择", "auto_asr_rtf"),
    GEMINI("Gemini", "gemini_35_transcribe_rtf"),
    GROQ("Groq", "groq_whisper_turbo_rtf"),
    LOCAL("本地离线", "local_sherpa_rtf"),
    ALIYUN("阿里云 Paraformer", "aliyun_paraformer_rtf"),
    QWEN3("阿里云 Qwen3-ASR", "aliyun_qwen3_asr_rtf"),
    DOUBAO("豆包 ASR", "doubao_asr_rtf");

    public final String label;
    public final String perfKey;
    SubtitleProvider(String label, String perfKey) { this.label = label; this.perfKey = perfKey; }
    public static SubtitleProvider fromSaved(String value) {
        if ("AUTO".equals(value) || "DUAL".equals(value)) return AUTO;
        if ("GROQ".equals(value)) return GROQ;
        if ("LOCAL".equals(value)) return LOCAL;
        if ("ALIYUN".equals(value)) return ALIYUN;
        if ("QWEN3".equals(value)) return QWEN3;
        if ("DOUBAO".equals(value)) return DOUBAO;
        return GEMINI;
    }

    public boolean isCloud() { return this != LOCAL && this != AUTO; }
}
