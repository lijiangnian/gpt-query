package com.example.mediaparser.subtitle;
import android.content.Context;
public final class AliyunSettings {
    private AliyunSettings(){}
    public static void saveWorkspace(Context c,String value){String v=value==null?"":value.trim();if(!v.matches("[A-Za-z0-9-]{3,80}"))throw new IllegalArgumentException("Workspace ID 格式不正确");c.getSharedPreferences("aliyun_cloud_settings",0).edit().putString("workspace",v).apply();}
    public static String workspace(Context c){return c.getSharedPreferences("aliyun_cloud_settings",0).getString("workspace","");}
    public static void clear(Context c){AliyunKeyStore.clear(c);c.getSharedPreferences("aliyun_cloud_settings",0).edit().remove("workspace").apply();}
}
