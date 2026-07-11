package com.embyx.app;

import android.os.Bundle;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
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

        // Start local HTTP server to serve bundled assets
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

        // Load from local HTTP server (avoids file:// restrictions on external resources)
        webView.loadUrl("http://127.0.0.1:" + httpServer.getPort() + "/en/index.html");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (httpServer != null) {
            httpServer.stop();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private static class LocalHttpServer {
        private final android.content.Context context;
        private ServerSocket serverSocket;
        private ExecutorService threadPool;
        private volatile boolean running = false;
        private int port;

        LocalHttpServer(android.content.Context context) {
            this.context = context;
        }

        void start() {
            try {
                serverSocket = new ServerSocket(0, 10);
                port = serverSocket.getLocalPort();
                running = true;
                threadPool = Executors.newCachedThreadPool();

                new Thread(() -> {
                    while (running) {
                        try {
                            Socket client = serverSocket.accept();
                            threadPool.execute(() -> handleClient(client));
                        } catch (Exception e) {
                            if (running) {
                                e.printStackTrace();
                            }
                        }
                    }
                }).start();
            } catch (Exception e) {
                throw new RuntimeException("Failed to start HTTP server", e);
            }
        }

        int getPort() {
            return port;
        }

        void stop() {
            running = false;
            if (threadPool != null) {
                threadPool.shutdownNow();
            }
            if (serverSocket != null) {
                try { serverSocket.close(); } catch (Exception ignored) {}
            }
        }

        private void handleClient(Socket client) {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                String requestLine = reader.readLine();
                if (requestLine == null) return;

                // Parse request path
                String path = "/index.html";
                if (requestLine.startsWith("GET ")) {
                    path = requestLine.substring(4, requestLine.lastIndexOf(" HTTP"));
                    if (path.equals("/")) path = "/index.html";
                }

                // Read remaining headers
                String line;
                int contentLength = 0;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }

                // Map path to asset file
                String assetPath = path.startsWith("/") ? path.substring(1) : path;

                // Try to open the file from assets
                try {
                    InputStream is = context.getAssets().open(assetPath);
                    byte[] fileBytes = readAllBytes(is);
                    is.close();

                    String mimeType = getMimeType(assetPath);
                    String headers = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: " + mimeType + "\r\n" +
                            "Content-Length: " + fileBytes.length + "\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Connection: close\r\n\r\n";

                    OutputStream os = client.getOutputStream();
                    os.write(headers.getBytes());
                    os.write(fileBytes);
                    os.flush();
                } catch (Exception e) {
                    // 404
                    String notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                    client.getOutputStream().write(notFound.getBytes());
                    client.getOutputStream().flush();
                }

                client.close();
            } catch (Exception ignored) {}
        }

        private byte[] readAllBytes(InputStream is) throws Exception {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] data = new byte[65536];
            int n;
            while ((n = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, n);
            }
            return buffer.toByteArray();
        }

        private String getMimeType(String path) {
            if (path.endsWith(".html")) return "text/html; charset=utf-8";
            if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (path.endsWith(".css")) return "text/css; charset=utf-8";
            if (path.endsWith(".json")) return "application/json; charset=utf-8";
            if (path.endsWith(".png")) return "image/png";
            if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
            if (path.endsWith(".webp")) return "image/webp";
            if (path.endsWith(".svg")) return "image/svg+xml";
            if (path.endsWith(".ico")) return "image/x-icon";
            if (path.endsWith(".woff2")) return "font/woff2";
            if (path.endsWith(".woff")) return "font/woff";
            if (path.endsWith(".ttf")) return "font/ttf";
            return "application/octet-stream";
        }
    }
}
