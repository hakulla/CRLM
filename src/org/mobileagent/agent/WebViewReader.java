package org.mobileagent.agent;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Hidden WebView used only when a static fetch cannot see JavaScript-rendered content. */
final class WebViewReader {
    private static final String UA = "Mozilla/5.0 (Linux; Android 14; Mobile) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final int MAX_TEXT = 14_000;
    private static final String JS_EXTRACT = "(function(){"
            + "var text='';try{text=document.body?(document.body.innerText||''):'';}catch(e){}"
            + "var frames='';try{for(var i=0;i<window.frames.length;i++){"
            + "try{var d=window.frames[i].document;if(d&&d.body)frames+='\\n'+d.body.innerText;}catch(e){}}}catch(e){}"
            + "var all=text+frames;"
            + "return JSON.stringify({title:document.title||'',url:location.href||'',text:all,len:all.length});"
            + "})()";

    static final class Result {
        final String data;
        final String error;

        private Result(String data, String error) {
            this.data = data;
            this.error = error;
        }

        static Result ok(String data) { return new Result(data, null); }
        static Result fail(String error) { return new Result(null, error); }
    }

    private WebViewReader() { }

    static Result read(Context context, String url, long timeoutMs) {
        if (context == null || url == null || url.trim().isEmpty()) {
            return Result.fail("Missing address");
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return Result.fail("Can not wait for page rendering on main thread");
        }
        final String clean = url.trim();
        final CountDownLatch latch = new CountDownLatch(1);
        final Handler main = new Handler(Looper.getMainLooper());
        final Session[] session = new Session[1];
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    session[0] = new Session(context, clean, timeoutMs, latch, main);
                    session[0].start();
                } catch (Throwable error) {
                    if (session[0] != null) {
                        session[0].close(Result.fail("Page renderer startup failed:" + error));
                    } else {
                        latch.countDown();
                    }
                }
            }
        });
        try {
            if (!latch.await(timeoutMs + 10_000L, TimeUnit.MILLISECONDS)) {
                final Session current = session[0];
                if (current != null) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            current.close(Result.fail("Page render timeout"));
                        }
                    });
                }
                return Result.fail("rendering timeout;pages may need to login or network too slow");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("Page render interrupted");
        }
        Session current = session[0];
        return current == null || current.result == null
                ? Result.fail("Page Renderer does not return the result") : current.result;
    }

    private static final class Session {
        final Context context;
        final String url;
        final long timeoutMs;
        final CountDownLatch latch;
        final Handler main;
        final long started = System.currentTimeMillis();
        volatile Result result;
        WebView webView;
        FrameLayout holder;
        boolean pageFinished;
        boolean finished;
        int lastLength = -1;
        int stableCount;

        Session(Context context, String url, long timeoutMs, CountDownLatch latch, Handler main) {
            this.context = context;
            this.url = url;
            this.timeoutMs = timeoutMs;
            this.latch = latch;
            this.main = main;
        }

        void start() {
            Activity activity = MainActivity.current();
            Context viewContext = activity == null ? context.getApplicationContext() : activity;
            webView = new WebView(viewContext);
            webView.setBackgroundColor(Color.TRANSPARENT);
            webView.setAlpha(1f);
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setSupportMultipleWindows(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setUserAgentString(UA);
            settings.setBlockNetworkImage(true);
            settings.setLoadsImagesAutomatically(false);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setMediaPlaybackRequiresUserGesture(true);
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            CookieManager.getInstance().setAcceptCookie(true);
            webView.onResume();
            webView.resumeTimers();
            webView.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onJsAlert(WebView view, String url, String message,
                                                   android.webkit.JsResult result) {
                    result.confirm();
                    return true;
                }
                @Override public boolean onJsConfirm(WebView view, String url, String message,
                                                     android.webkit.JsResult result) {
                    result.confirm();
                    return true;
                }
                @Override public boolean onJsPrompt(WebView view, String url, String message,
                                                    String defaultValue,
                                                    android.webkit.JsPromptResult result) {
                    result.confirm(defaultValue);
                    return true;
                }
                @Override public boolean onConsoleMessage(ConsoleMessage message) {
                    if (message != null) {
                        Log.i("AgentWebView", "console " + message.messageLevel() + " "
                                + message.message() + " @" + message.sourceId() + ":"
                                + message.lineNumber());
                    }
                    return true;
                }
            });
            webView.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    if (request == null || request.getUrl() == null) return true;
                    String scheme = request.getUrl().getScheme();
                    return !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
                }

                @Override public void onPageStarted(WebView view, String loadedUrl,
                                                    android.graphics.Bitmap favicon) {
                    Log.i("AgentWebView", "start " + loadedUrl);
                }

                @Override public void onPageFinished(WebView view, String loadedUrl) {
                    pageFinished = true;
                    Log.i("AgentWebView", "finish " + loadedUrl
                            + " viewUrl=" + (view == null ? "" : view.getUrl())
                            + " title=" + (view == null ? "" : view.getTitle()));
                    schedulePoll(250);
                }

                @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                      WebResourceError error) {
                    if (request != null && request.isForMainFrame()) {
                        Log.w("AgentWebView", "main error " + request.getUrl() + " "
                                + (error == null ? "" : error.getDescription()));
                        close(Result.fail("Cannot initialise Evolution's mail component."
                                + (error == null ? "Unknown error" : error.getDescription())
                                + "; " + request.getUrl()));
                    }
                }

                @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                                          WebResourceResponse response) {
                    if (request != null && request.isForMainFrame() && response != null) {
                        Log.w("AgentWebView", "http error " + response.getStatusCode()
                                + " " + request.getUrl());
                        close(Result.fail("Page HTTP " + response.getStatusCode()
                                + "; " + request.getUrl()));
                    }
                }
            });
            if (activity != null) {
                try {
                    ViewGroup root = activity.findViewById(android.R.id.content);
                    holder = new FrameLayout(activity);
                    holder.setAlpha(0.01f);
                    holder.setTranslationX(10000f);
                    holder.setClipChildren(false);
                    holder.setClickable(false);
                    holder.setFocusable(false);
                    holder.setImportantForAccessibility(
                            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                    root.addView(holder, new ViewGroup.LayoutParams(360, 640));
                    holder.addView(webView, new ViewGroup.LayoutParams(360, 640));
                } catch (Throwable ignored) {
                    holder = null;
                }
            }
            webView.loadUrl(url);
            schedulePoll(500);
        }

        void schedulePoll(long delayMs) {
            main.postDelayed(new Runnable() {
                @Override public void run() { poll(); }
            }, delayMs);
        }

        void poll() {
            if (finished) return;
            long elapsed = System.currentTimeMillis() - started;
            if (elapsed >= timeoutMs) {
                extract(true);
                return;
            }
            if (!pageFinished && elapsed < 1500) {
                schedulePoll(300);
                return;
            }
            extract(false);
        }

        void extract(final boolean finalPhase) {
            if (finished) return;
            try {
                webView.evaluateJavascript(JS_EXTRACT, value -> onJs(value, finalPhase));
            } catch (Throwable error) {
                if (finalPhase) close(Result.fail("Page content read failed:" + error));
                else schedulePoll(500);
            }
        }

        void onJs(String value, boolean finalPhase) {
            if (finished) return;
            try {
                Object decoded = new JSONTokener(value == null ? "\"\"" : value).nextValue();
                if (!(decoded instanceof String)) throw new IllegalArgumentException("bad JS result");
                JSONObject object = new JSONObject((String) decoded);
                String text = object.optString("text", "");
                String title = object.optString("title", "");
                String finalUrl = object.optString("url", url);
                int length = text.length();
                Log.i("AgentWebView", "extract len=" + length + " title=" + title
                        + " url=" + finalUrl + " preview="
                        + (text.length() > 180 ? text.substring(0, 180) : text));
                if (length > 200 && length == lastLength) stableCount++;
                else stableCount = 0;
                lastLength = length;
                // Wait for two stable reads so a loading shell is not mistaken for the article.
                if (length > 200 && stableCount >= 2) {
                    close(Result.ok(format(title, finalUrl, text)));
                    return;
                }
                if (finalPhase) {
                    if (length > 40) {
                        close(Result.ok(format(title, finalUrl, text)
                                + "\n\n[Clip: The main text is smaller, and the page may require login or still load]"));
                    } else {
                        close(Result.fail("page body is empty; may need to log in or be limited by site"
                                + "(title=" + title + ", url=" + finalUrl
                                + ", len=" + length + ")"));
                    }
                    return;
                }
                schedulePoll(500);
            } catch (Throwable error) {
                if (finalPhase) close(Result.fail("Page content resolution failed:" + error));
                else schedulePoll(500);
            }
        }

        String format(String title, String finalUrl, String text) {
            StringBuilder out = new StringBuilder();
            out.append("Source:").append(finalUrl).append('\n');
            if (title != null && !title.trim().isEmpty() && !"Chien-sama.".equals(title.trim())) {
                out.append("Title:").append(title.trim()).append('\n');
            }
            out.append("Rendering:WebView(Executed Page JavaScript)\n\n");
            String body = text == null ? "" : text.trim();
            if (body.length() > MAX_TEXT) {
                body = body.substring(0, MAX_TEXT) + "\n...[Text has been cut.]";
            }
            out.append(body);
            if ((body.contains("Login") || body.toLowerCase(java.util.Locale.ROOT).contains("sign in"))
                    && body.length() < 1000) {
                out.append("\n\n[Tip: The page appears to be a login page; the contents that require login are still not readable]");
            }
            return out.toString();
        }

        void close(Result value) {
            if (finished) return;
            finished = true;
            result = value;
            try {
                if (webView != null) {
                    webView.stopLoading();
                    if (webView.getParent() instanceof ViewGroup) {
                        ((ViewGroup) webView.getParent()).removeView(webView);
                    }
                    webView.destroy();
                    webView = null;
                }
                if (holder != null && holder.getParent() instanceof ViewGroup) {
                    ((ViewGroup) holder.getParent()).removeView(holder);
                }
                holder = null;
            } catch (Throwable ignored) {
            }
            latch.countDown();
        }
    }
}
