package com.claudedoctor.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private WebView web;
    private static final int REQ_PHONE = 1001;

    public class Bridge {

        /* ---------------- 设备与 SIM 基础 ---------------- */
        @JavascriptInterface
        public String device() {
            JSONObject o = new JSONObject();
            try {
                o.put("manufacturer", safe(Build.MANUFACTURER));
                o.put("brand", safe(Build.BRAND));
                o.put("model", safe(Build.MODEL));
                o.put("device", safe(Build.DEVICE));
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("release", safe(Build.VERSION.RELEASE));

                String simCc = "", simOp = "", netOp = "";
                try {
                    TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                    if (tm != null) {
                        simCc = safe(tm.getSimCountryIso());
                        simOp = safe(tm.getSimOperatorName());
                        netOp = safe(tm.getNetworkOperatorName());
                    }
                } catch (Throwable ignored) { }
                o.put("simCountry", simCc);
                o.put("simOperator", simOp);
                o.put("networkOperator", netOp);
            } catch (Throwable ignored) { }
            return o.toString();
        }

        /**
         * SIM 与号码的硬数据：MCC/MNC 国家码、物理卡还是 eSIM、双卡槽、
         * 当前注册网络国家、本机号码是否可读
         */
        @JavascriptInterface
        public String sim() {
            JSONObject o = new JSONObject();
            try {
                JSONArray slots = new JSONArray();
                try {
                    SubscriptionManager sm = (SubscriptionManager)
                            getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE);
                    List<SubscriptionInfo> list = sm != null ? sm.getActiveSubscriptionInfoList() : null;
                    if (list != null) {
                        for (SubscriptionInfo si : list) {
                            JSONObject s = new JSONObject();
                            String mcc = Build.VERSION.SDK_INT >= 29 ? safe(si.getMccString())
                                    : String.valueOf(si.getMcc());
                            String mnc = Build.VERSION.SDK_INT >= 29 ? safe(si.getMncString())
                                    : String.valueOf(si.getMnc());
                            s.put("slot", si.getSimSlotIndex());
                            s.put("mcc", mcc);
                            s.put("mnc", mnc);
                            s.put("country", safe(si.getCountryIso()).toUpperCase());
                            // getCarrierName() 在无服务时会返回"无服务"，不可当运营商名。
                            // displayName 来自运营商配置库，更稳定；两者都带上便于交叉核对。
                            String disp = "";
                            try { disp = si.getDisplayName() == null ? "" : si.getDisplayName().toString(); } catch (Throwable ignored) { }
                            String car = si.getCarrierName() == null ? "" : si.getCarrierName().toString();
                            s.put("carrier", car);
                            s.put("displayName", disp);
                            // 该卡当前是否有服务：无服务时系统把 carrierName 置为"无服务"或空
                            boolean svc = car.length() > 0
                                    && !car.contains("无服务") && !car.toLowerCase().contains("no service")
                                    && !car.contains("僅限緊急") && !car.toLowerCase().contains("emergency");
                            s.put("service", svc);
                            // 号码：部分运营商把号码写入 SIM 卡，可直接读
                            String num = "";
                            try { num = si.getNumber() == null ? "" : si.getNumber(); } catch (Throwable ignored) { }
                            s.put("number", num);
                            s.put("esim", Build.VERSION.SDK_INT >= 28 && si.isEmbedded());
                            slots.put(s);
                        }
                    }
                } catch (Throwable ignored) { }

                String line1 = "";
                TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
                try {
                    if (tm != null) line1 = safe(tm.getLine1Number());
                } catch (Throwable ignored) { }

                o.put("slots", slots);
                o.put("count", slots.length());
                if (tm != null) {
                    o.put("simState", simStateName(tm.getSimState()));
                    o.put("phoneType", phoneTypeName(tm.getPhoneType()));
                    o.put("simOperator", safe(tm.getSimOperator()));
                    o.put("networkOperator", safe(tm.getNetworkOperator()));
                    o.put("networkCountry", safe(tm.getNetworkCountryIso()).toUpperCase());
                }
                o.put("line1", line1);
                o.put("line1Readable", !line1.isEmpty());
                o.put("phonePermission", hasPhonePermission());
            } catch (Throwable ignored) { }
            return o.toString();
        }

        /**
         * 应用与支付环境：安装来源、Google 服务、支付类应用、
         * 多开/分身工具、多用户与工作资料
         */
        @JavascriptInterface
        public String apps() {
            JSONObject o = new JSONObject();
            try {
                PackageManager pm = getPackageManager();

                String installer = "";
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        installer = safe(pm.getInstallSourceInfo(getPackageName()).getInstallingPackageName());
                    } else {
                        installer = safe(pm.getInstallerPackageName(getPackageName()));
                    }
                } catch (Throwable ignored) { }
                o.put("installer", installer);

                String[][] probes = {
                        {"com.anthropic.claude", "Claude 客户端"},
                        {"com.android.vending", "Google Play 商店"},
                        {"com.google.android.gms", "Google 服务框架"},
                        {"com.eg.android.AlipayGphone", "支付宝"},
                        {"com.tencent.mm", "微信"},
                        {"com.unionpay", "云闪付"},
                        {"com.google.android.apps.walletnfcrel", "Google 钱包"},
                        {"com.paypal.android.p2pmobile", "PayPal"},
                        {"com.lbe.parallel.intl", "平行空间"},
                        {"com.excelliance.dualaid", "双开助手"},
                        {"com.parallel.space.lite", "平行空间 Lite"},
                        {"io.va.exposed", "VirtualApp 容器"},
                        {"com.oasisfeng.island", "Island 工作资料"},
                        {"net.typeblog.shelter", "Shelter 隔离"},
                        {"com.applisto.appcloner", "App Cloner"},
                        {"com.dual.space.multiple.accounts", "多开空间"},
                        {"com.clone.app.parallel", "应用分身"}
                };

                JSONArray found = new JSONArray();
                JSONArray parallel = new JSONArray();
                JSONArray pay = new JSONArray();
                boolean gms = false, play = false, claude = false;
                long claudeFirst = 0;

                for (String[] p : probes) {
                    if (!isInstalled(pm, p[0])) continue;
                    JSONObject j = new JSONObject();
                    j.put("pkg", p[0]);
                    j.put("name", p[1]);
                    found.put(j);

                    if (p[0].equals("com.google.android.gms")) gms = true;
                    if (p[0].equals("com.android.vending")) play = true;
                    if (p[0].equals("com.anthropic.claude")) {
                        claude = true;
                        try { claudeFirst = pm.getPackageInfo(p[0], 0).firstInstallTime; } catch (Throwable ignored) { }
                    }
                    String n = p[1];
                    if (n.contains("空间") || n.contains("双开") || n.contains("分身") || n.contains("多开")
                            || n.contains("Parallel") || n.contains("Island") || n.contains("Shelter")
                            || n.contains("Cloner") || n.contains("VirtualApp")) {
                        parallel.put(n);
                    }
                    if (n.equals("支付宝") || n.equals("微信") || n.equals("云闪付")) pay.put(n);
                }

                o.put("found", found);
                o.put("gms", gms);
                o.put("play", play);
                o.put("claudeInstalled", claude);
                o.put("claudeFirstInstall", claudeFirst);
                o.put("parallel", parallel);
                o.put("payApps", pay);

                int profiles = 1;
                try {
                    UserManager um = (UserManager) getSystemService(USER_SERVICE);
                    if (um != null) {
                        List<UserHandle> list = um.getUserProfiles();
                        if (list != null && !list.isEmpty()) profiles = list.size();
                    }
                } catch (Throwable ignored) { }
                o.put("profiles", profiles);
            } catch (Throwable ignored) { }
            return o.toString();
        }

        /**
         * 原生 HTTP 请求：只允许白名单域名。
         * 走原生可以绕开 WebView 的跨域限制，从而使用不支持 CORS 的情报接口。
         */
        /** 异步取数：在子线程发请求，完成后把结果回调给 JS，绝不阻塞渲染线程 */
        @JavascriptInterface
        public void httpGetAsync(final String url, final String cbId) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String body = doHttpGet(url);
                    final String js = "window.__nativeHttp&&window.__nativeHttp("
                            + JSONObject.quote(cbId) + "," + JSONObject.quote(body) + ")";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try { web.evaluateJavascript(js, null); } catch (Throwable ignored) { }
                        }
                    });
                }
            }).start();
        }

        /** 同步版本，保留给必须同步的场景；JS 侧默认走 async */
        @JavascriptInterface
        public String httpGet(String url) {
            return doHttpGet(url);
        }

        private String doHttpGet(String url) {
            final String[] hosts = {"ip-api.com", "ipwho.is", "proxycheck.io",
                    "api.iplocation.net", "api64.ipify.org", "ipapi.co"};
            try {
                java.net.URI u = new java.net.URI(url);
                String host = u.getHost();
                boolean allowed = false;
                if (host != null) {
                    for (String h : hosts) {
                        if (h.equalsIgnoreCase(host)) { allowed = true; break; }
                    }
                }
                if (!allowed) return "";
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.toURL().openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setRequestProperty("User-Agent", "ClaudeDoctor/1.0");
                int code = c.getResponseCode();
                java.io.InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (is == null) return "";
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                c.disconnect();
                return bos.toString("UTF-8");
            } catch (Throwable t) {
                return "";
            }
        }

        private boolean isInstalled(PackageManager pm, String pkg) {
            try {
                return pm.getPackageInfo(pkg, 0) != null;
            } catch (Throwable t) {
                return false;
            }
        }

        /** 系统字体表 + 字体文件名 */
        @JavascriptInterface
        public String fonts() {
            List<String> names = new ArrayList<>();
            String[] candidates = {
                    "/system/etc/fonts.xml",
                    "/system/etc/font_fallback.xml",
                    "/product/etc/fonts_customization.xml",
                    "/system/fonts/fonts.xml"
            };
            for (String path : candidates) {
                File f = new File(path);
                if (!f.exists() || !f.canRead()) continue;
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new FileReader(f));
                    String line;
                    while ((line = br.readLine()) != null) {
                        int i = line.indexOf("name=\"");
                        if (i < 0) continue;
                        int j = line.indexOf('"', i + 6);
                        if (j < 0) continue;
                        String name = line.substring(i + 6, j).trim();
                        if (!name.isEmpty() && name.length() < 60) names.add(name);
                    }
                } catch (Throwable ignored) {
                } finally {
                    try { if (br != null) br.close(); } catch (Throwable ignored) { }
                }
            }
            File fontDir = new File("/system/fonts");
            File[] files = fontDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String n = f.getName();
                    int dot = n.lastIndexOf('.');
                    if (dot > 0) n = n.substring(0, dot);
                    if (n.length() < 80) names.add(n);
                }
            }
            try {
                return new JSONArray(names).toString();
            } catch (Throwable t) {
                return "[]";
            }
        }

        /** Root / 模拟器 / VPN / ROM */
        @JavascriptInterface
        public String integrity() {
            JSONObject o = new JSONObject();
            try {
                String[] suPaths = {
                        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                        "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
                        "/data/local/bin/su", "/data/local/xbin/su"
                };
                String magisk = "";
                for (String p : suPaths) {
                    if (new File(p).exists()) { magisk = p; break; }
                }
                boolean magiskDir = new File("/data/adb/magisk").exists()
                        || new File("/sbin/.magisk").exists()
                        || new File("/data/adb/modules").exists();
                String tags = safe(Build.TAGS);
                boolean testKeys = tags.contains("test-keys");
                o.put("root", !magisk.isEmpty() || magiskDir || testKeys);
                o.put("rootEvidence", !magisk.isEmpty() ? ("su 二进制：" + magisk)
                        : magiskDir ? "Magisk / 模块目录存在"
                        : testKeys ? "ROM 为 test-keys 签名" : "");

                String fp = safe(Build.FINGERPRINT).toLowerCase();
                String hw = safe(Build.HARDWARE).toLowerCase();
                String product = safe(Build.PRODUCT).toLowerCase();
                String brand = safe(Build.BRAND).toLowerCase();
                boolean emu = fp.startsWith("generic") || fp.contains("emulator") || fp.contains("vbox")
                        || hw.contains("goldfish") || hw.contains("ranchu")
                        || product.contains("sdk") || brand.contains("generic")
                        || safe(Build.MODEL).toLowerCase().contains("emulator")
                        || "1".equals(getProp("ro.kernel.qemu"));
                o.put("emulator", emu);
                o.put("emuEvidence", emu ? (hw + " / " + fp) : "");

                boolean vpn = false;
                try {
                    ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                    if (cm != null) {
                        for (Network n : cm.getAllNetworks()) {
                            NetworkCapabilities c = cm.getNetworkCapabilities(n);
                            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                                vpn = true;
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) { }
                String proxyHost = System.getProperty("http.proxyHost");
                String proxyPort = System.getProperty("http.proxyPort");
                boolean sysProxy = proxyHost != null && !proxyHost.isEmpty();
                o.put("vpn", vpn || sysProxy);
                o.put("vpnType", vpn ? "系统级 VPN / 代理隧道已启用"
                        : sysProxy ? ("系统代理 " + proxyHost + ":" + safe(proxyPort)) : "");

                o.put("tags", tags);
                o.put("buildType", safe(Build.TYPE));
                o.put("fingerprint", safe(Build.FINGERPRINT));
                o.put("patch", safe(Build.VERSION.SECURITY_PATCH));
                o.put("officialRom", safe(Build.TAGS).contains("release-keys") && "user".equals(Build.TYPE));
            } catch (Throwable ignored) { }
            return o.toString();
        }

        private String getProp(String key) {
            try {
                Class<?> c = Class.forName("android.os.SystemProperties");
                Object o = c.getMethod("get", String.class).invoke(null, key);
                return o == null ? "" : o.toString();
            } catch (Throwable t) {
                return "";
            }
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String simStateName(int s) {
        switch (s) {
            case TelephonyManager.SIM_STATE_ABSENT: return "无 SIM 卡";
            case TelephonyManager.SIM_STATE_READY: return "已就绪";
            case TelephonyManager.SIM_STATE_PIN_REQUIRED: return "需要 PIN";
            case TelephonyManager.SIM_STATE_PUK_REQUIRED: return "需要 PUK";
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED: return "网络锁定";
            default: return "状态未知";
        }
    }

    private static String phoneTypeName(int t) {
        switch (t) {
            case TelephonyManager.PHONE_TYPE_GSM: return "GSM";
            case TelephonyManager.PHONE_TYPE_CDMA: return "CDMA";
            case TelephonyManager.PHONE_TYPE_SIP: return "SIP";
            default: return "未知";
        }
    }

    private boolean hasPhonePermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(false);
        }
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        w.setStatusBarColor(0x00000000);
        w.setNavigationBarColor(0x00000000);

        web = new WebView(this);
        web.setBackgroundColor(0xFFFDFCFA);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setUserAgentString(s.getUserAgentString() + " ClaudeDoctorApp/1.0");

        web.addJavascriptInterface(new Bridge(), "Native");

        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                android.util.Log.i("CDOC", cm.message());
                return true;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String u = req.getUrl().toString();
                return !u.startsWith("file://");
            }
        });

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        if (!hasPhonePermission()) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, REQ_PHONE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_PHONE) {
            web.reload();
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
                "(function(){try{return window.appBack?window.appBack():false}catch(e){return false}})()",
                new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (!"true".equals(value)) finish();
                    }
                });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }
}
