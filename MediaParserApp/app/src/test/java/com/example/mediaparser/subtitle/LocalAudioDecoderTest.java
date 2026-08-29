package com.example.mediaparser.subtitle;

import org.junit.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.Assert.*;

public class LocalAudioDecoderTest {
    @Test public void resamplerKeepsPhaseAndDurationAcrossArbitraryInputBoundaries()throws Exception{
        LocalAudioDecoder.StreamingResampler r=new LocalAudioDecoder.StreamingResampler(48_000);ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int i=0;i<48_000;i++)r.accept((short)(i%3==0?1000:i%3==1?2000:3000),out);r.flush(out);
        assertEquals(16_000,r.outputSamples());assertEquals(32_000,out.size());byte[] b=out.toByteArray();for(int i=0;i<20;i++){short v=(short)((b[i*2]&255)|(b[i*2+1]<<8));assertEquals(2000,v);}
    }

    @Test public void resamplerHandles44100WithoutPerBufferRoundingDrift()throws Exception{
        LocalAudioDecoder.StreamingResampler r=new LocalAudioDecoder.StreamingResampler(44_100);ByteArrayOutputStream out=new ByteArrayOutputStream();for(int i=0;i<44_100;i++)r.accept((short)1234,out);r.flush(out);assertEquals(16_000,r.outputSamples());
    }
}
