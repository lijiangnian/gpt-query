package com.example.mediaparser.subtitle;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Recognition hints, never a text-replacement dictionary. No media is sent to a second service. */
public final class TranscriptionOptions {
    public static final String ACCURATE_MODEL = "whisper-large-v3";
    public static final String FAST_MODEL = "whisper-large-v3-turbo";
    public final boolean visualReview;
    public final String language;
    public final boolean accurate;
    public final boolean diarization;
    public final List<String> vocabulary;

    public TranscriptionOptions(String language, boolean accurate, String terms) { this(language,accurate,terms,false,false); }
    public TranscriptionOptions(String language, boolean accurate, String terms, boolean visualReview) {
        this(language,accurate,terms,visualReview,false);
    }
    public TranscriptionOptions(String language, boolean accurate, String terms, boolean visualReview,boolean diarization) {
        this.visualReview=visualReview;
        this.diarization=diarization;
        if (!"auto".equals(language) && !"zh".equals(language)) throw new IllegalArgumentException("识别语言设置无效");
        this.language = language;
        this.accurate = accurate;
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (terms != null) for (String raw : terms.split("[,，、;；\\r\\n]+")) {
            String word = raw.trim();
            if (word.chars().anyMatch(c -> c < 32 || c == 127))
                throw new IllegalArgumentException("术语不能包含控制字符");
            if (!word.isEmpty()) unique.add(word);
        }
        if (unique.size() > 20) throw new IllegalArgumentException("术语最多填写20个，逗号分隔");
        String joined = String.join(", ", unique);
        // A conservative UTF-8 byte budget also stays below Groq's 224-token prompt limit.
        if (joined.getBytes(StandardCharsets.UTF_8).length > 200)
            throw new IllegalArgumentException("术语过长，请控制在约60个汉字以内（英文也计入长度）");
        vocabulary = Collections.unmodifiableList(new ArrayList<>(unique));
    }

    public static TranscriptionOptions defaults() {
        return new TranscriptionOptions("auto", true, "");
    }

    public String groqModel() { return accurate ? ACCURATE_MODEL : FAST_MODEL; }
    public String groqPrompt() { return String.join(", ", vocabulary); }

    static JSONObject geminiConfig(TranscriptionOptions options) throws Exception {
        JSONObject mode = new JSONObject().put("type", "verbatim")
                .put("timestamp_granularities", new JSONArray().put("word"));
        JSONObject config = new JSONObject().put("mode", mode);
        if ("zh".equals(options.language)) config.put("language_codes", new JSONArray().put("cmn-Hans-CN"));
        if (!options.vocabulary.isEmpty()) {
            JSONArray terms = new JSONArray();
            for (String term : options.vocabulary) terms.put(term);
            config.put("custom_vocabulary", terms);
        }
        return config;
    }
}
