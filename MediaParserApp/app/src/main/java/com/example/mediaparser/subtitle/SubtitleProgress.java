package com.example.mediaparser.subtitle;

import java.util.EnumMap;

/** One job, distinct phase slots. UI owns this model on its main thread. */
public final class SubtitleProgress {
    public enum Phase { PREPARE, AUTO, LOCAL, ALIYUN, QWEN3, DOUBAO, GROQ, GEMINI }
    public enum State { QUEUED, RUNNING, COMPLETE, WARNING, FAILED, SKIPPED }
    private final EnumMap<Phase,State> states = new EnumMap<>(Phase.class);
    private final EnumMap<Phase,String> details = new EnumMap<>(Phase.class);
    private final EnumMap<Phase,Long> starts = new EnumMap<>(Phase.class);
    public SubtitleProgress(SubtitleProvider provider) {
        for (Phase phase : Phase.values()) {
            boolean enabled = phase == Phase.PREPARE || provider == SubtitleProvider.AUTO
                    || phase == Phase.GROQ && provider == SubtitleProvider.GROQ
                    || phase == Phase.GEMINI && provider == SubtitleProvider.GEMINI
                    || phase == Phase.LOCAL && provider == SubtitleProvider.LOCAL
                    || phase == Phase.ALIYUN && provider == SubtitleProvider.ALIYUN;
            enabled = enabled || phase == Phase.QWEN3 && provider == SubtitleProvider.QWEN3
                    || phase == Phase.DOUBAO && provider == SubtitleProvider.DOUBAO;
            states.put(phase, enabled ? State.QUEUED : State.SKIPPED);
            details.put(phase, enabled ? "等待" : "未启用");
        }
    }
    public static String rawDetail(SubtitleOutput raw) {
        return raw.hasTiming()?"SRT + TXT 已保存"+(raw.timingWarning.isBlank()?"":" · "+raw.timingWarning)
                :"仅文字 · "+raw.timingWarning;
    }
    public void update(Phase phase, State state, String detail, long now) {
        State current = states.get(phase);
        if (current == State.COMPLETE || current == State.WARNING || current == State.FAILED || current == State.SKIPPED) return;
        if (state == State.RUNNING && !starts.containsKey(phase)) starts.put(phase, now);
        states.put(phase, state); details.put(phase, detail);
    }
    public void finish() {
        for (Phase phase : Phase.values()) {
            if (states.get(phase) == State.QUEUED) { states.put(phase,State.SKIPPED); details.put(phase,"未执行"); }
            else if (states.get(phase) == State.RUNNING) { states.put(phase,State.FAILED); details.put(phase,"流程中断，请查看错误提示"); }
        }
    }
    public String render(long now) {
        StringBuilder text = new StringBuilder();
        for (Phase phase : Phase.values()) {
            if (phase != Phase.PREPARE && states.get(phase) == State.SKIPPED && "未启用".equals(details.get(phase))) continue;
            if (text.length()>0) text.append("\n\n");
            text.append(label(phase)).append("：").append(details.get(phase));
            if (states.get(phase) == State.RUNNING) text.append(" · 已用 ").append(Math.max(0,now-starts.get(phase))/1000).append("秒");
        }
        return text.toString();
    }
    public static String label(Phase phase) {
        switch (phase) { case AUTO:return "自动切换"; case LOCAL:return "本地模型"; case ALIYUN:return "阿里云 Paraformer"; case QWEN3:return "阿里云 Qwen3-ASR"; case DOUBAO:return "豆包 ASR"; case GROQ:return "Groq"; case GEMINI:return "Gemini"; default:return "准备音频"; }
    }
}
