package com.japa.counter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
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
    private MediaPlayer alarmMediaPlayer; // For bhajan alarm
    private AudioManager audioManager;
    private Handler mainHandler;
    
    private static final int PICK_AUDIO_FILE = 1001;

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

        @JavascriptInterface
        public void playAudioFile(String filePath) {
            mainHandler.post(() -> {
                try {
                    stopAudioInternal();
                    alarmMediaPlayer = new MediaPlayer();
                    alarmMediaPlayer.setDataSource(filePath);
                    alarmMediaPlayer.setLooping(true); // Loop until stopped
                    alarmMediaPlayer.prepare();
                    alarmMediaPlayer.start();
                    Log.d(TAG, "Playing audio: " + filePath);
                } catch (Exception e) {
                    Log.e(TAG, "Error playing audio: " + e.getMessage());
                    callJS("D('Error playing audio: " + e.getMessage() + "','warn')");
                }
            });
        }

        @JavascriptInterface
        public void playYouTubeAudio(String url) {
            // YouTube audio requires external library or intent
            // For now, open YouTube app/browser
            mainHandler.post(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    Log.d(TAG, "Opening YouTube: " + url);
                } catch (Exception e) {
                    Log.e(TAG, "Error opening YouTube: " + e.getMessage());
                    callJS("alert('Could not open YouTube. Please install YouTube app.')");
                }
            });
        }

        @JavascriptInterface
        public void stopAudio() {
            mainHandler.post(() -> stopAudioInternal());
        }

        @JavascriptInterface
        public void selectAudioFile() {
            mainHandler.post(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("audio/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try {
                    startActivityForResult(intent, PICK_AUDIO_FILE);
                } catch (Exception e) {
                    Log.e(TAG, "Error selecting file: " + e.getMessage());
                    callJS("D('Error opening file picker: " + e.getMessage() + "','warn')");
                }
            });
        }

        @JavascriptInterface
        public void scheduleNativeAlarm(String time, String audioPath, String audioSource) {
            mainHandler.post(() -> {
                AlarmScheduler.scheduleAlarm(MainActivity.this, time, audioPath, audioSource);
                Log.d(TAG, "Native alarm scheduled for " + time);
            });
        }

        @JavascriptInterface
        public void cancelNativeAlarm() {
            mainHandler.post(() -> {
                AlarmScheduler.cancelAlarm(MainActivity.this);
                Log.d(TAG, "Native alarm cancelled");
            });
        }

        @JavascriptInterface
        public void stopNativeAlarm() {
            mainHandler.post(() -> {
                AlarmScheduler.stopAlarmService(MainActivity.this);
                Log.d(TAG, "Native alarm stopped");
            });
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
                        isListening = false;
                        
                        int delay = 300;
                        boolean shouldRestart = shouldContinueListening;
                        String errorMsg = "";
                        boolean showInDebug = true;
                        
                        switch(error) {
                            case SpeechRecognizer.ERROR_AUDIO: 
                                errorMsg = "Audio error"; 
                                delay = 1000;
                                break;
                            case SpeechRecognizer.ERROR_CLIENT: 
                                errorMsg = "Client error";
                                delay = 300;
                                showInDebug = false; // Don't spam
                                break;
                            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: 
                                errorMsg = "No mic permission"; 
                                shouldRestart = false;
                                break;
                            case SpeechRecognizer.ERROR_NETWORK: 
                                errorMsg = "Network error - check internet"; 
                                delay = 3000;
                                break;
                            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: 
                                errorMsg = "Network timeout"; 
                                delay = 2000;
                                break;
                            case SpeechRecognizer.ERROR_NO_MATCH: 
                                // ERROR 7 - Normal, just silence - restart quickly
                                delay = 100;
                                showInDebug = false;
                                break;
                            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: 
                                // ERROR 11 - Already running, wait longer!
                                errorMsg = "Recognizer busy, waiting...";
                                delay = 1000; // Wait 1 second
                                showInDebug = false;
                                break;
                            case SpeechRecognizer.ERROR_SERVER: 
                                errorMsg = "Google server error"; 
                                delay = 2000;
                                break;
                            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: 
                                // ERROR 6 - Silence too long - restart quickly
                                delay = 100;
                                showInDebug = false;
                                break;
                            default:
                                errorMsg = "Error " + error;
                                delay = 500;
                                break;
                        }
                        
                        Log.d(TAG, "Speech error: " + error + " - " + errorMsg);
                        
                        if (showInDebug && !errorMsg.isEmpty()) {
                            callJS("D('⚠️ " + errorMsg + "','warn')");
                        }
                        
                        if (shouldRestart) {
                            mainHandler.postDelayed(() -> {
                                if (shouldContinueListening && !isListening) {
                                    restartListening();
                                }
                            }, delay);
                        }
                    }

                    @Override
                    public void onResults(Bundle results) {
                        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null && !matches.isEmpty()) {
                            Log.d(TAG, "FINAL: \"" + matches.get(0) + "\"");
                            processTranscript(matches.get(0), true);
                        }
                        isListening = false; // Mark as not listening
                        
                        // Restart with small delay
                        if (shouldContinueListening) {
                            mainHandler.postDelayed(() -> restartListening(), 100);
                        }
                    }

                    @Override
                    public void onPartialResults(Bundle partialResults) {
                        ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if (matches != null && !matches.isEmpty()) {
                            String transcript = matches.get(0);
                            Log.d(TAG, "PARTIAL: \"" + transcript + "\"");
                            processTranscript(transcript, false);
                        } else {
                            Log.d(TAG, "PARTIAL: empty or null");
                        }
                    }

                    @Override public void onEvent(int eventType, Bundle params) {}
                });

                speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
                
                // MAXIMUM timeouts to prevent premature finalization
                speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300000); // 5 minutes
                speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 30000); // 30 sec silence
                speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 15000); // 15 sec

                // Language based on mantra
                String lang = (language != null && !language.isEmpty()) ? language : "en-IN";
                speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
                speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
                speechIntent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false);

                shouldContinueListening = true;
                lastPartialResult = "";
                totalWordsDetected = 0;
                
                // Reset v44 tracking
                lastProcessedText = "";
                lastHareCount = 0;
                lastProcessTime = 0;
                
                Log.d(TAG, "Starting speech recognition...");
                callJS("D('🎤 Starting speech recognition...','info')");
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
        if (speechRecognizer == null || speechIntent == null || !shouldContinueListening) {
            return;
        }
        
        // Prevent double-start
        if (isListening) {
            Log.d(TAG, "Already listening, skip restart");
            return;
        }
        
        try {
            // Cancel first, then wait before starting
            speechRecognizer.cancel();
            lastPartialResult = "";
            
            // Longer delay to ensure previous session is fully stopped
            mainHandler.postDelayed(() -> {
                if (shouldContinueListening && speechRecognizer != null && !isListening) {
                    try {
                        isListening = true; // Set BEFORE starting
                        speechRecognizer.startListening(speechIntent);
                        Log.d(TAG, "Speech recognition restarted");
                    } catch (Exception e) {
                        Log.e(TAG, "Start error: " + e.getMessage());
                        isListening = false;
                        // Try recreating after delay
                        mainHandler.postDelayed(() -> recreateSpeechRecognizer(), 500);
                    }
                }
            }, 150); // 150ms delay between stop and start
        } catch (Exception e) {
            Log.e(TAG, "Restart error: " + e.getMessage());
            isListening = false;
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
    
    // v44: SMART COUNTING - all logic in Java, send only counts to JS
    private String lastProcessedText = "";
    private int lastHareCount = 0;
    private long lastProcessTime = 0;
    
    // Process transcript - count in Java, send result to JavaScript
    private void processTranscript(String text, boolean isFinal) {
        if (text == null || text.isEmpty()) return;
        
        long now = System.currentTimeMillis();
        
        // STRICT DEDUP: Skip exact duplicates within 200ms
        if (text.equals(lastProcessedText) && (now - lastProcessTime) < 200) {
            Log.d(TAG, "SKIP duplicate: " + text.substring(0, Math.min(text.length(), 30)));
            return;
        }
        
        // Count HARE words in this transcript
        int hareCount = countHareWords(text);
        
        Log.d(TAG, "TRANSCRIPT: \"" + text.substring(0, Math.min(text.length(), 40)) + "\" → " + hareCount + " Hare (prev: " + lastHareCount + ")");
        
        // Calculate NEW Hare words
        int newWords = 0;
        
        if (hareCount > lastHareCount) {
            // More Hare than before = new words
            newWords = hareCount - lastHareCount;
        } else if (hareCount > 0 && hareCount < lastHareCount) {
            // Count dropped = new phrase started
            newWords = hareCount;
            Log.d(TAG, "NEW PHRASE detected");
        }
        // If hareCount == lastHareCount, no new words
        
        // Update tracking
        lastProcessedText = text;
        lastHareCount = hareCount;
        lastProcessTime = now;
        
        // Send to JS: transcript for display, and word count
        String safeText = text.replace("'", "").replace("\\", "").replace("\n", " ");
        
        if (newWords > 0) {
            Log.d(TAG, "ADDING " + newWords + " words");
            // Call JS to add words
            callJS("onNativeWordCount(" + newWords + ",'" + safeText.substring(0, Math.min(safeText.length(), 30)) + "'," + hareCount + ")");
        } else {
            // Just log transcript, no count
            callJS("onNativeTranscript('" + safeText.substring(0, Math.min(safeText.length(), 30)) + "'," + hareCount + ")");
        }
        
        // Reset on final result
        if (isFinal) {
            lastHareCount = 0;
            lastProcessedText = "";
        }
    }
    
    // Count only HARE words (8 per complete Maha Mantra)
    private int countHareWords(String text) {
        if (text == null || text.isEmpty()) return 0;
        
        int count = 0;
        
        // Count Hindi हरे
        int idx = 0;
        while ((idx = text.indexOf("हरे", idx)) != -1) {
            count++;
            idx += 2; // Move past this match
        }
        
        // Count English variations
        String lower = text.toLowerCase();
        String[] words = lower.split("[\\s,]+");
        for (String word : words) {
            word = word.replaceAll("[^a-z]", "");
            if (word.equals("hare") || word.equals("hari") || word.equals("harey") || 
                word.equals("harry") || word.equals("hurry") || word.equals("hore")) {
                count++;
            }
        }
        
        return count;
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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        Log.d(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode);
        
        if (requestCode == PICK_AUDIO_FILE) {
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();
                Log.d(TAG, "URI received: " + (uri != null ? uri.toString() : "null"));
                
                if (uri != null) {
                    String filePath = uri.toString();
                    String fileName = "Selected Audio";
                    
                    try {
                        // Try to get persistent permission
                        final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                        getContentResolver().takePersistableUriPermission(uri, takeFlags);
                        Log.d(TAG, "Persistent permission granted");
                    } catch (SecurityException e) {
                        Log.w(TAG, "Could not get persistent permission: " + e.getMessage());
                        // Continue anyway - it might still work for this session
                    }
                    
                    // Try to get display name
                    try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                            if (nameIndex >= 0) {
                                fileName = cursor.getString(nameIndex);
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not get file name: " + e.getMessage());
                    }
                    
                    final String fName = fileName;
                    final String fPath = filePath;
                    
                    Log.d(TAG, "Calling JS with file: " + fName + " at " + fPath);
                    
                    // Call JavaScript callback
                    mainHandler.post(() -> {
                        String js = "onBhajanFileSelected('" + fPath.replace("'", "\\'") + "','" + fName.replace("'", "\\'") + "')";
                        Log.d(TAG, "Executing JS: " + js);
                        callJS(js);
                    });
                }
            } else {
                Log.d(TAG, "File selection cancelled or failed");
                mainHandler.post(() -> {
                    callJS("D('File selection cancelled','info')");
                });
            }
        }
    }
    
    private void stopAudioInternal() {
        if (alarmMediaPlayer != null) {
            try {
                if (alarmMediaPlayer.isPlaying()) {
                    alarmMediaPlayer.stop();
                }
                alarmMediaPlayer.release();
            } catch (Exception e) {}
            alarmMediaPlayer = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) { speechRecognizer.destroy(); }
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (mediaPlayer != null) { mediaPlayer.release(); }
        stopAudioInternal();
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
