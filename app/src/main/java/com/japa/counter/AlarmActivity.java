package com.japa.counter;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.util.TypedValue;

public class AlarmActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager != null) {
                keyguardManager.requestDismissKeyguard(this, null);
            }
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        }
        
        // Full screen
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        
        // Create UI programmatically
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setBackgroundColor(Color.parseColor("#1a1a2e"));
        layout.setPadding(48, 48, 48, 48);
        
        // Time text
        TextView timeText = new TextView(this);
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
        timeText.setText(sdf.format(new java.util.Date()));
        timeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 72);
        timeText.setTextColor(Color.WHITE);
        timeText.setGravity(Gravity.CENTER);
        layout.addView(timeText);
        
        // Title
        TextView titleText = new TextView(this);
        titleText.setText("🙏 Japa Time");
        titleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        titleText.setTextColor(Color.parseColor("#FF9800"));
        titleText.setGravity(Gravity.CENTER);
        titleText.setPadding(0, 32, 0, 16);
        layout.addView(titleText);
        
        // Subtitle
        TextView subtitleText = new TextView(this);
        subtitleText.setText("Hare Krishna! Time for your morning japa.");
        subtitleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        subtitleText.setTextColor(Color.parseColor("#CCCCCC"));
        subtitleText.setGravity(Gravity.CENTER);
        subtitleText.setPadding(0, 0, 0, 64);
        layout.addView(subtitleText);
        
        // Button container
        LinearLayout buttonLayout = new LinearLayout(this);
        buttonLayout.setOrientation(LinearLayout.HORIZONTAL);
        buttonLayout.setGravity(Gravity.CENTER);
        
        // Snooze button
        Button snoozeBtn = new Button(this);
        snoozeBtn.setText("Snooze\n5 min");
        snoozeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        snoozeBtn.setTextColor(Color.BLACK);
        snoozeBtn.setBackgroundColor(Color.parseColor("#E0E0E0"));
        snoozeBtn.setPadding(48, 32, 48, 32);
        LinearLayout.LayoutParams snoozeParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        snoozeParams.setMargins(16, 0, 16, 0);
        snoozeBtn.setLayoutParams(snoozeParams);
        snoozeBtn.setOnClickListener(v -> {
            snoozeAlarm();
        });
        buttonLayout.addView(snoozeBtn);
        
        // Stop button
        Button stopBtn = new Button(this);
        stopBtn.setText("Stop");
        stopBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        stopBtn.setTextColor(Color.WHITE);
        stopBtn.setBackgroundColor(Color.parseColor("#FF5722"));
        stopBtn.setPadding(64, 32, 64, 32);
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        stopParams.setMargins(16, 0, 16, 0);
        stopBtn.setLayoutParams(stopParams);
        stopBtn.setOnClickListener(v -> {
            stopAlarm();
        });
        buttonLayout.addView(stopBtn);
        
        layout.addView(buttonLayout);
        
        setContentView(layout);
    }
    
    private void stopAlarm() {
        // Stop the alarm service
        Intent stopIntent = new Intent(this, AlarmService.class);
        stopIntent.setAction(AlarmService.ACTION_STOP);
        startService(stopIntent);
        
        finish();
    }
    
    private void snoozeAlarm() {
        // Stop current alarm
        Intent stopIntent = new Intent(this, AlarmService.class);
        stopIntent.setAction(AlarmService.ACTION_STOP);
        startService(stopIntent);
        
        // Schedule new alarm in 5 minutes
        // For now, just stop - can add snooze logic later
        finish();
    }
    
    @Override
    public void onBackPressed() {
        // Don't allow back button to dismiss alarm
        // User must tap Stop or Snooze
    }
}
