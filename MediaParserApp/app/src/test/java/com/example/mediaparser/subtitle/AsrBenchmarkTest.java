package com.example.mediaparser.subtitle;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class AsrBenchmarkTest {
    private SubtitleOutput output(){
        AsrDocument.Segment s=new AsrDocument.Segment(1,1000,3000,"今天开会", "",-1,Collections.emptyList());
        return SubtitleOutput.fromDocument(new AsrDocument("测试引擎","zh",4000,Collections.singletonList(s),Collections.emptyList()));
    }

    @Test public void refusesToInventCerWithoutReference(){AsrBenchmark.Score s=AsrBenchmark.score("测试",output(),1200,"");assertEquals(-1,s.cer,0);assertEquals(-1,s.timestampErrorMs,0);}

    @Test public void computesCerAndTimestampAgainstSrt(){String ref="1\n00:00:01,000 --> 00:00:03,000\n今天开会\n";AsrBenchmark.Score s=AsrBenchmark.score("测试",output(),1200,ref);assertEquals(0,s.cer,0);assertEquals(0,s.deletionRate,0);assertEquals(0,s.timestampErrorMs,0);}

    @Test public void weightedScoreNeedsReference(){AsrBenchmark.Score s=AsrBenchmark.score("测试",output(),1200,"");assertEquals(-1,AsrBenchmark.weighted(s,Collections.singletonList(s)),0);}

    @Test public void perfectReferenceProducesHighWeightedScore(){String ref="1\n00:00:01,000 --> 00:00:03,000\n今天开会\n";AsrBenchmark.Score s=AsrBenchmark.score("测试",output(),1200,ref);assertTrue(AsrBenchmark.weighted(s,Collections.singletonList(s))>95);}
}
