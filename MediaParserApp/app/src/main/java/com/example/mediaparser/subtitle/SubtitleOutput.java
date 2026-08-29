package com.example.mediaparser.subtitle;

import java.util.*;

/** One ASR draft plus its local exports. No model-to-model correction state is stored. */
public final class SubtitleOutput {
    final List<SubtitleExtractor.Word> timedWords;
    public final List<SubtitleSegment> segments;
    public final String fullText,srt,detectedLanguage,srtLocation,txtLocation,alignmentLocation,actualEngine;
    public final String timingWarning;
    public final long durationMs;

    public SubtitleOutput(List<SubtitleSegment> segments,String fullText,String srt,String language,String srtLocation,String txtLocation){
        this(segments,fullText,srt,language,srtLocation,txtLocation,Collections.emptyList(),"","",0,"");
    }
    SubtitleOutput(List<SubtitleSegment> segments,String fullText,String srt,String language,String srtLocation,String txtLocation,List<SubtitleExtractor.Word> words){
        this(segments,fullText,srt,language,srtLocation,txtLocation,words,"","",0,"");
    }
    SubtitleOutput(List<SubtitleSegment> segments,String fullText,String srt,String language,String srtLocation,String txtLocation,List<SubtitleExtractor.Word> words,String alignmentLocation,String actualEngine){
        this(segments,fullText,srt,language,srtLocation,txtLocation,words,alignmentLocation,actualEngine,0,"");
    }
    private SubtitleOutput(List<SubtitleSegment> segments,String fullText,String srt,String language,String srtLocation,String txtLocation,List<SubtitleExtractor.Word> words,String alignmentLocation,String actualEngine,long durationMs,String warning){
        this.segments=Collections.unmodifiableList(new ArrayList<>(segments));this.timedWords=Collections.unmodifiableList(new ArrayList<>(words));this.fullText=n(fullText);this.srt=n(srt);this.detectedLanguage=n(language);this.srtLocation=n(srtLocation);this.txtLocation=n(txtLocation);this.alignmentLocation=n(alignmentLocation);this.actualEngine=n(actualEngine);this.durationMs=durationMs;this.timingWarning=n(warning);
    }
    static SubtitleOutput timed(SubtitleOutput x,long duration,String warning){return new SubtitleOutput(x.segments,x.fullText,x.srt,x.detectedLanguage,x.srtLocation,x.txtLocation,x.timedWords,x.alignmentLocation,x.actualEngine,duration,warning);}
    public boolean hasTiming(){return !segments.isEmpty()&&!srt.isBlank();}
    private static String n(String s){return s==null?"":s;}
}
