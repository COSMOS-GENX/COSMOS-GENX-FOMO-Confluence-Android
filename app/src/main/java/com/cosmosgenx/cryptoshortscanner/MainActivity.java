package com.cosmosgenx.cryptoshortscanner;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import com.google.firebase.messaging.FirebaseMessaging;

public class MainActivity extends Activity {
    private static final long AUTO_REFRESH_MS = 60_000L;
    private static final String ALERT_CHANNEL = "short_scanner_alerts";

    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient wsClient = new OkHttpClient();

    private LinearLayout results;
    private TextView status, liveStatus, topSummary;
    private Button scanButton;
    private CheckBox autoRefresh;
    private ProgressBar progress;
    private WebSocket priceSocket;

    private final DecimalFormat pct = new DecimalFormat("0.00");
    private final DecimalFormat priceFmt = new DecimalFormat("0.########");
    private final Set<String> EXCLUDED = new HashSet<>();
    private final Set<String> alerted = new HashSet<>();
    private final Map<String, TextView> livePriceViews = new HashMap<>();
    private List<Candidate> lastResults = new ArrayList<>();
    private boolean scanning = false;

    private final Runnable autoScanRunnable = new Runnable() {
        @Override public void run() {
            if (autoRefresh != null && autoRefresh.isChecked()) {
                scan();
                main.postDelayed(this, AUTO_REFRESH_MS);
            }
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        Collections.addAll(EXCLUDED,
                "BTCUSDT","ETHUSDT","BNBUSDT","SOLUSDT","XRPUSDT","DOGEUSDT","ADAUSDT",
                "TRXUSDT","AVAXUSDT","LINKUSDT","BCHUSDT","LTCUSDT","DOTUSDT","TONUSDT");
        createNotificationChannel();
        requestNotificationPermission();
        setContentView(buildUi());
        registerPushToken();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(autoScanRunnable);
        if (priceSocket != null) priceSocket.cancel();
        executor.shutdownNow();
        wsClient.dispatcher().executorService().shutdown();
    }

    private View buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad,pad,pad,pad);
        root.setBackgroundColor(Color.rgb(8,12,18));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("SHORT SCANNER", 24, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0,-2,1));
        liveStatus = pill("LIVE • OFF", Color.rgb(105,115,130));
        header.addView(liveStatus);
        root.addView(header);

        TextView sub = text("Binance USDⓈ-M • 1–3 min scalper mode • execution disabled", 12, Color.rgb(145,155,170), false);
        sub.setPadding(0,dp(4),0,dp(12));
        root.addView(sub);

        topSummary = text("No scan yet. Tap SCAN NOW to rank volatile altcoin short setups.", 13, Color.rgb(205,211,222), false);
        topSummary.setPadding(dp(12),dp(11),dp(12),dp(11));
        topSummary.setBackgroundColor(Color.rgb(18,24,34));
        root.addView(topSummary, new LinearLayout.LayoutParams(-1,-2));

        scanButton = new Button(this);
        scanButton.setText("SCAN NOW");
        scanButton.setTextSize(18);
        scanButton.setTextColor(Color.rgb(8,12,18));
        scanButton.setBackgroundColor(Color.rgb(246,195,68));
        scanButton.setAllCaps(false);
        scanButton.setOnClickListener(v -> {
            BackendClient.triggerRemoteScan(this);
            scan();
        });
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-1,dp(58));
        sbp.setMargins(0,dp(12),0,0);
        root.addView(scanButton, sbp);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        autoRefresh = new CheckBox(this);
        autoRefresh.setText("Auto scan every 60s");
        autoRefresh.setTextColor(Color.rgb(205,211,222));
        autoRefresh.setTextSize(13);
        autoRefresh.setOnCheckedChangeListener((buttonView, isChecked) -> {
            main.removeCallbacks(autoScanRunnable);
            if (isChecked) main.postDelayed(autoScanRunnable, AUTO_REFRESH_MS);
        });
        controls.addView(autoRefresh, new LinearLayout.LayoutParams(0,-2,1));
        TextView alertTag = pill("PUSH + LOCAL", Color.rgb(245,196,81));
        controls.addView(alertTag);
        root.addView(controls);

        LinearLayout serverRow = new LinearLayout(this);
        serverRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView bg = text(BackendClient.isConfigured(this) ? "Background server: configured" : "Background server: not configured", 11,
                BackendClient.isConfigured(this) ? Color.rgb(86,215,163) : Color.rgb(245,196,81), true);
        serverRow.addView(bg, new LinearLayout.LayoutParams(0,-2,1));
        Button configure = new Button(this);
        configure.setText("SERVER"); configure.setAllCaps(false); configure.setTextSize(11);
        configure.setOnClickListener(v -> showServerDialog(bg));
        serverRow.addView(configure, new LinearLayout.LayoutParams(dp(95),dp(42)));
        root.addView(serverRow);

        status = text("Ready.", 12, Color.rgb(155,165,180), false);
        status.setPadding(0,dp(8),0,dp(6));
        root.addView(status);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1,dp(4)));

        ScrollView sv = new ScrollView(this);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        sv.addView(results);
        root.addView(sv, new LinearLayout.LayoutParams(-1,0,1));

        TextView foot = text("Tap a token for detailed entry logic. Local scans use Binance market data. When a backend URL + Firebase values are configured, server-side monitoring can continue with the app closed and deliver push alerts from multiple catalyst sources. No trading API key is stored in the APK. Signals are probabilistic; verify before trading.", 10, Color.rgb(110,120,135), false);
        foot.setPadding(0,dp(8),0,0);
        root.addView(foot);
        return root;
    }

    private void scan() {
        if (scanning) return;
        scanning = true;
        scanButton.setEnabled(false);
        scanButton.setText("SCANNING…");
        progress.setVisibility(View.VISIBLE);
        status.setText("Scanning Binance perpetuals + negative catalysts…");

        executor.execute(() -> {
            try {
                Map<String,String> catalysts = getBinanceNegativeCatalysts();
                List<Candidate> shortlist = getShortlist();
                List<Candidate> scored = Collections.synchronizedList(new ArrayList<>());
                List<Thread> threads = new ArrayList<>();
                for (Candidate c : shortlist) {
                    Thread t = new Thread(() -> {
                        try {
                            analyze(c);
                            String base = c.symbol.substring(0, c.symbol.length()-4);
                            c.catalyst = catalysts.get(base);
                            if (c.catalyst != null) {
                                c.score = Math.min(100, c.score + 15);
                                c.catalystScore = 15;
                                deriveAction(c);
                            }
                            scored.add(c);
                        } catch (Exception ignored) {}
                    });
                    threads.add(t); t.start();
                }
                for (Thread t : threads) t.join();
                scored.sort((a,b2) -> Double.compare(b2.score,a.score));
                main.post(() -> render(scored));
            } catch (Exception e) {
                main.post(() -> {
                    scanning = false;
                    status.setText("Scan failed: " + safeMsg(e));
                    scanButton.setEnabled(true);
                    scanButton.setText("SCAN AGAIN");
                    progress.setVisibility(View.GONE);
                });
            }
        });
    }

    private List<Candidate> getShortlist() throws Exception {
        JSONArray arr = new JSONArray(get("https://fapi.binance.com/fapi/v1/ticker/24hr"));
        List<Candidate> all = new ArrayList<>();
        for (int i=0;i<arr.length();i++) {
            JSONObject o=arr.getJSONObject(i);
            String s=o.optString("symbol");
            if (!s.endsWith("USDT") || EXCLUDED.contains(s)) continue;
            double q=toD(o.optString("quoteVolume"));
            double ch=toD(o.optString("priceChangePercent"));
            if (q < 3_000_000) continue;
            Candidate c=new Candidate();
            c.symbol=s; c.change24=ch; c.quoteVolume=q; c.price=toD(o.optString("lastPrice"));
            c.preScore=Math.min(35,Math.abs(ch))*1.25 + Math.log10(Math.max(q,1))*2;
            all.add(c);
        }
        all.sort((a,b)->Double.compare(b.preScore,a.preScore));
        return all.subList(0,Math.min(40,all.size()));
    }

    private void analyze(Candidate c) throws Exception {
        JSONArray k = new JSONArray(get("https://fapi.binance.com/fapi/v1/klines?symbol="+c.symbol+"&interval=1m&limit=40"));
        if (k.length()<25) return;
        double[] close=new double[k.length()], high=new double[k.length()], low=new double[k.length()], vol=new double[k.length()];
        for(int i=0;i<k.length();i++) {
            JSONArray x=k.getJSONArray(i);
            high[i]=x.getDouble(2); low[i]=x.getDouble(3); close[i]=x.getDouble(4); vol[i]=x.getDouble(5);
        }
        int n=k.length(); c.price=close[n-1];
        c.ret1=(close[n-1]/close[n-2]-1)*100.0;
        c.ret5=(close[n-1]/close[n-6]-1)*100.0;
        c.ret15=(close[n-1]/close[n-16]-1)*100.0;
        c.range15High = high[n-1]; c.range15Low = low[n-1];
        for(int i=n-15;i<n;i++){ c.range15High=Math.max(c.range15High,high[i]); c.range15Low=Math.min(c.range15Low,low[i]); }

        double recent=0, base=0;
        for(int i=n-5;i<n;i++) recent+=vol[i];
        for(int i=n-25;i<n-5;i++) base+=vol[i];
        c.volRatio=(recent/5.0)/Math.max(base/20.0,1e-12);
        int lower=0;
        for(int i=n-6;i<n;i++) if(high[i]<high[i-1] && low[i]<low[i-1]) lower++;
        c.lowerCount=lower;

        double score=0;
        if(c.change24<0) score += Math.min(28, -c.change24*0.85); else score -= Math.min(12,c.change24*0.25);
        if(c.ret1<0) score += Math.min(10,-c.ret1*8); else score -= Math.min(8,c.ret1*5);
        if(c.ret5<0) score += Math.min(24,-c.ret5*5.5); else score -= Math.min(10,c.ret5*3);
        if(c.ret15<0) score += Math.min(14,-c.ret15*2.2);
        score += Math.min(12,Math.max(0,c.volRatio-1)*7);
        score += c.lowerCount*3;
        if(c.ret5 < -8) { score -= 14; c.extended=true; }
        if(c.ret15 < -18) { score -= 8; c.extended=true; }
        c.score=Math.max(0,Math.min(100,score));
        deriveAction(c);
    }

    private void deriveAction(Candidate c) {
        if (c.extended && c.score >= 55) {
            c.action="WAIT";
            c.reason="Trend is bearish but the move is extended; wait for a bounce and rejection.";
        } else if(c.score>=75 && c.ret1<0 && c.lowerCount>=3) {
            c.action="ENTER WATCH";
            c.reason="Bearish 1m/5m/15m alignment with repeated lower highs/lows. Confirm the next 1m breakdown before entry.";
        } else if(c.score>=50) {
            c.action="WAIT";
            c.reason="Bearish candidate, but confirmation is incomplete. Prefer a lower-high rejection or swing-low break.";
        } else {
            c.action="ABORT";
            c.reason="Current momentum/structure is not strong enough for this short-scanner profile.";
        }
    }

    /**
     * Uses Binance's public CMS announcement endpoint. It is intentionally best-effort because
     * Binance can change this undocumented public response shape. Failure never blocks technical scan.
     */
    private Map<String,String> getBinanceNegativeCatalysts() {
        Map<String,String> out = new HashMap<>();
        String[] urls = new String[] {
                "https://www.binance.com/bapi/composite/v1/public/cms/article/catalog/list/query?catalogId=161&pageNo=1&pageSize=50",
                "https://www.binance.com/bapi/composite/v1/public/cms/article/catalog/list/query?catalogId=48&pageNo=1&pageSize=50"
        };
        for (String url : urls) {
            try {
                JSONObject root = new JSONObject(get(url));
                JSONArray articles = findArticles(root);
                if (articles == null) continue;
                for (int i=0;i<articles.length();i++) {
                    JSONObject a=articles.optJSONObject(i); if(a==null) continue;
                    String title=a.optString("title","");
                    String lower=title.toLowerCase(Locale.US);
                    if (!(lower.contains("delist") || lower.contains("remov") || lower.contains("suspend") || lower.contains("margin") || lower.contains("contract"))) continue;
                    for (String token : extractUpperTokens(title)) {
                        if (token.length() >= 2 && token.length() <= 12) out.put(token, title);
                    }
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    private JSONArray findArticles(JSONObject root) {
        JSONObject data=root.optJSONObject("data");
        if(data==null) return null;
        JSONArray arr=data.optJSONArray("articles"); if(arr!=null) return arr;
        JSONArray catalogs=data.optJSONArray("catalogs");
        if(catalogs!=null) {
            JSONArray merged=new JSONArray();
            for(int i=0;i<catalogs.length();i++) {
                JSONObject c=catalogs.optJSONObject(i); if(c==null) continue;
                JSONArray aa=c.optJSONArray("articles"); if(aa==null) continue;
                for(int j=0;j<aa.length();j++) merged.put(aa.opt(j));
            }
            return merged.length()>0?merged:null;
        }
        return null;
    }

    private Set<String> extractUpperTokens(String title) {
        Set<String> r=new HashSet<>();
        String cleaned=title.replaceAll("[^A-Za-z0-9]"," ");
        for(String w:cleaned.split("\\s+")) {
            if(w.matches("[A-Z0-9]{2,12}") && !w.equals("BINANCE") && !w.equals("USDT") && !w.equals("USD") && !w.equals("USDC")) r.add(w);
        }
        return r;
    }

    private void render(List<Candidate> xs) {
        scanning=false;
        progress.setVisibility(View.GONE);
        scanButton.setEnabled(true);
        scanButton.setText("SCAN AGAIN");
        results.removeAllViews();
        livePriceViews.clear();
        lastResults = new ArrayList<>(xs);

        SimpleDateFormat f=new SimpleDateFormat("MMM d • h:mm:ss a",Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Manila"));
        status.setText("Scan complete • "+f.format(new Date())+" • Manila");
        if(xs.isEmpty()) {
            topSummary.setText("No candidates returned.");
            results.addView(text("No candidates returned.",15,Color.WHITE,false));
            return;
        }
        int shown=Math.min(10,xs.size());
        Candidate top=xs.get(0);
        topSummary.setText("TOP SETUP  •  "+top.symbol+"  •  "+Math.round(top.score)+"/100  •  "+top.action+"\n"+
                "24h "+fmt(top.change24)+"%  |  1m "+fmt(top.ret1)+"%  |  5m "+fmt(top.ret5)+"%  |  15m "+fmt(top.ret15)+"%");
        for(int i=0;i<shown;i++) addCard(i+1,xs.get(i));
        startPriceStream(xs.subList(0, shown));
        maybeAlert(top);
    }

    private void addCard(int rank, Candidate c) {
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14),dp(12),dp(14),dp(12));
        card.setBackgroundColor(Color.rgb(19,26,37));
        card.setOnClickListener(v -> showDetail(c));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(8),0,0);
        results.addView(card,lp);

        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name=text("#"+rank+"  "+c.symbol,18,Color.WHITE,true);
        row.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        int sig=signalColor(c);
        TextView sc=text(((int)Math.round(c.score))+"/100",17,sig,true); row.addView(sc); card.addView(row);

        LinearLayout row2=new LinearLayout(this); row2.setGravity(Gravity.CENTER_VERTICAL);
        TextView action=text(c.action,12,sig,true); row2.addView(action,new LinearLayout.LayoutParams(0,-2,1));
        TextView livePrice=text(priceFmt.format(c.price),15,Color.rgb(86,215,163),true);
        livePriceViews.put(c.symbol, livePrice);
        row2.addView(livePrice); card.addView(row2);

        String meta="1m  "+fmt(c.ret1)+"%    5m  "+fmt(c.ret5)+"%    15m  "+fmt(c.ret15)+"%    24h  "+fmt(c.change24)+"%\n"+
                "Volume  "+pct.format(c.volRatio)+"×    Lower structure  "+c.lowerCount+"/5";
        TextView m=text(meta,12,Color.rgb(184,192,204),false); m.setPadding(0,dp(6),0,0); card.addView(m);

        String catalyst = c.catalyst == null ? "Catalyst: none detected in latest Binance public notices" : "⚠ Catalyst: "+c.catalyst;
        TextView cat=text(catalyst,11,c.catalyst==null?Color.rgb(125,135,150):Color.rgb(245,196,81),false);
        cat.setPadding(0,dp(5),0,0); card.addView(cat);

        TextView hint=text("Tap for detail →",10,Color.rgb(112,152,220),false); hint.setPadding(0,dp(5),0,0); card.addView(hint);
    }

    private void showDetail(Candidate c) {
        String catalyst = c.catalyst == null ? "No fresh Binance negative notice detected." : c.catalyst;
        String message =
                "Current price: "+priceFmt.format(c.price)+"\n\n"+
                "SHORT SCORE: "+Math.round(c.score)+"/100\n"+
                "ACTION: "+c.action+"\n\n"+
                "Momentum\n"+
                "• 1m: "+fmt(c.ret1)+"%\n"+
                "• 5m: "+fmt(c.ret5)+"%\n"+
                "• 15m: "+fmt(c.ret15)+"%\n"+
                "• 24h: "+fmt(c.change24)+"%\n\n"+
                "Structure\n"+
                "• Lower candles: "+c.lowerCount+"/5\n"+
                "• Volume acceleration: "+pct.format(c.volRatio)+"×\n"+
                "• 15m range: "+priceFmt.format(c.range15Low)+" – "+priceFmt.format(c.range15High)+"\n\n"+
                "Catalyst\n"+catalyst+"\n\n"+
                "Scanner logic\n"+c.reason+"\n\n"+
                "Execution remains disabled in this build.";
        new AlertDialog.Builder(this)
                .setTitle(c.symbol)
                .setMessage(message)
                .setPositiveButton("CLOSE",null)
                .show();
    }

    private int signalColor(Candidate c) {
        if (c.action.equals("ENTER WATCH")) return Color.rgb(255,78,96);
        if (c.action.equals("WAIT")) return Color.rgb(245,196,81);
        return Color.rgb(145,155,170);
    }

    private void startPriceStream(List<Candidate> xs) {
        if (priceSocket != null) priceSocket.cancel();
        if(xs.isEmpty()) return;
        StringBuilder streams=new StringBuilder();
        for(Candidate c:xs) {
            if(streams.length()>0) streams.append('/');
            streams.append(c.symbol.toLowerCase(Locale.US)).append("@markPrice@1s");
        }
        Request req=new Request.Builder().url("wss://fstream.binance.com/stream?streams="+streams).build();
        priceSocket=wsClient.newWebSocket(req,new WebSocketListener(){
            @Override public void onOpen(WebSocket webSocket, Response response) {
                main.post(() -> { liveStatus.setText("LIVE • ON"); liveStatus.setTextColor(Color.rgb(86,215,163)); });
            }
            @Override public void onMessage(WebSocket webSocket,String textMsg) {
                try {
                    JSONObject root=new JSONObject(textMsg); JSONObject d=root.optJSONObject("data"); if(d==null)return;
                    String s=d.optString("s"); double p=toD(d.optString("p"));
                    main.post(() -> {
                        TextView tv=livePriceViews.get(s); if(tv!=null) tv.setText(priceFmt.format(p));
                        for(Candidate c:lastResults) if(c.symbol.equals(s)) c.price=p;
                    });
                } catch(Exception ignored){}
            }
            @Override public void onFailure(WebSocket webSocket,Throwable t,Response response) {
                main.post(() -> { liveStatus.setText("LIVE • RETRY"); liveStatus.setTextColor(Color.rgb(245,196,81)); });
            }
            @Override public void onClosed(WebSocket webSocket,int code,String reason) {
                main.post(() -> { liveStatus.setText("LIVE • OFF"); liveStatus.setTextColor(Color.rgb(125,135,150)); });
            }
        });
    }

    private void maybeAlert(Candidate c) {
        if(c.score < 80 || c.action.equals("ABORT") || alerted.contains(c.symbol)) return;
        alerted.add(c.symbol);
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationCompat.Builder b=new NotificationCompat.Builder(this,ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("Short setup: "+c.symbol)
                .setContentText(Math.round(c.score)+"/100 • "+c.action+" • 1m "+fmt(c.ret1)+"% • 5m "+fmt(c.ret5)+"%")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        NotificationManagerCompat.from(this).notify(Math.abs(c.symbol.hashCode()),b.build());
    }

    private void createNotificationChannel() {
        if(Build.VERSION.SDK_INT>=26) {
            NotificationChannel ch=new NotificationChannel(ALERT_CHANNEL,"Short Scanner Alerts",NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("High-score short setup alerts while the app is scanning.");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private void requestNotificationPermission() {
        if(Build.VERSION.SDK_INT>=33 && ActivityCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.POST_NOTIFICATIONS},7);
        }
    }

    private void showServerDialog(TextView serverStatus) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://scanner.example.com");
        input.setText(BackendClient.getBaseUrl(this));
        EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setHint("Server API key (optional)");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(BackendClient.getApiKey(this));
        int p=dp(18);
        LinearLayout wrap=new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL); wrap.setPadding(p,dp(4),p,0);
        wrap.addView(input,new LinearLayout.LayoutParams(-1,-2)); wrap.addView(key,new LinearLayout.LayoutParams(-1,-2));
        new AlertDialog.Builder(this)
                .setTitle("Background scanner server")
                .setMessage("Enter the HTTPS URL of your deployed v3 backend. Leave blank to disable remote scanning/push registration.")
                .setView(wrap)
                .setNegativeButton("CANCEL",null)
                .setPositiveButton("SAVE",(d,w)-> {
                    BackendClient.setBaseUrl(this,input.getText().toString().trim());
                    BackendClient.setApiKey(this,key.getText().toString().trim());
                    boolean ok=BackendClient.isConfigured(this);
                    serverStatus.setText(ok?"Background server: configured":"Background server: not configured");
                    serverStatus.setTextColor(ok?Color.rgb(86,215,163):Color.rgb(245,196,81));
                    if(ok) registerPushToken();
                    Toast.makeText(this, ok?"Server saved. Registering push token…":"Remote background scanning disabled.", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void registerPushToken() {
        if (!BackendClient.isConfigured(this) || !ScannerApplication.isFirebaseConfigured()) return;
        try {
            FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
                if(task.isSuccessful() && task.getResult()!=null) BackendClient.registerDevice(this,task.getResult());
            });
        } catch (Exception ignored) {}
    }

    private TextView pill(String s,int color){
        TextView t=text(s,11,color,true); t.setPadding(dp(9),dp(5),dp(9),dp(5)); t.setBackgroundColor(Color.rgb(25,32,44)); return t;
    }
    private String safeMsg(Exception e){ return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(); }
    private String fmt(double x){return (x>0?"+":"")+pct.format(x);}
    private double toD(String s){try{return Double.parseDouble(s);}catch(Exception e){return 0;}}
    private String get(String u) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent","CryptoShortScanner/3.0");
        c.setRequestProperty("Accept","application/json");
        int code=c.getResponseCode(); if(code<200||code>=300) throw new Exception("HTTP "+code);
        BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder sb=new StringBuilder(); String line; while((line=br.readLine())!=null) sb.append(line);
        br.close(); c.disconnect(); return sb.toString();
    }
    private TextView text(String s,int sp,int color,boolean bold){ TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); if(bold)t.setTypeface(null,1); return t; }
    private int dp(int x){return (int)(x*getResources().getDisplayMetrics().density+0.5f);}

    static class Candidate {
        String symbol,action="",reason="",catalyst;
        double change24,quoteVolume,preScore,price,ret1,ret5,ret15,volRatio,score,range15High,range15Low,catalystScore;
        int lowerCount; boolean extended=false;
    }
}
