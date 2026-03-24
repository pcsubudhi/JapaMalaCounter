package com.japa.counter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
        
        Log.d(TAG, "audioPath: " + audioPath);
        Log.d(TAG, "audioSource: " + audioSource);
        
        // Start foreground immediately
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
            
            // Set audio attributes for alarm
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                );
            }
            
            if (audioPath != null && !audioPath.isEmpty()) {
                // Play from file - use Uri for content:// paths
                mediaPlayer.setDataSource(this, Uri.parse(audioPath));
                Log.d(TAG, "Playing from: " + audioPath);
            } else {
                // No audio path
                Log.d(TAG, "No audio path, stopping");
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
        // Intent to open app
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        // Intent to stop alarm - FIXED: use ACTION_STOP constant
        Intent stopIntent = new Intent(this, AlarmService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("🙏 Japa Time!")
            .setContentText("Hare Krishna! Time for your morning japa.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_delete, "STOP", stopPendingIntent)
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
            } catch (Exception e) {}
            mediaPlayer = null;
        }
        
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        
        super.onDestroy();
    }
    
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }
}
