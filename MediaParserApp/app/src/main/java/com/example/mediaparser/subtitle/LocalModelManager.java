package com.example.mediaparser.subtitle;

import android.content.Context;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.*;

/** Verified, resumable model downloads. No model is bundled in the APK. */
public final class LocalModelManager {
    public enum Engine { SENSEVOICE, PARAFORMER }
    public static final class FileSpec { public final String name,url,sha256;public final long bytes;
        FileSpec(String n,String u,long b,String s){name=n;url=u;bytes=b;sha256=s;}}
    public static final class ModelSpec {
        public final String id,name,description;public final Engine engine;public final int installedMb;public final boolean archive;public final List<FileSpec> files;
        ModelSpec(String i,String n,String d,Engine e,int mb,boolean a,FileSpec...f){id=i;name=n;description=d;engine=e;installedMb=mb;archive=a;files=Collections.unmodifiableList(Arrays.asList(f));}
    }
    public interface Progress { void onProgress(String stage,long done,long total); }
    private static final String RELEASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/";
    private static final FileSpec SILERO_VAD=new FileSpec("silero_vad.onnx",RELEASE+"silero_vad.onnx",643854L,"9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6");
    public static final ModelSpec SENSEVOICE=new ModelSpec("sensevoice-2024","SenseVoice Small INT8（轻快）","约230MB；中英日韩粤，速度快，适合干净人声。",Engine.SENSEVOICE,230,true,
            new FileSpec("sensevoice.tar.bz2",RELEASE+"sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",163002883L,"7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e"));
    public static final ModelSpec FUNASR=new ModelSpec("funasr-nano","Fun-ASR-Nano 轻量转换版 INT8（小米13推荐）","官方 sherpa-onnx 轻量转换包；约253MB安装占用，兼顾中文质量与速度。",Engine.SENSEVOICE,253,true,
            new FileSpec("funasr-nano.tar.bz2",RELEASE+"sherpa-onnx-sense-voice-funasr-nano-int8-2025-12-17.tar.bz2",187693225L,"257936ea9a64cbe33200274e6367fc26d373ff6ca58b996b15108ffd6b9f6148"));
    public static final ModelSpec PARAFORMER=new ModelSpec("paraformer-trilingual","Paraformer 三语 INT8（中文增强）","整包下载约1.0GB；安装仅保留INT8模型，中文/英文/粤语和标点较强。",Engine.PARAFORMER,245,true,
            new FileSpec("paraformer.tar.bz2",RELEASE+"sherpa-onnx-paraformer-trilingual-zh-cantonese-en.tar.bz2",1059453702L,"78359a14d367407c9a0b4122daffe367399f8342ac1e55f93a5490dd5895c100"));
    public static final List<ModelSpec> CATALOG=Collections.unmodifiableList(Arrays.asList(SENSEVOICE,FUNASR,PARAFORMER));
    private LocalModelManager(){}
    public static ModelSpec find(String id){for(ModelSpec s:CATALOG)if(s.id.equals(id))return s;return FUNASR;}
    public static ModelSpec selected(Context c){return find(c.getSharedPreferences("local_asr",0).getString("model",FUNASR.id));}
    public static void select(Context c,ModelSpec s){if(!isInstalled(c,s))throw new IllegalStateException("请先下载并校验 "+s.name);c.getSharedPreferences("local_asr",0).edit().putString("model",s.id).apply();}
    public static File dir(Context c,ModelSpec s){return new File(new File(c.getFilesDir(),"asr_models"),s.id);}
    public static boolean isInstalled(Context c,ModelSpec s){File d=dir(c,s);File m=findFile(d,"model.int8.onnx");if(m==null)m=findAnyOnnx(d);File t=findFile(d,"tokens.txt");return m!=null&&m.length()>50_000_000L&&t!=null&&t.length()>10_000L&&new File(d,".verified").isFile();}
    /** Bytes already downloaded into the resumable staging directory. */
    public static long partialBytes(Context c,ModelSpec s){File root=new File(c.getFilesDir(),"asr_models"),temp=new File(root,s.id+".installing");long n=0;for(FileSpec f:s.files){File done=new File(temp,f.name),part=new File(temp,f.name+".part");File present=done.isFile()?done:part;n+=Math.min(f.bytes,present.isFile()?present.length():0);}return n;}
    public static void delete(Context c,ModelSpec s){deleteTree(dir(c,s));if(selected(c).id.equals(s.id))c.getSharedPreferences("local_asr",0).edit().remove("model").apply();}
    public static long downloadBytes(ModelSpec s){long n=0;for(FileSpec f:s.files)n+=f.bytes;return n;}
    /** Downloads the small shared sentence-boundary model once. It is deliberately not bundled in the APK. */
    public static File ensureVad(Context c,Progress progress)throws Exception{
        File shared=new File(new File(c.getFilesDir(),"asr_models"),"shared");shared.mkdirs();
        File done=new File(shared,SILERO_VAD.name);
        if(done.isFile()&&done.length()==SILERO_VAD.bytes&&sha256(done).equalsIgnoreCase(SILERO_VAD.sha256))return done;
        if(done.exists())done.delete();File part=new File(shared,SILERO_VAD.name+".part");
        if(part.isFile()&&part.length()==SILERO_VAD.bytes&&sha256(part).equalsIgnoreCase(SILERO_VAD.sha256)){if(!part.renameTo(done))throw new IOException("无法保存本地分句模型");return done;}
        if(progress!=null)progress.onProgress("下载本地分句模型",Math.min(SILERO_VAD.bytes,part.isFile()?part.length():0),SILERO_VAD.bytes);
        download(SILERO_VAD,part,(n,total)->{if(progress!=null)progress.onProgress("下载本地分句模型",n,total);});
        if(part.length()!=SILERO_VAD.bytes){part.delete();throw new IOException("本地分句模型大小不符");}
        if(progress!=null)progress.onProgress("校验本地分句模型",SILERO_VAD.bytes,SILERO_VAD.bytes);
        if(!sha256(part).equalsIgnoreCase(SILERO_VAD.sha256)){part.delete();throw new IOException("本地分句模型校验失败");}
        if(!part.renameTo(done))throw new IOException("无法保存本地分句模型");return done;
    }
    public static void install(Context c,ModelSpec spec,Progress progress)throws Exception{
        File root=new File(c.getFilesDir(),"asr_models");root.mkdirs();File temp=new File(root,spec.id+".installing");temp.mkdirs();
        try{
            long all=downloadBytes(spec),base=0;
            for(FileSpec f:spec.files){File part=new File(temp,f.name+".part");File done=new File(temp,f.name);final long fileBase=base;
                if(!(done.isFile()&&done.length()==f.bytes&&sha256(done).equalsIgnoreCase(f.sha256))){if(done.exists())done.delete();if(progress!=null)progress.onProgress("准备下载",fileBase+Math.min(f.bytes,part.isFile()?part.length():0),all);download(f,part,(n,total)->{if(progress!=null)progress.onProgress("正在下载",fileBase+n,all);});
                    if(part.length()!=f.bytes){part.delete();throw new IOException("模型文件大小不符："+f.name);}if(progress!=null)progress.onProgress("正在校验 SHA-256",fileBase+f.bytes,all);String hash=sha256(part);if(!hash.equalsIgnoreCase(f.sha256)){part.delete();throw new IOException("模型校验失败："+f.name);}
                    if(!part.renameTo(done))throw new IOException("无法完成模型文件："+f.name);
                }base+=f.bytes;
            }
            if(spec.archive){File arc=new File(temp,spec.files.get(0).name);if(progress!=null)progress.onProgress("校验通过，正在安全解压",all,all);extractSelected(arc,temp);arc.delete();}
            File model=findFile(temp,"model.int8.onnx");if(model==null)model=findAnyOnnx(temp);File tokens=findFile(temp,"tokens.txt");
            if(model==null||model.length()<50_000_000L||tokens==null||tokens.length()<10_000L)throw new IOException("模型包缺少INT8模型或tokens.txt");
            try(PrintWriter notice=new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(temp,"MODEL_SOURCE.txt")),java.nio.charset.StandardCharsets.UTF_8))){notice.println(spec.name);notice.println("Runtime: sherpa-onnx 1.13.4 (Apache-2.0)");for(FileSpec f:spec.files)notice.println(f.url+"\nsha256="+f.sha256);notice.println("Model license and documentation: https://github.com/k2-fsa/sherpa-onnx and the source model repository.");}
            new File(temp,".verified").createNewFile();File target=dir(c,spec);deleteTree(target);if(!temp.renameTo(target))throw new IOException("无法安装模型");
            try{ensureVad(c,(stage,done,total)->{if(progress!=null)progress.onProgress(stage,done,total);});if(progress!=null)progress.onProgress("安装完成",all,all);}
            catch(Exception vadError){if(progress!=null)progress.onProgress("模型已安装；分句组件将在首次识别时重试",all,all);}
        }catch(Exception e){throw e;}
    }
    private interface Bytes{void value(long n,long total);}
    private static void download(FileSpec f,File part,Bytes cb)throws Exception{
        long have=part.isFile()?part.length():0;if(have>f.bytes){part.delete();have=0;}int redirects=0;URL url=new URL(f.url);
        if(cb!=null)cb.value(have,f.bytes);
        while(true){HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(20000);c.setReadTimeout(60000);c.setInstanceFollowRedirects(false);c.setRequestProperty("User-Agent","MediaParser/0.2.0");if(have>0)c.setRequestProperty("Range","bytes="+have+"-");
            int code=c.getResponseCode();if(code/100==3&&redirects++<8){String loc=c.getHeaderField("Location");c.disconnect();if(loc==null)throw new IOException("模型下载重定向缺少地址");url=new URL(url,loc);continue;}
            if(have>0&&code==200){c.disconnect();part.delete();have=0;continue;}if(code!=200&&code!=206){String msg="HTTP "+code;c.disconnect();throw new IOException("模型下载失败："+msg);}
            try(InputStream in=new BufferedInputStream(c.getInputStream());OutputStream out=new BufferedOutputStream(new FileOutputStream(part,have>0),256*1024)){byte[] b=new byte[128*1024];int n;long done=have;while((n=in.read(b))>=0){if(Thread.currentThread().isInterrupted())throw new InterruptedException("模型下载已取消");out.write(b,0,n);done+=n;if(cb!=null)cb.value(done,f.bytes);}}finally{c.disconnect();}break;
        }
    }
    private static void extractSelected(File archive,File dest)throws Exception{
        long expanded=0;try(TarArchiveInputStream tar=new TarArchiveInputStream(new BZip2CompressorInputStream(new BufferedInputStream(new FileInputStream(archive))))){TarArchiveEntry e;byte[] b=new byte[128*1024];while((e=tar.getNextTarEntry())!=null){if(!e.isFile())continue;String name=new File(e.getName()).getName();boolean keep=name.equals("model.int8.onnx")||name.equals("tokens.txt")||name.toLowerCase(Locale.ROOT).startsWith("license")||name.toLowerCase(Locale.ROOT).startsWith("readme");if(!keep)continue;File out=new File(dest,name);String root=dest.getCanonicalPath()+File.separator;if(!out.getCanonicalPath().startsWith(root))throw new IOException("模型包路径不安全");try(OutputStream os=new BufferedOutputStream(new FileOutputStream(out))){int n;while((n=tar.read(b))>=0){os.write(b,0,n);expanded+=n;if(expanded>900_000_000L)throw new IOException("模型解压超过安全上限");}}}}
    }
    public static File findFile(File root,String name){if(root==null||!root.exists())return null;File[] files=root.listFiles();if(files==null)return null;for(File f:files){if(f.isFile()&&f.getName().equals(name))return f;if(f.isDirectory()){File x=findFile(f,name);if(x!=null)return x;}}return null;}
    private static File findAnyOnnx(File root){if(root==null||!root.exists())return null;File[] fs=root.listFiles();if(fs==null)return null;for(File f:fs){if(f.isFile()&&f.getName().endsWith(".onnx")&&!f.getName().contains("fp32"))return f;if(f.isDirectory()){File x=findAnyOnnx(f);if(x!=null)return x;}}return null;}
    private static String sha256(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new BufferedInputStream(new FileInputStream(f))){byte[] b=new byte[128*1024];int n;while((n=in.read(b))>=0)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte x:d.digest())s.append(String.format(Locale.ROOT,"%02x",x));return s.toString();}
    private static void deleteTree(File f){if(f==null||!f.exists())return;if(f.isDirectory()){File[] a=f.listFiles();if(a!=null)for(File x:a)deleteTree(x);}f.delete();}
}
