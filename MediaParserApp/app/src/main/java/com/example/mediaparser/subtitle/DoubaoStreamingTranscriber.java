package com.example.mediaparser.subtitle;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** Direct-audio fallback for Doubao streaming hourly ASR. No public file URL is required. */
final class DoubaoStreamingTranscriber {
    private static final String URL="wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_nostream";
    private static final String RESOURCE="volc.bigasr.sauc.duration";
    private static final int CHUNK_BYTES=LocalAudioDecoder.SAMPLE_RATE*2/5; // 200 ms, PCM16 mono
    // The non-streaming endpoint accepts buffered audio. Four-times pacing keeps the
    // websocket queue bounded while avoiding a mandatory one-minute wait per minute
    // of source audio. Live tests still verify that final timestamps cover the file.
    private static final int SEND_SPEED=4;

    private DoubaoStreamingTranscriber(){}

    static DoubaoTranscriber.Result transcribe(File media,File cacheDir,DoubaoCredentialStore.Credentials creds,
                                                String language,DoubaoTranscriber.Listener listener)throws Exception{
        if(creds==null||!creds.configured())throw new IllegalArgumentException("请先设置豆包 App ID + Access Token");
        if(creds.appId.isBlank()||creds.accessToken.isBlank())throw new IllegalArgumentException("防盗链直传回退需要旧版 App ID + Access Token，并开通流式小时版");
        if(listener!=null)listener.stage("豆包无法读取原链接 · 正在解码为 16k 单声道音频…");
        LocalAudioDecoder.PcmFile pcm=LocalAudioDecoder.decode(media,cacheDir,p->{if(listener!=null)listener.stage("豆包直传准备音频 "+p+"%");});
        try{return stream(pcm,creds,language,listener);}finally{pcm.file.delete();}
    }

    private static DoubaoTranscriber.Result stream(LocalAudioDecoder.PcmFile pcm,DoubaoCredentialStore.Credentials creds,
                                                     String language,DoubaoTranscriber.Listener listener)throws Exception{
        OkHttpClient client=new OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).build();
        CountDownLatch opened=new CountDownLatch(1),finished=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicReference<JSONObject> latest=new AtomicReference<>();
        Request req=new Request.Builder().url(URL).header("X-Api-App-Key",creds.appId).header("X-Api-Access-Key",creds.accessToken)
                .header("X-Api-Resource-Id",RESOURCE).header("X-Api-Connect-Id",UUID.randomUUID().toString()).build();
        WebSocket ws=client.newWebSocket(req,new WebSocketListener(){
            @Override public void onOpen(WebSocket webSocket,Response response){opened.countDown();}
            @Override public void onMessage(WebSocket webSocket,ByteString bytes){
                try{Packet p=parsePacket(bytes.toByteArray());if(p.json!=null&&p.json.optJSONObject("result")!=null)latest.set(p.json);if(p.terminal)finished.countDown();}
                catch(Throwable e){failure.compareAndSet(null,e);finished.countDown();}
            }
            @Override public void onFailure(WebSocket webSocket,Throwable t,Response response){failure.compareAndSet(null,new IOException("豆包直传连接失败"+(response==null?"":" · HTTP "+response.code())+"："+safe(t.getMessage())));opened.countDown();finished.countDown();}
            @Override public void onClosed(WebSocket webSocket,int code,String reason){finished.countDown();}
        });
        try{
            if(!opened.await(25,TimeUnit.SECONDS))throw new IOException("豆包直传连接超时");throwIfFailed(failure);
            JSONObject audio=new JSONObject().put("format","pcm").put("codec","raw").put("rate",16000).put("bits",16).put("channel",1);
            if(language!=null&&!language.isBlank()&&!"auto".equals(language))audio.put("language",language.startsWith("zh")?"zh-CN":language);
            JSONObject body=new JSONObject().put("user",new JSONObject().put("uid",creds.appId)).put("audio",audio)
                    .put("request",new JSONObject().put("model_name","bigmodel").put("enable_itn",true).put("enable_punc",true).put("enable_ddc",true).put("show_utterances",true).put("result_type","full"));
            if(!ws.send(ByteString.of(packet(0x10,0x10,body.toString().getBytes(StandardCharsets.UTF_8)))))throw new IOException("豆包直传初始化发送失败");
            long sent=0,total=pcm.file.length(),started=System.nanoTime();byte[] buf=new byte[CHUNK_BYTES];
            try(FileInputStream in=new FileInputStream(pcm.file)){
                int n;while((n=in.read(buf))>=0){if(Thread.currentThread().isInterrupted())throw new InterruptedException("豆包直传任务已取消");throwIfFailed(failure);if(n==0)continue;
                    byte[] chunk=n==buf.length?buf:java.util.Arrays.copyOf(buf,n);sent+=n;boolean last=sent>=total;
                    while(ws.queueSize()>1024*1024){Thread.sleep(20);throwIfFailed(failure);}
                    if(!ws.send(ByteString.of(packet(last?0x22:0x20,0x00,chunk))))throw new IOException("豆包直传音频发送失败");
                    int percent=(int)Math.min(100,sent*100/Math.max(1,total));
                    if(listener!=null){long remainingAudioMs=Math.max(0,(total-sent)*1000L/(LocalAudioDecoder.SAMPLE_RATE*2L));long etaMs=remainingAudioMs/SEND_SPEED;listener.stage("豆包快速直传 "+percent+"% · 约剩 "+eta(etaMs));}
                    if(!last){long targetNs=(sent/2)*1_000_000_000L/(LocalAudioDecoder.SAMPLE_RATE*SEND_SPEED);long waitNs=targetNs-(System.nanoTime()-started);if(waitNs>0)TimeUnit.NANOSECONDS.sleep(waitNs);}
                }
            }
            if(!finished.await(180,TimeUnit.SECONDS))throw new IOException("豆包直传已发送完成，但等待最终结果超时");throwIfFailed(failure);
            JSONObject result=latest.get();if(result==null)throw new IOException("豆包直传没有返回最终分句结果");
            return DoubaoTranscriber.parse(result);
        }finally{ws.close(1000,"done");client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();}
    }

    /** Builds the official four-byte header + uint32 payload size + payload frame. */
    static byte[] packet(int typeAndFlags,int serializationAndCompression,byte[] payload){
        ByteBuffer b=ByteBuffer.allocate(8+payload.length).order(ByteOrder.BIG_ENDIAN);b.put((byte)0x11).put((byte)typeAndFlags).put((byte)serializationAndCompression).put((byte)0).putInt(payload.length).put(payload);return b.array();
    }

    static Packet parsePacket(byte[] data)throws Exception{
        if(data==null||data.length<8)throw new IOException("豆包返回了不完整的数据包");int headerBytes=(data[0]&15)*4,type=(data[1]>>>4)&15,flags=data[1]&15,compression=data[2]&15,pos=headerBytes;boolean terminal=false;
        if(headerBytes<4||headerBytes>data.length)throw new IOException("豆包返回了无效协议头");
        if(type==15){if(pos+8>data.length)throw new IOException("豆包返回了无效错误包");int code=readInt(data,pos);pos+=4;int size=readInt(data,pos);pos+=4;String message=decode(data,pos,size,compression);throw new IOException("豆包直传失败 · "+code+(message.isBlank()?"":" · "+message));}
        if((flags&1)!=0){if(pos+4>data.length)throw new IOException("豆包返回缺少序号");int sequence=readInt(data,pos);pos+=4;terminal=sequence<0;}
        if(pos+4>data.length)throw new IOException("豆包返回缺少长度");int size=readInt(data,pos);pos+=4;if(size<0||pos+size>data.length)throw new IOException("豆包返回长度不合法");
        String text=decode(data,pos,size,compression);JSONObject json=text.isBlank()?null:new JSONObject(text);return new Packet(json,terminal||(flags&2)!=0);
    }
    private static String decode(byte[] data,int offset,int size,int compression)throws Exception{byte[] raw=java.util.Arrays.copyOfRange(data,offset,offset+size);if(compression==1){try(GZIPInputStream in=new GZIPInputStream(new ByteArrayInputStream(raw));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>=0)out.write(b,0,n);raw=out.toByteArray();}}return new String(raw,StandardCharsets.UTF_8);}
    private static int readInt(byte[] data,int p){return ByteBuffer.wrap(data,p,4).order(ByteOrder.BIG_ENDIAN).getInt();}
    private static void throwIfFailed(AtomicReference<Throwable> failure)throws Exception{Throwable t=failure.get();if(t==null)return;if(t instanceof Exception)throw (Exception)t;throw new IOException(t);}
    private static String eta(long ms){long seconds=Math.max(0,(ms+999)/1000);return seconds>=60?(seconds/60)+"分"+(seconds%60)+"秒":seconds+"秒";}
    private static String safe(String s){return s==null?"":s.replaceAll("[\\r\\n]+"," ").trim();}
    static final class Packet{final JSONObject json;final boolean terminal;Packet(JSONObject j,boolean t){json=j;terminal=t;}}
}
