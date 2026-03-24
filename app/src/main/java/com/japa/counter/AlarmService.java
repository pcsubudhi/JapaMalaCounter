package com.japa.counter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class AlarmService extends Service {
    private static final String TAG = "JapaAlarmService";
    private static final String CHANNEL_ID = "japa_alarm_channel";
    private static final int NOTIFICATION_ID = 1001;
    public static final String ACTION_STOP = "com.japa.counter.STOP_ALARM";
    
    private MediaPlayer mediaPlayer;
    private PowerManager.WakeLock wakeLock;
    
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        
        // Acquire wake lock to keep CPU running
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "japa:alarm");
        wakeLock.acquire(30 * 60 * 1000L); // 30 minutes max
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "AlarmService onStartCommand");
        
        // Check if this is a stop action
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.d(TAG, "Stop action received - stopping service");
            stopSelf();
            return START_NOT_STICKY;
        }
        
        String audioPath = intent != null ? intent.getStringExtra("audioPath") : null;
        String audioSource = intent != null ? intent.getStringExtra("audioSource") : "tts";
        
        // Start foreground immediately with full-screen notification
        startForeground(NOTIFICATION_ID, createNotification());
        
        // Play the audio
        playAudio(audioPath, audioSource);
        
        return START_NOT_STICKY;
    }
    
    private void playAudio(String audioPath, String audioSource) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
            }
            
            mediaPlayer = new MediaPlayer();
            
            // Set audio attributes for alarm (plays even in silent mode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                );
            }
            
            if (audioPath != null && !audioPath.isEmpty()) {
                // Play from file
                mediaPlayer.setDataSource(this, Uri.parse(audioPath));
                Log.d(TAG, "Playing from: " + audioPath);
            } else {
                Log.d(TAG, "No audio path provided");
                stopSelf();
                return;
            }
            
            mediaPlayer.setLooping(true);
            mediaPlayer.setOnPreparedListener(mp -> {
                mp.start();
                Log.d(TAG, "Audio playing");
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
                stopSelf();
                return true;
            });
            mediaPlayer.prepareAsync();
            
        } catch (Exception e) {
            Log.e(TAG, "Error playing audio: " + e.getMessage());
            stopSelf();
        }
    }
    
    private Notification createNotification() {
        // Intent to open app when notification tapped
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        // Intent to stop alarm - send to service directly
        Intent stopIntent = new Intent(this, AlarmService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        // Full screen intent for lock screen
        Intent fullScreenIntent = new Intent(this, MainActivity.class);
        fullScreenIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
            this, 2, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("🙏 Japa Time!")
            .setContentText("Hare Krishna! Tap STOP to silence.")
            .setStyle(new NotificationCompat.BigTextStyle()
                .bigText("Hare Krishna! Time for your morning japa.\n\nTap STOP ALARM button below to silence."))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(android.R.drawable.ic_delete, "⏹ STOP ALARM", stopPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false);
        
        return builder.build();
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Japa Alarm",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Wake-up bhajan alarm");
            channel.setSound(null, null); // We handle sound ourselves
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 500, 200, 500});
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setBypassDnd(true); // Bypass Do Not Disturb
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        Log.d(TAG, "AlarmService stopped");
        
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing media player: " + e.getMessage());
            }
            mediaPlayer = null;
        }
        
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        // Cancel notification
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
        
        super.onDestroy();
    }
}
