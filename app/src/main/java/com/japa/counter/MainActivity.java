package com.japa.counter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private WebView webView;
    private PowerManager.WakeLock wakeLock;
    private Vibrator vibrator;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private PermissionRequest pendingPermissionRequest;
    private LocalServer localServer;
    private static final int MIC_PERMISSION_CODE = 100;
    private static final int SERVER_PORT = 8899;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(0xFF1A0F0A);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JapaCounter::Chanting");
        }

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        // Initialize native Text-to-Speech
        tts = new TextToSpeech(this, this);

        // Start local HTTP server (required for getUserMedia)
        try {
            localServer = new LocalServer(SERVER_PORT, getAssets());
            localServer.start();
        } catch (IOException e) {
            Toast.makeText(this, "Server error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        webView = new WebView(this);
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_CODE);
        } else {
            loadPage();
        }
    }

    // ========= TTS INIT CALLBACK =========
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

    // ========= VOLUME BUTTON CAPTURE =========
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

        // NATIVE TEXT-TO-SPEECH - called from JavaScript
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
    }

    // ========= PERMISSIONS =========
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
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
