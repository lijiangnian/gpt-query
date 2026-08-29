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

    @Test public void plainReferenceRenormalizesMissingTimestampWeight(){AsrBenchmark.Score s=AsrBenchmark.score("测试",output(),1200,"今天开会");assertEquals(-1,s.timestampErrorMs,0);assertTrue(AsrBenchmark.weighted(s,Collections.singletonList(s))>95);}

    @Test public void reportContainsAccuracyAndCompositeRankings(){AsrBenchmark.Score s=AsrBenchmark.score("测试",output(),1200,"今天开会");String report=AsrBenchmark.report(Collections.singletonList(s),true);assertTrue(report.contains("文字准确率：100.0%"));assertTrue(report.contains("文字准确率排名"));assertTrue(report.contains("综合排名"));}

    @Test public void normalizesChineseAndArabicNumberStyles(){assertEquals("下周1上午10点项目预算12800元",AsrBenchmark.normalizeChineseNumbers("下周一上午十点项目预算一万二千八百元"));}

    @Test public void numberStyleDoesNotInflateCer(){AsrDocument.Segment segment=new AsrDocument.Segment(1,0,2000,"下周一上午10点，预算12800元", "",-1,Collections.emptyList());SubtitleOutput actual=SubtitleOutput.fromDocument(new AsrDocument("测试","zh",2000,Collections.singletonList(segment),Collections.emptyList()));AsrBenchmark.Score score=AsrBenchmark.score("测试",actual,1000,"下周一上午十点，预算一万二千八百元");assertEquals(0,score.cer,0);}

    @Test public void textOnlyTranscriptKeepsAccuracyWhenTimingWasRejected(){SubtitleOutput textOnly=new SubtitleOutput(Collections.emptyList(),"完整全文","","zh","","");AsrBenchmark.Score score=AsrBenchmark.score("测试",textOnly,1000,"完整全文");assertEquals(0,score.cer,0);assertEquals(0,score.segments);assertEquals(0,score.coverage,0);}
}
