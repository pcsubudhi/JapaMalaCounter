package com.japa.counter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
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
import android.speech.tts.TextToSpeech;
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
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final String TAG = "JapaCounter";
    private static final int MIC_PERMISSION_CODE = 100;
    private static final int SERVER_PORT = 8899;

    private WebView webView;
    private PowerManager.WakeLock wakeLock;
    private Vibrator vibrator;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private LocalServer localServer;
    private MediaPlayer mediaPlayer;
    private AudioManager audioManager;
    private Handler mainHandler;

    // Google Speech Recognition
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean isListening = false;
    private boolean shouldContinueListening = false;
    private String lastPartialResult = "";
    private long lastWordTime = 0;
    private int totalWordsDetected = 0;
    private String targetWord = "hare"; // Can be changed to "radha" etc.

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mainHandler = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF140B07);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JapaCounter::Chanting");
        }

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        tts = new TextToSpeech(this, this);

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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_CODE);
        } else {
            loadPage();
        }
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            int result = tts.setLanguage(new Locale("en", "IN"));
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.ENGLISH);
            }
            tts.setSpeechRate(0.9f);
            tts.setPitch(1.0f);
            ttsReady = true;
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
                    "if(typeof tapCount==='function'&&document.getElementById('counterScreen')&&document.getElementById('counterScreen').classList.contains('active')){tapCount()}", null));
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
        public void log(String msg) {
            Log.d(TAG, "JS: " + msg);
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

        @JavascriptInterface
        public void speak(String text) {
            if (ttsReady && tts != null) {
                tts.speak(text, TextToSpeech.QUEUE_ADD, null, "japa");
            }
        }

        // =====================================================
        // GOOGLE SPEECH RECOGNITION
        // =====================================================

        @JavascriptInterface
        public void startSpeechRecognition(String word, String language) {
            targetWord = (word != null && !word.isEmpty()) ? word.toLowerCase() : "hare";
            Log.d(TAG, "Starting Google Speech for: " + targetWord + ", lang: " + language);
            
            mainHandler.post(() -> {
                if (speechRecognizer != null) {
                    speechRecognizer.destroy();
                }

                if (!SpeechRecognizer.isRecognitionAvailable(MainActivity.this)) {
                    callJS("onSpeechError('Speech recognition not available')");
                    return;
                }

                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(MainActivity.this);
                speechRecognizer.setRecognitionListener(new RecognitionListener() {
                    @Override
                    public void onReadyForSpeech(Bundle params) {
                        isListening = true;
                        callJS("onSpeechReady()");
                    }

                    @Override public void onBeginningOfSpeech() {}
                    @Override public void onRmsChanged(float rmsdB) {}
                    @Override public void onBufferReceived(byte[] buffer) {}
                    @Override public void onEndOfSpeech() {}

                    @Override
                    public void onError(int error) {
                        String errorMsg = "";
                        boolean shouldRestart = true;
                        boolean showError = false;
                        int delay = 300;
                        
                        switch(error) {
                            case SpeechRecognizer.ERROR_AUDIO: 
                                errorMsg = "Audio error"; 
                                showError = true;
                                delay = 1000;
                                break;
                            case SpeechRecognizer.ERROR_CLIENT: 
                                // Normal restart - don't show
                                delay = 200;
                                break;
                            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: 
                                errorMsg = "No mic permission"; 
                                showError = true;
                                shouldRestart = false;
                                break;
                            case SpeechRecognizer.ERROR_NETWORK: 
                                errorMsg = "Network error"; 
                                showError = true;
                                delay = 2000;
                                break;
                            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: 
                                errorMsg = "Network timeout"; 
                                showError = true;
                                delay = 2000;
                                break;
                            case SpeechRecognizer.ERROR_NO_MATCH: 
                                // Normal - no speech, just restart
                                delay = 100;
                                break;
                            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: 
                                // Busy, wait longer
                                delay = 1000;
                                break;
                            case SpeechRecognizer.ERROR_SERVER: 
                                errorMsg = "Server error"; 
                                showError = true;
                                delay = 2000;
                                break;
                            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: 
                                // Normal silence timeout
                                delay = 100;
                                break;
                            default:
                                // Unknown error code - just restart
                                delay = 500;
                                break;
                        }
                        
                        Log.d(TAG, "Speech error code: " + error);
                        
                        if (showError && !errorMsg.isEmpty()) {
                            callJS("onSpeechError('" + errorMsg + "')");
                        }
                        
                        isListening = false;
                        
                        if (shouldContinueListening && shouldRestart) {
                            mainHandler.postDelayed(() -> {
                                if (shouldContinueListening) {
                                    restartListening();
                                }
                            }, delay);
                        }
                    }

                    @Override
                    public void onResults(Bundle results) {
                        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null && !matches.isEmpty()) {
                            processTranscript(matches.get(0), true);
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
                            processTranscript(matches.get(0), false);
                        }
                    }

                    @Override public void onEvent(int eventType, Bundle params) {}
                });

                speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
                // Longer listening time
                speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000);
                speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000);
                speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000);

                // Try English India first - it often works better for "Hare Krishna"
                String lang = (language != null && !language.isEmpty()) ? language : "en-IN";
                speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
                speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
                // Also allow other languages as fallback
                speechIntent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false);

                shouldContinueListening = true;
                lastPartialResult = "";
                totalWordsDetected = 0;
                speechRecognizer.startListening(speechIntent);
            });
        }

        @JavascriptInterface
        public void stopSpeechRecognition() {
            Log.d(TAG, "Stopping speech. Total: " + totalWordsDetected);
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
        public boolean isSpeechAvailable() {
            return SpeechRecognizer.isRecognitionAvailable(MainActivity.this);
        }

        @JavascriptInterface
        public void setTargetWord(String word) {
            targetWord = (word != null) ? word.toLowerCase() : "hare";
            Log.d(TAG, "Target word set to: " + targetWord);
        }

        // =====================================================
        // BLUETOOTH SCO
        // =====================================================

        @JavascriptInterface
        public void startBtSco() {
            mainHandler.post(() -> {
                try {
                    audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    audioManager.startBluetoothSco();
                    audioManager.setBluetoothScoOn(true);
                } catch (Exception e) {
                    Log.e(TAG, "BT SCO error: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void stopBtSco() {
            mainHandler.post(() -> {
                try {
                    audioManager.setBluetoothScoOn(false);
                    audioManager.stopBluetoothSco();
                    audioManager.setMode(AudioManager.MODE_NORMAL);
                } catch (Exception e) {}
            });
        }

        // =====================================================
        // AUDIO PLAYBACK
        // =====================================================

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
                                callJS("onNativePlaybackDone()");
                                mp.release(); mediaPlayer = null;
                            });
                            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                                audioManager.setSpeakerphoneOn(false);
                                callJS("onNativePlaybackDone()");
                                mp.release(); mediaPlayer = null;
                                return true;
                            });
                            mediaPlayer.prepareAsync();
                        } catch (Exception e) { Log.e(TAG, "Play error: " + e.getMessage()); }
                    });
                } catch (Exception e) { Log.e(TAG, "WAV error: " + e.getMessage()); }
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

    // =====================================================
    // SPEECH PROCESSING
    // =====================================================

    private void restartListening() {
        if (speechRecognizer != null && speechIntent != null && shouldContinueListening) {
            try {
                // Cancel any pending recognition first
                speechRecognizer.cancel();
                lastPartialResult = "";
                
                // Small delay then restart
                mainHandler.postDelayed(() -> {
                    if (shouldContinueListening && speechRecognizer != null) {
                        try {
                            speechRecognizer.startListening(speechIntent);
                            Log.d(TAG, "Speech recognition restarted");
                        } catch (Exception e) {
                            Log.e(TAG, "Start error: " + e.getMessage());
                            // Try recreating the recognizer
                            recreateSpeechRecognizer();
                        }
                    }
                }, 50);
            } catch (Exception e) {
                Log.e(TAG, "Restart error: " + e.getMessage());
                recreateSpeechRecognizer();
            }
        }
    }
    
    private void recreateSpeechRecognizer() {
        Log.d(TAG, "Recreating speech recognizer...");
        mainHandler.post(() -> {
            try {
                if (speechRecognizer != null) {
                    speechRecognizer.destroy();
                    speechRecognizer = null;
                }
                // Restart via JS bridge call
                if (shouldContinueListening) {
                    callJS("restartGoogleSpeech()");
                }
            } catch (Exception e) {
                Log.e(TAG, "Recreate error: " + e.getMessage());
            }
        });
    }

    // Mantra patterns - count words per complete mantra
    private int wordsPerMantra = 16; // Default: Full Maha Mantra (16 words)
    private String mantraType = "maha_mantra"; // maha_mantra, hare_krishna, hare_rama, radhe, custom
    
    private int countTargetWords(String text) {
        if (text == null || text.isEmpty()) return 0;
        
        int totalWords = 0;
        
        // Count Hindi/Devanagari words
        
        // Count HARE - हरे (and variations)
        totalWords += countOccurrences(text, "हरे");
        totalWords += countOccurrences(text, "हर ");
        
        // Count KRISHNA - कृष्णा or कृष्ण
        totalWords += countOccurrences(text, "कृष्णा");
        totalWords += countOccurrences(text, "कृष्ण ");
        
        // Count RAMA - राम or रामा  
        totalWords += countOccurrences(text, "रामा");
        totalWords += countOccurrences(text, "राम ");
        totalWords += countOccurrences(text, "राम,");
        // Avoid double-counting राम in रामा
        int ramaCount = countOccurrences(text, "राम");
        int ramaACount = countOccurrences(text, "रामा");
        totalWords += (ramaCount - ramaACount); // Only count राम not followed by ा
        
        // Count RADHA - राधा or राधे
        totalWords += countOccurrences(text, "राधे");
        totalWords += countOccurrences(text, "राधा");
        
        // Also check English transliterations
        String lowerText = text.toLowerCase();
        String[] words = lowerText.split("[\\s,]+");
        for (String word : words) {
            word = word.replaceAll("[^a-zA-Z]", "");
            if (word.isEmpty()) continue;
            if (matchesTarget(word)) {
                totalWords++;
            }
        }
        
        Log.d(TAG, "Total words found: " + totalWords + " in: " + text.substring(0, Math.min(text.length(), 60)));
        return totalWords;
    }
    
    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }
    
    // Process transcript - just send to JavaScript, let JS handle counting
    private void processTranscript(String text, boolean isFinal) {
        if (text == null || text.isEmpty()) return;
        
        // Send transcript to JS for processing
        String safeText = text.replace("'", "").replace("\\", "").replace("\n", " ");
        callJS("onTranscript('" + safeText + "','" + (isFinal ? "final" : "partial") + "')");
    }

    private boolean matchesTarget(String word) {
        if (word.equals(targetWord)) return true;
        
        // HARE variations
        if (targetWord.equals("hare")) {
            return word.equals("harey") || word.equals("hari") || word.equals("hary") ||
                   word.equals("harry") || word.equals("hurry") || word.equals("hore") ||
                   word.equals("are") || word.equals("haray") || word.equals("here");
        }
        // KRISHNA variations
        if (targetWord.equals("krishna")) {
            return word.equals("krishn") || word.equals("krsna") || word.equals("krishan") ||
                   word.equals("krishnaa") || word.equals("krushna") || word.equals("krishana") ||
                   word.equals("kisna") || word.equals("krisna") || word.equals("krishana");
        }
        // RAMA variations
        if (targetWord.equals("rama")) {
            return word.equals("ram") || word.equals("raam") || word.equals("ramaa") ||
                   word.equals("rom") || word.equals("ruma");
        }
        // RADHA variations
        if (targetWord.equals("radha")) {
            return word.equals("radhe") || word.equals("radhey") || word.equals("rada") ||
                   word.equals("radah") || word.equals("raadha") || word.equals("radharani");
        }
        // GOVINDA variations
        if (targetWord.equals("govinda")) {
            return word.equals("govind") || word.equals("gobinda") || word.equals("gobind");
        }
        
        // Fuzzy match - if word contains target (for any custom word)
        if (word.length() >= 3 && targetWord.length() >= 3) {
            if (word.contains(targetWord) || targetWord.contains(word)) {
                return true;
            }
        }
        
        return false;
    }

    private void callJS(String script) {
        mainHandler.post(() -> {
            if (webView != null) {
                Log.d(TAG, "CallJS: " + script);
                webView.evaluateJavascript("javascript:try{" + script + "}catch(e){console.log('JS Error:'+e)}", null);
            }
        });
    }

    // Debug helper - call from JS
    public void logFromJS(String msg) {
        Log.d(TAG, "FromJS: " + msg);
    }

    // ========= PERMISSIONS =========
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_PERMISSION_CODE) {
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
        if (speechRecognizer != null) { speechRecognizer.destroy(); }
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (mediaPlayer != null) { mediaPlayer.release(); }
        try {
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setMode(AudioManager.MODE_NORMAL);
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
