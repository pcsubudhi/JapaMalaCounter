package com.japa.counter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "JapaCounter";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int SERVER_PORT = 8899;

    private WebView webView;
    private PowerManager.WakeLock wakeLock;
    private Vibrator vibrator;
    private LocalServer localServer;
    private MediaPlayer mediaPlayer;
    private AudioManager audioManager;
    private Handler mainHandler;

    // Speech Recognition
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean isListening = false;
    private boolean shouldContinueListening = false;
    private String targetWord = "hare";
    private long lastWordTime = 0;
    private String lastPartialResult = "";

    // Bluetooth SCO
    private boolean btScoStarted = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mainHandler = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF1a1a2e);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JapaCounter::Chanting");
        }

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        try {
            localServer = new LocalServer(SERVER_PORT, getAssets());
            localServer.start();
        } catch (IOException e) {
            Toast.makeText(this, "Server error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        webView = new WebView(this);
        webView.setFitsSystemWindows(true);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                mainHandler.post(() -> request.grant(request.getResources()));
            }
        });

        webView.addJavascriptInterface(new JapaBridge(), "NativeApp");

        requestPermissions();
    }

    private void requestPermissions() {
        String[] permissions = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        };

        ArrayList<String> needed = new ArrayList<>();
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needed.add(perm);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            loadPage();
        }
    }

    private void loadPage() {
        webView.loadUrl("http://localhost:" + SERVER_PORT + "/index.html");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (webView != null) {
                mainHandler.post(() -> webView.evaluateJavascript(
                    "if(typeof manualCount==='function'){manualCount();}", null));
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ========= JS BRIDGE =========
    public class JapaBridge {
        
        @JavascriptInterface
        public void log(String message) {
            Log.d(TAG, message);
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(ms);
                }
            }
        }

        @JavascriptInterface
        public void showToast(String message) {
            mainHandler.post(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void keepScreenOn(final boolean on) {
            mainHandler.post(() -> {
                if (on) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 60 * 1000L);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                }
            });
        }

        // ============================================
        // SPEECH RECOGNITION - HYBRID COMPONENT
        // ============================================

        @JavascriptInterface
        public void startSpeechRecognition(String targetWordInput, String language) {
            targetWord = targetWordInput.toLowerCase().trim();
            Log.d(TAG, "Starting speech recognition for: " + targetWord + ", lang: " + language);

            mainHandler.post(() -> {
                if (speechRecognizer != null) {
                    speechRecognizer.destroy();
                }

                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(MainActivity.this);

                if (!SpeechRecognizer.isRecognitionAvailable(MainActivity.this)) {
                    callJavaScript("onSpeechError", "Speech recognition not available");
                    return;
                }

                speechRecognizer.setRecognitionListener(new RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(Bundle params) {
                        isListening = true;
                        callJavaScript("onSpeechReady", "");
                    }

                    @Override
                    public void onBeginningOfSpeech() {}

                    @Override
                    public void onRmsChanged(float rmsdB) {
                        callJavaScript("onAudioLevel", String.valueOf(rmsdB));
                    }

                    @Override
                    public void onBufferReceived(byte[] buffer) {}

                    @Override
                    public void onEndOfSpeech() {}

                    @Override
                    public void onError(int error) {
                        isListening = false;
                        if (shouldContinueListening) {
                            mainHandler.postDelayed(() -> {
                                if (shouldContinueListening) restartListening();
                            }, 100);
                        }
                    }

                    @Override
                    public void onResults(Bundle results) {
                        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null && !matches.isEmpty()) {
                            processRecognizedText(matches.get(0), true);
                        }
                        isListening = false;
                        if (shouldContinueListening) {
                            mainHandler.postDelayed(() -> {
                                if (shouldContinueListening) restartListening();
                            }, 50);
                        }
                    }

                    @Override
                    public void onPartialResults(Bundle partialResults) {
                        ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null && !matches.isEmpty()) {
                            processRecognizedText(matches.get(0), false);
                        }
                    }

                    @Override
                    public void onEvent(int eventType, Bundle params) {}
                });

                speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

                if (language != null && !language.isEmpty()) {
                    speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
                    speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language);
                } else {
                    speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN");
                }

                shouldContinueListening = true;
                lastPartialResult = "";
                speechRecognizer.startListening(speechIntent);
            });
        }

        @JavascriptInterface
        public void stopSpeechRecognition() {
            shouldContinueListening = false;
            mainHandler.post(() -> {
                if (speechRecognizer != null) {
                    speechRecognizer.stopListening();
                    speechRecognizer.cancel();
                    isListening = false;
                }
            });
        }

        @JavascriptInterface
        public void setTargetWord(String word) {
            targetWord = word.toLowerCase().trim();
        }

        @JavascriptInterface
        public boolean isSpeechRecognitionAvailable() {
            return SpeechRecognizer.isRecognitionAvailable(MainActivity.this);
        }

        // ============================================
        // BLUETOOTH SCO
        // ============================================

        @JavascriptInterface
        public void startBtSco() {
            mainHandler.post(() -> {
                try {
                    if (audioManager != null) {
                        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                        audioManager.startBluetoothSco();
                        audioManager.setBluetoothScoOn(true);
                        btScoStarted = true;
                        callJavaScript("onBtScoConnected", "");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "BT SCO error: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void stopBtSco() {
            mainHandler.post(() -> {
                try {
                    if (audioManager != null && btScoStarted) {
                        audioManager.setBluetoothScoOn(false);
                        audioManager.stopBluetoothSco();
                        audioManager.setMode(AudioManager.MODE_NORMAL);
                        btScoStarted = false;
                        callJavaScript("onBtScoDisconnected", "");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "BT SCO stop error: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public boolean isBtScoAvailable() {
            return audioManager != null && audioManager.isBluetoothScoAvailableOffCall();
        }

        // ============================================
        // AUDIO PLAYBACK
        // ============================================

        @JavascriptInterface
        public void playBase64Audio(final String base64Data) {
            new Thread(() -> {
                try {
                    byte[] audioBytes = Base64.decode(base64Data, Base64.DEFAULT);
                    final File tempFile = new File(getCacheDir(), "mic_test.wav");
                    FileOutputStream fos = new FileOutputStream(tempFile);
                    fos.write(audioBytes);
                    fos.close();

                    mainHandler.post(() -> {
                        try {
                            if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
                            audioManager.setMode(AudioManager.MODE_NORMAL);
                            audioManager.setSpeakerphoneOn(true);
                            
                            mediaPlayer = new MediaPlayer();
                            mediaPlayer.setDataSource(tempFile.getAbsolutePath());
                            mediaPlayer.setOnPreparedListener(mp -> mp.start());
                            mediaPlayer.setOnCompletionListener(mp -> {
                                audioManager.setSpeakerphoneOn(false);
                                callJavaScript("onNativePlaybackComplete", "");
                                mp.release(); mediaPlayer = null;
                            });
                            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                                audioManager.setSpeakerphoneOn(false);
                                callJavaScript("onNativePlaybackError", "Error: " + what);
                                mp.release(); mediaPlayer = null;
                                return true;
                            });
                            mediaPlayer.prepareAsync();
                        } catch (Exception e) {
                            Log.e(TAG, "Play error: " + e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "WAV error: " + e.getMessage());
                }
            }).start();
        }

        @JavascriptInterface
        public void stopAudioPlayback() {
            mainHandler.post(() -> {
                if (mediaPlayer != null) {
                    try { mediaPlayer.stop(); } catch (Exception e) {}
                    mediaPlayer.release(); mediaPlayer = null;
                }
                audioManager.setSpeakerphoneOn(false);
            });
        }
    }

    // ============================================
    // HELPER METHODS
    // ============================================

    private void restartListening() {
        if (speechRecognizer != null && speechIntent != null) {
            try {
                speechRecognizer.startListening(speechIntent);
            } catch (Exception e) {
                Log.e(TAG, "Restart error: " + e.getMessage());
            }
        }
    }

    private void processRecognizedText(String text, boolean isFinal) {
        if (text == null || text.isEmpty()) return;

        int newCount = countWordOccurrences(text.toLowerCase(), targetWord);

        if (!isFinal) {
            int previousCount = countWordOccurrences(lastPartialResult.toLowerCase(), targetWord);
            int incrementalCount = newCount - previousCount;

            if (incrementalCount > 0) {
                for (int i = 0; i < incrementalCount; i++) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastWordTime >= 150) {
                        lastWordTime = currentTime;
                        callJavaScript("onWordDetected", targetWord);
                        Log.d(TAG, "WORD DETECTED: " + targetWord);
                    }
                }
            }
            lastPartialResult = text;
        } else {
            int partialCount = countWordOccurrences(lastPartialResult.toLowerCase(), targetWord);
            int missedCount = newCount - partialCount;

            for (int i = 0; i < missedCount; i++) {
                callJavaScript("onWordDetected", targetWord);
            }
            lastPartialResult = "";
        }

        callJavaScript("onTranscript", text.replace("'", "") + "|" + (isFinal ? "final" : "partial"));
    }

    private int countWordOccurrences(String text, String word) {
        if (text == null || word == null) return 0;

        int count = 0;
        String[] words = text.toLowerCase().split("[\\s,]+");

        for (String w : words) {
            w = w.replaceAll("[^a-zA-Z]", "");
            if (w.equalsIgnoreCase(word)) count++;

            // Handle speech recognition variations
            if (word.equals("hare")) {
                if (w.equals("haray") || w.equals("hari") || w.equals("harey") ||
                    w.equals("hary") || w.equals("harry") || w.equals("hurry") ||
                    w.equals("are") || w.equals("har")) count++;
            }
            if (word.equals("krishna")) {
                if (w.equals("krishn") || w.equals("krsna") || w.equals("krishan") ||
                    w.equals("krishnaa") || w.equals("krushna")) count++;
            }
            if (word.equals("rama")) {
                if (w.equals("ram") || w.equals("raam") || w.equals("ramaa")) count++;
            }
        }
        return count;
    }

    private void callJavaScript(String fn, String param) {
        String safeParam = param.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
        String js = "javascript:if(typeof " + fn + "==='function'){" + fn + "('" + safeParam + "');}";
        mainHandler.post(() -> webView.evaluateJavascript(js, null));
    }

    // ========= PERMISSIONS =========
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            loadPage();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onPause() {
        super.onPause();
        shouldContinueListening = false;
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
        try {
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setSpeakerphoneOn(false);
        } catch (Exception e) {}
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (localServer != null) localServer.stopServer();
        if (webView != null) webView.destroy();
    }

    // ========= LOCAL HTTP SERVER =========
    private static class LocalServer extends Thread {
        private final int port;
        private final AssetManager assets;
        private ServerSocket serverSocket;
        private boolean running = true;

        LocalServer(int port, AssetManager assets) throws IOException {
            this.port = port;
            this.assets = assets;
            this.setDaemon(true);
        }

        @Override
        public void run() {
            try {
                serverSocket = new ServerSocket(port);
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        handleClient(client);
                    } catch (IOException e) {
                        if (running) e.printStackTrace();
                    }
                }
            } catch (IOException e) { e.printStackTrace(); }
        }

        private void handleClient(Socket client) {
            try {
                InputStream is = client.getInputStream();
                byte[] buf = new byte[4096];
                int len = is.read(buf);
                if (len <= 0) { client.close(); return; }
                String request = new String(buf, 0, len);
                String path = "index.html";
                if (request.startsWith("GET ")) {
                    int start = 4;
                    int end = request.indexOf(' ', start);
                    if (end > start) {
                        path = request.substring(start, end);
                        if (path.startsWith("/")) path = path.substring(1);
                        if (path.isEmpty()) path = "index.html";
                    }
                }
                byte[] content;
                String contentType = "text/html";
                try {
                    InputStream assetStream = assets.open(path);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = assetStream.read(buffer)) != -1) { baos.write(buffer, 0, read); }
                    assetStream.close();
                    content = baos.toByteArray();
                    if (path.endsWith(".js")) contentType = "application/javascript";
                    else if (path.endsWith(".css")) contentType = "text/css";
                    else if (path.endsWith(".png")) contentType = "image/png";
                    else if (path.endsWith(".jpg")) contentType = "image/jpeg";
                } catch (IOException e) {
                    content = "Not Found".getBytes();
                    contentType = "text/plain";
                }
                OutputStream os = client.getOutputStream();
                String header = "HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\nContent-Length: " + content.length + "\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
                os.write(header.getBytes());
                os.write(content);
                os.flush();
                client.close();
            } catch (IOException e) { e.printStackTrace(); }
        }

        void stopServer() {
            running = false;
            try { if (serverSocket != null) serverSocket.close(); } catch (IOException e) {}
        }
    }
}
