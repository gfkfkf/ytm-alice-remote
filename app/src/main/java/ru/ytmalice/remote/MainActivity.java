package ru.ytmalice.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String PREFS = "ytm_alice_remote";
    private static final String KEY_URL = "server_url";
    private static final int REQ_FILE = 42;
    private static final int DEFAULT_PORT = 24721;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool();

    private SharedPreferences prefs;
    private WebView webView;
    private ProgressBar pageProgress;
    private View statusDot;
    private View content;
    private View setupPanel;
    private View errorPanel;
    private EditText addressInput;
    private TextView setupStatus;
    private ProgressBar setupProgress;
    private Button connectButton;
    private Button findButton;
    private ValueCallback<Uri[]> fileCallback;
    private String currentUrl;
    private boolean loadFailed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(R.layout.activity_main);

        content = findViewById(R.id.content);
        setupPanel = findViewById(R.id.setup_panel);
        errorPanel = findViewById(R.id.error_panel);
        pageProgress = findViewById(R.id.page_progress);
        statusDot = findViewById(R.id.status_dot);
        addressInput = findViewById(R.id.address_input);
        setupStatus = findViewById(R.id.setup_status);
        setupProgress = findViewById(R.id.setup_progress);
        connectButton = findViewById(R.id.connect_button);
        findButton = findViewById(R.id.find_button);
        webView = findViewById(R.id.webview);

        limitSetupCardWidth();
        setupWebView();

        connectButton.setOnClickListener(v -> submitAddress());
        findButton.setOnClickListener(v -> findStation());
        addressInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submitAddress();
                return true;
            }
            return false;
        });

        findViewById(R.id.menu_button).setOnClickListener(this::showMenu);
        findViewById(R.id.error_retry).setOnClickListener(v -> {
            if (currentUrl != null) {
                hideError();
                startLoading(currentUrl);
            }
        });
        findViewById(R.id.error_change).setOnClickListener(v -> openSetup());

        String saved = prefs.getString(KEY_URL, null);
        if (saved == null) {
            openSetup();
        } else {
            currentUrl = saved;
            showPanel();
            startLoading(saved);
        }
    }

    // ---------------------------------------------------------------- WebView

    @SuppressWarnings("deprecation")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDefaultTextEncodingName("UTF-8");
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        webView.setBackgroundColor(0xFF0B0B0F);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                loadFailed = false;
                pageProgress.setVisibility(View.VISIBLE);
                statusDot.setBackgroundResource(R.drawable.dot_idle);
                hideError();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageProgress.setVisibility(View.GONE);
                if (!loadFailed) {
                    statusDot.setBackgroundResource(R.drawable.dot_online);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            android.webkit.WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) {
                    showError();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 100) {
                    pageProgress.setVisibility(View.GONE);
                } else {
                    pageProgress.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                String[] accept = params != null ? params.getAcceptTypes() : null;
                if (accept != null && accept.length > 0) {
                    java.util.ArrayList<String> mimes = new java.util.ArrayList<>();
                    for (String type : accept) {
                        String t = type == null ? "" : type.trim();
                        if (t.contains("/")) {
                            mimes.add(t);
                        }
                    }
                    if (!mimes.isEmpty()) {
                        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toArray(new String[0]));
                    }
                }
                try {
                    startActivityForResult(intent, REQ_FILE);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                if (!isUserGesture) {
                    return false;
                }
                final WebView popup = new WebView(MainActivity.this);
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        openExternal(request.getUrl());
                        v.destroy();
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                openExternal(Uri.parse(url)));
    }

    private boolean handleNavigation(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost();
        if (("http".equals(scheme) || "https".equals(scheme)) && host != null) {
            Uri base = currentUrl == null ? null : Uri.parse(currentUrl);
            if (base != null && host.equalsIgnoreCase(base.getHost())) {
                return false;
            }
            openExternal(uri);
            return true;
        }
        openExternal(uri);
        return true;
    }

    private void openExternal(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, R.string.error_unreachable, Toast.LENGTH_SHORT).show();
        }
    }

    private void startLoading(String url) {
        errorPanel.setVisibility(View.GONE);
        pageProgress.setVisibility(View.VISIBLE);
        statusDot.setBackgroundResource(R.drawable.dot_idle);
        webView.loadUrl(url);
    }

    private void showError() {
        loadFailed = true;
        pageProgress.setVisibility(View.GONE);
        statusDot.setBackgroundResource(R.drawable.dot_offline);
        errorPanel.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorPanel.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- Screens

    private void showPanel() {
        setupPanel.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);
    }

    private void openSetup() {
        content.setVisibility(View.GONE);
        errorPanel.setVisibility(View.GONE);
        setupPanel.setVisibility(View.VISIBLE);
        if (currentUrl != null) {
            addressInput.setText(prettyAddress(currentUrl));
        }
        addressInput.setSelection(addressInput.getText().length());
        setBusy(false);
        setStatus(null, false);
    }

    private void limitSetupCardWidth() {
        View card = findViewById(R.id.setup_card);
        int max = (int) dp(440);
        int available = getResources().getDisplayMetrics().widthPixels - (int) dp(48);
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        lp.width = Math.min(max, available);
        card.setLayoutParams(lp);
    }

    // -------------------------------------------------------------- Connection

    private void submitAddress() {
        String normalized = normalize(addressInput.getText().toString());
        if (normalized == null) {
            setStatus(getString(R.string.error_invalid_address), false);
            return;
        }
        hideKeyboard();
        setBusy(true);
        setStatus(getString(R.string.status_checking), true);
        fetchVersion(normalized, version -> {
            setBusy(false);
            if (version == null) {
                setStatus(getString(R.string.error_unreachable), false);
                return;
            }
            String shown = TextUtils.isEmpty(version) ? getString(R.string.version_unknown) : version;
            setStatus(getString(R.string.saved_ok, shown), false);
            addressInput.setText(prettyAddress(normalized));
            currentUrl = normalized;
            prefs.edit().putString(KEY_URL, normalized).apply();
            showPanel();
            startLoading(normalized);
            refreshWidgets();
        });
    }

    private void findStation() {
        hideKeyboard();
        setBusy(true);
        setStatus(getString(R.string.status_searching), true);
        StationFinder.find(results -> {
            setBusy(false);
            if (results.isEmpty()) {
                setStatus(getString(R.string.status_not_found), false);
                return;
            }
            if (results.size() == 1) {
                String url = results.get(0);
                addressInput.setText(prettyAddress(url));
                currentUrl = url;
                prefs.edit().putString(KEY_URL, url).apply();
                setStatus(getString(R.string.status_found, prettyAddress(url)), false);
                showPanel();
                startLoading(url);
                refreshWidgets();
                return;
            }
            setStatus(getString(R.string.status_found, String.valueOf(results.size())), false);
            String[] labels = new String[results.size()];
            for (int i = 0; i < results.size(); i++) {
                labels[i] = prettyAddress(results.get(i));
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.choose_station)
                    .setItems(labels, (dialog, which) -> {
                        String url = results.get(which);
                        addressInput.setText(prettyAddress(url));
                        currentUrl = url;
                        prefs.edit().putString(KEY_URL, url).apply();
                        showPanel();
                        startLoading(url);
                        refreshWidgets();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
    }

    interface VersionCallback {
        void onResult(String version);
    }

    private void fetchVersion(String base, VersionCallback callback) {
        io.execute(() -> {
            String version = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(base + "/api/info").openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setUseCaches(false);
                conn.setRequestProperty("Accept", "application/json");
                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    StringBuilder body = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                        char[] buffer = new char[2048];
                        int read;
                        while ((read = reader.read(buffer)) != -1 && body.length() < 8192) {
                            body.append(buffer, 0, read);
                        }
                    }
                    Matcher m = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]*)\"")
                            .matcher(body.toString());
                    version = m.find() ? m.group(1) : "";
                }
            } catch (Exception ignored) {
                version = null;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            final String result = version;
            main.post(() -> {
                if (!isFinishing()) {
                    callback.onResult(result);
                }
            });
        });
    }

    private void setBusy(boolean busy) {
        connectButton.setEnabled(!busy);
        findButton.setEnabled(!busy);
    }

    private void setStatus(String text, boolean busy) {
        setupProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (TextUtils.isEmpty(text)) {
            setupStatus.setText("");
            setupStatus.setVisibility(View.GONE);
        } else {
            setupStatus.setText(text);
            setupStatus.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------- Menu

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 1, R.string.menu_reconnect);
        popup.getMenu().add(0, 2, 2, R.string.menu_address);
        popup.getMenu().add(0, 3, 3, R.string.menu_browser);
        popup.getMenu().add(0, 4, 4, R.string.menu_about);
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    if (currentUrl != null) {
                        hideError();
                        startLoading(currentUrl);
                    }
                    return true;
                case 2:
                    openSetup();
                    return true;
                case 3:
                    if (currentUrl != null) {
                        openExternal(Uri.parse(currentUrl));
                    }
                    return true;
                case 4:
                    showAbout();
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    private void showAbout() {
        String version = "";
        long code = 0;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = info.versionName == null ? "" : info.versionName;
            code = info.versionCode;
        } catch (Exception ignored) {
        }
        String build = version + (code > 0 ? " (" + code + ")" : "");
        String about = getString(R.string.about_body, version, build)
                .replace("github.com/SPAWNRYS-ban/ytm-alice",
                        "<a href=\"https://github.com/SPAWNRYS-ban/ytm-alice\">github.com/SPAWNRYS-ban/ytm-alice</a>");
        TextView text = new TextView(this);
        int pad = (int) dp(20);
        text.setPadding(pad, pad, pad, pad);
        text.setText(android.text.Html.fromHtml(about, android.text.Html.FROM_HTML_MODE_LEGACY));
        text.setTextSize(14);
        text.setTextColor(0xFFD8D8DE);
        text.setLineSpacing(dp(3), 1f);
        text.setMovementMethod(LinkMovementMethod.getInstance());
        text.setTextIsSelectable(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setView(text)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ----------------------------------------------------------------- System

    @Override
    public void onBackPressed() {
        if (setupPanel.getVisibility() == View.VISIBLE) {
            if (currentUrl != null) {
                showPanel();
                if (loadFailed) {
                    startLoading(currentUrl);
                }
                return;
            }
            super.onBackPressed();
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.exit_title)
                .setMessage(R.string.exit_message)
                .setPositiveButton(R.string.exit_ok, (dialog, which) -> finish())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            if (fileCallback != null) {
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        webView.pauseTimers();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.resumeTimers();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    private void refreshWidgets() {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(this);
            ComponentName cn = new ComponentName(this, TrackWidget.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            for (int id : ids) {
                TrackWidget.updateWidget(this, mgr, id);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Accepts "192.168.1.50", "alice.lan", "192.168.1.50:24721" or a full URL
     * and returns a normalised base URL without a trailing slash, or null.
     */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.contains(" ")) {
            return null;
        }
        if (!value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*$")) {
            value = "http://" + value;
        }
        Uri uri;
        try {
            uri = Uri.parse(value);
        } catch (Exception e) {
            return null;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isEmpty()) {
            return null;
        }
        scheme = scheme.toLowerCase();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        int port = uri.getPort();
        String path = uri.getPath() == null ? "" : uri.getPath();
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://").append(host);
        if (port != -1) {
            sb.append(':').append(port);
        } else if (!"https".equals(scheme)) {
            sb.append(':').append(DEFAULT_PORT);
        }
        if (!path.isEmpty() && !"/".equals(path)) {
            sb.append(path);
        }
        while (sb.length() > 1 && sb.charAt(sb.length() - 1) == '/') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /** Short form for the address field: host[:port] without the http:// prefix. */
    static String prettyAddress(String url) {
        String value = url;
        if (value.startsWith("http://")) {
            value = value.substring("http://".length());
        } else if (value.startsWith("https://")) {
            value = value.substring("https://".length());
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private void hideKeyboard() {
        View view = getCurrentFocus();
        if (view == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
        view.clearFocus();
    }
}
