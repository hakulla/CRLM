package org.mobileagent.agent;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Resident inline browser session:Agent Open pages, screenshots, take the text, and do not take the dialogue page.
 * Pages are hung in an invisible FrameLayout Top (Sizes) 1080x1920), Make sure the screen is real.
 */
final class BrowserSession {

    private static final String TAG = "AgentBrowser";
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Mobile) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final int VIEW_W = 1080;
    private static final int VIEW_H = 1920;
    private static final int MAX_TEXT = 14_000;
    private static final int MAX_CONSOLE = 60;

    private static final String JS_EXTRACT = "(function(){"
            + "var text='';try{text=document.body?(document.body.innerText||''):'';}catch(e){}"
            + "var frames='';try{for(var i=0;i<window.frames.length;i++){"
            + "try{var d=window.frames[i].document;if(d&&d.body)frames+='\\n'+d.body.innerText;}catch(e){}}}catch(e){}"
            + "var all=text+frames;"
            + "return JSON.stringify({title:document.title||'',url:location.href||'',text:all,len:all.length});"
            + "})()";

    private static BrowserSession instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> console = new ArrayList<>();
    private WebView webView;
    private FrameLayout holder;
    private String currentUrl = "";
    private String lastError = "";
    private volatile boolean pageFinished;
    private volatile int lastLength = -1;
    private volatile int stableCount;

    private BrowserSession() { }

    static synchronized BrowserSession get() {
        if (instance == null) instance = new BrowserSession();
        return instance;
    }

    static synchronized void release() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    String currentUrl() {
        return currentUrl;
    }

    /** opening page and waiting for rendering to stabilize; return null indicates success, otherwise the wrong text is returned. */
    String open(Context context, String url, long timeoutMs) {
        if (context == null || url == null || url.trim().isEmpty()) return "Missing address";
        if (Looper.myLooper() == Looper.getMainLooper()) return "Can not wait for page loading at main thread";
        final String clean = normalize(url.trim());
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] error = new String[1];
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    ensureView(context);
                    pageFinished = false;
                    lastLength = -1;
                    stableCount = 0;
                    lastError = "";
                    console.clear();
                    currentUrl = clean;
                    webView.loadUrl(clean);
                } catch (Throwable t) {
                    error[0] = "Browser startup failed:" + t;
                    latch.countDown();
                }
            }
        });
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (error[0] != null) return error[0];
                if (pageFinished && stableCount >= 2) break;
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Page loading interrupted";
        }
        if (error[0] != null) return error[0];
        if (!pageFinished) return "Page Load Overtime (Page)" + timeoutMs + "ms); Could be login or network too slow";
        if (!lastError.isEmpty()) return "Error loading page:" + lastError;
        return null;
    }

    /** Take the current page body (executed) JS).  */
    String extractText() {
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = new String[1];
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    if (webView == null) {
                        out[0] = "Browser has not opened any page";
                    } else {
                        webView.evaluateJavascript(JS_EXTRACT, value -> {
                            out[0] = value;
                            latch.countDown();
                        });
                        return;
                    }
                } catch (Throwable t) {
                    out[0] = "Could not close temporary folder: %s" + t;
                }
                latch.countDown();
            }
        });
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) return "Timeout for body";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Taking body was interrupted";
        }
        String raw = out[0];
        if (raw == null) return "Failed to fetch text: No return";
        return decodeJsString(raw);
    }

    /** Execute on the current page JS , and returns the result (for reading) window.THREE / __errors / canvas The diagnosis, I'm afraid. */
    String evaluate(String script) {
        if (script == null || script.trim().isEmpty()) {
            return "Lack of implementation JS";
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = new String[1];
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    if (webView == null) {
                        out[0] = "Browser has not opened any page";
                    } else {
                        webView.evaluateJavascript(script, value -> {
                            out[0] = value;
                            latch.countDown();
                        });
                        return;
                    }
                } catch (Throwable t) {
                    out[0] = "Implementation JS Failed:" + t;
                }
                latch.countDown();
            }
        });
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) return "Implementation JS Timeout";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Implementation JS Interrupted";
        }
        if (out[0] == null) return "Implementation JS Failed: No return";
        return decodeJsString(out[0]);
    }

    /**
     * WebGL The canvas is not working. WebView.draw Grab (hardware synthesis back black),
     * First priority for page placement canvas Export As PNG; Normal DOM Page not available canvas And then back. draw(). 
     */
    private byte[] canvasPng() {
        // preserveDrawingBuffer=false , directly outside the frame toDataURL I'll get an empty buffer.
        // Put the drawings on the next frame. rAF Back in (tick on page) render (after) and then retake it.
        evaluate("(function(){window.__mobileagentShot='';requestAnimationFrame(function(){"
                + "try{var c=document.querySelector('canvas');"
                + "window.__mobileagentShot=c?c.toDataURL('image/png'):'';}"
                + "catch(e){window.__mobileagentShot='ERR:'+e;}});return 'scheduled';})()");
        long deadline = System.currentTimeMillis() + 4000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(80L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            String value = evaluate("(function(){var v=window.__mobileagentShot||'';"
                    + "window.__mobileagentShot='';return v;})()");
            if (value == null) {
                continue;
            }
            value = value.trim();
            if (value.isEmpty() || "scheduled".equals(value)) {
                continue;
            }
            String prefix = "data:image/png;base64,";
            if (value.startsWith(prefix)) {
                try {
                    byte[] png = Base64.decode(value.substring(prefix.length()), Base64.DEFAULT);
                    return png.length > 0 && png.length < 24 * 1024 * 1024 ? png : null;
                } catch (Throwable t) {
                    return null;
                }
            }
            if (value.startsWith("ERR:")) {
                lastError = value;
                return null;
            }
        }
        return null;
    }

    /** Intercept the current page as PNG bytes;failed returns null And write lastError.  */
    byte[] screenshot() {
        final CountDownLatch latch = new CountDownLatch(1);
        final byte[][] out = new byte[1][];
        // Wait for two frames. + Short-term sinking, avoid. Three.js The first frame is not drawn and the first one is a white screen.
        settleFrames();
        // canvas.toDataURL Main Thread Range Required evaluate, Not here. main.post . The main route is to be followed in the task.
        final byte[] canvasShot = canvasPng();
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    if (webView == null) {
                        lastError = "Browser has not opened any page";
                    } else if (canvasShot != null) {
                        out[0] = canvasShot;
                    } else {
                        Bitmap bitmap = Bitmap.createBitmap(VIEW_W, VIEW_H, Bitmap.Config.ARGB_8888);
                        Canvas canvas = new Canvas(bitmap);
                        canvas.drawColor(Color.WHITE);
                        webView.draw(canvas);
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, bos);
                        bitmap.recycle();
                        out[0] = bos.toByteArray();
                    }
                } catch (Throwable t) {
                    lastError = "Could not close temporary folder: %s" + t;
                }
                latch.countDown();
            }
        });
        try {
            if (!latch.await(40, TimeUnit.SECONDS)) {
                lastError = "Timeout";
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastError = "The screenshot was cut.";
            return null;
        }
        return out[0];
    }

    private void settleFrames() {
        if (webView == null) {
            return;
        }
        try {
            evaluate("(function(){requestAnimationFrame(function(){"
                    + "requestAnimationFrame(function(){});});return 'ok';})()");
        } catch (Throwable ignored) {
        }
        try {
            Thread.sleep(350L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    String lastError() {
        return lastError;
    }

    String consoleDump() {
        synchronized (console) {
            if (console.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String line : console) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    private void ensureView(Context context) {
        if (webView != null) return;
        Activity activity = MainActivity.current();
        Context viewContext = activity == null ? context.getApplicationContext() : activity;
        holder = new FrameLayout(viewContext);
        webView = new WebView(viewContext);
        webView.setBackgroundColor(Color.WHITE);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowContentAccess(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUserAgentString(UA);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        CookieManager.getInstance().setAcceptCookie(true);
        webView.onResume();
        webView.resumeTimers();
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                if (message != null) {
                    String line = "[" + message.messageLevel() + "] " + message.message()
                            + " @" + message.sourceId() + ":" + message.lineNumber();
                    synchronized (console) {
                        if (console.size() < MAX_CONSOLE) console.add(line);
                    }
                    Log.i(TAG, line);
                }
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
            @Override public void onPageFinished(WebView view, String loadedUrl) {
                pageFinished = true;
                currentUrl = loadedUrl == null ? currentUrl : loadedUrl;
                Log.i(TAG, "finish " + loadedUrl);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                  android.webkit.WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    lastError = String.valueOf(error == null ? "Unknown error" : error.getDescription());
                }
            }
        });
        holder.addView(webView, new FrameLayout.LayoutParams(VIEW_W, VIEW_H));
        holder.measure(View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY));
        holder.layout(0, 0, VIEW_W, VIEW_H);
        if (activity != null && activity.getWindow() != null) {
            ViewGroup root = activity.findViewById(android.R.id.content);
            if (root != null) {
                root.addView(holder, new ViewGroup.LayoutParams(1, 1));
                holder.setAlpha(0f);
            }
        }
        startStabilityPoll();
    }

    private void startStabilityPoll() {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (webView == null) return;
                if (pageFinished) {
                    webView.evaluateJavascript("(document.body?document.body.innerText.length:0)",
                            value -> {
                                int len = 0;
                                try {
                                    len = Integer.parseInt(value == null ? "0" : value.trim());
                                } catch (Throwable ignored) {
                                }
                                if (len == lastLength) {
                                    stableCount++;
                                } else {
                                    lastLength = len;
                                    stableCount = 0;
                                }
                            });
                }
                main.postDelayed(this, 250);
            }
        }, 250);
    }

    private void close() {
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    if (webView != null) {
                        webView.stopLoading();
                        webView.destroy();
                        webView = null;
                    }
                    if (holder != null && holder.getParent() instanceof ViewGroup) {
                        ((ViewGroup) holder.getParent()).removeView(holder);
                    }
                    holder = null;
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static String normalize(String url) {
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("file://") || lower.startsWith("content://")
                || lower.startsWith("data:")) {
            return url;
        }
        if (url.startsWith("/")) return "file://" + url;
        if (lower.startsWith("localhost:") || lower.startsWith("localhost/")
                || lower.startsWith("127.0.0.1:") || lower.startsWith("127.0.0.1/")) {
            return "http://" + url;
        }
        return "https://" + url;
    }

    private static String decodeJsString(String raw) {
        String value = raw.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1);
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char n = value.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'u':
                        if (i + 4 < value.length()) {
                            try {
                                sb.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (Throwable ignored) {
                            }
                        }
                        break;
                    default: sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static int maxText() {
        return MAX_TEXT;
    }
}
