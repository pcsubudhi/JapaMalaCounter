package com.japa.counter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "JapaAlarm";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Alarm received!");
        
        // Get audio file path from intent
        String audioPath = intent.getStringExtra("audioPath");
        String audioSource = intent.getStringExtra("audioSource");
        
        // Start the foreground service to play music
        Intent serviceIntent = new Intent(context, AlarmService.class);
        serviceIntent.putExtra("audioPath", audioPath);
        serviceIntent.putExtra("audioSource", audioSource);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }
}
