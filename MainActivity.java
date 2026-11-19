package ru.minigram.app;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Оболочка MiniGram: открывает ваш сервер в WebView.
 * Вся логика (чаты, подарки, кошелёк, админка) живёт на сервере, поэтому обновлять APK
 * при каждом изменении приложения не нужно.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "minigram";
    private static final String KEY_SERVER = "server";

    /**
     * Системная кнопка «назад»: закрыть меню или лист, иначе вернуться на предыдущий экран.
     * Возвращает "root", если вернуться некуда (тогда приложение сворачивается).
     */
    private static final String BACK_JS =
            "(function(){try{"
            + "var vis=function(e){if(!e)return false;var r=e.getBoundingClientRect(),s=getComputedStyle(e);"
            + "return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'};"
            + "var d=document.getElementById('drawer');"
            + "if(d&&d.style.display==='block'&&typeof dr==='function'){dr(false);return 'drawer'}"
            + "var sh=[].filter.call(document.querySelectorAll('.bsh'),vis);"
            + "if(sh.length){sh[sh.length-1].click();return 'sheet'}"
            + "var app=document.getElementById('app');"
            + "if(!app||!vis(app))return 'root';"
            + "var v=app.getAttribute('data-v');"
            + "if(!v||v==='side')return 'root';"
            + "var cols=[].filter.call(app.querySelectorAll('.col'),vis);"
            + "var c=cols.length?cols[cols.length-1]:null;"
            + "var b=c?c.querySelector('.hd button'):null;"
            + "if(b){b.click();return 'back'}"
            + "return 'root'}catch(e){return 'root'}})()";

    private static final String THEME_JS =
            "(function(){var m=document.querySelector('meta[name=theme-color]');"
            + "return m?m.getAttribute('content'):''})()";

    private FrameLayout root;
    private FrameLayout fullscreen;
    private View splash;
    private View offline;
    private TextView offMsg;
    private WebView web;
    private WebChromeClient chrome;

    private View customView;
    private WebChromeClient.CustomViewCallback customCb;

    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest pendingPerm;
    private String[] pendingResources;
    private ActivityResultLauncher<android.content.Intent> fileLauncher;
    private ActivityResultLauncher<String[]> permLauncher;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private String baseUrl = "";
    private String host = "";
    private boolean failed = false;

    private final Runnable themePoll = new Runnable() {
        @Override
        public void run() {
            applyTheme();
            ui.postDelayed(this, 2500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        fullscreen = findViewById(R.id.fullscreen);
        splash = findViewById(R.id.splash);
        offline = findViewById(R.id.offline);
        offMsg = findViewById(R.id.offMsg);
        web = findViewById(R.id.web);

        // Если система рисует под системными панелями (Android 15+), сдвигаем содержимое
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        fileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (fileCb != null) {
                Uri[] res = WebChromeClient.FileChooserParams.parseResult(r.getResultCode(), r.getData());
                fileCb.onReceiveValue(res);
                fileCb = null;
            }
        });
        permLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), res -> {
            PermissionRequest p = pendingPerm;
            String[] rs = pendingResources;
            pendingPerm = null;
            pendingResources = null;
            if (p == null || rs == null) return;
            boolean ok = true;
            for (Map.Entry<String, Boolean> e : res.entrySet()) {
                if (!Boolean.TRUE.equals(e.getValue())) ok = false;
            }
            if (ok) p.grant(rs);
            else p.deny();
        });

        setupWebView();

        ((Button) findViewById(R.id.retry)).setOnClickListener(v -> retry());
        ((Button) findViewById(R.id.change)).setOnClickListener(v -> askServer(true));

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (customView != null) {
                    chrome.onHideCustomView();
                    return;
                }
                if (offline.getVisibility() == View.VISIBLE || splash.getVisibility() == View.VISIBLE) {
                    moveTaskToBack(true);
                    return;
                }
                web.evaluateJavascript(BACK_JS, value -> {
                    if (value == null || value.contains("root")) moveTaskToBack(true);
                });
            }
        });

        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SERVER, "");
        String url = normalize(saved.isEmpty() ? BuildConfig.SERVER_URL : saved);
        if (url.isEmpty()) askServer(false);
        else start(url);
    }

    // ---------- адрес сервера ----------

    private static String normalize(String in) {
        if (in == null) return "";
        String s = in.trim();
        if (s.isEmpty()) return "";
        if (!s.contains("://")) s = "https://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        Uri u = Uri.parse(s);
        String sch = u.getScheme();
        if (u.getHost() == null || sch == null || !(sch.equals("http") || sch.equals("https"))) return "";
        return s;
    }

    private void askServer(boolean cancelable) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://ваш-сервер.ru");
        input.setSingleLine(true);
        input.setText(baseUrl);
        input.setSelection(input.getText().length());
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Адрес сервера MiniGram")
                .setMessage("Введите адрес, по которому работает ваш сервер. Для камеры и микрофона нужен https.")
                .setView(box)
                .setCancelable(cancelable)
                .setPositiveButton("Открыть", (d, w) -> {
                    String url = normalize(input.getText().toString());
                    if (url.isEmpty()) {
                        Toast.makeText(this, "Неверный адрес", Toast.LENGTH_SHORT).show();
                        askServer(cancelable);
                        return;
                    }
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SERVER, url).apply();
                    start(url);
                });
        if (cancelable) b.setNegativeButton("Отмена", null);
        b.show();
    }

    private void start(String url) {
        baseUrl = url;
        host = Uri.parse(url).getHost();
        failed = false;
        offline.setVisibility(View.GONE);
        splash.setVisibility(View.VISIBLE);
        web.setVisibility(View.VISIBLE);
        web.loadUrl(url);
    }

    private void retry() {
        if (baseUrl.isEmpty()) {
            askServer(false);
            return;
        }
        start(baseUrl);
    }

    private void showOffline(String msg) {
        failed = true;
        splash.setVisibility(View.GONE);
        web.setVisibility(View.INVISIBLE);
        offMsg.setText(msg);
        offline.setVisibility(View.VISIBLE);
    }

    // ---------- WebView ----------

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // токен входа лежит в localStorage
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(s.getUserAgentString() + " MiniGramApp/" + BuildConfig.VERSION_NAME);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(Color.TRANSPARENT);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openExternally(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                failed = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!failed) {
                    offline.setVisibility(View.GONE);
                    splash.setVisibility(View.GONE);
                    web.setVisibility(View.VISIBLE);
                }
                applyTheme();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showOffline("Не удаётся открыть " + host + "\n" + error.getDescription());
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse resp) {
                if (request.isForMainFrame() && resp.getStatusCode() >= 500) {
                    showOffline("Сервер ответил ошибкой " + resp.getStatusCode());
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                showOffline("Сертификат сервера недействителен. Проверьте HTTPS на сервере.");
            }
        });

        chrome = new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    fileLauncher.launch(params.createIntent());
                } catch (ActivityNotFoundException e) {
                    fileCb = null;
                    cb.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "Нет приложения для выбора файлов", Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> handleWebPermission(request));
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCb = callback;
                fullscreen.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreen.setVisibility(View.VISIBLE);
                web.setVisibility(View.INVISIBLE);
                setImmersive(true);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                fullscreen.removeView(customView);
                fullscreen.setVisibility(View.GONE);
                customView = null;
                if (customCb != null) customCb.onCustomViewHidden();
                customCb = null;
                web.setVisibility(View.VISIBLE);
                setImmersive(false);
            }
        };
        web.setWebChromeClient(chrome);

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setMimeType(mimeType);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) r.addRequestHeader("Cookie", cookie);
                r.addRequestHeader("User-Agent", userAgent);
                r.setTitle(name);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                dm.enqueue(r);
                Toast.makeText(this, "Загрузка: " + name, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                openExternally(Uri.parse(url), true);
            }
        });
    }

    /** true: ссылку обработали сами (открыли снаружи), false: пусть грузит WebView. */
    private boolean openExternally(Uri u) {
        return openExternally(u, false);
    }

    private boolean openExternally(Uri u, boolean force) {
        String sch = u.getScheme() == null ? "" : u.getScheme();
        if (!force) {
            if (sch.equals("blob") || sch.equals("data") || sch.equals("about") || sch.equals("javascript")) return false;
            if ((sch.equals("http") || sch.equals("https")) && host != null && host.equalsIgnoreCase(u.getHost())) return false;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    /** Камера и микрофон для голосовых сообщений и кружков: только для адреса вашего сервера. */
    private void handleWebPermission(PermissionRequest request) {
        Uri origin = request.getOrigin();
        if (origin == null || host == null || !host.equalsIgnoreCase(origin.getHost())) {
            request.deny();
            return;
        }
        List<String> need = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) need.add(Manifest.permission.CAMERA);
            else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) need.add(Manifest.permission.RECORD_AUDIO);
        }
        List<String> missing = new ArrayList<>();
        for (String p : need) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) {
            request.grant(request.getResources());
            return;
        }
        if (pendingPerm != null) pendingPerm.deny();
        pendingPerm = request;
        pendingResources = request.getResources();
        permLauncher.launch(missing.toArray(new String[0]));
    }

    // ---------- вид системных панелей ----------

    private void setImmersive(boolean on) {
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (on) {
            c.hide(WindowInsetsCompat.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            c.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    /** Красим статус-бар и нижнюю панель в цвет темы приложения (светлая/тёмная). */
    private void applyTheme() {
        if (web == null || failed) return;
        web.evaluateJavascript(THEME_JS, value -> {
            try {
                if (value == null) return;
                String v = value.replace("\"", "").trim();
                if (v.length() < 4 || v.charAt(0) != '#') return;
                int c = Color.parseColor(v);
                getWindow().setStatusBarColor(c);
                getWindow().setNavigationBarColor(c);
                boolean light = ColorUtils.calculateLuminance(c) > 0.5;
                WindowInsetsControllerCompat wc = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
                wc.setAppearanceLightStatusBars(light);
                wc.setAppearanceLightNavigationBars(light);
                root.setBackgroundColor(c);
            } catch (Exception ignored) {
                // цвет не распознан: оставляем как есть
            }
        });
    }

    // ---------- жизненный цикл ----------

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        ui.post(themePoll);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(themePoll);
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(themePoll);
        if (fileCb != null) fileCb.onReceiveValue(null);
        web.stopLoading();
        web.destroy();
        super.onDestroy();
    }
}
