package com.example.eporchabulk;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private WebView web;
    private TextView status;
    private Button start, pause, stop;
    private DownloadManager dm;
    private long activeDownloadId = -1;
    private boolean running = false, paused = false;
    private int total = 0, current = 0;
    private int waitToken = 0;          // watchdog: detects "no download started"
    private String currentName = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String URL = "https://eporcha.tech/mouza-vittik-khatian";

    // ---- blob/data download state (written from JS bridge thread) ----
    private OutputStream blobOut;
    private Uri blobUri;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else if (Build.VERSION.SDK_INT < 29) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2);
        }
        buildUi();
        setupWebView();
        web.loadUrl(URL);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFFFFFFF);
        // Android 15 (targetSdk 35) draws edge-to-edge: keep our UI clear of system bars.
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(ins.getSystemWindowInsetLeft(), ins.getSystemWindowInsetTop(),
                    ins.getSystemWindowInsetRight(), ins.getSystemWindowInsetBottom());
            return ins;
        });

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(12, 10, 12, 6);
        status = new TextView(this);
        status.setText("প্রথমে ওয়েবসাইটে বিভাগ, জেলা, উপজেলা/থানা ও সার্ভে নির্বাচন করুন। তারপর Start চাপুন।");
        status.setTextSize(15);
        status.setTextColor(0xFF000000);
        bar.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        start = new Button(this); start.setText("Start");
        pause = new Button(this); pause.setText("Pause");
        stop = new Button(this); stop.setText("Stop");
        buttons.addView(start, new LinearLayout.LayoutParams(0, WRAP(), 1));
        buttons.addView(pause, new LinearLayout.LayoutParams(0, WRAP(), 1));
        buttons.addView(stop, new LinearLayout.LayoutParams(0, WRAP(), 1));
        bar.addView(buttons);
        root.addView(bar, new LinearLayout.LayoutParams(-1, WRAP()));

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        start.setOnClickListener(v -> startAutomation());
        pause.setOnClickListener(v -> doPause());
        stop.setOnClickListener(v -> doStop());
    }

    private int WRAP() { return LinearLayout.LayoutParams.WRAP_CONTENT; }

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String u) { return false; }
            @Override public void onPageFinished(WebView v, String u) {
                if (!running) status.setText("পেজ প্রস্তুত। ফিল্টারগুলো নির্বাচন করে Start চাপুন।");
            }
        });
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                beginDownload(url, userAgent, contentDisposition, mimeType));
    }

    // ------------------------------------------------------------------ automation

    private void startAutomation() {
        if (running && paused) {
            paused = false;
            status.setText("Resume হয়েছে…");
            if (activeDownloadId < 0 && blobOut == null) clickNext();
            return;
        }
        if (running) return;
        running = true; paused = false; current = 0; total = 0;
        status.setText("মৌজার তালিকা শনাক্ত করা হচ্ছে…");
        String js = "(function(){"
                + "var ss=[].slice.call(document.querySelectorAll('select')).filter(function(s){return s.offsetParent!==null;});"
                + "if(!ss.length)return 'ERR';"
                + "var best=ss.reduce(function(a,b){return b.options.length>a.options.length?b:a;});"
                + "var arr=[].slice.call(best.options).filter(function(o){return o.value && !/নির্বাচ|select|প্রথমে/i.test((o.textContent||'').trim());});"
                + "window.__epBulk={vals:arr.map(function(o){return o.value;}),names:arr.map(function(o){return (o.textContent||'').trim();})};"
                + "return String(arr.length);})()";
        web.evaluateJavascript(js, value -> {
            try {
                total = Integer.parseInt(unquote(value));
                if (total <= 0) throw new Exception();
                status.setText("মোট " + total + "টি মৌজা পাওয়া গেছে। ১ নম্বর থেকে শুরু হচ্ছে…");
                clickNext();
            } catch (Exception e) {
                running = false;
                status.setText("মৌজা তালিকা পাওয়া যায়নি। নিশ্চিত করুন যে বিভাগ/জেলা/উপজেলা/সার্ভে নির্বাচন করা হয়েছে।");
            }
        });
    }

    private void clickNext() {
        if (!running || paused) return;
        if (current >= total) {
            running = false;
            status.setText("সমাপ্ত: " + total + "টি মৌজার ডাউনলোড শেষ হয়েছে।");
            return;
        }
        final int idx = current;
        // Re-find the select each time (the page may re-render it) and use the native
        // value setter so React/Vue style frameworks notice the change.
        String js = "(function(){try{var x=window.__epBulk;if(!x||!x.vals||x.vals.length<=" + idx + ")return 'ERR';"
                + "var ss=[].slice.call(document.querySelectorAll('select')).filter(function(s){return s.offsetParent!==null;});"
                + "if(!ss.length)return 'ERR';"
                + "var sel=ss.reduce(function(a,b){return b.options.length>a.options.length?b:a;});"
                + "var setter=Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'value').set;"
                + "setter.call(sel,x.vals[" + idx + "]);"
                + "sel.dispatchEvent(new Event('input',{bubbles:true}));"
                + "sel.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return x.names[" + idx + "];}catch(e){return 'ERR';}})()";
        web.evaluateJavascript(js, value -> {
            String name = unquote(value);
            if (name.equals("ERR")) {
                running = false;
                status.setText("মৌজা নির্বাচন করা যায়নি। পেজ রিলোড করে আবার চেষ্টা করুন।");
                return;
            }
            currentName = name;
            status.setText("[" + (idx + 1) + "/" + total + "] " + name + " — PDF তৈরি/ডাউনলোড শুরু হচ্ছে…");
            handler.postDelayed(this::clickDownloadButton, 1500);
        });
    }

    private void clickDownloadButton() {
        if (!running || paused) return;
        String js = "(function(){var bs=[].slice.call(document.querySelectorAll('button,a,input[type=button],input[type=submit]')).filter(function(e){return e.offsetParent!==null;});"
                + "var b=bs.find(function(e){return /পূর্ণ\\s*মৌজা\\s*PDF\\s*ডাউনলোড/i.test((e.innerText||e.value||e.textContent||'').trim());});"
                + "if(!b)b=bs.find(function(e){return /PDF\\s*ডাউনলোড/i.test((e.innerText||e.value||e.textContent||'').trim());});"
                + "if(!b)return 'ERR';b.scrollIntoView({block:'center'});b.click();return 'OK';})()";
        web.evaluateJavascript(js, value -> {
            if (value.contains("ERR")) {
                status.setText("ডাউনলোড বাটন পাওয়া যায়নি।");
                running = false;
            } else {
                status.setText("[" + (current + 1) + "/" + total + "] PDF তৈরির জন্য অপেক্ষা…");
                startWatchdog();
            }
        });
    }

    /** If no download begins within 3 minutes, stop instead of hanging forever. */
    private void startWatchdog() {
        final int token = ++waitToken;
        handler.postDelayed(() -> {
            if (token == waitToken && running && activeDownloadId < 0 && blobOut == null) {
                running = false;
                status.setText("৩ মিনিটেও ডাউনলোড শুরু হয়নি। ইন্টারনেট/সাইট পরীক্ষা করে Start চাপুন (বর্তমান: " + (current + 1) + "/" + total + ")।");
            }
        }, 180000);
    }

    // ------------------------------------------------------------------ downloads

    private String safeName() {
        String n = currentName == null ? "" : currentName.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (n.isEmpty()) n = "mouza";
        return String.format("%03d-%s.pdf", current + 1, n);
    }

    private void beginDownload(String url, String ua, String disposition, String mime) {
        if (!running) return;
        waitToken++; // download started: cancel watchdog
        if (url.startsWith("blob:") || url.startsWith("data:")) { downloadViaJs(url); return; }
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setMimeType(mime == null || mime.isEmpty() ? "application/pdf" : mime);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "ePorcha/" + safeName());
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setTitle("ePorcha PDF " + (current + 1) + "/" + total);
            r.setDescription(currentName);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            r.addRequestHeader("User-Agent", ua == null ? web.getSettings().getUserAgentString() : ua);
            r.setAllowedOverMetered(true);
            r.setAllowedOverRoaming(true);
            activeDownloadId = dm.enqueue(r);
            pollDownload(activeDownloadId);
        } catch (Exception e) {
            status.setText("ডাউনলোড শুরু করা যায়নি: " + e.getMessage());
            running = false;
        }
    }

    private void pollDownload(final long id) {
        handler.postDelayed(() -> {
            if (activeDownloadId != id) return;
            DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
            android.database.Cursor c = dm.query(q);
            boolean done = false, success = false;
            int reason = -1;
            if (c != null) {
                if (c.moveToFirst()) {
                    int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    done = (st == DownloadManager.STATUS_SUCCESSFUL || st == DownloadManager.STATUS_FAILED);
                    success = (st == DownloadManager.STATUS_SUCCESSFUL);
                } else {
                    done = true; // download record vanished (cancelled)
                }
                c.close();
            }
            if (!done) { pollDownload(id); return; }
            activeDownloadId = -1;
            finishItem(success, "ডাউনলোড ব্যর্থ হয়েছে (reason=" + reason + ")। Start দিয়ে আবার চেষ্টা করুন।");
        }, 1000);
    }

    /** blob:/data: URLs can't go through DownloadManager; read them in the page and save here. */
    private void downloadViaJs(String url) {
        String js = "(function(u,name){var x=new XMLHttpRequest();x.open('GET',u);x.responseType='blob';"
                + "x.onload=function(){var r=new FileReader();r.onloadend=function(){try{var s=r.result;var b=s.substring(s.indexOf(',')+1);"
                + "AndroidBridge.begin(name);var n=1048576;for(var i=0;i<b.length;i+=n){AndroidBridge.chunk(b.substring(i,i+n));}AndroidBridge.end();}"
                + "catch(e){AndroidBridge.fail(String(e));}};r.readAsDataURL(x.response);};"
                + "x.onerror=function(){AndroidBridge.fail('read failed');};x.send();})('" + jsq(url) + "','" + jsq(safeName()) + "')";
        status.setText("[" + (current + 1) + "/" + total + "] PDF সংরক্ষণ করা হচ্ছে…");
        web.evaluateJavascript(js, null);
    }

    private static String jsq(String s) { return s.replace("\\", "\\\\").replace("'", "\\'"); }

    private class Bridge {
        @JavascriptInterface public void begin(String name) {
            try {
                closeBlob();
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ePorcha");
                    blobUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    blobOut = getContentResolver().openOutputStream(blobUri);
                } else {
                    java.io.File dir = new java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ePorcha");
                    dir.mkdirs();
                    blobOut = new java.io.FileOutputStream(new java.io.File(dir, name));
                }
            } catch (Exception e) {
                blobOut = null;
                fail("ফাইল তৈরি করা যায়নি: " + e.getMessage());
            }
        }
        @JavascriptInterface public void chunk(String b64) {
            try { if (blobOut != null) blobOut.write(Base64.decode(b64, Base64.DEFAULT)); }
            catch (Exception e) { fail("লেখা যায়নি: " + e.getMessage()); }
        }
        @JavascriptInterface public void end() {
            closeBlob();
            handler.post(() -> finishItem(true, ""));
        }
        @JavascriptInterface public void fail(String msg) {
            closeBlob();
            handler.post(() -> finishItem(false, "ডাউনলোড ব্যর্থ: " + msg));
        }
    }

    private void closeBlob() {
        try { if (blobOut != null) blobOut.close(); } catch (Exception ignored) {}
        blobOut = null;
    }

    private void finishItem(boolean success, String errorMsg) {
        if (success) {
            current++;
            if (running && !paused) {
                status.setText("ডাউনলোড সম্পন্ন: " + current + "/" + total);
                handler.postDelayed(this::clickNext, 1200);
            } else if (!running) {
                status.setText("Stop করা হয়েছে।");
            } else {
                status.setText("Paused — Resume চাপুন।");
            }
        } else {
            running = false;
            status.setText(errorMsg);
        }
    }

    private void doPause() {
        if (!running) return;
        paused = true;
        status.setText("Paused. বর্তমান PDF শেষ হলে পরেরটি শুরু হবে না। Resume/Start চাপুন।");
    }

    private void doStop() {
        running = false; paused = false; waitToken++;
        if (activeDownloadId >= 0) { try { dm.remove(activeDownloadId); } catch (Exception ignored) {} }
        activeDownloadId = -1;
        closeBlob();
        status.setText("Stopped. Start চাপলে শুরু থেকে আবার চালু হবে।");
    }

    private String unquote(String s) {
        if (s == null) return "";
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\n", " ");
        }
        return s;
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
