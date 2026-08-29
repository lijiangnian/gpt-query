package com.example.mediaparser.subtitle;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit opt-in live test of the exact APK networking/parsing code; key via stdin only. */
public final class LiveGeminiSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length == 4 && "--replay-review".equals(args[0])) {
            SubtitleExtractor.Parsed anchor=SubtitleExtractor.parseInteraction(new JSONObject(Files.readString(Path.of(args[1]),StandardCharsets.UTF_8)),228276);
            java.util.List<java.util.List<SubtitleCorrector.Block>> batches=SubtitleCorrector.batches(SubtitleCorrector.blocks(anchor.words));
            java.util.List<SubtitleSegment> all=new java.util.ArrayList<>();
            StringBuilder text=new StringBuilder(),report=new StringBuilder("AI双稿整合，原稿保留。修改仍需核对。\n");
            int changes=0,uncertain=0;
            for(int i=0;i<batches.size();i++) {
                JSONObject response=new JSONObject(Files.readString(Path.of(args[2],"review-response-"+i+".json"),StandardCharsets.UTF_8));
                SubtitleCorrector.Result part=SubtitleCorrector.apply(batches.get(i),SubtitleCorrector.responseText(SubtitleProvider.GEMINI,response));
                all.addAll(part.segments);text.append(part.text);report.append(part.report);changes+=part.changes;uncertain+=part.uncertain;
            }
            for(SubtitleSegment s:all) {
                if(s.endCs<=s.startCs)throw new AssertionError("Invalid final timing");
                boolean start=false,end=false;
                for(SubtitleExtractor.Word w:anchor.words){start|=w.startMs/10==s.startCs;end|=w.endMs/10==s.endCs;}
                if(!start||!end)throw new AssertionError("Invented time boundary");
            }
            Path out=Path.of(args[3]);Files.createDirectories(out);
            Files.writeString(out.resolve("reviewed.srt"),SubtitleExtractor.buildSrt(all),StandardCharsets.UTF_8);
            Files.writeString(out.resolve("reviewed.txt"),text.toString(),StandardCharsets.UTF_8);
            Files.writeString(out.resolve("changes.txt"),report.toString(),StandardCharsets.UTF_8);
            System.out.println("PASS final replay: "+all.size()+" segments; "+changes+" changes; "+uncertain+" review flags; every boundary from original words; no network.");
            return;
        }
        if (args.length == 3 && "--replay-groq".equals(args[0])) {
            JSONObject response = new JSONObject(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
            SubtitleExtractor.Parsed parsed = GroqTranscriber.parse(response);
            for (SubtitleSegment segment : parsed.segments)
                if (segment.startCs < 0 || segment.endCs <= segment.startCs) throw new AssertionError("Invalid replay timing");
            Files.writeString(Path.of(args[2]), SubtitleExtractor.buildSrt(parsed.segments), StandardCharsets.UTF_8);
            System.out.println("PASS: recorded response with final parser; segments=" + parsed.segments.size());
            return;
        }
        if(args.length==4&&args[0].equals("--quality-parse")){
            Path in=Path.of(args[1]),out=Path.of(args[3]);Files.createDirectories(out);long duration=Long.parseLong(args[2]);
            for(SubtitleProvider provider:new SubtitleProvider[]{SubtitleProvider.GROQ,SubtitleProvider.GEMINI}){
                String name=provider==SubtitleProvider.GROQ?"groq":"gemini";
                SubtitleExtractor.Parsed p=SubtitleExtractor.parseSafe(new JSONObject(Files.readString(in.resolve(name+"-response.json"))),provider,duration);
                Files.writeString(out.resolve(name+".txt"),p.fullText,StandardCharsets.UTF_8);
                Files.writeString(out.resolve(name+".srt"),SubtitleExtractor.buildSrt(p.segments),StandardCharsets.UTF_8);
                System.out.println(name+" validated segments="+p.segments.size()+"; warning="+p.timingWarning);
            }
            return;
        }
        String key = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Key required on stdin");
        String remoteName = "";
        try {
            if("--quality-review".equals(args[0])){
                Path in=Path.of(args[1]),frames=Path.of(args[3]),out=Path.of(args[4]);Files.createDirectories(out);
                long duration=Long.parseLong(args[2]);
                SubtitleOutput gemini=SubtitleExtractor.rawOutput(SubtitleExtractor.parseSafe(new JSONObject(Files.readString(in.resolve("gemini-response.json"))),SubtitleProvider.GEMINI,duration));
                SubtitleOutput groq=SubtitleExtractor.rawOutput(SubtitleExtractor.parseSafe(new JSONObject(Files.readString(in.resolve("groq-response.json"))),SubtitleProvider.GROQ,duration));
                java.util.List<FrameEvidence.Frame> pictures=new java.util.ArrayList<>();
                if(Files.isDirectory(frames))for(long time:FrameEvidence.sampleTimes(duration))
                    pictures.add(new FrameEvidence.Frame(time,Files.readAllBytes(frames.resolve(time+".jpg"))));
                gemini=SubtitleOutput.visual(gemini,new FrameEvidence(pictures,"实测参考截图 "+pictures.size()+"张（ffmpeg取帧，Android解码未真机验证）"));
                SubtitleCorrector.Result result=SubtitleCorrector.reviewPair(gemini,groq,key,System.out::println,
                        (batch,response)->Files.writeString(out.resolve("review-response-"+batch+".json"),response.toString(2),StandardCharsets.UTF_8));
                SubtitleTimeline.segments(result.segments,duration);
                Files.writeString(out.resolve("reviewed.srt"),SubtitleExtractor.buildSrt(result.segments),StandardCharsets.UTF_8);
                Files.writeString(out.resolve("reviewed.txt"),result.text,StandardCharsets.UTF_8);
                Files.writeString(out.resolve("changes.txt"),result.report,StandardCharsets.UTF_8);
                System.out.println("PASS actual Gemini multimodal review: pictures="+pictures.size()+"; segments="+result.segments.size()+"; changes="+result.changes+"; flags="+result.uncertain);
                return;
            }
            if ("--dual-review".equals(args[0]) || "--review-pair".equals(args[0])) {
                Path out = Path.of(args[2]); Files.createDirectories(out);
                JSONObject gemini;
                if ("--dual-review".equals(args[0])) {
                    File audio = new File(args[1]); String mime = AudioMime.detect(audio);
                    Object upload = method("uploadFile",String.class,File.class,String.class,SubtitleExtractor.Listener.class).invoke(null,key,audio,mime,null);
                    remoteName = field(upload,"name");
                    method("waitUntilActive",String.class,upload.getClass(),SubtitleExtractor.Listener.class).invoke(null,key,upload,null);
                    gemini=(JSONObject)method("transcribe",String.class,String.class,String.class,SubtitleExtractor.Listener.class).invoke(null,key,field(upload,"uri"),mime,null);
                    Files.writeString(out.resolve("gemini-response.json"),gemini.toString(2),StandardCharsets.UTF_8);
                } else gemini=new JSONObject(Files.readString(Path.of(args[1]),StandardCharsets.UTF_8));
                SubtitleExtractor.Parsed anchor=SubtitleExtractor.parseInteraction(gemini,228276L);
                SubtitleExtractor.Parsed reference=GroqTranscriber.parse(new JSONObject(Files.readString(Path.of(args[3]),StandardCharsets.UTF_8)));
                Files.writeString(out.resolve("gemini-original.srt"),SubtitleExtractor.buildSrt(anchor.segments),StandardCharsets.UTF_8);
                Files.writeString(out.resolve("gemini-original.txt"),anchor.fullText,StandardCharsets.UTF_8);
                long begin=System.nanoTime();
                SubtitleCorrector.Result review=SubtitleCorrector.review(SubtitleExtractor.rawOutput(anchor),SubtitleExtractor.rawOutput(reference),SubtitleProvider.GEMINI,key,System.out::println,
                        (batch,response)->Files.writeString(out.resolve("review-response-"+batch+".json"),response.toString(2),StandardCharsets.UTF_8));
                Files.writeString(out.resolve("reviewed.srt"),SubtitleExtractor.buildSrt(review.segments),StandardCharsets.UTF_8);
                Files.writeString(out.resolve("reviewed.txt"),review.text,StandardCharsets.UTF_8);
                Files.writeString(out.resolve("changes.txt"),review.report,StandardCharsets.UTF_8);
                System.out.println("PASS: Gemini+Groq originals -> actual Gemini review client; segments="+review.segments.size()+"; changes="+review.changes+"; uncertain="+review.uncertain+"; review_seconds="+((System.nanoTime()-begin)/1e9));
                return;
            }
            File audio = new File(args[0]);
            String mime = AudioMime.detect(audio);
            if (args.length > 3 && "groq".equals(args[3])) {
                long begin = System.nanoTime();
                TranscriptionOptions options = args.length >= 8
                        ? new TranscriptionOptions(args[5], Boolean.parseBoolean(args[6]), args[7])
                        : TranscriptionOptions.defaults();
                GeminiKeyValidator.Result auth = GroqKeyValidator.validate(key, options.groqModel());
                if (!auth.usable()) throw new AssertionError(auth.message);
                JSONObject response = GroqTranscriber.transcribe(key, audio, mime, Long.parseLong(args[2]), options, null);
                SubtitleExtractor.Parsed parsed = GroqTranscriber.parse(response);
                String expected = args.length > 4 ? args[4] : "subtitle test";
                if (!parsed.fullText.toLowerCase(java.util.Locale.ROOT).contains(expected)
                        || (args.length <= 4 && !parsed.fullText.toLowerCase(java.util.Locale.ROOT).contains("notebooks"))
                        || parsed.segments.isEmpty()) throw new AssertionError("Unexpected Groq transcript");
                for (SubtitleSegment s : parsed.segments)
                    if (s.startCs < 0 || s.endCs <= s.startCs) throw new AssertionError("Invalid Groq timing");
                Path output = Path.of(args[1]);
                Files.createDirectories(output);
                Files.writeString(output.resolve("app-core-live.srt"), SubtitleExtractor.buildSrt(parsed.segments), StandardCharsets.UTF_8);
                Files.writeString(output.resolve("app-core-live.txt"), parsed.fullText, StandardCharsets.UTF_8);
                Files.writeString(output.resolve("app-core-response.json"), response.toString(2), StandardCharsets.UTF_8);
                System.out.println("PASS: exact Groq app key validation/multipart/transcribe/SRT code; mime=" + mime
                        + "; segments=" + parsed.segments.size() + "; elapsed_seconds=" + ((System.nanoTime()-begin)/1_000_000_000.0));
                return;
            }
            Method upload = method("uploadFile", String.class, File.class, String.class, SubtitleExtractor.Listener.class);
            Object uploaded = upload.invoke(null, key, audio, mime, null);
            remoteName = field(uploaded, "name");
            method("waitUntilActive", String.class, uploaded.getClass(), SubtitleExtractor.Listener.class).invoke(null, key, uploaded, null);
            long begin = System.nanoTime();
            JSONObject response = (JSONObject) method("transcribe", String.class, String.class, String.class, SubtitleExtractor.Listener.class)
                    .invoke(null, key, field(uploaded, "uri"), mime, null);
            SubtitleExtractor.Parsed parsed = SubtitleExtractor.parseInteraction(response, Long.parseLong(args[2]));
            if (!parsed.fullText.contains("subtitle test") || parsed.segments.size() != 3)
                throw new AssertionError("Unexpected test transcript");
            String srt = SubtitleExtractor.buildSrt(parsed.segments);
            if (!srt.contains("00:00:00,100")) throw new AssertionError("Missing real timestamps");
            Path output = Path.of(args[1]);
            Files.createDirectories(output);
            Files.writeString(output.resolve("app-core-live.srt"), srt, StandardCharsets.UTF_8);
            Files.writeString(output.resolve("app-core-live.txt"), parsed.fullText, StandardCharsets.UTF_8);
            Files.writeString(output.resolve("app-core-response.json"), response.toString(2), StandardCharsets.UTF_8);
            System.out.println("PASS: exact app upload/transcribe/SRT code; mime="+mime+"; segments="+parsed.segments.size()
                    +"; transcribe_seconds="+((System.nanoTime()-begin)/1_000_000_000.0));
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            System.err.println(GeminiHttp.redact(cause.toString(), key));
            throw new IllegalStateException("Live test failed; see redacted diagnostic above");
        } finally {
            if (!remoteName.isEmpty()) method("deleteRemoteFile", String.class, String.class).invoke(null, key, remoteName);
            key = "";
        }
    }
    private static Method method(String name, Class<?>... types) throws Exception {
        Method m = SubtitleExtractor.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m;
    }
    private static String field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (String) f.get(target);
    }
}
