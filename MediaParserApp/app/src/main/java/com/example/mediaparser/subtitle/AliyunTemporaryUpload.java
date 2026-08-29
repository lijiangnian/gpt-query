package com.example.mediaparser.subtitle;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Official DashScope temporary OSS upload. Files expire automatically after 48 hours. */
final class AliyunTemporaryUpload {
    interface Progress{void stage(String text);}
    private static final String POLICY="https://dashscope.aliyuncs.com/api/v1/uploads";
    private AliyunTemporaryUpload(){}

    static String upload(File file,String key,String model,Progress progress)throws Exception{
        if(file==null||!file.isFile()||file.length()==0)throw new IllegalArgumentException("阿里云临时上传文件为空");
        if(file.length()>1024L*1024*1024)throw new IllegalArgumentException("阿里云临时上传单文件不能超过1GB");
        if(progress!=null)progress.stage("获取阿里云官方临时上传凭证…");
        JSONObject p=policy(key,model);String host=p.getString("upload_host");URI uri=URI.create(host);String h=uri.getHost();if(!"https".equalsIgnoreCase(uri.getScheme())||h==null||!h.toLowerCase(Locale.ROOT).endsWith("aliyuncs.com"))throw new IllegalStateException("阿里云返回了不受信任的上传地址");
        String object=p.getString("upload_dir")+"/mediaparser_"+System.currentTimeMillis()+".m4a",boundary="MediaParser"+UUID.randomUUID().toString().replace("-","");
        ByteArrayOutputStream before=new ByteArrayOutputStream();field(before,boundary,"OSSAccessKeyId",p.getString("oss_access_key_id"));field(before,boundary,"Signature",p.getString("signature"));field(before,boundary,"policy",p.getString("policy"));field(before,boundary,"x-oss-object-acl",p.getString("x_oss_object_acl"));field(before,boundary,"x-oss-forbid-overwrite",p.getString("x_oss_forbid_overwrite"));field(before,boundary,"key",object);field(before,boundary,"success_action_status","200");
        before.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));byte[] prefix=before.toByteArray(),suffix=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();try{c.setRequestMethod("POST");c.setConnectTimeout(20000);c.setReadTimeout(180000);c.setInstanceFollowRedirects(false);c.setDoOutput(true);c.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);c.setFixedLengthStreamingMode(prefix.length+file.length()+suffix.length);
            if(progress!=null)progress.stage("上传到阿里云临时存储 0%");try(OutputStream out=c.getOutputStream();FileInputStream in=new FileInputStream(file)){out.write(prefix);byte[]b=new byte[64*1024];long done=0;int n;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException("阿里云临时上传已取消");out.write(b,0,n);done+=n;if(progress!=null)progress.stage("上传到阿里云临时存储 "+Math.min(100,done*100/Math.max(1,file.length()))+"%");}out.write(suffix);}int code=c.getResponseCode();String body=read(code>=400?c.getErrorStream():c.getInputStream());if(code<200||code>=300)throw new java.io.IOException("阿里云临时上传失败 HTTP "+code+(body.isBlank()?"":"："+body));
        }finally{c.disconnect();}if(progress!=null)progress.stage("临时文件已上传 · 48小时后自动清理");return"oss://"+object;
    }

    private static JSONObject policy(String key,String model)throws Exception{String q="?action=getPolicy&model="+URLEncoder.encode(model,StandardCharsets.UTF_8.name());HttpURLConnection c=(HttpURLConnection)URI.create(POLICY+q).toURL().openConnection();try{c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("Authorization","Bearer "+key);c.setRequestProperty("Accept","application/json");int code=c.getResponseCode();String body=read(code>=400?c.getErrorStream():c.getInputStream());if(code<200||code>=300)throw new java.io.IOException("获取阿里云临时上传凭证失败 HTTP "+code);JSONObject root=new JSONObject(body);JSONObject data=root.optJSONObject("data");if(data==null)throw new java.io.IOException("阿里云临时上传凭证缺少 data");return data;}finally{c.disconnect();}}
    private static void field(OutputStream out,String boundary,String name,String value)throws Exception{out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value+"\r\n").getBytes(StandardCharsets.UTF_8));}
    private static String read(InputStream in)throws Exception{if(in==null)return"";try(InputStream x=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[]b=new byte[8192];int n;while((n=x.read(b))!=-1){out.write(b,0,n);if(out.size()>1024*1024)break;}return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
}
