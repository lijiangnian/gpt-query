package com.example.mediaparser.subtitle;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Streaming Android MediaCodec decoder. It keeps long videos out of the Java heap. */
public final class LocalAudioDecoder {
    public static final int SAMPLE_RATE = 16000;
    public interface Progress { void onProgress(int percent); }
    public static final class PcmFile {
        public final File file; public final long samples;
        PcmFile(File file,long samples){this.file=file;this.samples=samples;}
        public long durationMs(){return samples*1000L/SAMPLE_RATE;}
    }
    private LocalAudioDecoder(){}

    public static PcmFile decode(File media, File cacheDir, Progress progress) throws Exception {
        MediaExtractor extractor=new MediaExtractor(); MediaCodec codec=null;
        File outFile=File.createTempFile("mediaparser_local_pcm_",".s16le",cacheDir);
        boolean ok=false;
        try {
            extractor.setDataSource(media.getAbsolutePath());
            int track=-1; MediaFormat input=null;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat f=extractor.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("audio/")){track=i;input=f;break;}
            }
            if(track<0||input==null)throw new IllegalStateException("音视频中没有可解码的音轨");
            extractor.selectTrack(track);
            String mime=input.getString(MediaFormat.KEY_MIME);
            int initialRate=input.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int initialChannels=input.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            long durationUs=input.containsKey(MediaFormat.KEY_DURATION)?input.getLong(MediaFormat.KEY_DURATION):0L;
            codec=MediaCodec.createDecoderByType(mime);
            codec.configure(input,null,null,0);codec.start();
            boolean inputDone=false,outputDone=false;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            int rate=initialRate,channels=initialChannels;long outputSamples=0,completedResamplerSamples=0;StreamingResampler resampler=null;
            try(BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(outFile),256*1024)){
                while(!outputDone){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedException("本地识别已取消");
                    if(!inputDone){int id=codec.dequeueInputBuffer(10000);if(id>=0){ByteBuffer b=codec.getInputBuffer(id);int n=extractor.readSampleData(b,0);
                        if(n<0){codec.queueInputBuffer(id,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}
                        else{codec.queueInputBuffer(id,0,n,extractor.getSampleTime(),0);extractor.advance();}
                    }}
                    int id=codec.dequeueOutputBuffer(info,10000);
                    if(id==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){MediaFormat f=codec.getOutputFormat();rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if(f.containsKey(MediaFormat.KEY_PCM_ENCODING)&&f.getInteger(MediaFormat.KEY_PCM_ENCODING)!=2)throw new IllegalStateException("该音轨解码后的PCM格式暂不支持");
                    } else if(id>=0){
                        ByteBuffer b=codec.getOutputBuffer(id);if(b!=null&&info.size>0){b.position(info.offset);b.limit(info.offset+info.size);ByteBuffer pcm=b.slice().order(ByteOrder.LITTLE_ENDIAN);
                            int frames=(info.size/2)/Math.max(1,channels);if(resampler==null||resampler.sourceRate()!=rate){if(resampler!=null){resampler.flush(out);completedResamplerSamples+=resampler.outputSamples();}resampler=new StreamingResampler(rate);}
                            for(int frame=0;frame<frames;frame++){int sum=0;for(int c=0;c<channels;c++)sum+=pcm.getShort((frame*channels+c)*2);resampler.accept((short)(sum/channels),out);}outputSamples=completedResamplerSamples+resampler.outputSamples();
                        }
                        codec.releaseOutputBuffer(id,false);if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)outputDone=true;
                        if(progress!=null&&durationUs>0)progress.onProgress((int)Math.min(99,Math.max(0,info.presentationTimeUs)*100/durationUs));
                    }
                }if(resampler!=null){resampler.flush(out);outputSamples=completedResamplerSamples+resampler.outputSamples();}
            }
            if(outputSamples<1600)throw new IllegalStateException("解码后的音频太短");
            if(progress!=null)progress.onProgress(100);ok=true;return new PcmFile(outFile,outputSamples);
        } finally {
            try{if(codec!=null){codec.stop();codec.release();}}catch(Exception ignored){}
            extractor.release();if(!ok)outFile.delete();
        }
    }

    /** Streaming box-filter resampler. Keeps phase across MediaCodec buffers and suppresses downsampling aliasing. */
    static final class StreamingResampler {
        private final int sourceRate;private final double ratio;private double remaining,sum,weight;private long outputSamples;
        StreamingResampler(int sourceRate){if(sourceRate<=0)throw new IllegalArgumentException("采样率无效");this.sourceRate=sourceRate;ratio=sourceRate/(double)SAMPLE_RATE;remaining=ratio;}
        int sourceRate(){return sourceRate;}long outputSamples(){return outputSamples;}
        void accept(short sample,OutputStream out)throws IOException{double available=1d;while(available>1e-9){double take=Math.min(available,remaining);sum+=sample*take;weight+=take;available-=take;remaining-=take;if(remaining<=1e-9){emit(out);remaining=ratio;}}}
        void flush(OutputStream out)throws IOException{if(weight>1e-9){emit(out);remaining=ratio;}}
        private void emit(OutputStream out)throws IOException{int v=(int)Math.round(sum/Math.max(1e-9,weight));v=Math.max(Short.MIN_VALUE,Math.min(Short.MAX_VALUE,v));out.write(v&255);out.write((v>>>8)&255);outputSamples++;sum=0;weight=0;}
    }
}
