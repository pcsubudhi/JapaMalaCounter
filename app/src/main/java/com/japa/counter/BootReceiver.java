package com.japa.counter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "JapaBootReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "Boot completed - rescheduling alarm");
            
            // Check if alarm was enabled
            SharedPreferences prefs = context.getSharedPreferences("japa_alarm", Context.MODE_PRIVATE);
            boolean enabled = prefs.getBoolean("enabled", false);
            
            if (enabled) {
                String time = prefs.getString("time", "04:30");
                String audioPath = prefs.getString("audioPath", "");
                String audioSource = prefs.getString("audioSource", "tts");
                
                // Reschedule the alarm
                AlarmScheduler.scheduleAlarm(context, time, audioPath, audioSource);
                Log.d(TAG, "Alarm rescheduled for " + time);
            }
        }
    }
}
