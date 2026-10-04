package com.example.eporchabulk;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private WebView web;
    private TextView status;
    private Button start, pause, stop;
    private DownloadManager dm;
    private long activeDownloadId = -1;
    private boolean running = false, paused = false, initialized = false;
    private int total = 0, current = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String URL = "https://eporcha.tech/mouza-vittik-khatian";

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        dm = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        buildUi();
        setupWebView();
        web.loadUrl(URL);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.VERTICAL); bar.setPadding(12,10,12,6);
        status = new TextView(this); status.setText("প্রথমে ওয়েবসাইটে বিভাগ, জেলা, উপজেলা/থানা ও সার্ভে নির্বাচন করুন। তারপর Start চাপুন।"); status.setTextSize(15); bar.addView(status);
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL);
        start = new Button(this); start.setText("Start"); pause = new Button(this); pause.setText("Pause"); stop = new Button(this); stop.setText("Stop");
        buttons.addView(start,new LinearLayout.LayoutParams(0,WRAP(),1)); buttons.addView(pause,new LinearLayout.LayoutParams(0,WRAP(),1)); buttons.addView(stop,new LinearLayout.LayoutParams(0,WRAP(),1));
        bar.addView(buttons); root.addView(bar,new LinearLayout.LayoutParams(-1,WRAP()));
        web = new WebView(this); root.addView(web,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
        start.setOnClickListener(v -> startAutomation()); pause.setOnClickListener(v -> doPause()); stop.setOnClickListener(v -> doStop());
    }
    private int WRAP(){return LinearLayout.LayoutParams.WRAP_CONTENT;}

    private void setupWebView() {
        WebSettings s=web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true); s.setSupportZoom(true); s.setBuiltInZoomControls(false); s.setDisplayZoomControls(false); s.setUserAgentString(s.getUserAgentString()+" EporchaBulkDownloader/1.0");
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(web,true);
        web.setWebViewClient(new WebViewClient(){ @Override public boolean shouldOverrideUrlLoading(WebView v,String u){ return false; } @Override public void onPageFinished(WebView v,String u){ status.setText("পেজ প্রস্তুত। ফিল্টারগুলো নির্বাচন করে Start চাপুন।"); }});
        web.setDownloadListener((url,userAgent,contentDisposition,mimeType,contentLength)->beginDownload(url,userAgent,contentDisposition,mimeType));
    }

    private void startAutomation(){
        if(running && paused){ paused=false; status.setText("Resume হয়েছে…"); if(activeDownloadId<0) clickNext(); return; }
        if(running) return;
        running=true; paused=false; initialized=false; current=0; total=0;
        status.setText("মৌজার তালিকা শনাক্ত করা হচ্ছে…");
        String js="(function(){var ss=[...document.querySelectorAll('select')].filter(s=>s.offsetParent!==null); if(!ss.length)return 'ERR'; var best=ss.reduce((a,b)=>b.options.length>a.options.length?b:a); var arr=[...best.options].filter(o=>o.value && !/নির্বাচ|select|প্রথমে/i.test((o.textContent||'').trim())); window.__epBulk={sel:best,opts:arr}; return String(arr.length);})()";
        web.evaluateJavascript(js, value -> { try { String x=value.replace("\\\"","").replace("\"",""); total=Integer.parseInt(x); if(total<=0) throw new Exception(); initialized=true; status.setText("মোট "+total+"টি মৌজা পাওয়া গেছে। ১ নম্বর থেকে শুরু হচ্ছে…"); clickNext(); } catch(Exception e){ running=false; status.setText("মৌজা তালিকা পাওয়া যায়নি। নিশ্চিত করুন যে বিভাগ/জেলা/উপজেলা/সার্ভে নির্বাচন করা হয়েছে।"); }});
    }

    private void clickNext(){
        if(!running || paused) return;
        if(current>=total){ running=false; status.setText("সমাপ্ত: "+total+"টি মৌজার ডাউনলোড শেষ হয়েছে।"); return; }
        final int idx=current;
        String js="(function(){try{var x=window.__epBulk;if(!x||!x.opts||!x.opts["+idx+"])return 'ERR'; var o=x.opts["+idx+"]; x.sel.value=o.value; x.sel.dispatchEvent(new Event('input',{bubbles:true})); x.sel.dispatchEvent(new Event('change',{bubbles:true})); return (o.textContent||'').trim();}catch(e){return 'ERR';}})()";
        web.evaluateJavascript(js, value->{ String name=unquote(value); status.setText("["+(idx+1)+"/"+total+"] "+name+" — PDF তৈরি/ডাউনলোড শুরু হচ্ছে…"); handler.postDelayed(()->clickDownloadButton(),1200); });
    }

    private void clickDownloadButton(){
        if(!running || paused) return;
        String js="(function(){var bs=[...document.querySelectorAll('button,a,input[type=button],input[type=submit]')].filter(e=>e.offsetParent!==null);var b=bs.find(e=>/পূর্ণ\\s*মৌজা\\s*PDF\\s*ডাউনলোড/i.test((e.innerText||e.value||e.textContent||'').trim()));if(!b)b=bs.find(e=>/PDF\\s*ডাউনলোড/i.test((e.innerText||e.value||e.textContent||'').trim()));if(!b)return 'ERR';b.scrollIntoView({block:'center'});b.click();return 'OK';})()";
        web.evaluateJavascript(js,value->{if(value.contains("ERR")){status.setText("ডাউনলোড বাটন পাওয়া যায়নি।"); running=false;} else status.setText("PDF তৈরির জন্য অপেক্ষা…");});
    }

    private void beginDownload(String url,String ua,String disposition,String mime){
        if(!running) return;
        try{
            DownloadManager.Request r=new DownloadManager.Request(Uri.parse(url));
            r.setMimeType(mime==null||mime.isEmpty()?"application/pdf":mime);
            String fileName = URLUtil.guessFileName(url, disposition, mime==null?"application/pdf":mime);
            if(fileName==null || fileName.trim().isEmpty()) fileName = "eporcha-"+(current+1)+".pdf";
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setTitle("ePorcha PDF "+(current+1)+"/"+total);
            r.setDescription("মৌজা ভিত্তিক খতিয়ান বহি");
            String cookie=CookieManager.getInstance().getCookie(url); if(cookie!=null) r.addRequestHeader("Cookie",cookie);
            r.addRequestHeader("User-Agent",ua==null?web.getSettings().getUserAgentString():ua);
            r.setAllowedOverMetered(true); r.setAllowedOverRoaming(true);
            activeDownloadId=dm.enqueue(r);
            final long id=activeDownloadId; pollDownload(id);
        }catch(Exception e){ status.setText("ডাউনলোড শুরু করা যায়নি: "+e.getMessage()); running=false; }
    }

    private void pollDownload(long id){
        handler.postDelayed(()->{
            if(activeDownloadId!=id) return;
            DownloadManager.Query q=new DownloadManager.Query().setFilterById(id); android.database.Cursor c=dm.query(q);
            boolean done=false, success=false; int reason=-1;
            if(c!=null){ if(c.moveToFirst()){ int st=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)); reason=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)); done=(st==DownloadManager.STATUS_SUCCESSFUL||st==DownloadManager.STATUS_FAILED); success=(st==DownloadManager.STATUS_SUCCESSFUL); } c.close(); }
            if(!done){ pollDownload(id); return; }
            activeDownloadId=-1;
            if(success){ current++; if(running&&!paused){ status.setText("ডাউনলোড সম্পন্ন: "+current+"/"+total); handler.postDelayed(this::clickNext,900); } else if(!running){status.setText("Stop করা হয়েছে।");} else status.setText("Paused — Resume চাপুন।"); }
            else { status.setText("ডাউনলোড ব্যর্থ হয়েছে (reason="+reason+")। Stop/Start দিয়ে আবার চেষ্টা করুন।"); running=false; }
        },1000);
    }

    private void doPause(){ if(!running)return; paused=true; status.setText("Paused. বর্তমান PDF শেষ হলে পরেরটি শুরু হবে না। Resume/Start চাপুন।"); }
    private void doStop(){ running=false; paused=false; if(activeDownloadId>=0){try{dm.remove(activeDownloadId);}catch(Exception ignored){}} activeDownloadId=-1; status.setText("Stopped. Start চাপলে শুরু থেকে আবার চালু হবে।"); }
    private String unquote(String s){ if(s==null)return ""; if(s.startsWith("\"")&&s.endsWith("\"")){s=s.substring(1,s.length()-1); s=s.replace("\\\"","\"").replace("\\n"," ");} return s; }
    @Override public void onBackPressed(){ if(web.canGoBack())web.goBack(); else super.onBackPressed(); }
}
