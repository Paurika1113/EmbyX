package com.embyx.app;

import android.content.Context;
import android.os.Bundle;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private LocalHttpServer httpServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        httpServer = new LocalHttpServer(this);
        httpServer.start();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setBlockNetworkLoads(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setUserAgentString(settings.getUserAgentString() + " EmbyX-Android/1.0");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                handler.proceed();
            }
        });

        // Load from local HTTP server: HTTP page → HTTP fetch() to Emby server, no mixed content
        webView.loadUrl("http://127.0.0.1:" + httpServer.getPort() + "/en/index.html");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (httpServer != null) httpServer.stop();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    static class LocalHttpServer {
        private final Context ctx;
        private ServerSocket ss;
        private volatile boolean running = false;
        private int port;
        private ExecutorService pool;

        LocalHttpServer(Context ctx) { this.ctx = ctx; }

        void start() {
            try {
                ss = new ServerSocket(0, 10);
                port = ss.getLocalPort();
                running = true;
                pool = Executors.newCachedThreadPool();
                new Thread(() -> {
                    while (running) {
                        try { Socket s = ss.accept(); pool.execute(() -> handle(s)); }
                        catch (Exception e) { if (running) e.printStackTrace(); }
                    }
                }).start();
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        int getPort() { return port; }
        void stop() { running = false; if (pool != null) pool.shutdownNow(); try { if (ss != null) ss.close(); } catch (Exception ignored) {} }

        private void handle(Socket client) {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(client.getInputStream()));
                String line = r.readLine();
                if (line == null) return;
                String path = "/index.html";
                if (line.startsWith("GET ")) {
                    path = line.substring(4, line.lastIndexOf(" HTTP"));
                    if (path.equals("/")) path = "/index.html";
                }
                while ((line = r.readLine()) != null && !line.isEmpty()) {}

                // Map to asset (strip leading /)
                String assetPath = path.startsWith("/") ? path.substring(1) : path;

                try {
                    InputStream is = ctx.getAssets().open(assetPath);
                    byte[] data = readAll(is);
                    is.close();
                    String mime = mimeType(assetPath);
                    String head = "HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n";
                    OutputStream os = client.getOutputStream();
                    os.write(head.getBytes());
                    os.write(data);
                    os.flush();
                } catch (Exception e) {
                    String nf = "HTTP/1.1 404\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                    client.getOutputStream().write(nf.getBytes());
                    client.getOutputStream().flush();
                }
                client.close();
            } catch (Exception ignored) {}
        }

        private byte[] readAll(InputStream is) throws Exception {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[65536];
            int n;
            while ((n = is.read(tmp, 0, tmp.length)) != -1) buf.write(tmp, 0, n);
            return buf.toByteArray();
        }

        private String mimeType(String p) {
            if (p.endsWith(".html")) return "text/html; charset=utf-8";
            if (p.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (p.endsWith(".css")) return "text/css; charset=utf-8";
            if (p.endsWith(".json")) return "application/json; charset=utf-8";
            if (p.endsWith(".png")) return "image/png";
            if (p.endsWith(".webp")) return "image/webp";
            if (p.endsWith(".svg")) return "image/svg+xml";
            return "application/octet-stream";
        }
    }
}
