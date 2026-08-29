package com.example.mediaparser.subtitle;

import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Plain Java smoke tests, matching the existing LinkExtractor test convention. */
public final class GeminiSubtitleSmokeTest {
    private static int passed;
    public static void main(String[] args) throws Exception {
        int[] calls = {0};
        List<Long> waits = new ArrayList<>();
        JSONObject result = GeminiHttp.retry(() -> {
            if (++calls[0] <= 2) throw GeminiHttp.error("transcribe", 500, "upstream unavailable", "", null);
            return new JSONObject("{\"status\":\"completed\"}");
        }, "transcribe", null, waits::add);
        check(calls[0] == 3 && "completed".equals(result.getString("status")), "500 recovers on third attempt");
        check(waits.size() == 2 && waits.get(0) >= 21000 && waits.get(1) >= 42000, "bounded backoff");
        for (int code : new int[] {400, 401, 403, 404, 500, 502, 503, 504, 429}) {
            calls[0] = 0;
            try {
                GeminiHttp.retry(() -> {
                    calls[0]++;
                    throw GeminiHttp.error("transcribe", code, "{}", "", null);
                }, "transcribe", null, ms -> {});
                throw new AssertionError("Expected HTTP " + code);
            } catch (GeminiHttp.ApiException expected) {
                check(calls[0] == (GeminiHttp.retryable(code) ? 3 : 1), "attempt limit for " + code);
                check(expected.code == code, "preserve HTTP " + code);
            }
        }
        calls[0] = 0;
        try {
            GeminiHttp.retry(() -> { calls[0]++; throw new IOException("timeout"); }, "transcribe", null, ms -> {});
            throw new AssertionError("Expected transport failure");
        } catch (IOException expected) { check(calls[0] == 1, "no duplicate POST on ambiguous timeout"); }
        try {
            GeminiHttp.retry(() -> { throw GeminiHttp.error("x", 500, "", "", null); }, "x", null,
                ms -> { throw new InterruptedException("cancelled"); });
            throw new AssertionError("Expected cancellation");
        } catch (InterruptedException expected) { check(true, "retry cancellation"); }
        String testKey = "AQ." + "test_credential_not_a_real_key_12345";
        String error = GeminiHttp.error("转写", 500,
                "<html>upstream " + testKey + " https://example.invalid/?token=secret</html>", testKey, null).getMessage();
        check(!error.contains(testKey) && !error.contains("token=secret") && error.contains("upstream"), "redact secret and URL, retain plain error");
        check(GeminiHttp.error("转写", 400, "{\"error\":{\"message\":\"bad mime\"}}", "", null)
                .getMessage().contains("bad mime"), "preserve JSON detail");
        check(GeminiHttp.error("转写", 500, "", "", null).getMessage().contains("未提供错误正文"), "explain empty error body");
        check(GeminiHttp.error("x", 429, "", "", "60").retryAfterMs == 60000L, "Retry-After");
        check("audio/mpeg".equals(AudioMime.detect("ID3test".getBytes(StandardCharsets.US_ASCII))), "MP3 is not MP4");
        check("audio/aac".equals(AudioMime.detect(new byte[]{(byte)255,(byte)241,0,0})), "ADTS AAC");
        check("audio/mpeg".equals(AudioMime.detect(new byte[]{(byte)255,(byte)251,0,0})), "MPEG frame");
        check("audio/mp4".equals(AudioMime.detect(new byte[]{0,0,0,24,'f','t','y','p'})), "M4A container");
        check("audio/wav".equals(AudioMime.detect("RIFF0000WAVE".getBytes(StandardCharsets.US_ASCII))), "WAV container");
        try { AudioMime.detect("<html>login</html>".getBytes(StandardCharsets.US_ASCII)); throw new AssertionError("Invalid audio accepted"); }
        catch (IOException expected) { check(true, "reject HTML masquerading as audio"); }
        String fixture = "{\"status\":\"completed\",\"steps\":[{\"content\":[{\"text\":\"Hello world.\",\"annotations\":["
                + "{\"type\":\"word_info\",\"text\":\"Hello\",\"start_offset\":\"0.100s\",\"end_offset\":\"0.500s\"},"
                + "{\"type\":\"word_info\",\"text\":\"world.\",\"start_offset\":\"0.500s\",\"end_offset\":\"1.500s\"}]}]}]}";
        SubtitleExtractor.Parsed parsed = SubtitleExtractor.parseInteraction(new JSONObject(fixture), 2000L);
        check("Hello world.".equals(parsed.fullText), "REST steps text parsed");
        check(SubtitleExtractor.buildSrt(parsed.segments).contains("00:00:00,100 --> 00:00:01,500"), "real offsets become SRT");
        try {
            SubtitleExtractor.parseInteraction(new JSONObject("{\"output_text\":\"No timecodes\"}"), 2000);
            throw new AssertionError("Estimated timestamps accepted");
        } catch (IllegalStateException expected) { check(true, "Gemini no longer fabricates timestamps"); }
        try {
            SubtitleExtractor.parseInteraction(new JSONObject(fixture.replace("0.100s", "bad")), 2000);
            throw new AssertionError("Bad Gemini timestamp accepted");
        } catch (IllegalStateException expected) { check(true, "Gemini rejects malformed word timing"); }
        check(SubtitleProvider.fromSaved(null) == SubtitleProvider.GEMINI, "default provider remains Gemini");
        check(SubtitleProvider.fromSaved("GROQ") == SubtitleProvider.GROQ, "Groq selection persists");
        check(SubtitleProvider.fromSaved("unknown") == SubtitleProvider.GEMINI, "unknown setting is safe default");
        String groqKey = "gsk_" + "fake_test_credential_12345";
        check(!GeminiHttp.redact("upstream " + groqKey, "").contains(groqKey), "Groq secrets redacted without exact key");
        check(!SubtitleExtractor.errorMessage(new IOException("timeout " + groqKey + " https://example.invalid/?token=secret"), groqKey).contains("token=secret"), "all UI subtitle errors redact credentials and URLs");
        String prefix = new String(GroqTranscriber.multipartPrefix("TEST_BOUNDARY", "audio/mpeg"), StandardCharsets.UTF_8);
        check(prefix.contains("filename=\"audio.mp3\"") && prefix.contains("Content-Type: audio/mpeg"), "Groq file extension follows actual MIME");
        check(prefix.contains("name=\"response_format\"\r\n\r\nverbose_json"), "Groq asks for verbose JSON");
        check(prefix.contains("name=\"timestamp_granularities[]\"\r\n\r\nword")
                && prefix.contains("name=\"timestamp_granularities[]\"\r\n\r\nsegment"), "Groq repeated timestamp form fields");
        check(!prefix.contains("name=\"language\""), "Groq automatic language detection");
        GroqTranscriber.validateAudio(25_000_000L, "audio/mp4");
        check(true, "25MB boundary accepted");
        for (long size : new long[]{0, 25_000_001L}) {
            try { GroqTranscriber.validateAudio(size, "audio/mp4"); throw new AssertionError("Invalid size accepted"); }
            catch (IOException expected) { check(true, "Groq size rejected: " + size); }
        }
        try { GroqTranscriber.validateAudio(50L, "audio/aac"); throw new AssertionError("Raw AAC accepted"); }
        catch (IOException expected) { check(true, "unsupported Groq format rejected before upload"); }
        SubtitleExtractor.Parsed groq = GroqTranscriber.parse(new JSONObject("{\"text\":\"Hello world.\",\"language\":\"english\",\"words\":[{\"word\":\"Hello\",\"start\":0.1,\"end\":0.5},{\"word\":\"world.\",\"start\":0.5,\"end\":1.5}]}"));
        check(groq.fullText.equals("Hello world.") && groq.language.equals("english"), "Groq text and language parsed");
        check(SubtitleExtractor.buildSrt(groq.segments).contains("00:00:00,100 --> 00:00:01,500"), "Groq real word timestamps preserved");
        groq = GroqTranscriber.parse(new JSONObject("{\"segments\":[{\"text\":\"你好。\",\"start\":0.25,\"end\":1.75}]}"));
        check(groq.fullText.equals("你好。") && groq.segments.get(0).startCs == 25 && groq.segments.get(0).endCs == 175, "Groq segment fallback preserves real times and Chinese");
        for (String invalid : new String[]{"{\"text\":\"no times\"}", "{\"words\":[{\"word\":\"bad\",\"end\":1}]}",
                "{\"segments\":[{\"text\":\"bad\",\"start\":-1,\"end\":2}]}",
                "{\"segments\":[{\"text\":\"bad\",\"start\":2,\"end\":1}]}"}) {
            try { GroqTranscriber.parse(new JSONObject(invalid)); throw new AssertionError("Invalid timestamps accepted"); }
            catch (IOException expected) { check(true, "Groq rejects missing or invalid timing"); }
        }
        if (args.length >= 1) {
            JSONObject live = new JSONObject(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
            parsed = SubtitleExtractor.parseInteraction(live, 8620L);
            check(parsed.fullText.contains("subtitle test") && parsed.fullText.contains("notebooks"), "live transcript content");
            check(parsed.segments.size() == 3, "live sentence grouping");
            check(parsed.segments.get(0).startCs == 10 && parsed.segments.get(2).endCs == 780, "live word timing preserved");
            if (args.length >= 2) {
                Files.writeString(Path.of(args[1] + ".srt"), SubtitleExtractor.buildSrt(parsed.segments), StandardCharsets.UTF_8);
                Files.writeString(Path.of(args[1] + ".txt"), parsed.fullText, StandardCharsets.UTF_8);
            }
        }
        if (args.length >= 3) {
            groq = GroqTranscriber.parse(new JSONObject(Files.readString(Path.of(args[2]), StandardCharsets.UTF_8)));
            check(groq.fullText.contains("subtitle test") && groq.fullText.contains("notebooks"), "recorded Groq English transcript");
            check(groq.segments.size() == 3, "recorded Groq English grouping");
            check(groq.segments.get(0).startCs == 12 && groq.segments.get(2).endCs == 816, "recorded Groq timestamps preserved");
        }
        if (args.length >= 4) {
            groq = GroqTranscriber.parse(new JSONObject(Files.readString(Path.of(args[3]), StandardCharsets.UTF_8)));
            check(groq.fullText.contains("字幕测试") && groq.fullText.contains("笔记本"), "recorded Groq Chinese transcript");
            check(!groq.segments.isEmpty() && groq.segments.get(0).endCs > groq.segments.get(0).startCs, "recorded Groq Chinese timing");
            check(groq.segments.size() == 3 && groq.segments.get(1).text.equals("会议上午九点半开始"), "Chinese pause-based grouping without punctuation");
            Files.writeString(Path.of(args[1] + "-groq-zh.srt"), SubtitleExtractor.buildSrt(groq.segments), StandardCharsets.UTF_8);
        }
        List<SubtitleSegment> previewSegments = new ArrayList<>();
        for (int i = 0; i < 47; i++) previewSegments.add(new SubtitleSegment(i * 200L + 10, i * 200L + 160, "字幕第" + (i + 1) + "段"));
        SubtitlePreview.Page preview = SubtitlePreview.first(previewSegments, SubtitlePreview.PAGE_SIZE);
        check(preview.shown == 47 && preview.total == 47 && !preview.hasMore, "47 screenshot segments are all visible");
        check(preview.text.equals(SubtitleExtractor.buildSrt(previewSegments)), "preview and exported SRT use identical timestamps");
        check(preview.text.contains("1\n00:00:00,100 --> 00:00:01,600\n字幕第1段"), "preview contains numbered segment and milliseconds");
        check(preview.text.contains("47\n00:01:32,100 --> 00:01:33,600\n字幕第47段"), "last segment remains visible");
        for (int i = 47; i < 125; i++) previewSegments.add(new SubtitleSegment(i * 200L + 10, i * 200L + 160, "字幕第" + (i + 1) + "段"));
        preview = SubtitlePreview.first(previewSegments, 60);
        check(preview.shown == 60 && preview.total == 125 && preview.hasMore && !preview.text.contains("字幕第61段"), "large transcript first page is bounded");
        preview = SubtitlePreview.first(previewSegments, 120);
        check(preview.shown == 120 && preview.hasMore && preview.text.contains("字幕第120段"), "load more preserves previous segments");
        preview = SubtitlePreview.first(previewSegments, 180);
        check(preview.shown == 125 && !preview.hasMore && preview.text.equals(SubtitleExtractor.buildSrt(previewSegments)), "last page never truncates SRT");
        preview = SubtitlePreview.first(new ArrayList<>(), 60);
        check(preview.shown == 0 && preview.text.isEmpty() && !preview.hasMore, "empty preview handled");
        try { SubtitlePreview.first(previewSegments, 0); throw new AssertionError("Zero preview limit accepted"); }
        catch (IllegalArgumentException expected) { check(true, "invalid preview limit rejected"); }

        TranscriptionOptions defaults = TranscriptionOptions.defaults();
        String defaultForm = new String(GroqTranscriber.multipartPrefix("B", "audio/mp4", defaults), StandardCharsets.UTF_8);
        check(defaultForm.contains("\r\nwhisper-large-v3\r\n") && !defaultForm.contains("name=\"prompt\""), "accurate default without topic bias");
        TranscriptionOptions hints = new TranscriptionOptions("zh", true, "AE86,猎豹，鬣狗;猎豹\n陆地");
        String hintForm = new String(GroqTranscriber.multipartPrefix("B", "audio/mp4", hints), StandardCharsets.UTF_8);
        check(hintForm.contains("name=\"language\"\r\n\r\nzh\r\n"), "explicit Chinese reaches multipart");
        check(hintForm.contains("name=\"prompt\"\r\n\r\nAE86, 猎豹, 鬣狗, 陆地\r\n"), "deduplicated UTF8 terms reach multipart");
        String fastForm = new String(GroqTranscriber.multipartPrefix("B", "audio/mp4", new TranscriptionOptions("auto", false, "")), StandardCharsets.UTF_8);
        check(fastForm.contains("\r\nwhisper-large-v3-turbo\r\n") && !fastForm.contains("name=\"language\""), "Turbo and auto remain available");
        JSONObject request = SubtitleExtractor.transcriptionRequest("https://example.invalid/audio", "audio/mpeg", hints);
        JSONObject config = request.getJSONObject("generation_config").getJSONObject("transcription_config");
        check(config.getJSONArray("language_codes").getString(0).equals("cmn-Hans-CN"), "Gemini Mandarin locale reaches real request");
        check(config.getJSONArray("custom_vocabulary").length() == 4 && config.getJSONArray("custom_vocabulary").getString(0).equals("AE86"), "Gemini custom vocabulary reaches request");
        check(config.getJSONObject("mode").getString("type").equals("verbatim") && config.getJSONObject("mode").getJSONArray("timestamp_granularities").getString(0).equals("word"), "verbatim timing retained with hints");
        check(request.getJSONArray("input").getJSONObject(0).getString("mime_type").equals("audio/mpeg"), "hints preserve actual audio MIME");
        config = SubtitleExtractor.transcriptionRequest("https://example.invalid/audio", "audio/mp4", defaults).getJSONObject("generation_config").getJSONObject("transcription_config");
        check(!config.has("language_codes") && !config.has("custom_vocabulary"), "automatic Gemini request omits optional hints");
        try { new TranscriptionOptions("xx", true, ""); throw new AssertionError("Invalid language accepted"); }
        catch (IllegalArgumentException expected) { check(true, "unsupported language rejected before upload"); }
        try { new TranscriptionOptions("zh", true, "汉".repeat(67)); throw new AssertionError("Long prompt accepted"); }
        catch (IllegalArgumentException expected) { check(true, "Groq prompt budget bounded before upload"); }
        List<String> manyTerms = new ArrayList<>();
        for (int i = 0; i < 21; i++) manyTerms.add("t" + i);
        try { new TranscriptionOptions("auto", true, String.join(",", manyTerms)); throw new AssertionError("Too many terms accepted"); }
        catch (IllegalArgumentException expected) { check(true, "term count bounded"); }
        try { hints.vocabulary.add("replacement"); throw new AssertionError("Mutable request options"); }
        catch (UnsupportedOperationException expected) { check(true, "in-flight vocabulary immutable"); }
        groq = GroqTranscriber.parse(new JSONObject("{\"text\":\"鬣豹与鬣狗 AE86\",\"segments\":[{\"text\":\"鬣豹与鬣狗 AE86\",\"start\":0.25,\"end\":1.75}]}"));
        check(groq.fullText.equals("鬣豹与鬣狗 AE86") && groq.segments.get(0).text.equals(groq.fullText), "no global replacement or invented correction");

        List<SubtitleExtractor.Word> acronym = new ArrayList<>();
        acronym.add(new SubtitleExtractor.Word("A", 100, 200, ""));
        acronym.add(new SubtitleExtractor.Word("E", 200, 300, ""));
        acronym.add(new SubtitleExtractor.Word("86", 300, 500, ""));
        List<SubtitleExtractor.Word> joined = GroqTranscriber.joinWrittenTokens(acronym, "AE86");
        check(joined.size() == 1 && joined.get(0).text.equals("AE86") && joined.get(0).startMs == 100 && joined.get(0).endMs == 500, "AE86 written token keeps real word bounds");
        check(SubtitleExtractor.buildSrt(SubtitleExtractor.groupWords(joined)).contains("\nAE86\n"), "AE86 remains joined in SRT");
        check(GroqTranscriber.joinWrittenTokens(acronym, "A E 86").size() == 3, "intentional spaces are preserved");
        check(GroqTranscriber.joinWrittenTokens(acronym, "A186") == acronym, "mismatched full text never rewrites recognized letters");
        check(GroqTranscriber.joinWrittenTokens(acronym, "AE86 extra") == acronym, "partial alignment fails safely");
        check(new TranscriptionOptions("auto", true, "AE86、猎豹").vocabulary.size() == 2, "Chinese list separator supported");
        try { new TranscriptionOptions("auto", true, "AE\u000186"); throw new AssertionError("Control character accepted"); }
        catch (IllegalArgumentException expected) { check(true, "control characters rejected"); }

        check(SubtitleProvider.fromSaved("DUAL") == SubtitleProvider.DUAL, "dual route explicitly persisted");
        List<SubtitleExtractor.Word> sourceWords = new ArrayList<>();
        sourceWords.add(new SubtitleExtractor.Word("会议",100,300,""));
        sourceWords.add(new SubtitleExtractor.Word("上午",300,500,""));
        sourceWords.add(new SubtitleExtractor.Word("9点",500,700,""));
        sourceWords.add(new SubtitleExtractor.Word("开始",700,1100,""));
        List<SubtitleCorrector.Block> blocks = SubtitleCorrector.blocks(sourceWords);
        String good = "{\"rows\":[{\"b\":0,\"s\":\"会议上午9点开始\",\"t\":\"会议上午9点开始。\",\"u\":false}]}";
        SubtitleCorrector.Result review = SubtitleCorrector.apply(blocks, good);
        check(review.segments.size()==1 && review.segments.get(0).startCs==10 && review.segments.get(0).endCs==110, "review uses actual word timestamps only");
        check(review.text.contains("9点开始。") && review.changes==1, "punctuation correction and change report");
        review=SubtitleCorrector.apply(blocks,good.replace("9点开始。","8点开始。"));
        check(review.text.contains("9点") && !review.text.contains("8点") && review.uncertain==1,"numeral edits retained as unaccepted suggestions");
        for (String bad : new String[]{good.replace("会议上午9点开始\\\"", "wrong"),
                "{\"rows\":[]}",good.replace("\"b\":0","\"b\":1"),
                good.replace("\"s\":\"会议上午9点开始\"","\"s\":\"会议上午\""),
                good.replace("\"u\":false","\"u\":\"false\""),good.replace("\"b\":0","\"b\":0,\"start\":999")}) {
            if (bad.equals(good)) continue;
            try { SubtitleCorrector.apply(blocks,bad); throw new AssertionError("Unsafe AI output accepted: "+bad); }
            catch (java.io.IOException | org.json.JSONException expected) { check(true,"unsafe review rejected"); }
        }
        review=SubtitleCorrector.apply(blocks,good.replace("会议上午9点开始。","会议上午9点开放。").replace("false","true"));
        check(review.uncertain==1 && review.text.contains("开始") && !review.text.contains("开放"),"uncertain lexical edits remain suggestions");
        String split="{\"rows\":[{\"b\":0,\"s\":\"会\",\"t\":\"会\",\"u\":false},{\"b\":0,\"s\":\"议上午9点开始\",\"t\":\"议上午9点开始。\",\"u\":false}]}";
        check(SubtitleCorrector.apply(blocks,split).segments.size()==1,"boundary inside a timed word is merged not retimed");
        JSONObject req=SubtitleCorrector.request(SubtitleProvider.GEMINI,blocks,"会议上午九点开始。");
        check(req.getString("input").contains("reference_transcript") && !req.toString().contains("audio/mp4"),"Gemini receives text reference only");
        check(req.getString("model").equals(SubtitleCorrector.GEMINI_MODEL),"review uses text model not ASR model");
        JSONObject gr=SubtitleCorrector.request(SubtitleProvider.GROQ,blocks);
        check(gr.getJSONObject("response_format").getJSONObject("json_schema").getBoolean("strict"),"strict Groq schema requested");
        JSONObject truncated=new JSONObject("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{}\"}}]}");
        try { SubtitleCorrector.responseText(SubtitleProvider.GROQ,truncated); throw new AssertionError("Truncation accepted"); }
        catch (java.io.IOException expected) { check(true,"truncated model response rejected"); }
        List<SubtitleCorrector.Block> plainBlocks = new ArrayList<>();
        plainBlocks.add(new SubtitleCorrector.Block("会议上午9点开始",new ArrayList<>()));
        check(SubtitleCorrector.apply(plainBlocks,good).segments.isEmpty(),"pasted text never gets fabricated timing");
        sourceWords.add(new SubtitleExtractor.Word("第二句",2500,3000,""));
        check(SubtitleCorrector.blocks(sourceWords).size()==2,"review never merges across a long pause");
        List<SubtitleExtractor.Word> lookback=new ArrayList<>();
        lookback.add(new SubtitleExtractor.Word("第一句，",0,2000,""));
        lookback.add(new SubtitleExtractor.Word("第二句",2200,3500,""));
        lookback.add(new SubtitleExtractor.Word("还没结束",3500,4800,""));
        List<SubtitleSegment> local=SubtitleExtractor.groupWords(lookback);
        check(local.size()==2 && local.get(0).text.equals("第一句，") && local.get(1).text.equals("第二句还没结束"),"local fallback prefers a nearby clause boundary");

        List<SubtitleCorrector.Block> protective = new ArrayList<>();
        protective.add(new SubtitleCorrector.Block("在我国的非洲草原上",new ArrayList<>()));
        review=SubtitleCorrector.apply(protective,"{\"rows\":[{\"b\":0,\"s\":\"在我国的非洲草原上\",\"t\":\"在非洲草原上。\",\"u\":false}]}");
        check(review.text.contains("我国") && review.uncertain==1,"do not delete an apparent factual joke");
        protective.set(0,new SubtitleCorrector.Block("不要修改剂量5mg",new ArrayList<>()));
        review=SubtitleCorrector.apply(protective,"{\"rows\":[{\"b\":0,\"s\":\"不要修改剂量5mg\",\"t\":\"要修改剂量50mg。\",\"u\":false}]}");
        check(review.text.contains("不要") && !review.text.contains("50mg"),"negation and dosage-like edits require review");
        protective.set(0,new SubtitleCorrector.Block("剂量5mg",new ArrayList<>()));
        review=SubtitleCorrector.apply(protective,"{\"rows\":[{\"b\":0,\"s\":\"剂量5mg\",\"t\":\"剂量50mg。\",\"u\":false}]}");
        check(review.text.contains("5mg") && !review.text.contains("50mg"),"digits adjacent to units are protected");
        protective.set(0,new SubtitleCorrector.Block("Do not change the deadline",new ArrayList<>()));
        review=SubtitleCorrector.apply(protective,"{\"rows\":[{\"b\":0,\"s\":\"Do not change the deadline\",\"t\":\"Do change the deadline.\",\"u\":false}]}");
        check(review.text.contains("not"),"English negation deletion is guarded");
        List<SubtitleSegment> originals=new ArrayList<>(); originals.add(new SubtitleSegment(10,20,"原稿"));
        SubtitleOutput immutable=new SubtitleOutput(originals,"原稿","original-srt","zh","original.srt","original.txt");
        originals.clear();
        check(immutable.segments.size()==1,"saved original list cannot be mutated by caller");
        SubtitleOutput versioned=SubtitleOutput.reviewed(immutable,immutable,immutable,"记录","");
        check(versioned.original==immutable && versioned.alternative==immutable && versioned.srt.equals("original-srt"),"review metadata retains original versions");
        List<SubtitleCorrector.Block> manyBlocks=new ArrayList<>();
        for(int i=0;i<4;i++)manyBlocks.add(new SubtitleCorrector.Block("a".repeat(300),new ArrayList<>()));
        check(SubtitleCorrector.batches(manyBlocks).size()==2,"long review is bounded into batches");

        for(String bad:new String[]{"666.700s","757.600s","939.400s"}) {
            JSONObject response=new JSONObject(fixture.replace("1.500s",bad));
            SubtitleExtractor.Parsed guarded=SubtitleExtractor.parseSafe(response,SubtitleProvider.GEMINI,25833);
            check(guarded.segments.isEmpty()&&!guarded.fullText.isBlank()&&!guarded.timingWarning.isBlank(),"beyond actual duration preserves text only "+bad);
        }
        SubtitleExtractor.Parsed unknown=SubtitleExtractor.parseSafe(new JSONObject(fixture),SubtitleProvider.GEMINI,0);
        check(unknown.segments.isEmpty()&&!unknown.fullText.isBlank(),"unknown media duration never authorizes SRT");
        SubtitleExtractor.Parsed reverse=SubtitleExtractor.parseSafe(new JSONObject(fixture.replace("1.500s","0.200s")),SubtitleProvider.GEMINI,2000);
        check(reverse.segments.isEmpty(),"reversed word end is not clamped into zero length");
        for(List<SubtitleSegment> invalid:List.of(List.of(new SubtitleSegment(200,200,"的")),
                List.of(new SubtitleSegment(0,100,"一"),new SubtitleSegment(50,150,"二")))) {
            try{SubtitleExtractor.buildSrt(invalid);throw new AssertionError("bad SRT accepted");}
            catch(IllegalStateException expected){check(true,"final export rejects zero or overlapping time");}
        }
        SubtitleOutput valid=SubtitleExtractor.rawOutput(SubtitleExtractor.parseSafe(new JSONObject(fixture),SubtitleProvider.GEMINI,2000));
        SubtitleOutput noTime=SubtitleExtractor.rawOutput(unknown);
        check(valid.hasTiming()&&!noTime.hasTiming(),"draft timing availability is explicit");
        check(SubtitleCorrector.reviewAnchor(valid,valid)==valid&&SubtitleCorrector.reviewAnchor(noTime,valid)==valid,
                "review uses valid anchor, not raw invalid timestamps");
        check(!new TranscriptionOptions("auto",true,"").visualReview,"pictures opt-in only");
        java.util.List<Long> sample=FrameEvidence.sampleTimes(25833);
        check(sample.size()==13&&sample.get(0)>=0&&sample.get(12)<25833,"short video bounded sample times");
        check(FrameEvidence.sampleTimes(1800000).size()==60,"long video capped at sixty frames");
        FrameEvidence.Frame picture=new FrameEvidence.Frame(1000,new byte[]{1,2,3});
        JSONObject visualRequest=FrameEvidence.attach(new JSONObject().put("input","test"),List.of(picture));
        check(visualRequest.getJSONArray("input").length()==3&&visualRequest.getJSONArray("input").getJSONObject(2).getString("mime_type").equals("image/jpeg"),"multimodal request sends jpeg plus text timestamp");
        check(FrameEvidence.attach(new JSONObject().put("input","test"),List.of()).get("input") instanceof String,"no implicit image when opt-out");
        SubtitleProgress progress=new SubtitleProgress(SubtitleProvider.DUAL);
        progress.update(SubtitleProgress.Phase.GROQ,SubtitleProgress.State.RUNNING,"上传 30%",0);
        progress.update(SubtitleProgress.Phase.GROQ,SubtitleProgress.State.COMPLETE,"Groq已完成",100);
        progress.update(SubtitleProgress.Phase.GEMINI,SubtitleProgress.State.RUNNING,"Gemini上传 10%",200);
        progress.update(SubtitleProgress.Phase.GROQ,SubtitleProgress.State.RUNNING,"过期回调",300);
        check(progress.render(1000).contains("Groq已完成")&&progress.render(1000).contains("Gemini上传 10%")&&!progress.render(1000).contains("过期回调"),"provider states independent and terminal state rejects stale events");
        progress.finish();check(progress.render(1000).contains("未执行"),"unstarted review not marked complete");
        for(SubtitleProvider failed:new SubtitleProvider[]{SubtitleProvider.GROQ,SubtitleProvider.GEMINI}) {
            java.util.List<SubtitleProvider> attempted=new ArrayList<>();
            SubtitleOutput partial=SubtitleExtractor.dualPipeline(new SubtitleExtractor.DualWork(){
                public SubtitleOutput transcribe(SubtitleProvider provider)throws Exception{attempted.add(provider);if(provider==failed)throw new IOException("offline");return valid;}
                public SubtitleOutput review(SubtitleOutput a,SubtitleOutput b){throw new AssertionError("review missing original");}
            },null,"","");
            check(attempted.size()==2&&!partial.reviewWarning.isBlank()&&partial.reviewReport.isBlank(),"one provider failure preserves other original "+failed);
        }
        SubtitleOutput reviewFailure=SubtitleExtractor.dualPipeline(new SubtitleExtractor.DualWork(){
            public SubtitleOutput transcribe(SubtitleProvider p){return valid;}
            public SubtitleOutput review(SubtitleOutput a,SubtitleOutput b)throws Exception{throw new IOException("quota");}
        },null,"","");
        check(reviewFailure.original!=null&&reviewFailure.alternative!=null&&reviewFailure.reviewReport.isBlank()&&reviewFailure.reviewWarning.contains("quota"),"review failure keeps both originals and no false corrected draft");
        SubtitleOutput successful=SubtitleExtractor.dualPipeline(new SubtitleExtractor.DualWork(){
            public SubtitleOutput transcribe(SubtitleProvider p){return valid;}
            public SubtitleOutput review(SubtitleOutput a,SubtitleOutput b){return SubtitleOutput.reviewed(valid,a,b,"review record","");}
        },null,"","");
        check(successful.original!=null&&successful.alternative!=null&&!successful.reviewReport.isBlank(),"successful review exposes all three drafts");

        if(args.length>0){
            Path f=Path.of(args[0]).getParent();
            SubtitleExtractor.Parsed qualityGroq=SubtitleExtractor.parseSafe(new JSONObject(Files.readString(f.resolve("quality-groq.json"))),SubtitleProvider.GROQ,25833);
            SubtitleExtractor.Parsed qualityGemini=SubtitleExtractor.parseSafe(new JSONObject(Files.readString(f.resolve("quality-gemini.json"))),SubtitleProvider.GEMINI,25833);
            check(qualityGroq.segments.size()==6&&qualityGemini.segments.size()==6,"both real new-video responses validate without manufacturing words");
            List<SubtitleCorrector.Block> qualityBlocks=SubtitleCorrector.blocks(qualityGroq.words);
            String json=SubtitleCorrector.responseText(SubtitleProvider.GEMINI,new JSONObject(Files.readString(f.resolve("quality-visual-review.json"))));
            SubtitleCorrector.Result textOnly=SubtitleCorrector.apply(qualityBlocks,json);
            SubtitleCorrector.Result visual=SubtitleCorrector.apply(qualityBlocks,json,true);
            check(visual.text.contains("这回忆的漩涡")&&!textOnly.text.contains("这回忆的漩涡"),"visual suggestions visible while text-only guard remains conservative");
            check(visual.report.contains("必须复听")&&visual.uncertain>0,"large visual corrections flagged for listening");
            SubtitleTimeline.segments(visual.segments,25833);
            for(SubtitleSegment segment:visual.segments){
                boolean start=false,end=false;
                for(SubtitleExtractor.Word word:qualityGroq.words){start|=word.startMs/10==segment.startCs;end|=word.endMs/10==segment.endCs;}
                check(start&&end,"visual review boundaries remain ASR anchors, not screenshot estimates");
            }
            check(qualityGroq.fullText.contains("希望"),"original transcript not modified by visual review");
        }

        JSONObject reversedWords=new JSONObject("{\"text\":\"第一句第二句\",\"language\":\"zh\",\"words\":[{\"word\":\"第一句\",\"start\":1.2,\"end\":1.8},{\"word\":\"第二句\",\"start\":1.0,\"end\":2.0}],\"segments\":[{\"text\":\"第一句\",\"start\":0.2,\"end\":1.0},{\"text\":\"第二句\",\"start\":1.1,\"end\":2.0}]}");
        String unchangedResponse=reversedWords.toString();
        SubtitleExtractor.Parsed fallback=SubtitleExtractor.parseSafe(reversedWords,SubtitleProvider.GROQ,3000);
        check(fallback.words.isEmpty() && fallback.segments.size()==2 && fallback.timingWarning.contains("服务端句级"),"bad words fall back to real provider segments");
        check(fallback.segments.get(0).startCs==20 && fallback.segments.get(1).endCs==200 && fallback.fullText.equals("第一句第二句"),"fallback retains provider endpoints and full transcript");
        check(reversedWords.toString().equals(unchangedResponse),"parsing fallback does not mutate original response");
        SubtitleOutput sentenceRaw=SubtitleExtractor.rawOutput(fallback);
        check(sentenceRaw.hasTiming() && sentenceRaw.timedWords.isEmpty() && SubtitleProgress.rawDetail(sentenceRaw).contains("服务端句级"),"sentence SRT is available without fake word anchors");
        check(SubtitleCorrector.reviewAnchor(valid,sentenceRaw)==sentenceRaw,"valid Groq sentence anchor remains usable for review");
        for(String invalid:new String[]{"missing","zero","overlap","reverse","beyond","unknown-duration"}) {
            JSONObject response=new JSONObject(unchangedResponse);
            if(invalid.equals("missing"))response.remove("segments");
            else if(invalid.equals("zero"))response.getJSONArray("segments").getJSONObject(0).put("end",0.2);
            else if(invalid.equals("overlap"))response.getJSONArray("segments").getJSONObject(1).put("start",0.5);
            else if(invalid.equals("reverse"))response.getJSONArray("segments").getJSONObject(0).put("end",0.1);
            else if(invalid.equals("beyond"))response.getJSONArray("segments").getJSONObject(1).put("end",900.0);
            SubtitleExtractor.Parsed rejected=SubtitleExtractor.parseSafe(response,SubtitleProvider.GROQ,invalid.equals("unknown-duration")?0:3000);
            check(rejected.segments.isEmpty() && rejected.words.isEmpty() && rejected.fullText.equals(fallback.fullText),"both timing paths unsafe retain text only: "+invalid);
        }
        for(String bad:new String[]{"end-reversed","non-numeric","long-span","bad-object"}) {
            JSONObject response=new JSONObject(unchangedResponse);
            if(bad.equals("end-reversed"))response.getJSONArray("words").getJSONObject(0).put("end",0.1);
            if(bad.equals("non-numeric"))response.getJSONArray("words").getJSONObject(0).put("start","oops");
            if(bad.equals("long-span"))response.getJSONArray("words").getJSONObject(0).put("end",50);
            if(bad.equals("bad-object"))response.getJSONArray("words").put(0,"oops");
            check(SubtitleExtractor.parseSafe(response,SubtitleProvider.GROQ,60000).segments.size()==2,"sentence fallback on unusable word data: "+bad);
        }
        List<SubtitleCorrector.Block> sentenceBlocks=SubtitleCorrector.segmentBlocks(sentenceRaw);
        String sentenceEdit="{\"rows\":[{\"b\":0,\"s\":\"第一\",\"t\":\"第一\",\"u\":false},{\"b\":0,\"s\":\"句\",\"t\":\"句。\",\"u\":false},{\"b\":1,\"s\":\"第二句\",\"t\":\"第二句。\",\"u\":false}]}";
        SubtitleCorrector.Result sentenceReview=SubtitleCorrector.apply(sentenceBlocks,sentenceEdit);
        check(sentenceReview.segments.size()==2 && sentenceReview.segments.get(0).startCs==20 && sentenceReview.segments.get(0).endCs==100 && sentenceReview.segments.get(1).startCs==110,"AI sentence splitting cannot invent internal timestamps");
        check(sentenceReview.text.contains("第一句。") && sentenceRaw.fullText.equals("第一句第二句"),"sentence punctuation review preserves original draft");
        check(SubtitleCorrector.request(SubtitleProvider.GEMINI,sentenceBlocks).getString("input").contains("segment: keep this whole sentence"),"sentence timing granularity supplied to reviewer");
        check(SubtitleCorrector.referenceText(sentenceRaw,sentenceBlocks).contains("第二句"),"sentence blocks select time-local reference text");
        FrameEvidence selectedEvidence=new FrameEvidence(List.of(new FrameEvidence.Frame(1000,new byte[]{1}),new FrameEvidence.Frame(20000,new byte[]{1})),"test");
        check(selectedEvidence.select(sentenceBlocks).size()==1,"sentence anchors select corresponding images only");
        SubtitleOutput retryBundle=SubtitleOutput.reviewed(sentenceRaw,valid,sentenceRaw,"","HTTP 500");
        SubtitleProgress retryProgress=SubtitleProgress.reviewRetry(retryBundle,5000);
        check(retryProgress.render(6000).contains("沿用原稿") && retryProgress.render(6000).contains("仅重试校对") && !retryProgress.render(6000).contains("HTTP 500"),"review retry starts fresh progress while retaining completed originals");
        retryProgress.update(SubtitleProgress.Phase.REVIEW,SubtitleProgress.State.COMPLETE,"校对完成",7000);
        retryProgress.update(SubtitleProgress.Phase.REVIEW,SubtitleProgress.State.RUNNING,"stale",8000);
        check(!retryProgress.render(9000).contains("stale") && progress.render(9000).contains("Groq已完成"),"retry terminal progress rejects stale callback without changing old job");

        // Exercise the actual correction POST + header parsing + retry boundary with fake connections.
        List<FakeConnection> requests=new ArrayList<>(); List<Long> reviewWaits=new ArrayList<>();List<String> stages=new ArrayList<>();
        JSONObject correctionRequest=new JSONObject().put("model",SubtitleCorrector.GEMINI_MODEL).put("input","original drafts and images");
        JSONObject recovered=SubtitleCorrector.postWithRetry("test-key",correctionRequest,"Gemini校对 2/3",stages::add,()->{
            FakeConnection connection=new FakeConnection(requests.size()<2?500:200,"60");requests.add(connection);return connection;
        },reviewWaits::add);
        check(recovered.getString("status").equals("completed") && requests.size()==3,"correction HTTP 500 recovers on third POST attempt");
        check(reviewWaits.size()==2 && reviewWaits.stream().allMatch(ms->ms>=60000),"correction honors Retry-After response header");
        check(stages.size()==2 && stages.get(0).contains("2/3") && stages.get(0).contains("1/2"),"retry progress identifies current batch and retry count");
        check(requests.stream().allMatch(c->c.closed && c.uploaded.toString(StandardCharsets.UTF_8).equals(correctionRequest.toString())),"each retry closes connection and resends only same correction body");
        for(int code:new int[]{429,500,503,401}) {
            int[] attempts={0};
            try {
                SubtitleCorrector.postWithRetry("test-key",correctionRequest,"校对",null,()->{attempts[0]++;return new FakeConnection(code,null);},ms->{});
                throw new AssertionError("persistent HTTP accepted");
            }catch(GeminiHttp.ApiException expected){check(attempts[0]==(code==401?1:3)&&expected.code==code,"correction stops at bounded attempts for "+code);}
        }
        int[] transports={0};
        try {
            SubtitleCorrector.postWithRetry("test-key",correctionRequest,"校对",null,()->{transports[0]++;throw new IOException("timeout");},ms->{});
            throw new AssertionError("transport accepted");
        }catch(IOException expected){check(transports[0]==1,"correction transport timeout is not automatically replayed");}
        try {
            SubtitleCorrector.postWithRetry("test-key",correctionRequest,"校对",null,()->new FakeConnection(500,null),ms->{throw new InterruptedException("cancel");});
            throw new AssertionError("cancel accepted");
        }catch(InterruptedException expected){check(true,"correction retry wait is cancellable");}
        if(args.length>0) {
            JSONObject actual=new JSONObject(Files.readString(Path.of(args[0]).getParent().resolve("groq-word-order.json")));
            SubtitleExtractor.Parsed realFallback=SubtitleExtractor.parseSafe(actual,SubtitleProvider.GROQ,228276);
            check(realFallback.segments.size()==90 && realFallback.words.isEmpty(),"real 701-word unordered response recovers 90 server segments");
            boolean exact=true;
            for(int i=0;i<90;i++) {
                JSONObject segment=actual.getJSONArray("segments").getJSONObject(i);
                exact &= realFallback.segments.get(i).startCs==Math.round(segment.getDouble("start")*1000)/10
                        && realFallback.segments.get(i).endCs==Math.round(segment.getDouble("end")*1000)/10;
            }
            check(exact && realFallback.fullText.equals(actual.getString("text").trim()),"all recovered real endpoints and transcript match server data");
        }

        System.out.println("Gemini/Groq subtitle regression: " + passed + "/" + passed + " PASS");
    }
    private static final class FakeConnection extends java.net.HttpURLConnection {
        final int code; final String retryAfter;
        boolean closed;
        final java.io.ByteArrayOutputStream uploaded=new java.io.ByteArrayOutputStream();
        FakeConnection(int code,String retryAfter) throws Exception {
            super(java.net.URI.create("https://example.invalid/correction-test").toURL());this.code=code;this.retryAfter=retryAfter;
        }
        public void connect(){} public void disconnect(){closed=true;} public boolean usingProxy(){return false;}
        public java.io.OutputStream getOutputStream(){return uploaded;}
        public int getResponseCode(){return code;}
        public String getHeaderField(String name){return "Retry-After".equalsIgnoreCase(name)?retryAfter:null;}
        public java.io.InputStream getInputStream(){return new java.io.ByteArrayInputStream("{\"status\":\"completed\"}".getBytes(StandardCharsets.UTF_8));}
        public java.io.InputStream getErrorStream(){return new java.io.ByteArrayInputStream("{\"error\":{\"message\":\"high demand\"}}".getBytes(StandardCharsets.UTF_8));}
    }
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        passed++;
    }
}

