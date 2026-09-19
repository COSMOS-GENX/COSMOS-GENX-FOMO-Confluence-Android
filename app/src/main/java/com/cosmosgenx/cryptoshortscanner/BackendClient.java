package com.cosmosgenx.cryptoshortscanner;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BackendClient {
    private static final String PREF="scanner_backend";
    private static final String KEY_URL="base_url";
    private static final String KEY_API="api_key";
    private static final ExecutorService IO= Executors.newSingleThreadExecutor();
    private BackendClient() {}

    public static String getBaseUrl(Context c) {
        return c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY_URL,"").trim().replaceAll("/+$","");
    }
    public static void setBaseUrl(Context c,String url) {
        c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString(KEY_URL,url==null?"":url.trim().replaceAll("/+$","")).apply();
    }
    public static String getApiKey(Context c) {
        return c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString(KEY_API,"").trim();
    }
    public static void setApiKey(Context c,String key) {
        c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().putString(KEY_API,key==null?"":key.trim()).apply();
    }
    public static boolean isConfigured(Context c) { return getBaseUrl(c).startsWith("https://"); }

    public static void registerDevice(Context c,String token) {
        if(!isConfigured(c) || token==null || token.isEmpty()) return;
        IO.execute(() -> post(c,"/devices/register",new JSONObjectBuilder().put("token",token).put("platform","android").json()));
    }
    public static void triggerRemoteScan(Context c) {
        if(!isConfigured(c)) return;
        IO.execute(() -> post(c,"/scan-now",new JSONObjectBuilder().json()));
    }
    private static void post(Context c,String path,JSONObject body) {
        HttpURLConnection conn=null;
        try {
            conn=(HttpURLConnection)new URL(getBaseUrl(c)+path).openConnection();
            conn.setConnectTimeout(7000); conn.setReadTimeout(12000); conn.setRequestMethod("POST"); conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type","application/json"); conn.setRequestProperty("User-Agent","CryptoShortScanner/3.0");
            String api=getApiKey(c); if(!api.isEmpty()) conn.setRequestProperty("X-Scanner-Key",api);
            byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
            try(OutputStream os=conn.getOutputStream()){ os.write(bytes); }
            conn.getResponseCode();
        } catch(Exception ignored) {} finally { if(conn!=null) conn.disconnect(); }
    }

    private static final class JSONObjectBuilder {
        final JSONObject o=new JSONObject();
        JSONObjectBuilder put(String k,String v){ try{o.put(k,v);}catch(Exception ignored){} return this; }
        JSONObject json(){ return o; }
    }
}
