package com.japa.counter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

public class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "JapaAlarm";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Alarm received!");
        
        // Get audio file path from SharedPreferences (more reliable than intent extras)
        SharedPreferences prefs = context.getSharedPreferences("japa_alarm", Context.MODE_PRIVATE);
        String audioPath = prefs.getString("audioPath", "");
        String audioSource = prefs.getString("audioSource", "tts");
        
        Log.d(TAG, "Audio path from prefs: " + audioPath);
        Log.d(TAG, "Audio source: " + audioSource);
        
        // Start the foreground service to play music
        Intent serviceIntent = new Intent(context, AlarmService.class);
        serviceIntent.putExtra("audioPath", audioPath);
        serviceIntent.putExtra("audioSource", audioSource);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
        
        // Reschedule for tomorrow (daily alarm)
        String time = prefs.getString("time", "05:00");
        boolean enabled = prefs.getBoolean("enabled", false);
        if (enabled) {
            AlarmScheduler.scheduleAlarm(context, time, audioPath, audioSource);
            Log.d(TAG, "Rescheduled alarm for tomorrow at " + time);
        }
    }
}
