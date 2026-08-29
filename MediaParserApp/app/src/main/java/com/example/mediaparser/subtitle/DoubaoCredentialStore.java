package com.example.mediaparser.subtitle;

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

/** Encrypts all Volcengine speech credentials as one Android-Keystore protected record. */
public final class DoubaoCredentialStore {
    private static final String PREFS="doubao_cloud_settings",IV="credentials_iv",DATA="credentials_ciphertext";
    private static final String ALIAS="mediaparser_doubao_credentials_v1";
    public static final String DEFAULT_RESOURCE="volc.bigasr.auc";
    private DoubaoCredentialStore(){}

    public static final class Credentials {
        public final String apiKey,appId,accessToken,resourceId;
        public Credentials(String apiKey,String appId,String accessToken,String resourceId){
            this.apiKey=clean(apiKey);this.appId=clean(appId);this.accessToken=clean(accessToken);
            this.resourceId=clean(resourceId).isBlank()?DEFAULT_RESOURCE:clean(resourceId);
        }
        public boolean configured(){return !apiKey.isBlank()||(!appId.isBlank()&&!accessToken.isBlank());}
        public String masked(){String x=!apiKey.isBlank()?apiKey:accessToken;if(x.isBlank())return "";return x.length()<=8?"••••••••":x.substring(0,4)+"••••"+x.substring(x.length()-4);}
    }
    public static void save(Context c,Credentials v)throws Exception{
        if(v==null||!v.configured())throw new IllegalArgumentException("请填写 API Key，或同时填写 App ID 与 Access Token");
        JSONObject json=new JSONObject().put("api_key",v.apiKey).put("app_id",v.appId).put("access_token",v.accessToken).put("resource_id",v.resourceId);
        SecretKey key=key();Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);
        byte[] encrypted=cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
        c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(IV,Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).putString(DATA,Base64.encodeToString(encrypted,Base64.NO_WRAP)).apply();
    }
    public static Credentials load(Context c){
        try{SharedPreferences p=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);String iv=p.getString(IV,""),data=p.getString(DATA,"");if(iv==null||iv.isBlank()||data==null||data.isBlank())return new Credentials("","","",DEFAULT_RESOURCE);
            KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);SecretKey key=(SecretKey)ks.getKey(ALIAS,null);if(key==null)return new Credentials("","","",DEFAULT_RESOURCE);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
            JSONObject j=new JSONObject(new String(cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)),StandardCharsets.UTF_8));return new Credentials(j.optString("api_key"),j.optString("app_id"),j.optString("access_token"),j.optString("resource_id",DEFAULT_RESOURCE));
        }catch(Exception ignored){return new Credentials("","","",DEFAULT_RESOURCE);}
    }
    public static void clear(Context c){c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply();}
    private static SecretKey key()throws Exception{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);java.security.Key existing=ks.getKey(ALIAS,null);if(existing instanceof SecretKey)return(SecretKey)existing;KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());return g.generateKey();}
    private static String clean(String s){return s==null?"":s.trim();}
}
