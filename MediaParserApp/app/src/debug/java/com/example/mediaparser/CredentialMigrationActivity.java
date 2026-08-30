package com.example.mediaparser;

import android.app.Activity;
import android.os.Bundle;
import android.util.Base64;

import com.example.mediaparser.cloud.OpenListSettings;
import com.example.mediaparser.subtitle.AliyunKeyStore;
import com.example.mediaparser.subtitle.AliyunSettings;
import com.example.mediaparser.subtitle.DoubaoCredentialStore;
import com.example.mediaparser.subtitle.GeminiKeyStore;
import com.example.mediaparser.subtitle.GroqKeyStore;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** One-time ADB migration helper compiled only into debug builds. It never logs plaintext credentials. */
public final class CredentialMigrationActivity extends Activity {
    private static final String FILE="credential-migration.bin",KEY_FILE="credential-migration-key.bin",STATUS="credential-migration-status.txt";
    @Override protected void onCreate(Bundle b){super.onCreate(b);new Thread(this::run).start();}
    private void run(){String state;byte[] key=null;try{String action=getIntent().getStringExtra("mode");if("check".equals(action)){JSONObject o=bundle();int configured=0;configured+=present(o.optString("gemini"));configured+=present(o.optString("groq"));configured+=present(o.optString("aliyun_key"));JSONObject d=o.optJSONObject("doubao");if(d!=null&&(present(d.optString("api_key"))==1||(present(d.optString("app_id"))==1&&present(d.optString("access_token"))==1)))configured++;JSONObject ol=o.optJSONObject("openlist");if(ol!=null&&present(ol.optString("server"))==1)configured++;state="OK check configured="+configured;}else{File keyFile=new File(getFilesDir(),KEY_FILE);key=Files.readAllBytes(keyFile.toPath());Files.deleteIfExists(keyFile.toPath());if(key.length!=32)throw new IllegalArgumentException("key must be 32 bytes");if("export".equals(action)){byte[] plain=bundle().toString().getBytes(StandardCharsets.UTF_8);Files.write(new File(getFilesDir(),FILE).toPath(),encrypt(key,plain));state="OK export";}else if("import".equals(action)){byte[] enc=Files.readAllBytes(new File(getFilesDir(),FILE).toPath());restore(new JSONObject(new String(decrypt(key,enc),StandardCharsets.UTF_8)));Files.deleteIfExists(new File(getFilesDir(),FILE).toPath());state="OK import";}else throw new IllegalArgumentException("invalid mode");}}catch(Exception e){state="ERROR "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}finally{if(key!=null)java.util.Arrays.fill(key,(byte)0);}try{Files.write(new File(getFilesDir(),STATUS).toPath(),state.getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}runOnUiThread(this::finish);}
    private static int present(String value){return value!=null&&!value.isBlank()?1:0;}
    private JSONObject bundle()throws Exception{JSONObject o=new JSONObject();o.put("gemini",GeminiKeyStore.load(this));o.put("groq",GroqKeyStore.load(this));o.put("aliyun_key",AliyunKeyStore.load(this));o.put("aliyun_workspace",AliyunSettings.workspace(this));DoubaoCredentialStore.Credentials d=DoubaoCredentialStore.load(this);JSONObject db=new JSONObject();db.put("api_key",d.apiKey);db.put("app_id",d.appId);db.put("access_token",d.accessToken);db.put("resource_id",d.resourceId);o.put("doubao",db);OpenListSettings.Config c=OpenListSettings.load(this);JSONObject ol=new JSONObject();ol.put("server",c.server);ol.put("token",c.token);ol.put("username",c.username);ol.put("password",c.password);o.put("openlist",ol);return o;}
    private void restore(JSONObject o)throws Exception{String v=o.optString("gemini");if(!v.isBlank())GeminiKeyStore.save(this,v);v=o.optString("groq");if(!v.isBlank())GroqKeyStore.save(this,v);v=o.optString("aliyun_key");if(!v.isBlank())AliyunKeyStore.save(this,v);v=o.optString("aliyun_workspace");if(!v.isBlank())AliyunSettings.saveWorkspace(this,v);JSONObject d=o.optJSONObject("doubao");if(d!=null){DoubaoCredentialStore.Credentials c=new DoubaoCredentialStore.Credentials(d.optString("api_key"),d.optString("app_id"),d.optString("access_token"),d.optString("resource_id"));if(c.configured())DoubaoCredentialStore.save(this,c);}JSONObject ol=o.optJSONObject("openlist");if(ol!=null&&!ol.optString("server").isBlank())OpenListSettings.save(this,new OpenListSettings.Config(ol.optString("server"),ol.optString("token"),ol.optString("username"),ol.optString("password")));getSharedPreferences("gemini_key_meta",0).edit().clear().apply();getSharedPreferences("groq_key_meta",0).edit().clear().apply();getSharedPreferences("aliyun_key_meta",0).edit().clear().apply();getSharedPreferences("doubao_key_meta",0).edit().clear().apply();}
    private static byte[] encrypt(byte[] key,byte[] plain)throws Exception{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));byte[] body=c.doFinal(plain),out=new byte[iv.length+body.length];System.arraycopy(iv,0,out,0,iv.length);System.arraycopy(body,0,out,iv.length,body.length);return out;}
    private static byte[] decrypt(byte[] key,byte[] enc)throws Exception{if(enc.length<29)throw new IllegalArgumentException("migration file truncated");byte[] iv=java.util.Arrays.copyOfRange(enc,0,12),body=java.util.Arrays.copyOfRange(enc,12,enc.length);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));return c.doFinal(body);}
}
