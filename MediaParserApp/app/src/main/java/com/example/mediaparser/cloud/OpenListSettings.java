package com.example.mediaparser.cloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** OpenList credentials encrypted with a non-exportable Android Keystore key. */
public final class OpenListSettings {
    private static final String PREFS="openlist_settings_v1", IV="iv", DATA="data", ALIAS="mediaparser_openlist_v1";
    private OpenListSettings() {}
    public static final class Config {
        public final String server,token,username,password;
        public Config(String server,String token,String username,String password){this.server=normalize(server);this.token=n(token);this.username=n(username);this.password=n(password);}
        public boolean configured(){return !server.isBlank();}
        public String masked(){if(!token.isBlank())return mask(token);if(!username.isBlank())return username+" · 密码已保存";return"匿名访问";}
    }
    public static void save(Context c,Config v)throws Exception{if(v==null||v.server.isBlank())throw new IllegalArgumentException("服务器地址不能为空");if(!v.server.startsWith("https://"))throw new IllegalArgumentException("为保护网盘凭证，仅允许 HTTPS OpenList 地址");JSONObject o=new JSONObject();o.put("server",v.server);o.put("token",v.token);o.put("username",v.username);o.put("password",v.password);SecretKey k=key();Cipher cp=Cipher.getInstance("AES/GCM/NoPadding");cp.init(Cipher.ENCRYPT_MODE,k);byte[] enc=cp.doFinal(o.toString().getBytes(StandardCharsets.UTF_8));c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(IV,Base64.encodeToString(cp.getIV(),Base64.NO_WRAP)).putString(DATA,Base64.encodeToString(enc,Base64.NO_WRAP)).apply();}
    public static Config load(Context c){try{SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);String iv=p.getString(IV,""),data=p.getString(DATA,"");if(iv==null||data==null||iv.isBlank()||data.isBlank())return new Config("","","","");KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);SecretKey k=(SecretKey)ks.getKey(ALIAS,null);if(k==null)return new Config("","","","");Cipher cp=Cipher.getInstance("AES/GCM/NoPadding");cp.init(Cipher.DECRYPT_MODE,k,new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));JSONObject o=new JSONObject(new String(cp.doFinal(Base64.decode(data,Base64.NO_WRAP)),StandardCharsets.UTF_8));return new Config(o.optString("server"),o.optString("token"),o.optString("username"),o.optString("password"));}catch(Exception e){return new Config("","","","");}}
    public static void clear(Context c){c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply();}
    private static SecretKey key()throws Exception{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);java.security.Key old=ks.getKey(ALIAS,null);if(old instanceof SecretKey)return(SecretKey)old;KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());return g.generateKey();}
    private static String normalize(String s){String v=n(s);while(v.endsWith("/"))v=v.substring(0,v.length()-1);return v;}
    private static String n(String s){return s==null?"":s.trim();}private static String mask(String s){return s.length()<9?"••••••••":s.substring(0,4)+"••••"+s.substring(s.length()-4);}
}
