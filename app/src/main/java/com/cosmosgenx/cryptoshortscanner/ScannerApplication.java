package com.cosmosgenx.cryptoshortscanner;

import android.app.Application;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

public class ScannerApplication extends Application {
    private static boolean firebaseConfigured = false;

    @Override public void onCreate() {
        super.onCreate();
        String appId = getStringResource("firebase_app_id");
        String apiKey = getStringResource("firebase_api_key");
        String projectId = getStringResource("firebase_project_id");
        String senderId = getStringResource("firebase_sender_id");
        if (!appId.isEmpty() && !apiKey.isEmpty() && !projectId.isEmpty() && !senderId.isEmpty()) {
            try {
                FirebaseOptions options = new FirebaseOptions.Builder()
                        .setApplicationId(appId)
                        .setApiKey(apiKey)
                        .setProjectId(projectId)
                        .setGcmSenderId(senderId)
                        .build();
                FirebaseApp.initializeApp(this, options);
                firebaseConfigured = true;
            } catch (Exception ignored) { firebaseConfigured = false; }
        }
    }

    private String getStringResource(String name) {
        int id=getResources().getIdentifier(name,"string",getPackageName());
        return id==0?"":getString(id).trim();
    }

    public static boolean isFirebaseConfigured() { return firebaseConfigured; }
}
