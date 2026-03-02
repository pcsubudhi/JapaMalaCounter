package com.japa.counter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
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
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private WebView webView;
    private PowerManager.WakeLock wakeLock;
    private Vibrator vibrator;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private PermissionRequest pendingPermissionRequest;
    private LocalServer localServer;
    private MediaPlayer mediaPlayer;
    private static final int MIC_PERMISSION_CODE = 100;
    private static final int BT_PERMISSION_CODE = 101;
    private static final int SERVER_PORT = 8899;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (ContextCompat.checkSelfPermission(MainActivity.this,
                                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            request.grant(request.getResources());
                        } else {
                            pendingPermissionRequest = request;
                            ActivityCompat.requestPermissions(MainActivity.this,
                                    new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_CODE);
                        }
                    }
                });
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                pendingPermissionRequest = null;
            }
        });

        webView.addJavascriptInterface(new JapaBridge(), "NativeApp");

        // Request both mic and Bluetooth permissions
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms = new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH_CONNECT
            };
        } else {
            perms = new String[]{Manifest.permission.RECORD_AUDIO};
        }

        boolean needPerms = false;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needPerms = true;
                break;
            }
        }

        if (needPerms) {
            ActivityCompat.requestPermissions(this, perms, MIC_PERMISSION_CODE);
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
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        webView.evaluateJavascript(
                            "if(typeof tapCount==='function'&&document.getElementById('counterScreen')&&document.getElementById('counterScreen').classList.contains('active')){tapCount()}", null);
                    }
                });
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
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (on) {
                        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(4 * 60 * 60 * 1000L);
                    } else {
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                    }
                }
            });
        }

        @JavascriptInterface
        public void showToast(final String msg) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void speak(final String text) {
            if (tts != null && ttsReady) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "japa_tts");
                } else {
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
                }
            }
        }

        /**
         * Returns JSON array of audio devices with REAL device names from Android native API.
         * WebView's enumerateDevices() gives generic labels like "Bluetooth headset",
         * but AudioDeviceInfo.getProductName() returns actual names like "soundcore V20i".
         */
        @JavascriptInterface
        public String getAudioDevices() {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am == null) return "[]";

                JSONArray result = new JSONArray();

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioDeviceInfo[] inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
                    for (AudioDeviceInfo device : inputs) {
                        JSONObject obj = new JSONObject();
                        obj.put("id", device.getId());
                        obj.put("name", device.getProductName().toString());
                        obj.put("type", getDeviceTypeName(device.getType()));
                        obj.put("typeCode", device.getType());
                        obj.put("isSource", device.isSource());
                        result.put(obj);
                    }

                    // Also check outputs for BT device real name
                    AudioDeviceInfo[] outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                    for (AudioDeviceInfo device : outputs) {
                        int t = device.getType();
                        if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            t == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            t == AudioDeviceInfo.TYPE_USB_HEADSET ||
                            t == AudioDeviceInfo.TYPE_USB_DEVICE) {
                            JSONObject obj = new JSONObject();
                            obj.put("id", device.getId());
                            obj.put("name", device.getProductName().toString());
                            obj.put("type", getDeviceTypeName(device.getType()));
                            obj.put("typeCode", device.getType());
                            obj.put("isSource", false);
                            obj.put("isOutput", true);
                            result.put(obj);
                        }
                    }
                }

                return result.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        private String getDeviceTypeName(int type) {
            switch (type) {
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth";
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth";
                case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "Built-in";
                case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "Wired";
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "Wired";
                case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB";
                case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB";
                case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "Earpiece";
                case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "Speaker";
                case AudioDeviceInfo.TYPE_TELEPHONY: return "Telephony";
                default: return "Other";
            }
        }

        /**
         * Play audio from base64-encoded WAV data via native MediaPlayer.
         * This works reliably on all Android versions unlike WebView Audio playback.
         */
        @JavascriptInterface
        public void playBase64Audio(final String base64Data) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] audioBytes = Base64.decode(base64Data, Base64.DEFAULT);
                        File tempFile = new File(getCacheDir(), "mic_test.wav");
                        FileOutputStream fos = new FileOutputStream(tempFile);
                        fos.write(audioBytes);
                        fos.close();

                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (mediaPlayer != null) {
                                        mediaPlayer.release();
                                        mediaPlayer = null;
                                    }
                                    mediaPlayer = new MediaPlayer();
                                    mediaPlayer.setDataSource(tempFile.getAbsolutePath());
                                    mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                                        @Override
                                        public void onPrepared(MediaPlayer mp) {
                                            mp.start();
                                        }
                                    });
                                    mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                                        @Override
                                        public void onCompletion(MediaPlayer mp) {
                                            webView.evaluateJavascript("if(typeof onNativePlaybackDone==='function')onNativePlaybackDone()", null);
                                            mp.release();
                                            mediaPlayer = null;
                                        }
                                    });
                                    mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                                        @Override
                                        public boolean onError(MediaPlayer mp, int what, int extra) {
                                            webView.evaluateJavascript("if(typeof onNativePlaybackDone==='function')onNativePlaybackDone()", null);
                                            mp.release();
                                            mediaPlayer = null;
                                            return true;
                                        }
                                    });
                                    mediaPlayer.prepareAsync();
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public void stopAudioPlayback() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mediaPlayer != null) {
                        try { mediaPlayer.stop(); } catch (Exception e) {}
                        mediaPlayer.release();
                        mediaPlayer = null;
                    }
                }
            });
        }
    }

    // ========= PERMISSIONS =========
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_PERMISSION_CODE) {
            boolean micGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(Manifest.permission.RECORD_AUDIO) && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    micGranted = true;
                }
            }
            if (micGranted) {
                if (pendingPermissionRequest != null) {
                    pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                    pendingPermissionRequest = null;
                }
                if (webView.getUrl() == null) {
                    loadPage();
                }
            } else {
                Toast.makeText(this, "Microphone needed for voice counting", Toast.LENGTH_LONG).show();
                loadPage();
            }
        }
    }

    // ========= LIFECYCLE =========
    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
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
            } catch (IOException e) {
                e.printStackTrace();
            }
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
                    while ((read = assetStream.read(buffer)) != -1) {
                        baos.write(buffer, 0, read);
                    }
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
                String header = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: " + contentType + "\r\n"
                        + "Content-Length: " + content.length + "\r\n"
                        + "Access-Control-Allow-Origin: *\r\n"
                        + "Connection: close\r\n"
                        + "\r\n";
                os.write(header.getBytes());
                os.write(content);
                os.flush();
                client.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        void stopServer() {
            running = false;
            try {
                if (serverSocket != null) serverSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
