package com.japa.counter;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;

public class AlarmScheduler {
    private static final String TAG = "JapaAlarmScheduler";
    private static final int ALARM_REQUEST_CODE = 1001;
    
    public static void scheduleAlarm(Context context, String time, String audioPath, String audioSource) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        
        // Parse time (HH:mm format)
        String[] parts = time.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        
        // Set calendar for alarm time
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        
        // If time has passed today, schedule for tomorrow
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }
        
        // Create intent for alarm receiver
        Intent intent = new Intent(context, AlarmReceiver.class);
        intent.putExtra("audioPath", audioPath);
        intent.putExtra("audioSource", audioSource);
        
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        // Schedule exact alarm
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ requires checking permission
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAlarmClock(
                        new AlarmManager.AlarmClockInfo(calendar.getTimeInMillis(), pendingIntent),
                        pendingIntent
                    );
                } else {
                    // Fallback to inexact alarm
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        calendar.getTimeInMillis(),
                        pendingIntent
                    );
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAlarmClock(
                    new AlarmManager.AlarmClockInfo(calendar.getTimeInMillis(), pendingIntent),
                    pendingIntent
                );
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    calendar.getTimeInMillis(),
                    pendingIntent
                );
            }
            
            Log.d(TAG, "Alarm scheduled for: " + calendar.getTime().toString());
            
            // Save alarm settings
            saveAlarmSettings(context, time, audioPath, audioSource, true);
            
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling alarm: " + e.getMessage());
        }
    }
    
    public static void cancelAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        
        Intent intent = new Intent(context, AlarmReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        alarmManager.cancel(pendingIntent);
        
        // Update saved settings
        SharedPreferences prefs = context.getSharedPreferences("japa_alarm", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("enabled", false).apply();
        
        Log.d(TAG, "Alarm cancelled");
    }
    
    public static void stopAlarmService(Context context) {
        Intent serviceIntent = new Intent(context, AlarmService.class);
        context.stopService(serviceIntent);
    }
    
    private static void saveAlarmSettings(Context context, String time, String audioPath, String audioSource, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences("japa_alarm", Context.MODE_PRIVATE);
        prefs.edit()
            .putBoolean("enabled", enabled)
            .putString("time", time)
            .putString("audioPath", audioPath)
            .putString("audioSource", audioSource)
            .apply();
    }
}
