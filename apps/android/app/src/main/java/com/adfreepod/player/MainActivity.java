package com.adfreepod.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Html;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;

public class MainActivity extends Activity {
    private static volatile MainActivity activeInstance;
    private static final int BG = 0xfff4f7fb;
    private static final int SURFACE = 0xffffffff;
    private static final int BLUE = 0xff1769aa;
    private static final int BROWN = 0xff8b5e3c;
    private static final int TEAL = 0xff0f766e;
    private static final int CORAL = 0xffd94f70;
    private static final int INK = 0xff202124;
    private static final int MUTED = 0xff68707a;
    private static final int LINE = 0xffdce3ea;
    private static final int PLAYER_BG = 0xff111111;
    private static final int PLAYER_SURFACE = 0xff181818;
    private static final int PLAYER_TEXT = 0xffffffff;
    private static final int PLAYER_MUTED = 0xffb3b3b3;
    private static final int PLAYER_ACCENT = 0xff22c55e;
    private static final int SOFT_BLUE = 0xffeaf3ff;
    private static final int SOFT_GREEN = 0xffeaf8f2;
    private static final int SOFT_RED = 0xffffedf1;
    private static final String ENGINE_TUNNEL_PARAKEET = "tunnel_parakeet";
    private static final String DEFAULT_TRANSCRIPTION_ENGINE = ENGINE_TUNNEL_PARAKEET;
    private static final String BACKEND_DEFAULT_VERSION = "4";
    private static final String DEFAULT_OPENAI_MODEL = "gpt-5.5";
    private static final String CURRENT_AD_API_HOST = "adsbegone.sitesindevelopment.com";
    private static final String LEGACY_AD_API_HOST = "agitated-engelbart.74-208-203-194.plesk.page";
    private static final String DEFAULT_AD_API_BASE_URL = "https://" + CURRENT_AD_API_HOST + "/adfree-api";
    private static final String SETTING_SERVER_USER_ID = "server_user_id";
    private static final String SETTING_SERVER_USER_NAME = "server_user_name";
    private static final String SETTING_SERVER_USER_EXPLICIT = "server_user_explicit";
    private static final String SETTING_DEVICE_SERVER_USER_ID = "device_server_user_id";
    private static final String SETTING_DEVICE_SERVER_USER_NAME = "device_server_user_name";
    private static final int EPISODE_PAGE_SIZE = 40;
    private static final long PODCAST_CACHE_TTL_MS = 12L * 3600L * 1000L;
    private static final int REMOTE_JOB_POLL_INTERVAL_MS = 10000;
    private static final int REMOTE_JOB_STATUS_MAX_ERRORS = 12;
    private static final int REMOTE_JOB_LONG_NOTICE_POLLS = 60;
    private static final String[] FILTERS = {"All", "New", "In progress", "Downloaded", "Not downloaded", "Listened", "Unlistened"};

    private static final String NOTIF_CH_REMOVAL = "ad_removal";
    private static final String NOTIF_CH_COMPLETE = "ad_complete";
    private static final String NOTIF_CH_PLAY    = "playback";
    private static final int    NOTIF_ID_REMOVAL = 1001;
    private static final int    NOTIF_ID_PLAY    = 1002;
    private static final String ACTION_PLAYER_TOGGLE = "com.adfreepod.player.action.TOGGLE";
    private static final String ACTION_PLAYER_BACK = "com.adfreepod.player.action.BACK";
    private static final String ACTION_PLAYER_FORWARD = "com.adfreepod.player.action.FORWARD";
    private static final String ACTION_PLAYER_NEXT = "com.adfreepod.player.action.NEXT";
    private static final String ACTION_PLAYER_CLOSE = "com.adfreepod.player.action.CLOSE";

    // Static so they survive screen navigation inside the same activity instance.
    private static final LinkedList<String>  adQueue         = new LinkedList<>();
    private static volatile boolean          adRunning       = false;
    private static volatile String           adCurrentId     = null;
    private static volatile String           adCurrentTitle  = "";
    private static volatile String           adCurrentStatus = "";
    private static volatile String           adCurrentEst    = "";
    private static final AtomicBoolean       adCancelled     = new AtomicBoolean(false);
    // Each entry: [episodeId, episodeTitle, "ok"|"no_ads"]
    private static final ArrayList<String[]> completionCards = new ArrayList<>();

    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private Db db;
    private FrameLayout appFrame;
    private LinearLayout root;
    private TextView status;
    private LinearLayout busyPanel;
    private ProgressBar busySpinner;
    private TextView busyText;
    private FrameLayout modalOverlay;
    private TextView modalTitle;
    private TextView modalBody;
    private ProgressBar modalProgress;
    private LinearLayout modalActions;
    private int modalGeneration;
    private MediaPlayer player;
    private Episode currentEpisode;
    private boolean userSeeking;
    private boolean playerPrepared;
    private NotificationManager notifMgr;
    private LinearLayout playerDock;
    private TextView dockTitle;
    private TextView dockSubtitle;
    private TextView dockPosition;
    private TextView dockDuration;
    private SeekBar dockSeek;
    private ImageButton dockPlayButton;
    private final ArrayList<CallLog> callLogs = new ArrayList<>();
    private final ArrayList<Runnable> screenBackStack = new ArrayList<>();
    private final ArrayList<ServerUser> serverUsers = new ArrayList<>();
    private Runnable currentScreenAction;
    private volatile boolean serverUsersLoaded;
    private volatile boolean serverUsersLoading;
    private long lastBackPressedAt;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            saveCurrentProgress();
            renderMiniPlayer(false);
            if (playerPrepared && player != null && currentEpisode != null) updatePlaybackNotification();
            main.postDelayed(this, 1000);
        }
    };

    private static class ServerUser {
        final String id;
        final String name;

        ServerUser(String id, String name) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
        }
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        activeInstance = this;
        installCrashRecorder();
        db = new Db(this);
        migrateRuntimeSettings();
        notifMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannels();
        registerDeviceUserAsync();
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 0);
        cleanupExpiredDownloads();
        replaceScreen(() -> showHome());
        handlePlaybackIntent(getIntent());
        main.post(tick);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!handlePlaybackIntent(intent)) {
            replaceScreen(() -> showHome());
        }
    }

    @Override public void onBackPressed() {
        if (!screenBackStack.isEmpty()) {
            Runnable previous = screenBackStack.remove(screenBackStack.size() - 1);
            currentScreenAction = previous;
            safeScreen(previous);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPressedAt < 1800) {
            finish();
            return;
        }
        lastBackPressedAt = now;
        setStatus("Tap back again to exit.");
    }

    @Override protected void onPause() {
        super.onPause();
        saveCurrentProgress();
    }

    @Override protected void onDestroy() {
        saveCurrentProgress();
        if (player != null) player.release();
        playerPrepared = false;
        io.shutdownNow();
        if (notifMgr != null) notifMgr.cancel(NOTIF_ID_PLAY);
        if (activeInstance == this) activeInstance = null;
        super.onDestroy();
    }

    private void base(String title) {
        resetPlayerDockRefs();
        FrameLayout frame = new FrameLayout(this);
        appFrame = frame;
        modalOverlay = null;
        modalTitle = null;
        modalBody = null;
        modalProgress = null;
        modalActions = null;
        modalGeneration++;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), statusBarInset() + dp(10), dp(14), dp(232));
        scroll.addView(root);
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        playerDock = new LinearLayout(this);
        playerDock.setOrientation(LinearLayout.VERTICAL);
        playerDock.setPadding(dp(10), 0, dp(10), dp(10));
        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        frame.addView(playerDock, dockLp);
        setContentView(frame);

        TextView h = text(title, 25, INK, true);
        h.setPadding(0, dp(4), 0, dp(8));
        root.addView(h);
        TextView buildInfo = text("v" + BuildConfig.VERSION_NAME + " | " + formatBuildTimestampEastern(BuildConfig.BUILD_TIMESTAMP_UTC), 10, MUTED, false);
        buildInfo.setPadding(0, 0, 0, dp(6));
        root.addView(buildInfo);
        LinearLayout nav = row();
        nav.setBackground(rounded(0xffffffff, dp(20), LINE));
        nav.setPadding(dp(3), dp(3), dp(3), dp(3));
        nav.addView(navButton("Home", R.drawable.ic_home, v -> navigate(() -> showHome())));
        nav.addView(navButton("Search", R.drawable.ic_search, v -> navigate(() -> showSearch())));
        nav.addView(navButton("Ad-free", R.drawable.ic_adfree, v -> navigate(() -> showRemoteAdFree())));
        nav.addView(navButton("Debug", R.drawable.ic_debug, v -> navigate(() -> showDebug())));
        nav.addView(navButton("Settings", R.drawable.ic_settings, v -> navigate(() -> showSettings())));
        root.addView(nav);
        addAccountSwitcher();

        status = text("", 14, MUTED, false);
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.setMargins(0, dp(10), 0, dp(4));
        status.setLayoutParams(statusLp);
        status.setBackground(rounded(SOFT_BLUE, dp(14), 0));
        status.setVisibility(View.GONE);
        root.addView(status);

        // Persistent ad-removal busy panel. It survives tab switches via static state.
        busyPanel = new LinearLayout(this);
        busyPanel.setOrientation(LinearLayout.VERTICAL);
        busyPanel.setPadding(dp(14), dp(12), dp(14), dp(12));
        busyPanel.setBackground(rounded(SOFT_GREEN, dp(14), 0));
        LinearLayout.LayoutParams busyLp = new LinearLayout.LayoutParams(-1, -2);
        busyLp.setMargins(0, dp(6), 0, dp(4));
        busyPanel.setLayoutParams(busyLp);
        LinearLayout busyTopRow = row();
        busySpinner = new ProgressBar(this);
        LinearLayout.LayoutParams spinnerLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        spinnerLp.rightMargin = dp(10);
        busyTopRow.addView(busySpinner, spinnerLp);
        busyText = text("", 14, INK, false);
        busyTopRow.addView(busyText, new LinearLayout.LayoutParams(0, -2, 1));
        Button cancelAdBtn = new Button(this);
        cancelAdBtn.setText("Cancel");
        cancelAdBtn.setTextSize(12);
        cancelAdBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        cancelAdBtn.setBackground(rounded(SOFT_RED, dp(20), 0));
        cancelAdBtn.setTextColor(CORAL);
        cancelAdBtn.setOnClickListener(v -> {
            adCancelled.set(true);
            synchronized (adQueue) { adQueue.clear(); }
            busyPanel.setVisibility(View.GONE);
            setStatus("Ad removal cancelled. Transcript cache is preserved - re-tapping Remove ads will resume from the detection step.");
        });
        busyTopRow.addView(cancelAdBtn);
        busyPanel.addView(busyTopRow);
        busyPanel.setVisibility(adRunning ? View.VISIBLE : View.GONE);
        if (adRunning) refreshAdBusyPanel();
        root.addView(busyPanel);

        // Persistent completion cards. One per finished job, until dismissed.
        synchronized (completionCards) {
            for (int i = completionCards.size() - 1; i >= 0; i--) {
                String[] card = completionCards.get(i);
                final String[] cardRef = card;
                String cardEpId    = card[0];
                String cardEpTitle = card[1];
                boolean isOk       = "ok".equals(card[2]);
                LinearLayout cc = new LinearLayout(this);
                cc.setOrientation(LinearLayout.VERTICAL);
                cc.setPadding(dp(14), dp(12), dp(14), dp(12));
                LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(-1, -2);
                ccLp.setMargins(0, dp(6), 0, 0);
                cc.setLayoutParams(ccLp);
                cc.setBackground(rounded(isOk ? 0xffe6f4ea : SOFT_BLUE, dp(14), 0));
                LinearLayout topRow = row();
                String msg = isOk
                    ? cardEpTitle + " - ad removal complete"
                    : cardEpTitle + " - no ads detected";
                topRow.addView(text(msg, 14, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
                Button xBtn = new Button(this);
                xBtn.setText("x");
                xBtn.setBackgroundColor(0);
                xBtn.setTextColor(MUTED);
                xBtn.setPadding(dp(8), 0, 0, 0);
                xBtn.setOnClickListener(v -> {
                    synchronized (completionCards) { completionCards.remove(cardRef); }
                    replaceScreen(() -> showHome());
                });
                topRow.addView(xBtn);
                cc.addView(topRow);
                if (isOk) {
                    Episode cardEp = db.episode(cardEpId);
                    if (cardEp != null && cardEp.downloaded()) {
                        LinearLayout acts = row();
                        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(-2, -2);
                        actLp.topMargin = dp(6);
                        acts.setLayoutParams(actLp);
                        acts.addView(button("Play ad-free", v -> playEpisode(cardEp)));
                        cc.addView(acts);
                    } else {
                        cc.addView(text("Ad-free audio is not stored on this device. Open the Ad-free tab or run Remove ads again.", 13, MUTED, false));
                    }
                }
                root.addView(cc);
            }
        }

        renderMiniPlayer(true);
    }

    private void addAccountSwitcher() {
        ensureServerUsersLoading();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(-1, -2);
        boxLp.setMargins(0, dp(4), 0, 0);
        box.setLayoutParams(boxLp);
        box.setBackground(rounded(SURFACE, dp(16), LINE));

        TextView label = text("Account", 11, MUTED, true);
        box.addView(label, new LinearLayout.LayoutParams(-2, -2));

        if (serverUsers.isEmpty()) {
            String current = selectedServerUserName(db);
            if (TextUtils.isEmpty(current)) current = serverUsersLoading ? "Loading..." : "test";
            TextView value = text(current, 12, INK, false);
            value.setGravity(Gravity.END);
            box.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
            root.addView(box);
            return;
        }

        ArrayList<String> labels = new ArrayList<>();
        int selectedIndex = 0;
        String selectedId = selectedServerUserId(db);
        for (int i = 0; i < serverUsers.size(); i++) {
            ServerUser user = serverUsers.get(i);
            labels.add(displayServerUserName(user.name));
            if (TextUtils.equals(user.id, selectedId)) selectedIndex = i;
        }

        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                if (view instanceof TextView) {
                    TextView t = (TextView) view;
                    t.setTextSize(12);
                    t.setSingleLine(true);
                    t.setPadding(dp(6), 0, dp(6), 0);
                }
                return view;
            }

            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                if (view instanceof TextView) {
                    TextView t = (TextView) view;
                    t.setTextSize(12);
                    t.setSingleLine(true);
                    t.setPadding(dp(10), dp(6), dp(10), dp(6));
                }
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selectedIndex);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= serverUsers.size()) return;
                ServerUser picked = serverUsers.get(position);
                if (TextUtils.equals(picked.id, selectedServerUserId(db))) return;
                selectServerUser(picked, true);
                setStatus("Account switched to " + displayServerUserName(picked.name) + ".");
                Runnable screen = currentScreenAction;
                if (screen != null) main.postDelayed(() -> replaceScreen(screen), 150);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        box.addView(spinner, new LinearLayout.LayoutParams(0, dp(34), 1));
        root.addView(box);
    }

    private void ensureServerUsersLoading() {
        if (serverUsersLoaded || serverUsersLoading || db == null || io.isShutdown()) return;
        serverUsersLoading = true;
        io.execute(() -> {
            ArrayList<ServerUser> next = new ArrayList<>();
            try {
                String apiBase = apiBaseUrlSetting(db);
                JSONObject parsed = fetchApiJsonWithFallback(apiBase, "/api/users", "Account list failed", "users response");
                JSONArray users = parsed.optJSONArray("users");
                if (users != null) {
                    for (int i = 0; i < users.length(); i++) {
                        JSONObject user = users.optJSONObject(i);
                        if (user == null) continue;
                        String id = user.optString("id", "").trim();
                        String name = user.optString("name", "").trim();
                        if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) continue;
                        if (isSharedSwitchAccount(name)) next.add(new ServerUser(id, name));
                    }
                }
                sortServerUsers(next);
                main.post(() -> {
                    serverUsers.clear();
                    serverUsers.addAll(next);
                    serverUsersLoaded = true;
                    serverUsersLoading = false;
                    chooseInitialServerUserIfNeeded();
                    Runnable screen = currentScreenAction;
                    if (screen != null) replaceScreen(screen);
                });
            } catch (Exception ex) {
                addCallLog("API USERS FAILED", DEFAULT_AD_API_BASE_URL + "/api/users", "", 0,
                    "", ex.getClass().getSimpleName() + ": " + nullToEmpty(ex.getMessage()), 0);
                main.post(() -> {
                    serverUsersLoading = false;
                    serverUsersLoaded = false;
                });
            }
        });
    }

    private boolean isSharedSwitchAccount(String name) {
        String lower = nullToEmpty(name).toLowerCase(Locale.US);
        return "test".equals(lower) || lower.contains("samsung") || lower.contains("motorola");
    }

    private void sortServerUsers(ArrayList<ServerUser> users) {
        Collections.sort(users, (a, b) -> {
            int rankA = serverUserRank(a.name);
            int rankB = serverUserRank(b.name);
            if (rankA != rankB) return rankA - rankB;
            return displayServerUserName(a.name).compareToIgnoreCase(displayServerUserName(b.name));
        });
    }

    private int serverUserRank(String name) {
        String lower = nullToEmpty(name).toLowerCase(Locale.US);
        if ("test".equals(lower)) return 0;
        if (lower.contains("samsung")) return 1;
        if (lower.contains("motorola")) return 2;
        return 99;
    }

    private void chooseInitialServerUserIfNeeded() {
        if (serverUsers.isEmpty() || db == null) return;
        String explicit = db.setting(SETTING_SERVER_USER_EXPLICIT, "");
        String selectedId = "1".equals(explicit) ? db.setting(SETTING_SERVER_USER_ID, "").trim() : "";
        for (ServerUser user : serverUsers) {
            if (TextUtils.equals(user.id, selectedId)) return;
        }
        ServerUser fallback = serverUsers.get(0);
        for (ServerUser user : serverUsers) {
            if ("test".equalsIgnoreCase(user.name)) {
                fallback = user;
                break;
            }
        }
        selectServerUser(fallback, false);
    }

    private void selectServerUser(ServerUser user, boolean explicit) {
        if (db == null || user == null || TextUtils.isEmpty(user.id)) return;
        db.setSetting(SETTING_SERVER_USER_ID, user.id);
        db.setSetting(SETTING_SERVER_USER_NAME, user.name);
        db.setSetting(SETTING_SERVER_USER_EXPLICIT, explicit ? "1" : "0");
    }

    private String selectedServerUserId(Db localDb) {
        if (localDb == null) return "";
        String selected = localDb.setting(SETTING_SERVER_USER_ID, "").trim();
        if (!TextUtils.isEmpty(selected)) return selected;
        return localDb.setting(SETTING_DEVICE_SERVER_USER_ID, "").trim();
    }

    private String selectedServerUserName(Db localDb) {
        if (localDb == null) return "";
        String selected = localDb.setting(SETTING_SERVER_USER_NAME, "").trim();
        if (!TextUtils.isEmpty(selected)) return displayServerUserName(selected);
        return displayServerUserName(localDb.setting(SETTING_DEVICE_SERVER_USER_NAME, "").trim());
    }

    private String selectedServerUserQuerySuffix(Db localDb) {
        String id = selectedServerUserId(localDb);
        if (TextUtils.isEmpty(id)) return "";
        try {
            return "&user_id=" + URLEncoder.encode(id, "UTF-8");
        } catch (Exception ignored) {
            return "";
        }
    }

    private String displayServerUserName(String name) {
        String clean = nullToEmpty(name).trim();
        if (TextUtils.isEmpty(clean)) return "";
        if ("test".equalsIgnoreCase(clean)) return "test";
        return clean.replaceFirst("^Android\\s+", "");
    }

    private void migrateRuntimeSettings() {
        db.ensureLibrarySchema();
        apiBaseUrlSetting(db);
        String backendDefaultVersion = db.setting("backend_default_version", "");
        if (!BACKEND_DEFAULT_VERSION.equals(backendDefaultVersion)) {
            db.setSetting("transcription_engine", DEFAULT_TRANSCRIPTION_ENGINE);
            db.setSetting("backend_default_version", BACKEND_DEFAULT_VERSION);
        }
        String engine = db.setting("transcription_engine", "");
        if (!ENGINE_TUNNEL_PARAKEET.equals(engine)) {
            db.setSetting("transcription_engine", DEFAULT_TRANSCRIPTION_ENGINE);
        }
        if (TextUtils.isEmpty(db.setting("display_timezone", "").trim())) {
            db.setSetting("display_timezone", "America/New_York");
        }
        String openAiModel = db.setting("openai_model", "").trim();
        if (TextUtils.isEmpty(openAiModel) || !openAiModel.toLowerCase(Locale.US).startsWith("gpt-")) {
            db.setSetting("openai_model", DEFAULT_OPENAI_MODEL);
        }
    }

    private void showHome() {
        base("Ad Free Podcast Player");
        String crash = getSharedPreferences("crash", MODE_PRIVATE).getString("last", "");
        if (!TextUtils.isEmpty(crash)) {
            LinearLayout warning = card();
            warning.setBackground(rounded(SOFT_RED, dp(18), 0));
            warning.addView(text("Recovered from the last crash", 17, INK, true));
            warning.addView(text(crash, 13, INK, false));
            warning.addView(secondaryButton("Clear crash note", v -> {
                getSharedPreferences("crash", MODE_PRIVATE).edit().clear().apply();
                replaceScreen(() -> showHome());
            }));
            root.addView(warning);
        }
        ArrayList<Episode> recent = db.recentPlayed(3);
        if (!recent.isEmpty()) {
            section("Continue Listening");
            for (Episode e : recent) root.addView(episodeRow(e, true, true, true));
        }
        section("Favorites");
        ArrayList<Podcast> podcasts = db.subscriptions();
        if (podcasts.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("No favorites yet", 18, INK, true));
            LinearLayout actions = row();
            actions.addView(button("Search", v -> navigate(() -> showSearch())));
            empty.addView(actions);
            root.addView(empty);
        }
        for (Podcast p : podcasts) {
            LinearLayout card = compactRow();
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout meta = new LinearLayout(this);
            meta.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(p.title, 15, INK, true);
            title.setTextColor(BLUE);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setClickable(true);
            title.setOnClickListener(v -> navigate(() -> showPodcast(p.feedUrl, false)));
            meta.addView(title);
            meta.addView(text(db.countNew(p.id) + " new  |  " + db.countDownloaded(p.id) + " downloaded", 11, MUTED, false));
            card.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout actions = row();
            actions.setPadding(0, dp(2), 0, 0);
            actions.addView(iconActionButton(R.drawable.ic_open, "Open", v -> navigate(() -> showPodcast(p.feedUrl, false))));
            actions.addView(iconActionButton(android.R.drawable.ic_popup_sync, "Refresh", v -> navigate(() -> showPodcast(p.feedUrl, true))));
            card.addView(actions);
            card.setOnClickListener(v -> navigate(() -> showPodcast(p.feedUrl, false)));
            root.addView(card);
        }
        section("In Progress");
        ArrayList<Episode> progress = db.episodesByStatus("in_progress", 8);
        if (progress.isEmpty()) root.addView(emptyState("Nothing in progress yet."));
        for (Episode e : progress) root.addView(episodeRow(e, true, false, true));
        section("Queued & Processing");
        LinearLayout serverQueueContainer = new LinearLayout(this);
        serverQueueContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(serverQueueContainer);
        loadRemoteQueueForHome(serverQueueContainer);
        section("Saved Episodes");
        ArrayList<Episode> saved = db.savedEpisodes(20);
        if (saved.isEmpty()) root.addView(emptyState("Save episodes to build a metadata-only playlist."));
        for (Episode e : saved) root.addView(episodeRow(e, true, false, true));
        section("Downloads");
        ArrayList<Episode> downloads = db.episodesByStatus("downloaded", 8);
        if (downloads.isEmpty()) root.addView(emptyState("Downloaded episodes will appear here."));
        for (Episode e : downloads) root.addView(episodeRow(e, true, false, true));
    }

    private void showRemoteAdFree() {
        base("Ad-free");
        setStatus("Loading processed episodes from the server...");
        showLoadingOverlay();
        root.addView(text("Last 40 completed ad-free episodes from the server.", 14, MUTED, false));
        io.execute(() -> {
            try {
                String apiBase = apiBaseUrlSetting(db);
                JSONObject parsed = fetchApiJsonWithFallback(apiBase, "/api/jobs?status=completed&limit=40", "Server library failed", "server ad-free library");
                JSONArray jobs = parsed.optJSONArray("jobs");
                String renderApiBase = apiBaseUrlSetting(db);
                postUi(() -> renderRemoteAdFreeJobs(jobs == null ? new JSONArray() : jobs, renderApiBase));
            } catch (Exception ex) {
                String body = TextUtils.isEmpty(ex.getMessage()) ? "Could not load the server ad-free library." : ex.getMessage();
                if (isDnsLookupFailure(body)) {
                    body = "DNS lookup failed for both Ad API hostnames. Try toggling Wi-Fi/mobile data or set Android Private DNS to Automatic/Off, then reopen this tab.";
                }
                final String finalBody = body;
                postUi(() -> showError("Ad-free library unavailable", finalBody));
            }
        });
    }

    private void renderRemoteAdFreeJobs(JSONArray jobs, String apiBase) {
        base("Ad-free");
        setStatus("Server ad-free library loaded.");
        if (jobs.length() == 0) {
            root.addView(emptyState("No completed ad-free episodes are available from the server yet."));
            return;
        }
        ArrayList<JSONObject> sorted = sortedRemoteJobs(jobs);
        int shown = 0;
        for (int i = 0; i < sorted.size() && shown < 40; i++) {
            JSONObject job = sorted.get(i);
            if (job == null) continue;
            String downloadUrl = job.optString("download_url", "");
            if (TextUtils.isEmpty(downloadUrl)) continue;
            String jobId = job.optString("job_id", "");
            String episodeTitle = cleanRemoteEpisodeTitle(job.optString("episode_title", ""));
            if (TextUtils.isEmpty(episodeTitle)) episodeTitle = readableJobTitle(job);
            episodeTitle = cleanRemoteEpisodeTitle(episodeTitle);
            String podcastName = cleanRemotePodcastName(job.optString("podcast_name", ""));
            if (TextUtils.isEmpty(episodeTitle)) continue;
            if (TextUtils.isEmpty(podcastName)) podcastName = "Server ad-free library";
            String finalEpisodeTitle = episodeTitle;
            String finalPodcastName = podcastName;
            String finalDownloadUrl = resolveApiUrl(apiBase, downloadUrl);
            String finalJobId = TextUtils.isEmpty(jobId) ? sha(finalDownloadUrl) : jobId;
            LinearLayout item = compactRow();
            TextView podcast = text(finalPodcastName, 11, MUTED, true);
            podcast.setTextColor(BLUE);
            podcast.setMaxLines(1);
            podcast.setEllipsize(TextUtils.TruncateAt.END);
            String releaseDate = job.optString("episode_pub_date", "");
            if (TextUtils.isEmpty(releaseDate)) releaseDate = job.optString("pub_date", "");
            if (TextUtils.isEmpty(releaseDate)) releaseDate = job.optString("published_at", "");
            String finalReleaseDate = releaseDate;
            podcast.setOnClickListener(v -> {
                String id = db.saveServerAdFreeEpisode(finalJobId, finalEpisodeTitle, finalPodcastName, finalDownloadUrl, finalReleaseDate);
                Episode saved = db.episode(id);
                if (saved != null) navigate(() -> showPodcastById(saved.podcastId));
            });
            item.addView(podcast);
            item.addView(text(finalEpisodeTitle, 14, INK, true));
            String releaseDisplay = displayReleaseDate(releaseDate);
            String processedDisplay = displayReleaseDate(remoteJobProcessedAt(job));
            String meta = TextUtils.isEmpty(releaseDisplay) ? "" : "Released " + releaseDisplay;
            if (!TextUtils.isEmpty(processedDisplay)) {
                meta = TextUtils.isEmpty(meta) ? "Processed " + processedDisplay : meta + "  |  Processed " + processedDisplay;
            }
            if (!TextUtils.isEmpty(meta)) item.addView(text(meta, 11, MUTED, false));
            LinearLayout actions = row();
            actions.addView(iconActionButton(R.drawable.ic_play, "Play", v -> {
                String id = db.saveServerAdFreeEpisode(finalJobId, finalEpisodeTitle, finalPodcastName, finalDownloadUrl, finalReleaseDate);
                Episode saved = db.episode(id);
                if (saved != null) playOrDownloadEpisode(saved);
            }));
            actions.addView(iconActionButton(R.drawable.ic_save, db.isSaved("server:" + finalJobId) ? "Saved" : "Save", v -> {
                String id = db.saveServerAdFreeEpisode(finalJobId, finalEpisodeTitle, finalPodcastName, finalDownloadUrl, finalReleaseDate);
                db.setEpisodeSaved(id, true);
                setStatus("Saved to your episode playlist.");
                v.setEnabled(false);
                v.setAlpha(0.55f);
            }));
            actions.addView(iconActionButton(R.drawable.ic_trash, "Delete from server", v -> deleteRemoteAdFreeJob(apiBase, finalJobId, item)));
            item.addView(actions);
            root.addView(item);
            shown++;
        }
        setStatus(shown + " cleaned processed episode(s) shown.");
        if (shown == 0) {
            root.addView(emptyState("The server did not return any completed episodes with downloadable audio."));
        }
    }

    private void loadRemoteQueueForHome(LinearLayout container) {
        if (container == null) return;
        container.removeAllViews();
        container.addView(text("Loading current conversion queue...", 13, MUTED, false));
        String apiBase = apiBaseUrlSetting(db);
        io.execute(() -> {
            try {
                JSONObject parsed = fetchApiJsonWithFallback(apiBase, "/api/jobs?status=queued,running&limit=20", "Server queue failed", "server conversion queue");
                JSONArray jobs = parsed.optJSONArray("jobs");
                ArrayList<JSONObject> sorted = sortJobsByQueueTime(jobs == null ? new JSONArray() : jobs);
                postUi(() -> {
                    if (container.getParent() == null) return;
                    container.removeAllViews();
                    if (sorted.isEmpty()) {
                        container.addView(emptyState("No queued or processing conversions right now."));
                        return;
                    }
                    for (JSONObject job : sorted) {
                        if (job == null) continue;
                        String jobId = job.optString("job_id", "");
                        if (TextUtils.isEmpty(jobId)) continue;
                        String status = job.optString("status", "").trim().toLowerCase(Locale.US);
                        if (TextUtils.isEmpty(status)) status = "queued";
                        String progress = String.format(Locale.US, "%d", Math.max(0, (int) Math.round(job.optDouble("progress", 0.0))));
                        String episodeTitle = cleanRemoteEpisodeTitle(job.optString("episode_title", ""));
                        if (TextUtils.isEmpty(episodeTitle)) episodeTitle = readableJobTitle(job);
                        String podcastName = cleanRemotePodcastName(job.optString("podcast_name", ""));
                        String backend = TextUtils.isEmpty(job.optString("backend", "")) ? "server" : job.optString("backend", "");
                        String when = displayDateTimeForJob(job);

                        LinearLayout item = compactRow();
                        item.setPadding(0, dp(8), 0, dp(8));
                        item.setClickable(true);
                        final String statusForClick = status;
                        item.setOnClickListener(v -> {
                            String id = cleanJobId(jobId);
                            if (!TextUtils.isEmpty(id)) setStatus("Job " + id + " is in " + statusForClick + ".");
                        });
                        TextView statusLine = text(capitalizeFirst(status) + "  |  " + progress + "%", 11, statusColor(status), true);
                        statusLine.setEllipsize(TextUtils.TruncateAt.END);
                        item.addView(statusLine);
                        if (!TextUtils.isEmpty(podcastName)) item.addView(text(podcastName, 11, MUTED, true));
                        item.addView(text(TextUtils.isEmpty(episodeTitle) ? "Episode in queue" : episodeTitle, 14, INK, true));
                        String meta = "Job " + cleanJobId(jobId) + "  |  " + backend;
                        if (!TextUtils.isEmpty(when)) meta = meta + "  |  " + when;
                        item.addView(text(meta, 11, MUTED, false));
                        container.addView(item);
                    }
                });
            } catch (Exception ex) {
                final String message = TextUtils.isEmpty(ex.getMessage()) ? "Could not load queued conversions." : ex.getMessage();
                postUi(() -> {
                    if (container.getParent() == null) return;
                    container.removeAllViews();
                    container.addView(emptyState(message));
                });
            }
        });
    }

    private ArrayList<JSONObject> sortJobsByQueueTime(JSONArray jobs) {
        ArrayList<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.optJSONObject(i);
            if (job != null) out.add(job);
        }
        Collections.sort(out, (a, b) -> Long.compare(jobQueueMillis(b), jobQueueMillis(a)));
        return out;
    }

    private long jobQueueMillis(JSONObject job) {
        Date parsed = parseFeedOrIsoDate(jobQueueTime(job));
        return parsed == null ? 0L : parsed.getTime();
    }

    private String jobQueueTime(JSONObject job) {
        if (job == null) return "";
        String raw = job.optString("started_at", "");
        if (TextUtils.isEmpty(raw)) raw = job.optString("updated_at", "");
        if (TextUtils.isEmpty(raw)) raw = job.optString("created_at", "");
        return raw;
    }

    private String displayDateTimeForJob(JSONObject job) {
        String raw = jobQueueTime(job);
        if (TextUtils.isEmpty(raw)) return "";
        Date parsed = parseFeedOrIsoDate(raw);
        return parsed == null ? "" : displayDateTime(parsed);
    }

    private String cleanJobId(String jobId) {
        if (TextUtils.isEmpty(jobId)) return "";
        return jobId.substring(0, Math.min(8, jobId.length()));
    }

    private int statusColor(String status) {
        if ("running".equalsIgnoreCase(status)) return TEAL;
        if ("queued".equalsIgnoreCase(status)) return CORAL;
        return TEAL;
    }

    private String capitalizeFirst(String value) {
        if (TextUtils.isEmpty(value)) return "";
        if (value.length() == 1) return value.toUpperCase(Locale.US);
        return value.substring(0, 1).toUpperCase(Locale.US) + value.substring(1);
    }

    private ArrayList<JSONObject> sortedRemoteJobs(JSONArray jobs) {
        ArrayList<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.optJSONObject(i);
            if (job != null) out.add(job);
        }
        Collections.sort(out, (a, b) -> Long.compare(remoteJobProcessedMillis(b), remoteJobProcessedMillis(a)));
        return out;
    }

    private long remoteJobProcessedMillis(JSONObject job) {
        Date parsed = parseFeedOrIsoDate(remoteJobProcessedAt(job));
        return parsed == null ? 0L : parsed.getTime();
    }

    private String remoteJobProcessedAt(JSONObject job) {
        if (job == null) return "";
        String raw = job.optString("finished_at", "");
        if (TextUtils.isEmpty(raw)) raw = job.optString("completed_at", "");
        if (TextUtils.isEmpty(raw)) raw = job.optString("processed_at", "");
        if (TextUtils.isEmpty(raw)) raw = job.optString("updated_at", "");
        if (TextUtils.isEmpty(raw)) raw = job.optString("created_at", "");
        return raw;
    }

    private void deleteRemoteAdFreeJob(String apiBase, String jobId, View itemView) {
        if (TextUtils.isEmpty(jobId)) return;
        setStatus("Deleting server ad-free record...");
        showLoadingOverlay();
        io.execute(() -> {
            try {
                deleteApiWithFallback(apiBase, "/api/jobs/" + URLEncoder.encode(jobId, "UTF-8"), "Delete failed");
                db.deleteLocalEpisodeMetadata("server:" + jobId);
                postUi(() -> {
                    hideModalOverlay();
                    if (itemView != null && itemView.getParent() instanceof LinearLayout) {
                        ((LinearLayout) itemView.getParent()).removeView(itemView);
                    }
                    setStatus("Deleted server ad-free record.");
                });
            } catch (Exception ex) {
                String msg = TextUtils.isEmpty(ex.getMessage()) ? "Could not delete that server record." : ex.getMessage();
                postUi(() -> showError("Delete failed", msg));
            }
        });
    }

    private String cleanRemoteEpisodeTitle(String title) {
        String out = nullToEmpty(title).trim();
        if ("null".equalsIgnoreCase(out) || "undefined".equalsIgnoreCase(out)) return "";
        out = out.replaceFirst("(?i)^after[\\s:_-]+", "");
        out = out.replaceFirst("(?i)^ad[-\\s]*free[\\s:_-]+", "");
        return out.trim();
    }

    private String cleanRemotePodcastName(String name) {
        String out = nullToEmpty(name).trim();
        if ("null".equalsIgnoreCase(out) || "undefined".equalsIgnoreCase(out)) return "";
        return out;
    }

    private String readableJobTitle(JSONObject job) {
        String source = job.optString("source_url", "");
        if (!TextUtils.isEmpty(source)) {
            try {
                Uri u = Uri.parse(source);
                String last = u.getLastPathSegment();
                if (!TextUtils.isEmpty(last)) return cleanName(last);
                if (!TextUtils.isEmpty(u.getHost())) return u.getHost();
            } catch (Exception ignored) {}
        }
        String id = job.optString("job_id", "");
        return TextUtils.isEmpty(id) ? "Processed episode" : "Processed episode " + id.substring(0, Math.min(8, id.length()));
    }
    private void showSearch() {
        base("Search");
        EditText q = input("podcast name");
        root.addView(q);
        Button go = button("Search", v -> {
            hideKeyboard(q);
            String term = q.getText().toString().trim();
            if (term.isEmpty()) return;
            setStatus("Searching...");
            showLoadingOverlay();
            io.execute(() -> searchOnline(term));
        });
        root.addView(go);
    }

    private String buildIdentity() {
            try {
                String account = selectedServerUserName(db);
                if (TextUtils.isEmpty(account)) account = deviceUserName();
                String accountSuffix = TextUtils.isEmpty(account) ? "" : "  |  " + account;
                if (Build.VERSION.SDK_INT >= 33) {
                    android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), android.content.pm.PackageManager.PackageInfoFlags.of(0));
                    return "Build " + info.versionName + " (" + info.getLongVersionCode() + ")  |  " + getPackageName() + accountSuffix;
                }
                android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
                return "Build " + info.versionName + " (" + info.versionCode + ")  |  " + getPackageName() + accountSuffix;
            } catch (Exception ignored) {
                return "Package " + getPackageName();
            }
    }

    private String directoryCacheKey(String term) {
        return nullToEmpty(term).trim().toLowerCase(Locale.US);
    }

    private void renderSearchResults(ArrayList<Podcast> results, boolean cached) {
        base("Search Results");
        setStatus(results.size() + " result(s)" + (cached ? " from cache." : "."));
        if (results.isEmpty()) {
            root.addView(emptyState("No podcasts found."));
            return;
        }
        for (Podcast p : results) root.addView(searchRow(p));
    }

    private void searchOnline(String term) {
        try {
            String cacheKey = directoryCacheKey(term);
            ArrayList<Podcast> cached = db.cachedDirectoryForQuery(cacheKey);
            if (!cached.isEmpty()) {
                postUi(() -> renderSearchResults(cached, true));
                return;
            }
            String url = "https://itunes.apple.com/search?media=podcast&limit=50&term=" + URLEncoder.encode(term, "UTF-8");
            JSONObject json = new JSONObject(readUrl(url));
            JSONArray arr = json.getJSONArray("results");
            ArrayList<Podcast> results = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String feed = o.optString("feedUrl", "");
                if (feed.isEmpty()) continue;
                Podcast p = new Podcast();
                p.id = sha(feed);
                p.source = "itunes";
                p.sourceId = o.optString("collectionId", "");
                p.title = o.optString("collectionName", "Untitled podcast");
                p.publisher = o.optString("artistName", "");
                p.feedUrl = feed;
                p.directoryUrl = o.optString("collectionViewUrl", "");
                p.artworkUrl = o.optString("artworkUrl100", "");
                p.description = o.optString("primaryGenreName", "");
                db.cacheDirectory(p, cacheKey, now);
                results.add(p);
            }
            postUi(() -> renderSearchResults(results, false));
        } catch (Exception e) {
            postUi(() -> showError("Search failed", "Check your connection and try again."));
        }
    }

    private View searchRow(Podcast p) {
        LinearLayout card = compactRow();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(p.title, 15, INK, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(BLUE);
        title.setClickable(true);
        title.setOnClickListener(v -> {
            if (TextUtils.isEmpty(p.feedUrl)) showError("Podcast unavailable", "This directory result did not include a playable podcast source.");
            else navigate(() -> showPodcast(p.feedUrl, false));
        });
        meta.addView(title);
        if (!TextUtils.isEmpty(p.publisher)) meta.addView(text(nullToEmpty(p.publisher), 12, MUTED, false));
        if (!TextUtils.isEmpty(p.description)) meta.addView(text(p.description, 11, MUTED, false));
        card.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(iconActionButton(R.drawable.ic_open, "Open podcast", v -> {
            if (TextUtils.isEmpty(p.feedUrl)) showError("Podcast unavailable", "This directory result did not include a playable podcast source.");
            else navigate(() -> showPodcast(p.feedUrl, false));
        }));
        card.setOnClickListener(v -> {
            if (TextUtils.isEmpty(p.feedUrl)) showError("Podcast unavailable", "This directory result did not include a playable podcast source.");
            else navigate(() -> showPodcast(p.feedUrl, false));
        });
        return card;
    }

    private void showPodcast(String feedUrl, boolean forceRefresh) {
        feedUrl = normalizeUrl(feedUrl);
        if (TextUtils.isEmpty(feedUrl)) {
            showError("Podcast unavailable", "This podcast does not have a usable source URL.");
            return;
        }
        base("Podcast");
        setStatus("Loading podcast...");
        final String finalFeedUrl = feedUrl;
        Podcast cached = db.podcastByFeed(finalFeedUrl);
        if (cached != null && !forceRefresh && db.podcastFeedFresh(cached, PODCAST_CACHE_TTL_MS)) {
            renderPodcast(cached);
            setStatus("Using cached episodes. Last updated: " + friendly(cached.lastFeedRefreshAt));
            return;
        }
        if (cached != null && !forceRefresh) {
            renderPodcast(cached);
            setStatus("Refreshing stale episode cache...");
        } else {
            showLoadingOverlay();
        }
        io.execute(() -> {
            try {
                Feed parsed = parseFeed(finalFeedUrl);
                db.saveFeed(parsed);
                postUi(() -> {
                    Podcast saved = db.podcastByFeed(finalFeedUrl);
                    renderPodcast(saved != null ? saved : parsed.podcast);
                });
            } catch (Exception e) {
                postUi(() -> {
                    Podcast p = db.podcastByFeed(finalFeedUrl);
                    if (p != null) {
                        renderPodcast(p);
                        setStatus("Could not refresh this podcast. Using cached episodes.");
                    } else {
                        showError("Could not load this podcast", "Try again later. The podcast must include at least one playable audio episode.");
                    }
                });
            }
        });
    }

    private void renderPodcast(Podcast p) {
        if (p == null || TextUtils.isEmpty(p.id)) {
            showError("Podcast unavailable", "The podcast loaded, but the app could not save enough metadata to display it.");
            return;
        }
        base(TextUtils.isEmpty(p.title) ? "Podcast" : p.title);
        setStatus("Last updated: " + friendly(p.lastFeedRefreshAt));
        boolean serverPodcast = isServerPodcast(p);
        if (!TextUtils.isEmpty(p.publisher) && !serverPodcast) root.addView(text(p.publisher, 16, MUTED, false));
        String desc = strip(p.description);
        if (!TextUtils.isEmpty(desc)) {
            TextView descView = text(desc, 14, MUTED, false);
            descView.setMaxLines(4);
            descView.setEllipsize(TextUtils.TruncateAt.END);
            root.addView(descView);
        }
        Button sub = button(p.subscribed ? "Remove favorite" : "Add favorite", v -> {
            db.setSubscribed(p.id, !p.subscribed);
            p.subscribed = !p.subscribed;
            renderPodcast(p);
        });
        root.addView(sub);
        LinearLayout actions = row();
        if (!serverPodcast) actions.addView(secondaryButton("Refresh episodes", v -> replaceScreen(() -> showPodcast(p.feedUrl, true))));
        actions.addView(secondaryButton("About/legal", v -> legalDialog()));
        root.addView(actions);
        section("Episodes");
        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.VERTICAL);
        root.addView(filters);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        for (int i = 0; i < FILTERS.length; i += 2) {
            LinearLayout filterRow = row();
            String firstFilter = FILTERS[i];
            filterRow.addView(filterButton(firstFilter, v -> renderEpisodeList(list, p, firstFilter, 0)));
            if (i + 1 < FILTERS.length) {
                String nextFilter = FILTERS[i + 1];
                filterRow.addView(filterButton(nextFilter, v -> renderEpisodeList(list, p, nextFilter, 0)));
            }
            filters.addView(filterRow);
        }
        renderEpisodeList(list, p, "All", 0);
    }

    private Button filterButton(String label, View.OnClickListener listener) {
        Button b = secondaryButton(label, listener);
        b.setTextSize(12);
        b.setSingleLine(false);
        b.setMaxLines(2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(0, dp(3), dp(7), dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private void renderEpisodeList(LinearLayout list, Podcast p, String filter, int page) {
        try {
            list.removeAllViews();
            int total = db.episodeCountForPodcast(p.id, filter);
            int maxPage = total <= 0 ? 0 : (total - 1) / EPISODE_PAGE_SIZE;
            int safePage = Math.max(0, Math.min(page, maxPage));
            ArrayList<Episode> episodes = db.episodesForPodcast(p.id, filter, EPISODE_PAGE_SIZE, safePage * EPISODE_PAGE_SIZE);
            if (episodes.isEmpty()) {
                if (safePage > 0) {
                    renderEpisodeList(list, p, filter, 0);
                } else {
                    list.addView(emptyState("No episodes match this filter yet."));
                }
                return;
            }
            list.addView(text(filter + " episodes - page " + (safePage + 1) + " of " + (maxPage + 1), 16, INK, true));
            for (Episode e : episodes) {
                try {
                    list.addView(episodeRow(e, true));
                } catch (Exception rowError) {
                    list.addView(emptyState("One episode could not be displayed safely: " + rowError.getClass().getSimpleName() + " " + nullToEmpty(rowError.getMessage())));
                }
            }
            LinearLayout pager = row();
            pager.setGravity(Gravity.CENTER_VERTICAL);
            Button prev = secondaryButton("Previous", v -> renderEpisodeList(list, p, filter, safePage - 1));
            prev.setEnabled(safePage > 0);
            TextView current = text(String.valueOf(safePage + 1), 16, INK, true);
            current.setGravity(Gravity.CENTER);
            current.setPadding(dp(10), 0, dp(10), 0);
            current.setBackground(rounded(SURFACE, dp(14), LINE));
            Button next = secondaryButton("Next", v -> renderEpisodeList(list, p, filter, safePage + 1));
            next.setEnabled(safePage < maxPage);
            pager.addView(prev, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams currentLp = new LinearLayout.LayoutParams(dp(72), dp(44));
            currentLp.setMargins(0, dp(3), dp(7), dp(3));
            pager.addView(current, currentLp);
            pager.addView(next, new LinearLayout.LayoutParams(0, -2, 1f));
            list.addView(pager);
            list.addView(jumpToPageRow(list, p, filter, safePage, maxPage + 1));
            int start = safePage * EPISODE_PAGE_SIZE + 1;
            int end = safePage * EPISODE_PAGE_SIZE + episodes.size();
            list.addView(text("Showing " + start + "-" + end + " of " + total, 13, MUTED, false));
        } catch (Exception e) {
            list.removeAllViews();
            list.addView(emptyState("Episodes could not be displayed, but the app recovered instead of closing: " + e.getClass().getSimpleName() + " " + nullToEmpty(e.getMessage())));
        }
    }

    private View jumpToPageRow(LinearLayout list, Podcast p, String filter, int safePage, int totalPages) {
        LinearLayout jumpRow = row();
        jumpRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text("Jump to page:", 13, MUTED, true);
        jumpRow.addView(label);
        EditText jump = input("");
        jump.setText(String.valueOf(safePage + 1));
        jump.setSelectAllOnFocus(true);
        jump.setTextSize(13);
        jump.setGravity(Gravity.CENTER);
        jump.setMinHeight(dp(38));
        jump.setPadding(dp(8), 0, dp(8), 0);
        jump.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        jump.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        final boolean[] cleaning = new boolean[]{false};
        jump.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (cleaning[0]) return;
                String raw = s == null ? "" : s.toString();
                String cleaned = raw.replaceAll("[^0-9]", "");
                if (!raw.equals(cleaned)) {
                    cleaning[0] = true;
                    jump.setText(cleaned);
                    jump.setSelection(cleaned.length());
                    cleaning[0] = false;
                }
            }
        });
        jump.setOnEditorActionListener((v, actionId, event) -> {
            jumpToEpisodePage(list, p, filter, jump, totalPages);
            return true;
        });
        jump.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) jumpToEpisodePage(list, p, filter, jump, totalPages);
        });
        LinearLayout.LayoutParams jumpLp = new LinearLayout.LayoutParams(dp(82), dp(42));
        jumpLp.setMargins(dp(8), dp(4), dp(8), dp(4));
        jumpRow.addView(jump, jumpLp);
        jumpRow.addView(text("of " + totalPages, 13, MUTED, false));
        return jumpRow;
    }

    private TextView podcastLink(String podcastId, String podcastName, int size, int fallbackColor, boolean bold) {
        TextView link = text(podcastName, size, fallbackColor, bold);
        link.setMaxLines(1);
        link.setEllipsize(TextUtils.TruncateAt.END);
        if (!TextUtils.isEmpty(podcastId) && db != null && db.podcastExists(podcastId)) {
            link.setTextColor(fallbackColor == PLAYER_MUTED ? PLAYER_ACCENT : BLUE);
            link.setClickable(true);
            link.setOnClickListener(v -> navigate(() -> showPodcastById(podcastId)));
        }
        return link;
    }

    private void showPodcastById(String podcastId) {
        Podcast p = db == null ? null : db.podcastById(podcastId);
        if (p == null) {
            showError("Podcast unavailable", "This podcast could not be found on this device.");
            return;
        }
        renderPodcast(p);
    }

    private boolean isServerPodcast(Podcast p) {
        return p != null && ("server".equalsIgnoreCase(nullToEmpty(p.source)) || nullToEmpty(p.feedUrl).startsWith("server://"));
    }

    private void jumpToEpisodePage(LinearLayout list, Podcast p, String filter, EditText jump, int totalPages) {
        String raw = jump == null ? "" : jump.getText().toString();
        String cleaned = raw.replaceAll("[^0-9]", "");
        if (jump != null && !raw.equals(cleaned)) {
            jump.setText(cleaned);
            jump.setSelection(cleaned.length());
        }
        if (TextUtils.isEmpty(cleaned)) return;
        int pageNumber;
        try {
            pageNumber = Integer.parseInt(cleaned);
        } catch (Exception ignored) {
            pageNumber = totalPages;
        }
        int target = Math.max(1, Math.min(pageNumber, Math.max(1, totalPages))) - 1;
        renderEpisodeList(list, p, filter, target);
    }

    private View episodeRow(Episode e, boolean openable) {
        return episodeRow(e, openable, false);
    }

    private View episodeRow(Episode e, boolean openable, boolean hideDownload) {
        return episodeRow(e, openable, hideDownload, false);
    }

    private View episodeRow(Episode e, boolean openable, boolean hideDownload, boolean showPodcastName) {
        LinearLayout card = compactRow();
        if (showPodcastName) {
            String podcastName = db == null ? "" : db.podcastTitle(e.podcastId);
            if (!TextUtils.isEmpty(podcastName)) {
                card.addView(podcastLink(e.podcastId, podcastName, 11, MUTED, true));
            }
        }
        TextView title = text(e.title, 14, INK, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title);
        String releaseDate = e.id.startsWith("server:") ? "" : displayReleaseDate(e.pubDate);
        String meta = (TextUtils.isEmpty(releaseDate) ? "" : releaseDate + "  ") + indicators(e);
        TextView metaView = text(meta.trim(), 11, MUTED, false);
        metaView.setMaxLines(2);
        metaView.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(metaView);
        LinearLayout actions = row();
        actions.setPadding(0, dp(2), 0, 0);
        actions.addView(iconActionButton(R.drawable.ic_play, e.positionSeconds > 0 ? "Resume" : "Play", v -> playOrDownloadEpisode(e)));
        actions.addView(iconActionButton(R.drawable.ic_save, db.isSaved(e.id) ? "Saved" : "Save", v -> {
            boolean next = !db.isSaved(e.id);
            db.setEpisodeSaved(e.id, next);
            setStatus(next ? "Saved to your episode playlist." : "Removed from saved episodes.");
            if (!openable) showEpisode(e.id);
        }));
        boolean processed = "processed".equals(e.adSupportedStatus);
        if (!hideDownload && !processed) {
            actions.addView(iconActionButton(e.downloaded() ? R.drawable.ic_trash : R.drawable.ic_download, e.downloaded() ? "Remove download" : "Download", v -> {
                if (e.downloaded()) removeDownload(e); else downloadEpisode(e);
            }));
        }
        if (processed) {
            TextView pill = text("Ad-free", 12, TEAL, false);
            pill.setSingleLine(true);
            pill.setPadding(dp(10), dp(6), dp(10), dp(6));
            pill.setBackground(rounded(SOFT_GREEN, dp(20), 0));
            actions.addView(pill);
        } else {
            actions.addView(iconActionButton(R.drawable.ic_adfree, adQueue.contains(e.id) || e.id.equals(adCurrentId) ? "Queued..." : "Remove ads", v -> removeAds(e)));
        }
        if (db.isSaved(e.id) || e.id.startsWith("server:") || processed) {
            actions.addView(iconActionButton(R.drawable.ic_trash, "Remove from playlist", v -> {
                if (processed && !e.id.startsWith("server:")) removeDownload(e);
                db.removeFromPlaylist(e.id);
                setStatus("Removed from this device.");
                replaceScreen(() -> showHome());
            }));
        }
        actions.addView(iconActionButton(R.drawable.ic_open, "Details", v -> navigate(() -> showEpisode(e.id))));
        card.addView(actions);
        if (openable) card.setOnClickListener(v -> navigate(() -> showEpisode(e.id)));
        return card;
    }
    private void showEpisode(String id) {
        Episode e = db.episode(id);
        if (e == null) {
            showError("Episode unavailable", "This episode could not be found in local storage.");
            return;
        }
        base("Episode");
        root.addView(text(e.title, 21, INK, true));
        String podcastName = db.podcastTitle(e.podcastId);
        if (!TextUtils.isEmpty(podcastName)) root.addView(podcastLink(e.podcastId, podcastName, 15, MUTED, false));
        String releaseDate = e.id.startsWith("server:") ? "" : displayReleaseDate(e.pubDate);
        if (!TextUtils.isEmpty(releaseDate)) root.addView(text(releaseDate, 13, MUTED, false));
        root.addView(text(indicators(e), 13, MUTED, false));
        root.addView(text("Audio enclosure URL", 13, MUTED, true));
        TextView enclosure = code(e.enclosureUrl);
        enclosure.setMaxLines(4);
        enclosure.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(enclosure);
        String desc = strip(e.description);
        if (!TextUtils.isEmpty(desc)) root.addView(text(desc, 14, INK, false));
        LinearLayout actions = row();
        actions.addView(iconActionButton(R.drawable.ic_play, e.positionSeconds > 0 ? "Resume" : "Play", v -> playOrDownloadEpisode(e)));
        actions.addView(iconActionButton(R.drawable.ic_save, db.isSaved(e.id) ? "Saved" : "Save", v -> {
            boolean next = !db.isSaved(e.id);
            db.setEpisodeSaved(e.id, next);
            setStatus(next ? "Saved to your episode playlist." : "Removed from saved episodes.");
            showEpisode(e.id);
        }));
        actions.addView(secondaryButton(e.isListened ? "Mark unlistened" : "Mark listened", v -> {
            db.markListened(e.id, !e.isListened);
            showEpisode(e.id);
        }));
        boolean processed = "processed".equals(e.adSupportedStatus);
        if (!processed) {
            actions.addView(iconActionButton(e.downloaded() ? R.drawable.ic_trash : R.drawable.ic_download, e.downloaded() ? "Remove download" : "Download", v -> {
                if (e.downloaded()) removeDownload(e); else downloadEpisode(e);
            }));
        }
        if (processed) {
            TextView pill = text("Ad-free", 13, TEAL, false);
            pill.setSingleLine(true);
            pill.setPadding(dp(12), dp(7), dp(12), dp(7));
            pill.setBackground(rounded(SOFT_GREEN, dp(20), 0));
            actions.addView(pill);
        } else {
            actions.addView(iconActionButton(R.drawable.ic_adfree, adQueue.contains(e.id) || e.id.equals(adCurrentId) ? "Queued..." : "Remove ads", v -> removeAds(e)));
        }
        if (db.isSaved(e.id) || e.id.startsWith("server:") || processed) {
            actions.addView(iconActionButton(R.drawable.ic_trash, "Remove from playlist", v -> {
                if (processed && !e.id.startsWith("server:")) removeDownload(e);
                db.removeFromPlaylist(e.id);
                setStatus("Removed from this device.");
                replaceScreen(() -> showHome());
            }));
        }
        root.addView(actions);
    }
    private void showSettings() {
        base("Settings");
        CheckBox autoplay = new CheckBox(this);
        autoplay.setText("Autoplay next");
        autoplay.setChecked(settingBool("autoplay_next", false));
        autoplay.setOnCheckedChangeListener((b, checked) -> db.setSetting("autoplay_next", checked ? "1" : "0"));
        root.addView(autoplay);
        timezoneSelector();
        db.setSetting("transcription_engine", DEFAULT_TRANSCRIPTION_ENGINE);
        settingNumber("Download retention days", "download_retention_days", 30);
        settingNumber("Seek back seconds", "seek_back", 15);
        settingNumber("Seek forward seconds", "seek_forward", 30);
        section("Storage");
        root.addView(text(readableSize(downloadBytes()) + " used by downloaded episodes.", 16, INK, false));
        root.addView(text("Unsaved originals can be cleared automatically after 30 days. Saved ad-free files should remain available offline.", 14, MUTED, false));
        root.addView(secondaryButton("Clear listened downloads", v -> { db.clearDownloads(true, false); setStatus("Listened downloads cleared."); showSettings(); }));
        root.addView(secondaryButton("Clear downloads older than retention", v -> { cleanupExpiredDownloads(); showSettings(); }));
        root.addView(secondaryButton("Clear all downloads", v -> confirm("Clear all downloads?", () -> { db.clearDownloads(false, true); showSettings(); })));
        section("Legal / About");
        root.addView(text("Only remove ads from podcasts you own, have licensed, or otherwise have the rights to modify for personal use. Podcast audio, names, artwork, descriptions, and metadata belong to their respective publishers. Downloaded episodes are stored only on this device for personal offline listening.", 14, INK, false));
    }

    private void showDebug() {
        base("Debug");
        setStatus("Recent processed jobs and network calls.");
        showLoadingOverlay();
        section("Last 10 Processed");
        LinearLayout processed = new LinearLayout(this);
        processed.setOrientation(LinearLayout.VERTICAL);
        processed.addView(emptyState("Loading processed jobs..."));
        root.addView(processed);
        loadDebugProcessedJobs(processed);
        section("Recent Network Calls");
        root.addView(secondaryButton("Clear debug log", v -> {
            synchronized (callLogs) { callLogs.clear(); }
            showDebug();
        }));
        ArrayList<CallLog> copy;
        synchronized (callLogs) { copy = new ArrayList<>(callLogs); }
        if (copy.isEmpty()) {
            root.addView(emptyState("No network calls logged yet."));
            return;
        }
        int count = Math.min(copy.size(), 10);
        for (int i = 0; i < count; i++) {
            CallLog log = copy.get(i);
            LinearLayout card = card();
            card.addView(text(log.label + "  |  " + log.status, 17, INK, true));
            card.addView(text(log.at, 12, MUTED, false));
            card.addView(text("Requested URL", 13, MUTED, true));
            card.addView(code(log.url));
            if (!TextUtils.isEmpty(log.finalUrl) && !log.finalUrl.equals(log.url)) {
                card.addView(text("Final URL", 13, MUTED, true));
                card.addView(code(log.finalUrl));
            }
            card.addView(text("Headers", 13, MUTED, true));
            card.addView(code(log.headers));
            card.addView(text("Raw preview (" + log.rawLength + " bytes/chars)", 13, MUTED, true));
            card.addView(code(log.rawPreview));
            root.addView(card);
        }
    }

    private void loadDebugProcessedJobs(LinearLayout target) {
        io.execute(() -> {
            try {
                String apiBase = apiBaseUrlSetting(db);
                JSONObject parsed = fetchApiJsonWithFallback(apiBase, "/api/jobs?status=completed&limit=10", "Processed jobs failed", "debug processed jobs");
                JSONArray jobs = parsed.optJSONArray("jobs");
                postUi(() -> renderDebugProcessedJobs(target, jobs == null ? new JSONArray() : jobs));
            } catch (Exception ex) {
                String body = TextUtils.isEmpty(ex.getMessage()) ? "Could not load processed jobs." : ex.getMessage();
                postUi(() -> {
                    if (target.getParent() == null) return;
                    hideModalOverlay();
                    target.removeAllViews();
                    target.addView(emptyState(body));
                });
            }
        });
    }

    private void renderDebugProcessedJobs(LinearLayout target, JSONArray jobs) {
        if (target.getParent() == null) return;
        hideModalOverlay();
        target.removeAllViews();
        ArrayList<JSONObject> sorted = sortedRemoteJobs(jobs);
        int shown = 0;
        for (int i = 0; i < sorted.size() && shown < 10; i++) {
            JSONObject job = sorted.get(i);
            String downloadUrl = job.optString("download_url", "");
            if (TextUtils.isEmpty(downloadUrl)) continue;
            String title = cleanRemoteEpisodeTitle(job.optString("episode_title", ""));
            if (TextUtils.isEmpty(title)) title = readableJobTitle(job);
            String podcast = cleanRemotePodcastName(job.optString("podcast_name", ""));
            if (TextUtils.isEmpty(podcast)) podcast = "Server ad-free library";
            String processed = displayReleaseDate(remoteJobProcessedAt(job));
            String jobId = job.optString("job_id", "");
            String finalJobId = TextUtils.isEmpty(jobId) ? sha(downloadUrl) : jobId;
            String finalTitle = title;
            String finalPodcast = podcast;
            String finalDownloadUrl = resolveApiUrl(apiBaseUrlSetting(db), downloadUrl);
            String releaseDate = job.optString("episode_pub_date", "");
            if (TextUtils.isEmpty(releaseDate)) releaseDate = job.optString("pub_date", "");
            if (TextUtils.isEmpty(releaseDate)) releaseDate = job.optString("published_at", "");
            String finalReleaseDate = releaseDate;
            LinearLayout row = compactRow();
            TextView podcastName = text(finalPodcast, 11, BLUE, true);
            podcastName.setMaxLines(1);
            podcastName.setEllipsize(TextUtils.TruncateAt.END);
            podcastName.setOnClickListener(v -> {
                String id = db.saveServerAdFreeEpisode(finalJobId, finalTitle, finalPodcast, finalDownloadUrl, finalReleaseDate);
                Episode saved = db.episode(id);
                if (saved != null) navigate(() -> showPodcastById(saved.podcastId));
            });
            row.addView(podcastName);
            row.addView(text(title, 14, INK, true));
            String meta = TextUtils.isEmpty(processed) ? "Completed" : "Completed " + processed;
            if (!TextUtils.isEmpty(jobId)) meta += "  |  " + jobId.substring(0, Math.min(8, jobId.length()));
            row.addView(text(meta, 11, MUTED, false));
            target.addView(row);
            shown++;
        }
        if (shown == 0) target.addView(emptyState("No completed processed jobs found."));
    }

    private void settingNumber(String label, String key, int def) {
        LinearLayout r = row();
        TextView t = text(label, 15, INK, false);
        EditText value = input(String.valueOf(def));
        value.setText(String.valueOf(settingInt(key, def)));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(value, new LinearLayout.LayoutParams(dp(80), -2));
        r.addView(button("Save", v -> db.setSetting(key, value.getText().toString().trim())));
        root.addView(r);
    }

    private void timezoneSelector() {
        String current = displayTimezoneId();
        root.addView(text("Current: " + current, 13, MUTED, false));
        String[][] zones = {
            {"America/New_York", "Eastern"},
            {"America/Chicago", "Central"},
            {"America/Denver", "Mountain"},
            {"America/Los_Angeles", "Pacific"},
            {"UTC", "UTC"}
        };
        LinearLayout row1 = row();
        LinearLayout row2 = row();
        for (int i = 0; i < zones.length; i++) {
            String[] zone = zones[i];
            String zoneId = zone[0];
            Button btn = secondaryButton(zone[1], v -> {
                db.setSetting("display_timezone", zoneId);
                showSettings();
            });
            if (zoneId.equals(current)) {
                btn.setBackground(rounded(BLUE, dp(14), 0));
                btn.setTextColor(0xffffffff);
            }
            if (i < 3) row1.addView(btn); else row2.addView(btn);
        }
        root.addView(row1);
        root.addView(row2);
    }

    private void playOrDownloadEpisode(Episode e) {
        if (isServerEpisode(e) && !e.downloaded() && isHttpUrl(e.enclosureUrl)) {
            downloadServerEpisodeWithOverlay(e);
            return;
        }
        playEpisode(e);
    }

    private void playEpisode(Episode e) {
        try {
            saveCurrentProgress();
            if (player != null) player.release();
            player = new MediaPlayer();
            playerPrepared = false;
            currentEpisode = db.episode(e.id);
            if (currentEpisode == null || TextUtils.isEmpty(currentEpisode.enclosureUrl)) {
                handlePlaybackFailure("Episode unavailable. This episode does not have a playable audio URL.");
                return;
            }
            String source = playableSourceForEpisode(currentEpisode);
            if (TextUtils.isEmpty(source)) {
                handlePlaybackFailure("Ad-free audio is not stored on this device. Open the Ad-free tab or run Remove ads again.");
                return;
            }
            addCallLog("MEDIA PLAY", source, source, 0, "MediaPlayer.setDataSource", "", 0);
            setMediaPlayerDataSource(player, source);
            player.setOnPreparedListener(mp -> {
                playerPrepared = true;
                int pos = Math.max(0, currentEpisode.positionSeconds * 1000);
                if (pos > 0 && pos < mp.getDuration()) mp.seekTo(pos);
                mp.start();
                db.touchPlayed(currentEpisode.id);
                setStatus("Playing.");
                renderMiniPlayer(true);
                updatePlaybackNotification();
            });
            player.setOnCompletionListener(mp -> {
                db.markComplete(currentEpisode.id, mp.getDuration() / 1000);
                updatePlaybackNotification();
                if (settingBool("autoplay_next", false)) {
                    Episode next = db.nextEpisode(currentEpisode);
                    if (next != null) playEpisode(next);
                } else {
                    updatePlayerDock();
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Episode failedEpisode = currentEpisode;
                addCallLog("MEDIA ERROR", failedEpisode == null ? "" : failedEpisode.enclosureUrl, "", 0, "what=" + what + ", extra=" + extra, "", 0);
                if (cacheServerEpisodeForPlayback(failedEpisode)) return true;
                handlePlaybackFailure();
                return true;
            });
            player.prepareAsync();
            setStatus("Preparing audio...");
        } catch (Exception ex) {
            Episode failedEpisode = currentEpisode;
            addCallLog("MEDIA EXCEPTION", failedEpisode == null ? "" : failedEpisode.enclosureUrl, "", 0, ex.getClass().getSimpleName(), nullToEmpty(ex.getMessage()), 0);
            if (cacheServerEpisodeForPlayback(failedEpisode)) return;
            handlePlaybackFailure();
        }
    }

    private String playableSourceForEpisode(Episode episode) {
        if (episode == null) return "";
        if (!TextUtils.isEmpty(episode.localFilePath) && new File(episode.localFilePath).exists()) {
            return episode.localFilePath;
        }
        if ("processed".equals(episode.adSupportedStatus) && !isServerEpisode(episode)) {
            return "";
        }
        return normalizeUrl(episode.enclosureUrl);
    }

    private boolean isServerEpisode(Episode episode) {
        return episode != null && !TextUtils.isEmpty(episode.id) && episode.id.startsWith("server:");
    }

    private void setMediaPlayerDataSource(MediaPlayer mediaPlayer, String source) throws Exception {
        if (isHttpUrl(source)) {
            mediaPlayer.setDataSource(this, Uri.parse(source), audioRequestHeaders());
        } else {
            try (FileInputStream input = new FileInputStream(source)) {
                mediaPlayer.setDataSource(input.getFD());
            }
        }
    }

    private Map<String, String> audioRequestHeaders() {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36 AdFreePodAndroid/1.0");
        headers.put("Accept", "audio/mpeg,audio/mp4,audio/aac,audio/*,*/*");
        headers.put("Connection", "keep-alive");
        return headers;
    }

    private void applyAudioRequestHeaders(HttpURLConnection connection) {
        for (Map.Entry<String, String> entry : audioRequestHeaders().entrySet()) {
            connection.setRequestProperty(entry.getKey(), entry.getValue());
        }
    }

    private boolean cacheServerEpisodeForPlayback(Episode failedEpisode) {
        if (!isServerEpisode(failedEpisode) || failedEpisode.downloaded() || !isHttpUrl(failedEpisode.enclosureUrl)) return false;
        try {
            if (player != null) player.release();
        } catch (Exception ignored) {}
        player = null;
        playerPrepared = false;
        downloadServerEpisodeWithOverlay(failedEpisode);
        return true;
    }

    private void downloadServerEpisodeWithOverlay(Episode episode) {
        if (episode == null) return;
        final String episodeId = episode.id;
        showDownloadOverlay(episode.title);
        setStatus("Downloading ad-free audio...");
        io.execute(() -> {
            try {
                Episode latest = db.episode(episodeId);
                if (latest == null || TextUtils.isEmpty(latest.enclosureUrl)) throw new RuntimeException("Missing ad-free URL.");
                File output = buildProcessedAudioPath(latest, "mp3");
                File staging = buildProcessedStagingPath(output);
                if (staging.exists() && !staging.delete()) throw new RuntimeException("Could not replace temporary playback file.");
                downloadRemotePlaybackFile(latest.enclosureUrl, staging, (downloaded, total) -> postUi(() -> updateDownloadOverlayProgress(downloaded, total)));
                if (output.exists() && !output.delete()) throw new RuntimeException("Could not replace cached playback file.");
                copyFile(staging, output);
                if (staging.exists()) staging.delete();
                db.setProcessedAudio(latest.id, output.getAbsolutePath(), "processed");
                Episode playable = db.episode(latest.id);
                main.post(() -> {
                    setStatus("Ad-free audio cached for playback.");
                    if (playable != null) showDownloadReadyOverlay(playable);
                });
            } catch (Exception ex) {
                main.post(() -> {
                    hideModalOverlay();
                    handlePlaybackFailure("Playback failed. The ad-free audio could not be downloaded on this device.");
                });
            }
        });
    }

    private void downloadRemotePlaybackFile(String sourceUrl, File target) throws Exception {
        downloadRemotePlaybackFile(sourceUrl, target, null);
    }

    private void downloadRemotePlaybackFile(String sourceUrl, File target, DownloadProgress progress) throws Exception {
        ArrayList<String> urls = new ArrayList<>();
        if (isKnownApiUrl(sourceUrl)) {
            for (String candidateBase : apiBaseCandidates(apiBaseUrlSetting(db))) {
                String candidateUrl = resolveApiUrl(candidateBase, sourceUrl);
                if (!urls.contains(candidateUrl)) urls.add(candidateUrl);
            }
        } else {
            urls.add(sourceUrl);
        }

        Exception last = null;
        for (String url : urls) {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(300000);
                connection.setRequestMethod("GET");
                applyAudioRequestHeaders(connection);
                int code = connection.getResponseCode();
                if (code >= 400) throw new RuntimeException("HTTP " + code);
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                long total = Build.VERSION.SDK_INT >= 24
                        ? connection.getContentLengthLong()
                        : connection.getContentLength();
                long downloaded = 0;
                long lastUiUpdate = 0;
                try (InputStream in = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        downloaded += n;
                        long now = System.currentTimeMillis();
                        if (progress != null && now - lastUiUpdate > 300) {
                            progress.onProgress(downloaded, total);
                            lastUiUpdate = now;
                        }
                    }
                }
                if (progress != null) progress.onProgress(Math.max(downloaded, total), total);
                if (!target.exists() || target.length() <= 0) throw new RuntimeException("Downloaded file was empty.");
                return;
            } catch (Exception ex) {
                last = ex;
                if (target.exists()) target.delete();
            }
        }
        throw last == null ? new RuntimeException("Download failed.") : last;
    }

    interface DownloadProgress {
        void onProgress(long downloaded, long total);
    }

    private void renderMiniPlayer(boolean add) {
        if (root == null || playerDock == null) return;
        if (currentEpisode == null) {
            playerDock.setVisibility(View.GONE);
            return;
        }
        if (!add) {
            if (dockSeek == null || playerDock.getChildCount() == 0) {
                renderMiniPlayer(true);
                return;
            }
            updatePlayerDock();
            return;
        }
        playerDock.removeAllViews();
        playerDock.setVisibility(View.VISIBLE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setBackground(rounded(PLAYER_BG, dp(18), 0));
        panel.setElevation(dp(8));

        LinearLayout top = row();
        top.setPadding(0, 0, 0, dp(2));

        TextView art = text(initials(currentEpisode.title), 17, PLAYER_BG, true);
        art.setGravity(Gravity.CENTER);
        art.setBackground(rounded(PLAYER_ACCENT, dp(8), 0));
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(dp(50), dp(50));
        artLp.rightMargin = dp(10);
        top.addView(art, artLp);

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.VERTICAL);
        dockTitle = text(currentEpisode.title, 15, PLAYER_TEXT, true);
        dockTitle.setSingleLine(true);
        dockTitle.setEllipsize(TextUtils.TruncateAt.END);
        dockSubtitle = podcastLink(currentEpisode.podcastId, db.podcastTitle(currentEpisode.podcastId), 12, PLAYER_MUTED, false);
        dockSubtitle.setSingleLine(true);
        dockSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        meta.addView(dockTitle);
        meta.addView(dockSubtitle);
        top.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));

        top.addView(playerIconButton(R.drawable.ic_close, "Close player", v -> closePlayer(), PLAYER_SURFACE, PLAYER_TEXT, 42));
        panel.addView(top);

        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(3), 0, 0);
        controls.addView(playerIconButton(android.R.drawable.ic_media_rew, "Seek back", v -> seekBy(-settingInt("seek_back", 15)), PLAYER_SURFACE, PLAYER_TEXT, 42));
        dockPlayButton = playerIconButton(isPlayerPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, "Play or pause", v -> togglePlayback(), PLAYER_ACCENT, PLAYER_BG, 50);
        controls.addView(dockPlayButton);
        controls.addView(playerIconButton(android.R.drawable.ic_media_ff, "Seek forward", v -> seekBy(settingInt("seek_forward", 30)), PLAYER_SURFACE, PLAYER_TEXT, 42));
        controls.addView(playerIconButton(android.R.drawable.ic_media_next, "Next episode", v -> playNextEpisode(), PLAYER_SURFACE, PLAYER_TEXT, 42));
        panel.addView(controls);

        SeekBar bar = new SeekBar(this);
        dockSeek = bar;
        int dur = playerDurationSec();
        int pos = playerPositionSec();
        bar.setMax(dur);
        bar.setProgress(pos);
        tintSeekBar(bar);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && player != null) player.seekTo(progress * 1000);
                if (fromUser) updatePlayerDock();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { userSeeking = false; saveCurrentProgress(); updatePlaybackNotification(); }
        });
        panel.addView(bar);

        LinearLayout times = row();
        times.setPadding(0, 0, 0, 0);
        dockPosition = text(formatClock(pos), 11, PLAYER_MUTED, false);
        dockDuration = text(formatClock(dur), 11, PLAYER_MUTED, false);
        times.addView(dockPosition);
        TextView spacer = text("", 1, PLAYER_MUTED, false);
        times.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        times.addView(dockDuration);
        panel.addView(times);
        playerDock.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        updatePlayerDock();
    }

    private void updatePlayerDock() {
        if (currentEpisode == null) {
            if (playerDock != null) playerDock.setVisibility(View.GONE);
            return;
        }
        if (playerDock != null) playerDock.setVisibility(View.VISIBLE);
        int dur = playerDurationSec();
        int pos = playerPositionSec();
        if (dockTitle != null) dockTitle.setText(currentEpisode.title);
        if (dockSubtitle != null) dockSubtitle.setText(db.podcastTitle(currentEpisode.podcastId));
        if (dockSeek != null && !userSeeking) {
            dockSeek.setMax(dur);
            dockSeek.setProgress(Math.min(pos, dur));
        }
        if (dockPosition != null) dockPosition.setText(formatClock(pos));
        if (dockDuration != null) dockDuration.setText(formatClock(dur));
        if (dockPlayButton != null) {
            dockPlayButton.setImageResource(isPlayerPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        }
    }

    private void saveCurrentProgress() {
        if (!playerPrepared || player == null || currentEpisode == null || userSeeking) return;
        try {
            int pos = Math.max(0, player.getCurrentPosition() / 1000);
            int dur = Math.max(1, player.getDuration() / 1000);
            db.saveProgress(currentEpisode.id, currentEpisode.podcastId, pos, dur);
            currentEpisode.positionSeconds = pos;
            currentEpisode.durationSeconds = dur;
        } catch (Exception ignored) {}
    }

    private void seekBy(int seconds) {
        if (!playerPrepared || player == null) return;
        try {
            int target = Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + seconds * 1000));
            player.seekTo(target);
            saveCurrentProgress();
            updatePlayerDock();
            if (playerPrepared) updatePlaybackNotification();
        } catch (Exception ignored) {}
    }

    private void togglePlayback() {
        try {
            if (player == null) {
                if (currentEpisode != null) playEpisode(currentEpisode);
                return;
            }
            if (player.isPlaying()) {
                player.pause();
                saveCurrentProgress();
                setStatus("Paused.");
            } else {
                player.start();
                db.touchPlayed(currentEpisode.id);
                setStatus("Playing.");
            }
            updatePlayerDock();
            if (playerPrepared) updatePlaybackNotification();
        } catch (Exception ignored) {}
    }

    private void playNextEpisode() {
        if (currentEpisode == null) return;
        Episode next = db.nextEpisode(currentEpisode);
        if (next != null) {
            playEpisode(next);
        } else {
            setStatus("No next episode found.");
        }
    }

    private void closePlayer() {
        saveCurrentProgress();
        try {
            if (player != null) {
                player.pause();
                player.release();
            }
        } catch (Exception ignored) {}
        player = null;
        playerPrepared = false;
        currentEpisode = null;
        resetPlayerDockRefs();
        if (playerDock != null) {
            playerDock.removeAllViews();
            playerDock.setVisibility(View.GONE);
        }
        if (notifMgr != null) notifMgr.cancel(NOTIF_ID_PLAY);
        setStatus("Playback closed.");
    }

    private void handlePlaybackFailure() {
        handlePlaybackFailure("Playback failed. The audio URL was not reachable or the file may no longer exist.");
    }

    private void handlePlaybackFailure(String message) {
        try {
            if (player != null) {
                player.release();
            }
        } catch (Exception ignored) {}
        player = null;
        playerPrepared = false;
        currentEpisode = null;
        resetPlayerDockRefs();
        if (playerDock != null) {
            playerDock.removeAllViews();
            playerDock.setVisibility(View.GONE);
        }
        if (notifMgr != null) notifMgr.cancel(NOTIF_ID_PLAY);
        setStatus(message);
    }

    static void handlePlaybackActionFromNotification(String action) {
        MainActivity activity = activeInstance;
        if (activity == null || TextUtils.isEmpty(action)) return;
        activity.main.post(() -> activity.handlePlaybackAction(action));
    }

    private boolean handlePlaybackIntent(Intent intent) {
        if (intent == null || TextUtils.isEmpty(intent.getAction())) return false;
        return handlePlaybackAction(intent.getAction());
    }

    private boolean handlePlaybackAction(String action) {
        if (ACTION_PLAYER_TOGGLE.equals(action)) {
            togglePlayback();
        } else if (ACTION_PLAYER_BACK.equals(action)) {
            seekBy(-settingInt("seek_back", 15));
        } else if (ACTION_PLAYER_FORWARD.equals(action)) {
            seekBy(settingInt("seek_forward", 30));
        } else if (ACTION_PLAYER_NEXT.equals(action)) {
            playNextEpisode();
        } else if (ACTION_PLAYER_CLOSE.equals(action)) {
            closePlayer();
        } else {
            return false;
        }
        updatePlayerDock();
        if (playerPrepared) updatePlaybackNotification();
        return true;
    }

    private void downloadEpisode(Episode e) {
        setStatus("Downloading...");
        showLoadingOverlay();
        io.execute(() -> {
            try {
                URL url = new URL(e.enclosureUrl);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 LocalPod/1.0");
                String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.US);
                addCallLog("DOWNLOAD GET", e.enclosureUrl, c.getURL().toString(), c.getResponseCode(), headersToText(c), "Downloading to local app storage.", 0);
                if (type.contains("text/html") || type.contains("application/json") || c.getResponseCode() >= 400) {
                    throw new Exception("bad content");
                }
                File dir = new File(getExternalFilesDir(null), "podcasts/" + e.podcastId);
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, e.id + ".mp3");
                try (InputStream in = new BufferedInputStream(c.getInputStream()); FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
                db.setDownloaded(e.id, out.getAbsolutePath(), settingInt("download_retention_days", 30));
                main.post(() -> { setStatus("Downloaded for offline listening."); showEpisode(e.id); });
            } catch (Exception ex) {
                main.post(() -> {
                    hideModalOverlay();
                    setStatus("Download failed. The podcast host may have rejected the request or the file may no longer exist.");
                });
            }
        });
    }

    private void removeDownload(Episode e) {
        if (!TextUtils.isEmpty(e.localFilePath)) new File(e.localFilePath).delete();
        db.removeDownload(e.id);
        setStatus("Download removed. Metadata and progress were kept.");
        showEpisode(e.id);
    }

    private void removeAds(Episode e) {
        if (e == null || TextUtils.isEmpty(normalizeUrl(e.enclosureUrl))) {
            showError("Episode unavailable", "This episode does not have a source URL the server can process.");
            return;
        }
        db.setSetting("transcription_engine", DEFAULT_TRANSCRIPTION_ENGINE);
        boolean isRefresh = "processed".equals(e.adSupportedStatus) || "no_ads_found".equals(e.adSupportedStatus);
        showAdRemovalStartedOverlay(e);
        queueAdRemoval(e, isRefresh);
    }

    private void runAndroidAdRemoval(String episodeId) {
        adCancelled.set(false);
        Db localDb = db;
        if (localDb == null) { handleAdError(); return; }

        Episode episode = localDb.episode(episodeId);
        if (episode == null) {
            postUi(() -> showError("Episode unavailable", "This episode could not be found in local storage."));
            handleAdError(); return;
        }

        String openAiModel = DEFAULT_OPENAI_MODEL;
        String backend = apiBackendForEngine();
        String detectionMode = apiDetectionModeForEngine();
        String apiBase = apiBaseUrlSetting(localDb);
        String sourceUrl = normalizeUrl(episode.enclosureUrl);

        String jobId = "";
        try {
            if (adCancelled.get()) throw new AdRemovalCancelledException();
            if (TextUtils.isEmpty(sourceUrl)) {
                throw new RuntimeException("Episode source URL is missing.");
            }

            adCurrentStatus = "Queueing server job with episode URL...";
            postUi(() -> refreshAdBusyPanel());
            updateAdNotification();

            JSONObject created = createRemoteAdRemovalJob(apiBase, sourceUrl, backend, detectionMode, openAiModel, episode.title, localDb.podcastTitle(episode.podcastId), selectedServerUserId(localDb));
            jobId = created.optString("job_id", "").trim();
            if (TextUtils.isEmpty(jobId)) {
                throw new RuntimeException("Server did not return a job id.");
            }

            addCallLog("API CREATE JOB", resolveApiUrl(apiBase, "/api/jobs"), "", 201,
                "backend=" + backend + ", detection_mode=" + detectionMode,
                "job_id=" + jobId, 0);

            adCurrentStatus = "Server job queued (" + jobId.substring(0, Math.min(8, jobId.length())) + "...)";
            postUi(() -> refreshAdBusyPanel());
            updateAdNotification();

            JSONObject done = waitForRemoteJobCompletion(apiBase, jobId);
            String finalState = done.optString("status", "");
            if (!"completed".equals(finalState)) {
                String err = done.optString("error_message", "");
                if (TextUtils.isEmpty(err)) err = "Server job ended with status: " + finalState;
                throw new RuntimeException(err);
            }

            String downloadPath = done.optString("download_url", "").trim();
            if (TextUtils.isEmpty(downloadPath)) {
                downloadPath = "/api/jobs/" + jobId + "/download";
            }

            adCurrentStatus = "Downloading ad-free audio...";
            postUi(() -> {
                refreshAdBusyPanel();
                showDownloadOverlay(episode.title);
            });
            updateAdNotification();

            File output = buildProcessedAudioPath(episode, "mp3");
            File renderTarget = buildProcessedStagingPath(output);
            downloadRemoteAudioFromApi(apiBase, downloadPath, renderTarget);
            output = finalizeProcessedAudioOutput(episode, output, renderTarget);

            localDb.setProcessedAudio(episode.id, output.getAbsolutePath(), "processed");

            synchronized (completionCards) { completionCards.add(new String[]{episode.id, episode.title, "ok"}); }
            postCompletionNotification("Ad removal complete", episode.title + " is ready to play ad-free.");
            final String doneId = episode.id;
            postUi(() -> {
                refreshAdBusyPanel();
                showEpisode(doneId);
                Episode ready = db.episode(doneId);
                if (ready != null) showDownloadReadyOverlay(ready);
            });
            processNextFromQueue();
        } catch (AdRemovalCancelledException cancelled) {
            if (!TextUtils.isEmpty(jobId)) cancelRemoteJobQuietly(apiBase, jobId);
            handleCancelled();
        } catch (Exception ex) {
            String body = TextUtils.isEmpty(ex.getMessage()) ? "Server ad removal failed." : ex.getMessage();
            if (isDnsLookupFailure(body)) {
                body = "DNS lookup failed for both Ad API hostnames.\n\n"
                    + "Check the connection and retry. "
                    + "If it still fails, set Android Private DNS to Automatic or Off.";
            }
            final String finalBody = body;
            postUi(() -> showError("Ad removal failed", finalBody));
            if (adCancelled.get() && !TextUtils.isEmpty(jobId)) cancelRemoteJobQuietly(apiBase, jobId);
            handleAdError();
        }
    }

    private String apiBackendForEngine() {
        return "tunnel-parakeet";
    }

    private String apiDetectionModeForEngine() {
        return "openai";
    }

    private String apiBaseUrlSetting(Db localDb) {
        String configured = localDb.setting("ad_api_base_url", DEFAULT_AD_API_BASE_URL);
        String normalized = normalizeApiBaseUrl(configured);
        if (TextUtils.isEmpty(normalized)) {
            normalized = normalizeApiBaseUrl(DEFAULT_AD_API_BASE_URL);
        }
        if (!TextUtils.equals(configured, normalized)) {
            localDb.setSetting("ad_api_base_url", normalized);
        }
        return normalized;
    }

    private void registerDeviceUserAsync() {
        io.execute(() -> {
            try {
                String apiBase = apiBaseUrlSetting(db);
                JSONObject user = registerDeviceUser(apiBase);
                String id = user.optString("id", "").trim();
                String name = user.optString("name", "").trim();
                if (!TextUtils.isEmpty(id)) {
                    db.setSetting(SETTING_DEVICE_SERVER_USER_ID, id);
                    db.setSetting(SETTING_DEVICE_SERVER_USER_NAME, TextUtils.isEmpty(name) ? deviceUserName() : name);
                    addCallLog("API REGISTER USER", resolveApiUrl(apiBase, "/api/users"), "", 200,
                        "device_fingerprint=" + deviceFingerprint(), "user_id=" + id, 0);
                }
            } catch (Exception ex) {
                addCallLog("API REGISTER USER FAILED", DEFAULT_AD_API_BASE_URL + "/api/users", "", 0,
                    "", ex.getClass().getSimpleName() + ": " + nullToEmpty(ex.getMessage()), 0);
            } finally {
                ensureServerUsersLoading();
            }
        });
    }

    private JSONObject registerDeviceUser(String apiBase) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("name", deviceUserName());
        payload.put("device_fingerprint", deviceFingerprint());
        JSONObject parsed = postJsonApiJsonWithFallback(apiBase, "/api/users", payload, "Register user failed", "register user response");
        JSONObject user = parsed.optJSONObject("user");
        return user == null ? parsed : user;
    }

    private String deviceFingerprint() {
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (TextUtils.isEmpty(androidId)) {
            androidId = sha(nullToEmpty(Build.MANUFACTURER) + "|" + nullToEmpty(Build.MODEL) + "|" + nullToEmpty(Build.BOARD));
        }
        return "android:" + androidId;
    }

    private String deviceUserName() {
        String maker = nullToEmpty(Build.MANUFACTURER).trim();
        String model = nullToEmpty(Build.MODEL).trim();
        String label = (maker + " " + model).trim().replaceAll("\\s+", " ");
        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String suffix = TextUtils.isEmpty(androidId) ? "" : " #" + androidId.substring(Math.max(0, androidId.length() - 6)).toUpperCase(Locale.US);
        return TextUtils.isEmpty(label) ? "Android phone" + suffix : "Android " + label + suffix;
    }

    private boolean isDnsLookupFailure(String message) {
        if (TextUtils.isEmpty(message)) return false;
        String lower = message.toLowerCase(Locale.US);
        return lower.contains("unable to resolve host")
            || lower.contains("no address associated with hostname")
            || lower.contains("name or service not known");
    }

    private String normalizeApiBaseUrl(String configured) {
        if (configured == null) return "";
        String cleaned = configured
            .replace('\u2010', '-')
            .replace('\u2011', '-')
            .replace('\u2012', '-')
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace('\u2015', '-')
            .replace('\u2212', '-')
            .replace('\uFE63', '-')
            .replace('\uFF0D', '-')
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\uFEFF", "")
            .replace('\u00A0', ' ')
            .trim();

        if (TextUtils.isEmpty(cleaned)) return "";

        cleaned = stripWrappingQuotes(cleaned);
        cleaned = cleaned.replace(" ", "");

        if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
            cleaned = "https://" + cleaned;
        }

        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }

        try {
            Uri parsed = Uri.parse(cleaned);
            String scheme = parsed.getScheme();
            String host = parsed.getHost();
            if (TextUtils.isEmpty(scheme) || TextUtils.isEmpty(host)) {
                return cleaned;
            }

            StringBuilder rebuilt = new StringBuilder();
            rebuilt.append(scheme.toLowerCase(Locale.US)).append("://").append(host.toLowerCase(Locale.US));
            if (parsed.getPort() > 0) {
                rebuilt.append(':').append(parsed.getPort());
            }

            String path = parsed.getEncodedPath();
            if (!TextUtils.isEmpty(path)) {
                while (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                rebuilt.append(path);
            }
            return rebuilt.toString();
        } catch (Exception ignored) {
            return cleaned;
        }
    }

    private String stripWrappingQuotes(String text) {
        String out = text;
        if (out.length() >= 2) {
            char first = out.charAt(0);
            char last = out.charAt(out.length() - 1);
            boolean wrapped = (first == '"' && last == '"')
                || (first == '\'' && last == '\'')
                || (first == '\u201C' && last == '\u201D')
                || (first == '\u2018' && last == '\u2019');
            if (wrapped) {
                out = out.substring(1, out.length() - 1).trim();
            }
        }
        return out;
    }

    private String resolveApiUrl(String apiBase, String pathOrUrl) {
        if (TextUtils.isEmpty(pathOrUrl)) return apiBase;
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            try {
                URL target = new URL(pathOrUrl);
                URL base = new URL(apiBase);
                String targetHost = target.getHost();
                if (CURRENT_AD_API_HOST.equalsIgnoreCase(targetHost) || LEGACY_AD_API_HOST.equalsIgnoreCase(targetHost)) {
                    String host = base.getProtocol() + "://" + base.getHost();
                    if (base.getPort() > 0) host += ":" + base.getPort();
                    return host + target.getFile();
                }
            } catch (Exception ignored) {}
            return pathOrUrl;
        }
        if (!pathOrUrl.startsWith("/")) {
            return apiBase + "/" + pathOrUrl;
        }
        try {
            URL base = new URL(apiBase);
            String basePath = base.getPath();
            if (pathOrUrl.equals(basePath) || pathOrUrl.startsWith(basePath + "/")) {
                String host = base.getProtocol() + "://" + base.getHost();
                if (base.getPort() > 0) host += ":" + base.getPort();
                return host + pathOrUrl;
            }
        } catch (Exception ignored) {}
        return apiBase + pathOrUrl;
    }

    private ArrayList<String> apiBaseCandidates(String apiBase) {
        ArrayList<String> out = new ArrayList<>();
        String primary = normalizeApiBaseUrl(apiBase);
        if (TextUtils.isEmpty(primary)) primary = normalizeApiBaseUrl(DEFAULT_AD_API_BASE_URL);
        if (!TextUtils.isEmpty(primary)) out.add(primary);
        String alternate = alternateApiBaseUrl(primary);
        if (!TextUtils.isEmpty(alternate) && !out.contains(alternate)) out.add(alternate);
        return out;
    }

    private String alternateApiBaseUrl(String apiBase) {
        try {
            URL parsed = new URL(apiBase);
            String host = parsed.getHost();
            String alternateHost = "";
            if (CURRENT_AD_API_HOST.equalsIgnoreCase(host)) alternateHost = LEGACY_AD_API_HOST;
            else if (LEGACY_AD_API_HOST.equalsIgnoreCase(host)) alternateHost = CURRENT_AD_API_HOST;
            if (TextUtils.isEmpty(alternateHost)) return "";
            StringBuilder rebuilt = new StringBuilder();
            rebuilt.append(parsed.getProtocol()).append("://").append(alternateHost);
            if (parsed.getPort() > 0) rebuilt.append(':').append(parsed.getPort());
            rebuilt.append(parsed.getPath());
            return normalizeApiBaseUrl(rebuilt.toString());
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean isRetryableApiHostFailure(Exception ex) {
        if (ex == null) return false;
        String msg = ex.getClass().getSimpleName() + ": " + nullToEmpty(ex.getMessage());
        return isDnsLookupFailure(msg);
    }

    private void rememberWorkingApiBase(String originalApiBase, String successfulApiBase) {
        String original = normalizeApiBaseUrl(originalApiBase);
        String successful = normalizeApiBaseUrl(successfulApiBase);
        if (!TextUtils.isEmpty(successful) && !TextUtils.equals(original, successful) && db != null) {
            db.setSetting("ad_api_base_url", successful);
            addCallLog("API HOST FALLBACK", original, successful, 200, "", "Using alternate Ad API hostname after DNS failure.", 0);
        }
    }

    private JSONObject fetchApiJsonWithFallback(String apiBase, String path, String errorPrefix, String parseLabel) throws Exception {
        ArrayList<String> candidates = apiBaseCandidates(apiBase);
        Exception last = null;
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            try {
                String url = resolveApiUrl(candidate, path);
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(120000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "AdFreePodAndroid/1.0");
                String body = readConnectionBody(connection);
                int code = connection.getResponseCode();
                if (code >= 400) throw new RuntimeException(errorPrefix + ": " + summarizeApiError(body, code));
                rememberWorkingApiBase(apiBase, candidate);
                return parseJsonObject(body, parseLabel);
            } catch (Exception ex) {
                last = ex;
                if (!isRetryableApiHostFailure(ex) || i == candidates.size() - 1) throw ex;
            }
        }
        throw last == null ? new RuntimeException(errorPrefix) : last;
    }

    private JSONObject postFormApiJsonWithFallback(String apiBase, String path, String form, String errorPrefix, String parseLabel) throws Exception {
        ArrayList<String> candidates = apiBaseCandidates(apiBase);
        Exception last = null;
        byte[] bytes = nullToEmpty(form).getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            try {
                String url = resolveApiUrl(candidate, path);
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(300000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                connection.setRequestProperty("User-Agent", "AdFreePodAndroid/1.0");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                    output.flush();
                }
                String body = readConnectionBody(connection);
                int code = connection.getResponseCode();
                if (code >= 400) throw new RuntimeException(errorPrefix + ": " + summarizeApiError(body, code));
                rememberWorkingApiBase(apiBase, candidate);
                return parseJsonObject(body, parseLabel);
            } catch (Exception ex) {
                last = ex;
                if (!isRetryableApiHostFailure(ex) || i == candidates.size() - 1) throw ex;
            }
        }
        throw last == null ? new RuntimeException(errorPrefix) : last;
    }

    private JSONObject postJsonApiJsonWithFallback(String apiBase, String path, JSONObject payload, String errorPrefix, String parseLabel) throws Exception {
        ArrayList<String> candidates = apiBaseCandidates(apiBase);
        Exception last = null;
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            try {
                String url = resolveApiUrl(candidate, path);
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(60000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setRequestProperty("User-Agent", "AdFreePodAndroid/1.0");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                    output.flush();
                }
                String body = readConnectionBody(connection);
                int code = connection.getResponseCode();
                if (code >= 400) throw new RuntimeException(errorPrefix + ": " + summarizeApiError(body, code));
                rememberWorkingApiBase(apiBase, candidate);
                return parseJsonObject(body, parseLabel);
            } catch (Exception ex) {
                last = ex;
                if (!isRetryableApiHostFailure(ex) || i == candidates.size() - 1) throw ex;
            }
        }
        throw last == null ? new RuntimeException(errorPrefix) : last;
    }

    private void deleteApiWithFallback(String apiBase, String path, String errorPrefix) throws Exception {
        ArrayList<String> candidates = apiBaseCandidates(apiBase);
        Exception last = null;
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            try {
                String url = resolveApiUrl(candidate, path);
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(120000);
                connection.setRequestMethod("DELETE");
                connection.setRequestProperty("User-Agent", "AdFreePodAndroid/1.0");
                String body = readConnectionBody(connection);
                int code = connection.getResponseCode();
                if (code >= 400) throw new RuntimeException(errorPrefix + ": " + summarizeApiError(body, code));
                rememberWorkingApiBase(apiBase, candidate);
                return;
            } catch (Exception ex) {
                last = ex;
                if (!isRetryableApiHostFailure(ex) || i == candidates.size() - 1) throw ex;
            }
        }
        throw last == null ? new RuntimeException(errorPrefix) : last;
    }

    private JSONObject createRemoteAdRemovalJob(String apiBase, String sourceUrl, String backend, String detectionMode, String openAiModel, String episodeTitle, String podcastName, String userId) throws Exception {
        StringBuilder form = new StringBuilder();
        appendFormField(form, "source_url", sourceUrl);
        appendFormField(form, "backend", backend);
        appendFormField(form, "detection_mode", detectionMode);
        appendFormField(form, "openai_model", TextUtils.isEmpty(openAiModel) ? DEFAULT_OPENAI_MODEL : openAiModel);
        appendFormField(form, "episode_title", episodeTitle);
        appendFormField(form, "podcast_name", podcastName);
        if (!TextUtils.isEmpty(userId)) {
            appendFormField(form, "user_id", userId);
        }
        return postFormApiJsonWithFallback(apiBase, "/api/jobs", form.toString(), "Create job failed", "create job response");
    }

    private JSONObject fetchRemoteJobStatus(String apiBase, String jobId) throws Exception {
        return fetchApiJsonWithFallback(apiBase, "/api/jobs/" + URLEncoder.encode(jobId, "UTF-8"), "Status check failed", "job status response");
    }

    private JSONObject waitForRemoteJobCompletion(String apiBase, String jobId) throws Exception {
        int poll = 0;
        int transientErrors = 0;

        while (true) {
            if (adCancelled.get()) throw new AdRemovalCancelledException();

            JSONObject status;
            try {
                status = fetchRemoteJobStatus(apiBase, jobId);
                transientErrors = 0;
            } catch (Exception ex) {
                transientErrors++;
                if (transientErrors > REMOTE_JOB_STATUS_MAX_ERRORS) throw ex;

                String detail = TextUtils.isEmpty(ex.getMessage()) ? "temporary network error" : ex.getMessage();
                adCurrentStatus = "Waiting for server status... retrying (" + transientErrors + "/" + REMOTE_JOB_STATUS_MAX_ERRORS + ")";
                final String uiStatus = adCurrentStatus + "\n" + detail;
                postUi(() -> {
                    refreshAdBusyPanel();
                    setStatus(uiStatus);
                });
                updateAdNotification();
                Thread.sleep(REMOTE_JOB_POLL_INTERVAL_MS);
                continue;
            }

            String state = status.optString("status", "");
            double progress = status.optDouble("progress", 0);
            String step = lastLogLine(status.optString("logs", ""));
            String text = "Server " + (TextUtils.isEmpty(state) ? "running" : state) + " (" + (int)Math.round(progress) + "%)";
            if (!TextUtils.isEmpty(step)) {
                text += " - " + step;
            }
            adCurrentStatus = text;
            final String uiStatus = text;
            postUi(() -> {
                refreshAdBusyPanel();
                setStatus(uiStatus);
            });
            updateAdNotification();

            if ("completed".equals(state) || "failed".equals(state) || "cancelled".equals(state)) {
                return status;
            }

            poll++;
            if (poll == REMOTE_JOB_LONG_NOTICE_POLLS) {
                adCurrentStatus = text + "\nStill processing on the server. You can keep using the app; a notification will appear when it is ready.";
                postUi(() -> {
                    refreshAdBusyPanel();
                    setStatus(adCurrentStatus);
                });
                updateAdNotification();
            }

            Thread.sleep(REMOTE_JOB_POLL_INTERVAL_MS);
        }
    }

    private void cancelRemoteJobQuietly(String apiBase, String jobId) {
        try {
            postFormApiJsonWithFallback(apiBase, "/api/jobs/" + URLEncoder.encode(jobId, "UTF-8") + "/cancel", "", "Cancel failed", "cancel response");
        } catch (Exception ignored) {}
    }

    private void downloadRemoteAudioFromApi(String apiBase, String downloadPath, File target) throws Exception {
        ArrayList<String> candidates = apiBaseCandidates(apiBase);
        Exception last = null;
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            try {
                String url = resolveApiUrl(candidate, downloadPath);
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(300000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "AdFreePodAndroid/1.0");
                int code = connection.getResponseCode();
                if (code >= 400) {
                    String body = readConnectionBody(connection);
                    throw new RuntimeException("Download failed: " + summarizeApiError(body, code));
                }

                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                long total = Build.VERSION.SDK_INT >= 24
                        ? connection.getContentLengthLong()
                        : connection.getContentLength();
                long downloaded = 0;
                long lastUiUpdate = 0;

                try (InputStream in = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (adCancelled.get()) throw new AdRemovalCancelledException();
                        out.write(buf, 0, n);
                        downloaded += n;
                        long now = System.currentTimeMillis();
                        if (total > 0 && now - lastUiUpdate > 500) {
                            long pct = downloaded * 100 / total;
                            adCurrentStatus = "Downloading ad-free audio... " + pct + "%";
                            long uiDownloaded = downloaded;
                            long uiTotal = total;
                            postUi(() -> {
                                refreshAdBusyPanel();
                                updateDownloadOverlayProgress(uiDownloaded, uiTotal);
                            });
                            updateAdNotification();
                            lastUiUpdate = now;
                        } else if (total <= 0 && now - lastUiUpdate > 1000) {
                            long uiDownloaded = downloaded;
                            postUi(() -> updateDownloadOverlayProgress(uiDownloaded, total));
                            lastUiUpdate = now;
                        }
                    }
                }
                long finalDownloaded = downloaded;
                long finalTotal = total;
                postUi(() -> updateDownloadOverlayProgress(Math.max(finalDownloaded, finalTotal), finalTotal));

                if (!target.exists() || target.length() <= 0) {
                    throw new RuntimeException("Server download produced an empty file.");
                }
                rememberWorkingApiBase(apiBase, candidate);
                return;
            } catch (Exception ex) {
                last = ex;
                if (!isRetryableApiHostFailure(ex) || i == candidates.size() - 1) throw ex;
                if (target.exists()) target.delete();
            }
        }
        throw last == null ? new RuntimeException("Download failed.") : last;
    }

    private String summarizeApiError(String body, int statusCode) {
        if (!TextUtils.isEmpty(body)) {
            try {
                JSONObject obj = new JSONObject(body);
                String error = obj.optString("error", "");
                String detail = obj.optString("detail", "");
                if (!TextUtils.isEmpty(error) && !TextUtils.isEmpty(detail)) return error + " (" + detail + ")";
                if (!TextUtils.isEmpty(error)) return error;
                if (!TextUtils.isEmpty(detail)) return detail;
            } catch (Exception ignored) {}
            return body;
        }
        return "HTTP " + statusCode;
    }

    private void appendFormField(StringBuilder form, String key, String value) throws Exception {
        if (form.length() > 0) form.append('&');
        form.append(URLEncoder.encode(key, "UTF-8"));
        form.append('=');
        form.append(URLEncoder.encode(value == null ? "" : value, "UTF-8"));
    }

    private String lastLogLine(String logs) {
        if (TextUtils.isEmpty(logs)) return "";
        String[] lines = logs.split("\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (!TextUtils.isEmpty(line)) {
                return line.length() > 140 ? line.substring(0, 137) + "..." : line;
            }
        }
        return "";
    }

    private JSONObject parseJsonObject(String body, String label) throws Exception {
        try {
            return new JSONObject(body);
        } catch (Exception ex) {
            throw new RuntimeException("Could not parse " + label + ": " + body, ex);
        }
    }

    private void handleCancelled() {
        synchronized (adQueue) { adQueue.clear(); }
        adRunning = false; adCurrentId = null; adCurrentTitle = ""; adCurrentStatus = ""; adCurrentEst = "";
        if (notifMgr != null) notifMgr.cancel(NOTIF_ID_REMOVAL);
        postUi(() -> setWorking(false, null));
    }

    private void handleAdError() {
        adRunning = false; adCurrentId = null; adCurrentTitle = ""; adCurrentStatus = ""; adCurrentEst = "";
        if (notifMgr != null) notifMgr.cancel(NOTIF_ID_REMOVAL);
        postUi(() -> setWorking(false, null));
    }

    private void cleanupExpiredDownloads() {
        if (db == null) return;
        db.clearDownloads(false, false);
    }

    private void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private File buildProcessedAudioPath(Episode episode, String extension) {
        String cleanExt = TextUtils.isEmpty(extension) ? "mp3" : extension.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.US);
        if (TextUtils.isEmpty(cleanExt)) cleanExt = "mp3";
        File dir = new File(getExternalFilesDir(null), "podcasts/" + episode.podcastId);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, episode.id + ".noads." + cleanExt);
    }

    private File buildProcessedStagingPath(File finalOutput) {
        return new File(finalOutput.getParentFile(), finalOutput.getName() + ".part");
    }

    private File finalizeProcessedAudioOutput(Episode episode, File finalOutput, File renderTarget) throws Exception {
        if (finalOutput.exists() && !finalOutput.delete()) {
            throw new RuntimeException("Could not replace the existing ad-free file.");
        }
        copyFile(renderTarget, finalOutput);
        if (!renderTarget.delete()) {
            throw new RuntimeException("Could not remove the temporary ad-free file.");
        }
        if (!TextUtils.isEmpty(episode.localFilePath)) {
            File original = new File(episode.localFilePath);
            if (!original.getAbsolutePath().equals(finalOutput.getAbsolutePath())) original.delete();
        }
        return finalOutput;
    }

    private String readConnectionBody(HttpURLConnection connection) throws Exception {
        InputStream stream = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
            return out.toString();
        }
    }

    private double clampDouble(double value, double lower, double upper) { return Math.max(lower, Math.min(upper, value)); }
    private double round2(double value) { return Math.round(value * 100.0) / 100.0; }
    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private Feed parseFeed(String feedUrl) throws Exception {
        byte[] bytes = readBytes(feedUrl);
        XmlPullParser x = XmlPullParserFactory.newInstance().newPullParser();
        try { x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); } catch (Exception ignored) {}
        x.setInput(new ByteArrayInputStream(bytes), null);
        Podcast p = new Podcast();
        p.id = sha(feedUrl);
        p.feedUrl = feedUrl;
        p.source = "rss";
        p.lastFeedRefreshAt = isoNow();
        ArrayList<Episode> episodes = new ArrayList<>();
        Episode cur = null;
        String tag = "";
        boolean inItem = false;
        for (int event = x.getEventType(); event != XmlPullParser.END_DOCUMENT; event = x.next()) {
            if (event == XmlPullParser.START_TAG) {
                tag = cleanName(x.getName());
                if ("item".equals(tag)) { inItem = true; cur = new Episode(); cur.podcastId = p.id; cur.firstSeenAt = isoNow(); }
                if (inItem && cur != null && "enclosure".equals(tag)) {
                    cur.enclosureUrl = normalizeUrl(x.getAttributeValue(null, "url"));
                    cur.enclosureType = x.getAttributeValue(null, "type");
                    try { cur.enclosureLength = Long.parseLong(nullToEmpty(x.getAttributeValue(null, "length"))); } catch (Exception ignored) {}
                }
                if (!inItem && "image".equals(tag)) {
                    String href = x.getAttributeValue(null, "href");
                    if (!TextUtils.isEmpty(href)) p.artworkUrl = href;
                }
            } else if (event == XmlPullParser.TEXT) {
                String text = x.getText();
                if (TextUtils.isEmpty(text)) continue;
                if (inItem && cur != null) {
                    if ("title".equals(tag)) cur.title = text.trim();
                    else if ("guid".equals(tag)) cur.guid = text.trim();
                    else if ("pubDate".equals(tag)) cur.pubDate = normalizeFeedDate(text.trim());
                    else if ("description".equals(tag) || "encoded".equals(tag) || "summary".equals(tag)) cur.description = append(cur.description, text);
                    else if ("duration".equals(tag)) cur.durationSeconds = parseDuration(text.trim());
                } else {
                    if ("title".equals(tag)) p.title = text.trim();
                    else if ("description".equals(tag)) p.description = append(p.description, text);
                    else if ("author".equals(tag) || "itunes:author".equals(tag)) p.publisher = text.trim();
                    else if ("link".equals(tag)) p.websiteUrl = text.trim();
                    else if ("url".equals(tag) && TextUtils.isEmpty(p.artworkUrl)) p.artworkUrl = text.trim();
                }
            } else if (event == XmlPullParser.END_TAG) {
                if ("item".equals(cleanName(x.getName())) && cur != null) {
                    if (!TextUtils.isEmpty(cur.title) && isPlausibleAudioUrl(cur.enclosureUrl, cur.enclosureType)) {
                        String key = !TextUtils.isEmpty(cur.guid) ? cur.guid : cur.enclosureUrl;
                        cur.id = sha(feedUrl + "|" + key);
                        episodes.add(cur);
                    }
                    cur = null;
                    inItem = false;
                }
                tag = "";
            }
        }
        if (TextUtils.isEmpty(p.title) || episodes.isEmpty()) throw new Exception("invalid feed");
        Feed f = new Feed();
        f.podcast = p;
        f.episodes = episodes;
        return f;
    }

    private String readUrl(String url) throws Exception { return new String(readBytes(url), "UTF-8"); }
    private byte[] readBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 LocalPod/1.0");
        int code = -1;
        try {
            code = c.getResponseCode();
            if (code >= 400) throw new Exception("http " + code);
            try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[32 * 1024];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
                byte[] result = out.toByteArray();
                addCallLog("HTTP GET", url, c.getURL().toString(), code, headersToText(c), rawPreview(result), result.length);
                return result;
            }
        } catch (Exception e) {
            addCallLog("HTTP GET FAILED", url, safeFinalUrl(c), code, headersToText(c), e.getClass().getSimpleName() + ": " + nullToEmpty(e.getMessage()), 0);
            throw e;
        }
    }

    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); l.setPadding(0, dp(4), 0, dp(4)); return l; }
    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(12), dp(14), dp(12));
        l.setBackground(rounded(SURFACE, dp(18), LINE));
        l.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(7), 0, dp(9));
        l.setLayoutParams(lp);
        return l;
    }
    private LinearLayout compactRow() {
        LinearLayout l = card();
        l.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(3), 0, dp(4));
        l.setLayoutParams(lp);
        return l;
    }
    private ImageButton iconActionButton(int icon, String description, View.OnClickListener listener) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setColorFilter(INK);
        b.setContentDescription(description);
        b.setBackground(rounded(0xffffffff, dp(12), LINE));
        b.setPadding(dp(7), dp(7), dp(7), dp(7));
        b.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        b.setOnClickListener(v -> {
            try { listener.onClick(v); } catch (Exception e) { showError("Something went wrong", "The app recovered from an unexpected action error."); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
        lp.setMargins(0, 0, dp(5), 0);
        b.setLayoutParams(lp);
        return b;
    }
    private String formatBuildTimestampEastern(String utc) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date parsed = in.parse(utc);
            SimpleDateFormat out = new SimpleDateFormat("MMM d, yyyy h:mm a 'ET'", Locale.US);
            out.setTimeZone(TimeZone.getTimeZone("America/New_York"));
            return out.format(parsed == null ? new Date() : parsed);
        } catch (Exception ignored) {
            return nullToEmpty(utc);
        }
    }
    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s == null ? "" : s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(dp(2), 1.0f);
        t.setPadding(0, dp(3), 0, dp(3));
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }
    private Button button(String s, View.OnClickListener l) { return styledButton(s, l, BLUE, 0xffffffff, 0); }
    private Button secondaryButton(String s, View.OnClickListener l) { return styledButton(s, l, 0xffffffff, BLUE, LINE); }
    private Button styledButton(String s, View.OnClickListener l, int bg, int fg, int stroke) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextColor(fg);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinHeight(dp(40));
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setBackground(rounded(bg, dp(14), stroke));
        b.setOnClickListener(v -> {
            try { l.onClick(v); } catch (Exception e) { showError("Something went wrong", "The app recovered from an unexpected action error."); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, dp(3), dp(7), dp(3));
        b.setLayoutParams(lp);
        return b;
    }
    private ImageButton navButton(String s, int icon, View.OnClickListener l) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setColorFilter(BROWN);
        b.setContentDescription(s);
        b.setBackground(rounded(SOFT_BLUE, dp(16), 0));
        b.setPadding(dp(7), dp(7), dp(7), dp(7));
        b.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        b.setOnClickListener(v -> {
            try { l.onClick(v); } catch (Exception e) { showError("Something went wrong", "The app recovered from an unexpected action error."); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        return b;
    }
    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextColor(INK);
        e.setHintTextColor(MUTED);
        e.setPadding(dp(14), 0, dp(14), 0);
        e.setMinHeight(dp(52));
        e.setBackground(rounded(SURFACE, dp(16), LINE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(8), 0, dp(8));
        e.setLayoutParams(lp);
        return e;
    }
    private void section(String s) {
        String title = s == null ? "" : s;
        if (title.contains("Server Engine")) title = "Ad Removal - Server Engine";
        TextView t = text(title, 19, INK, true);
        t.setPadding(0, dp(18), 0, dp(6));
        root.addView(t);
    }
    // Notification channels
    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch1 = new NotificationChannel(
                NOTIF_CH_REMOVAL, "Ad Removal Progress", NotificationManager.IMPORTANCE_LOW);
            ch1.setDescription("Shows progress while ads are being removed.");
            ch1.setSound(null, null);
            NotificationChannel ch2 = new NotificationChannel(
                NOTIF_CH_COMPLETE, "Ad Removal Complete", NotificationManager.IMPORTANCE_DEFAULT);
            ch2.setDescription("Notifies when ad removal finishes.");
            NotificationChannel ch3 = new NotificationChannel(
                NOTIF_CH_PLAY, "Podcast Playback", NotificationManager.IMPORTANCE_LOW);
            ch3.setDescription("Shows episode controls while playing.");
            ch3.setSound(null, null);
            notifMgr.createNotificationChannel(ch1);
            notifMgr.createNotificationChannel(ch2);
            notifMgr.createNotificationChannel(ch3);
        }
    }

    /** Safe to call from any thread. */
    private void updateAdNotification() {
        if (notifMgr == null || !adRunning) return;
        try {
            Intent tap = new Intent(this, MainActivity.class);
            tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, NOTIF_CH_REMOVAL)
                : new Notification.Builder(this);
            String title = TextUtils.isEmpty(adCurrentTitle) ? "Ad Removal" : adCurrentTitle;
            String line  = adCurrentStatus + (TextUtils.isEmpty(adCurrentEst) ? "" : "  " + adCurrentEst);
            int queued; synchronized (adQueue) { queued = adQueue.size(); }
            if (queued > 0) line += "  (" + queued + " more queued)";
            b.setSmallIcon(android.R.drawable.ic_media_ff)
             .setContentTitle("Removing ads: " + title)
             .setContentText(line)
             .setStyle(new Notification.BigTextStyle().bigText(line))
             .setOngoing(true)
             .setContentIntent(pi)
             .setProgress(0, 0, true);
            notifMgr.notify(NOTIF_ID_REMOVAL, b.build());
        } catch (Exception ignored) {}
    }

    private void postCompletionNotification(String title, String body) {
        if (notifMgr == null) return;
        try {
            Intent tap = new Intent(this, MainActivity.class);
            tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this,
                (int)(System.currentTimeMillis() % Integer.MAX_VALUE), tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, NOTIF_CH_COMPLETE)
                : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.ic_popup_reminder)
             .setContentTitle(title)
             .setContentText(body)
             .setContentIntent(pi)
             .setAutoCancel(true);
            int id = NOTIF_ID_REMOVAL + 1 + (int)(System.currentTimeMillis() % 1000);
            notifMgr.notify(id, b.build());
        } catch (Exception ignored) {}
    }

    private PendingIntent playbackActionPendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, PlayerActionReceiver.class);
        intent.setAction(action);
        return PendingIntent.getBroadcast(this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void updatePlaybackNotification() {
        if (notifMgr == null) return;
        try {
            if (player == null || currentEpisode == null) {
                notifMgr.cancel(NOTIF_ID_PLAY);
                return;
            }
            boolean playing = false;
            try { playing = player.isPlaying(); } catch (Exception ignored2) {}
            Intent tap = new Intent(this, MainActivity.class);
            tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 1, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, NOTIF_CH_PLAY)
                : new Notification.Builder(this);
            int pos = playerPositionSec();
            int dur = playerDurationSec();
            String podcastTitle = db.podcastTitle(currentEpisode.podcastId);
            b.setSmallIcon(android.R.drawable.ic_media_play)
             .setContentTitle(currentEpisode.title)
             .setSubText(playing ? "Playing" : "Paused")
             .setContentText((TextUtils.isEmpty(podcastTitle) ? "Podcast" : podcastTitle) + "  |  " + formatClock(pos) + " / " + formatClock(dur))
             .setOngoing(true)
             .setOnlyAlertOnce(true)
             .setShowWhen(false)
             .setVisibility(Notification.VISIBILITY_PUBLIC)
             .setCategory(Notification.CATEGORY_TRANSPORT)
             .setContentIntent(pi)
             .setProgress(Math.max(1, dur), Math.min(pos, dur), false)
             .addAction(android.R.drawable.ic_media_rew, "Back", playbackActionPendingIntent(ACTION_PLAYER_BACK, 20))
             .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Play", playbackActionPendingIntent(ACTION_PLAYER_TOGGLE, 21))
             .addAction(android.R.drawable.ic_media_ff, "Forward", playbackActionPendingIntent(ACTION_PLAYER_FORWARD, 22))
             .addAction(android.R.drawable.ic_media_next, "Next", playbackActionPendingIntent(ACTION_PLAYER_NEXT, 23))
             .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", playbackActionPendingIntent(ACTION_PLAYER_CLOSE, 24));
            if (Build.VERSION.SDK_INT >= 21) {
                b.setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2));
            }
            notifMgr.notify(NOTIF_ID_PLAY, b.build());
        } catch (Exception ignored) {}
    }

    // Ad removal queue
    private void queueAdRemoval(Episode e, boolean forceRefresh) {
        boolean wasAlreadyRunning;
        synchronized (adQueue) {
            if (e.id.equals(adCurrentId)) {
                setStatus("Already processing this episode.");
                return;
            }
            if (adQueue.contains(e.id)) {
                setStatus(e.title + " is already in the queue.");
                return;
            }
            wasAlreadyRunning = adRunning;
            if (!wasAlreadyRunning) adRunning = true;
            else adQueue.add(e.id);
        }
        if (!wasAlreadyRunning) {
            adCurrentId = e.id;
            adCurrentTitle = e.title;
            adCurrentStatus = "Starting...";
            adCurrentEst = "";
            setWorking(true, e.title + ": Starting...");
            io.execute(() -> runAndroidAdRemoval(e.id));
        } else {
            setStatus(e.title + " added to queue (" + adQueue.size() + " waiting).");
            postUi(() -> refreshAdBusyPanel());
        }
    }

    private void processNextFromQueue() {
        String nextId;
        synchronized (adQueue) { nextId = adQueue.poll(); }
        if (nextId == null || io.isShutdown()) {
            adRunning = false; adCurrentId = null; adCurrentTitle = ""; adCurrentStatus = ""; adCurrentEst = "";
            if (notifMgr != null) notifMgr.cancel(NOTIF_ID_REMOVAL);
            postUi(() -> setWorking(false, null));
            return;
        }
        Db localDb = db;
        Episode next = localDb != null ? localDb.episode(nextId) : null;
        if (next == null) { processNextFromQueue(); return; }
        adCurrentId = nextId;
        adCurrentTitle = next.title;
        adCurrentStatus = "Starting...";
        adCurrentEst = "";
        postUi(() -> setWorking(true, next.title + ": Starting..."));
        io.execute(() -> runAndroidAdRemoval(nextId));
    }

    /** Must be called on the main thread (accesses Views). */
    private void refreshAdBusyPanel() {
        if (busyPanel == null) return;
        if (adRunning) {
            busyPanel.setVisibility(View.VISIBLE);
            if (busyText != null) {
                StringBuilder txt = new StringBuilder(adCurrentTitle).append("\n").append(adCurrentStatus);
                if (!TextUtils.isEmpty(adCurrentEst)) txt.append("  ").append(adCurrentEst);
                synchronized (adQueue) {
                    if (!adQueue.isEmpty()) txt.append("\n").append(adQueue.size()).append(" more queued");
                }
                busyText.setText(txt.toString());
            }
        } else {
            busyPanel.setVisibility(View.GONE);
        }
    }

    // Time estimation
    private String estimateRemovalTime(String engine, double audioSeconds) {
        try {
            Db localDb = db;
            if (localDb == null) return "";
            localDb.ensureTimingTable();
            long lo = Math.max(0, (long)(audioSeconds * 0.6));
            long hi = (long)(audioSeconds * 1.4) + 120;
            Cursor cur = localDb.getReadableDatabase().rawQuery(
                "SELECT AVG(CAST(transcription_seconds AS REAL)/NULLIF(audio_length_seconds,0))," +
                "AVG(detection_seconds),AVG(render_seconds),COUNT(*) " +
                "FROM ad_removal_timing WHERE engine=? AND audio_length_seconds BETWEEN ? AND ?",
                new String[]{engine, String.valueOf(lo), String.valueOf(hi)});
            try {
                if (cur.moveToFirst() && cur.getInt(3) > 0 && !cur.isNull(0)) {
                    double rate = cur.getDouble(0);
                    double det  = cur.getDouble(1);
                    double ren  = cur.getDouble(2);
                    double est  = audioSeconds * rate + det + ren;
                    if (est < 90) return "(~" + (int) est + "s est.)";
                    return "(~" + (int)(est / 60) + " min est.)";
                }
            } finally { cur.close(); }
        } catch (Exception ignored) {}
        return "";
    }

    private void setStatus(String s) {
        if (status != null) {
            status.setText(nullToEmpty(s));
            status.setVisibility(TextUtils.isEmpty(s) ? View.GONE : View.VISIBLE);
        }
    }
    private void setWorking(boolean working, String message) {
        if (busyPanel != null) busyPanel.setVisibility((working || adRunning) ? View.VISIBLE : View.GONE);
        if (!TextUtils.isEmpty(message)) {
            setStatus(message);
            if (busyText != null) busyText.setText(message);
        }
        if (!working && !adRunning && notifMgr != null) notifMgr.cancel(NOTIF_ID_REMOVAL);
    }

    private int showModalOverlay(String title, String body, boolean showProgress, boolean determinate) {
        if (appFrame == null) return modalGeneration;
        hideModalOverlay();
        int generation = ++modalGeneration;

        modalOverlay = new FrameLayout(this);
        modalOverlay.setBackgroundColor(0x99000000);
        modalOverlay.setClickable(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(22), dp(20), dp(22), dp(20));
        panel.setBackground(rounded(0xeeffffff, dp(18), 0));

        modalTitle = text(TextUtils.isEmpty(title) ? "Loading" : title, 22, INK, true);
        modalTitle.setGravity(Gravity.CENTER);
        panel.addView(modalTitle, new LinearLayout.LayoutParams(-1, -2));

        modalBody = text(nullToEmpty(body), 15, MUTED, false);
        modalBody.setGravity(Gravity.CENTER);
        modalBody.setPadding(0, dp(8), 0, dp(8));
        modalBody.setVisibility(TextUtils.isEmpty(body) ? View.GONE : View.VISIBLE);
        panel.addView(modalBody, new LinearLayout.LayoutParams(-1, -2));

        modalProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        modalProgress.setMax(100);
        modalProgress.setIndeterminate(!determinate);
        modalProgress.setProgress(0);
        modalProgress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, dp(18));
        progressLp.setMargins(0, dp(8), 0, dp(10));
        panel.addView(modalProgress, progressLp);

        modalActions = row();
        modalActions.setGravity(Gravity.CENTER);
        modalActions.setVisibility(View.GONE);
        panel.addView(modalActions, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        panelLp.setMargins(dp(42), 0, dp(42), 0);
        modalOverlay.addView(panel, panelLp);
        appFrame.addView(modalOverlay, new FrameLayout.LayoutParams(-1, -1));
        return generation;
    }

    private void showLoadingOverlay() {
        showModalOverlay("Loading", "", true, false);
    }

    private void showAdRemovalStartedOverlay(Episode episode) {
        String title = "Ad removal started";
        String body = "This can take some time. You'll get a notification when it is ready.";
        int generation = showModalOverlay(title, body, false, false);
        main.postDelayed(() -> {
            if (generation == modalGeneration) hideModalOverlay();
        }, 3000);
    }

    private void showDownloadOverlay(String episodeTitle) {
        showModalOverlay("Downloading", "Downloading ad-free audio" + (TextUtils.isEmpty(episodeTitle) ? "..." : " for " + episodeTitle + "..."), true, false);
        if (modalActions != null) {
            modalActions.removeAllViews();
            modalActions.setVisibility(View.VISIBLE);
            modalActions.addView(secondaryButton("Close", v -> hideModalOverlay()));
        }
    }

    private void updateDownloadOverlayProgress(long downloaded, long total) {
        if (modalProgress == null || modalOverlay == null) return;
        if (total > 0) {
            int pct = (int)Math.max(0, Math.min(100, downloaded * 100 / total));
            modalProgress.setIndeterminate(false);
            modalProgress.setProgress(pct);
            if (modalBody != null) {
                modalBody.setText("Downloading ad-free audio... " + pct + "%");
                modalBody.setVisibility(View.VISIBLE);
            }
        } else if (modalBody != null) {
            modalProgress.setIndeterminate(true);
            modalBody.setText("Downloading ad-free audio...");
            modalBody.setVisibility(View.VISIBLE);
        }
    }

    private void showDownloadReadyOverlay(Episode episode) {
        if (episode == null) return;
        showModalOverlay("Ready to play", episode.title, true, true);
        if (modalProgress != null) modalProgress.setProgress(100);
        if (modalActions != null) {
            modalActions.removeAllViews();
            modalActions.setVisibility(View.VISIBLE);
            modalActions.addView(button("Play", v -> {
                hideModalOverlay();
                playEpisode(episode);
            }));
            modalActions.addView(secondaryButton("Close", v -> hideModalOverlay()));
        }
    }

    private void hideModalOverlay() {
        if (modalOverlay != null && modalOverlay.getParent() instanceof FrameLayout) {
            ((FrameLayout) modalOverlay.getParent()).removeView(modalOverlay);
        }
        modalOverlay = null;
        modalTitle = null;
        modalBody = null;
        modalProgress = null;
        modalActions = null;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private int statusBarInset() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(20);
    }
    private void hideKeyboard(View v) { ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0); }
    private String indicators(Episode e) { ArrayList<String> a = new ArrayList<>(); if (e.isNew) a.add("New"); if (e.isListened) a.add("Listened"); else if (e.positionSeconds > 0) a.add("In progress"); if (e.downloaded()) a.add("Saved"); if ("processed".equals(e.adSupportedStatus)) a.add("Ad free"); else if ("no_ads_found".equals(e.adSupportedStatus)) a.add("No ads found"); else if ("supported".equals(e.adSupportedStatus)) a.add("AD"); return TextUtils.join("  |  ", a); }
    private ImageButton playerIconButton(int icon, String description, View.OnClickListener listener, int bg, int fg, int sizeDp) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setColorFilter(fg);
        button.setContentDescription(description);
        button.setBackground(rounded(bg, dp(sizeDp / 2), 0));
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        button.setOnClickListener(v -> {
            try { listener.onClick(v); } catch (Exception e) { showError("Something went wrong", "The app recovered from an unexpected player action."); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        lp.setMargins(dp(3), 0, dp(3), 0);
        button.setLayoutParams(lp);
        return button;
    }
    private void tintSeekBar(SeekBar bar) {
        if (Build.VERSION.SDK_INT < 21) return;
        try {
            bar.getProgressDrawable().setTint(PLAYER_ACCENT);
            bar.getThumb().setTint(PLAYER_ACCENT);
        } catch (Exception ignored) {}
    }
    private void resetPlayerDockRefs() {
        dockTitle = null;
        dockSubtitle = null;
        dockPosition = null;
        dockDuration = null;
        dockSeek = null;
        dockPlayButton = null;
    }
    private boolean isPlayerPlaying() {
        try { return player != null && player.isPlaying(); } catch (Exception ignored) { return false; }
    }
    private String formatClock(int seconds) {
        seconds = Math.max(0, seconds);
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        if (h > 0) return h + ":" + String.format(Locale.US, "%02d:%02d", m, s);
        return m + ":" + String.format(Locale.US, "%02d", s);
    }
    private String initials(String title) {
        String clean = cleanName(title).trim();
        if (TextUtils.isEmpty(clean)) return "AF";
        StringBuilder out = new StringBuilder();
        for (String part : clean.split("\\s+")) {
            if (part.length() == 0) continue;
            char c = part.charAt(0);
            if (Character.isLetterOrDigit(c)) out.append(Character.toUpperCase(c));
            if (out.length() >= 2) break;
        }
        return out.length() == 0 ? "AF" : out.toString();
    }
    private String strip(String s) {
        if (s == null) return "";
        try {
            if (Build.VERSION.SDK_INT >= 24) return Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().trim();
            return Html.fromHtml(s).toString().trim();
        } catch (Exception e) {
            return s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        }
    }
    private String append(String a, String b) { return TextUtils.isEmpty(a) ? b : a + b; }
    private String nullToEmpty(String s) { return s == null ? "" : s; }
    private String isoNow() { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.format(new Date()); }
    private String friendly(String iso) {
        if (TextUtils.isEmpty(iso)) return "Never";
        Date parsed = parseFeedOrIsoDate(iso);
        return parsed == null ? iso.replace("T", " ").replace("Z", " UTC") : displayDateTime(parsed);
    }
    private String displayReleaseDate(String raw) {
        if (TextUtils.isEmpty(raw)) return "";
        Date parsed = parseFeedOrIsoDate(raw);
        return parsed == null ? raw.replaceAll("\\s+\\d{1,2}:\\d{2}:\\d{2}.*$", "") : displayDateOnly(parsed);
    }
    private String displayDateOnly(Date date) {
        SimpleDateFormat out = new SimpleDateFormat("MMM d, yyyy", Locale.US);
        out.setTimeZone(displayTimeZone());
        return out.format(date);
    }
    private String displayDateTime(Date date) {
        SimpleDateFormat out = new SimpleDateFormat("MMM d, yyyy h:mm a z", Locale.US);
        out.setTimeZone(displayTimeZone());
        return out.format(date);
    }
    private TimeZone displayTimeZone() {
        return TimeZone.getTimeZone(displayTimezoneId());
    }
    private String displayTimezoneId() {
        String id = db == null ? "" : db.setting("display_timezone", "America/New_York").trim();
        if (TextUtils.isEmpty(id)) return "America/New_York";
        TimeZone zone = TimeZone.getTimeZone(id);
        if ("GMT".equals(zone.getID()) && !id.equalsIgnoreCase("GMT") && !id.equalsIgnoreCase("UTC")) {
            return "America/New_York";
        }
        return zone.getID();
    }
    private static long episodeSortMillis(String raw) {
        Date parsed = parseFeedOrIsoDate(raw);
        return parsed == null ? 0L : parsed.getTime();
    }
    private static String normalizeFeedDate(String raw) {
        Date parsed = parseFeedOrIsoDate(raw);
        if (parsed == null) return raw == null ? "" : raw.trim();
        SimpleDateFormat out = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        out.setTimeZone(TimeZone.getTimeZone("UTC"));
        return out.format(parsed);
    }
    private static Date parseFeedOrIsoDate(String raw) {
        if (TextUtils.isEmpty(raw)) return null;
        String trimmed = raw.trim();
        String[] patterns = {
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, d MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, d MMM yyyy HH:mm:ss zzz"
        };
        for (String pattern : patterns) {
            try {
                SimpleDateFormat parser = new SimpleDateFormat(pattern, Locale.US);
                if (pattern.endsWith("'Z'")) parser.setTimeZone(TimeZone.getTimeZone("UTC"));
                parser.setLenient(true);
                return parser.parse(trimmed);
            } catch (Exception ignored) {}
        }
        return null;
    }
    private void confirm(String msg, Runnable ok) { new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Yes", (d,w)->ok.run()).setNegativeButton("No", null).show(); }
    private void legalDialog() { new AlertDialog.Builder(this).setTitle("About / Legal").setMessage("This app is an independent podcast player. Only remove ads from podcasts you own, have licensed, or otherwise have the rights to modify for personal use. Podcast audio, names, artwork, descriptions, and metadata belong to their respective publishers.").setPositiveButton("OK", null).show(); }
    private int settingInt(String key, int def) { try { return Integer.parseInt(db.setting(key, String.valueOf(def))); } catch (Exception e) { return def; } }
    private boolean settingBool(String key, boolean def) { return "1".equals(db.setting(key, def ? "1" : "0")); }
    private int parseDuration(String s) { try { if (s.contains(":")) { String[] p = s.split(":"); int total = 0; for (String part : p) total = total * 60 + Integer.parseInt(part.trim()); return total; } return (int) Float.parseFloat(s); } catch (Exception e) { return 0; } }
    private void installCrashRecorder() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            String msg = error.getClass().getSimpleName() + ": " + nullToEmpty(error.getMessage());
            try { getSharedPreferences("crash", MODE_PRIVATE).edit().putString("last", msg).apply(); } catch (Exception ignored) {}
            if (thread == Looper.getMainLooper().getThread() && previous != null) previous.uncaughtException(thread, error);
        });
    }
    private GradientDrawable rounded(int color, int radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }
    private TextView emptyState(String message) {
        TextView t = text(message, 15, MUTED, false);
        t.setPadding(dp(14), dp(16), dp(14), dp(16));
        t.setBackground(rounded(SURFACE, dp(16), LINE));
        return t;
    }
    private TextView code(String message) {
        TextView t = text(message, 12, 0xffdbeafe, false);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        t.setPadding(dp(10), dp(10), dp(10), dp(10));
        t.setBackground(rounded(0xff0f172a, dp(10), 0));
        t.setTextIsSelectable(true);
        return t;
    }
    private void safeScreen(Runnable r) {
        try { r.run(); } catch (Exception e) { showError("Something went wrong", "The app recovered instead of closing. Try the action again, or open Search."); }
    }
    private void navigate(Runnable r) {
        if (currentScreenAction != null) screenBackStack.add(currentScreenAction);
        currentScreenAction = r;
        safeScreen(r);
    }
    private void replaceScreen(Runnable r) {
        currentScreenAction = r;
        safeScreen(r);
    }
    private void postUi(Runnable r) { main.post(() -> safeScreen(r)); }
    private void showError(String title, String body) {
        try {
            base(title);
            setStatus(body);
            LinearLayout card = card();
            card.setBackground(rounded(SOFT_RED, dp(18), 0));
            card.addView(text(body, 16, INK, false));
            LinearLayout actions = row();
            actions.addView(button("Search", v -> navigate(() -> showSearch())));
            actions.addView(secondaryButton("Home", v -> replaceScreen(() -> showHome())));
            card.addView(actions);
            root.addView(card);
        } catch (Exception ignored) {}
    }
    private int playerDurationSec() {
        try { if (playerPrepared && player != null) return Math.max(1, player.getDuration() / 1000); } catch (Exception ignored) {}
        return Math.max(1, currentEpisode == null ? 1 : currentEpisode.durationSeconds);
    }
    private int playerPositionSec() {
        try { if (playerPrepared && player != null) return Math.max(0, player.getCurrentPosition() / 1000); } catch (Exception ignored) {}
        return Math.max(0, currentEpisode == null ? 0 : currentEpisode.positionSeconds);
    }
    private String cleanName(String name) {
        if (name == null) return "";
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }
    private String normalizeUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.startsWith("//")) u = "https:" + u;
        if (!u.startsWith("http://") && !u.startsWith("https://") && u.contains(".")) u = "https://" + u;
        return u;
    }
    private boolean isHttpUrl(String url) {
        return !TextUtils.isEmpty(url) && (url.startsWith("http://") || url.startsWith("https://"));
    }
    private boolean isKnownApiUrl(String url) {
        try {
            if (!isHttpUrl(url)) return false;
            String host = new URL(url).getHost();
            return CURRENT_AD_API_HOST.equalsIgnoreCase(host) || LEGACY_AD_API_HOST.equalsIgnoreCase(host);
        } catch (Exception ignored) {
            return false;
        }
    }
    private void addCallLog(String label, String url, String finalUrl, int status, String headers, String rawPreview, int rawLength) {
        CallLog log = new CallLog();
        log.label = label;
        log.url = nullToEmpty(url);
        log.finalUrl = nullToEmpty(finalUrl);
        log.status = status <= 0 ? "status unknown" : String.valueOf(status);
        log.headers = nullToEmpty(headers);
        log.rawPreview = nullToEmpty(rawPreview);
        log.rawLength = rawLength;
        log.at = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (callLogs) {
            callLogs.add(0, log);
            while (callLogs.size() > 200) callLogs.remove(callLogs.size() - 1);
        }
    }
    private String headersToText(HttpURLConnection c) {
        try {
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, List<String>> entry : c.getHeaderFields().entrySet()) {
                out.append(entry.getKey() == null ? "status" : entry.getKey()).append(": ").append(TextUtils.join(", ", entry.getValue())).append("\n");
            }
            return out.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }
    private String rawPreview(byte[] bytes) {
        if (bytes == null) return "";
        int len = Math.min(bytes.length, 16000);
        try { return new String(bytes, 0, len, "UTF-8"); } catch (Exception e) { return ""; }
    }
    private String safeFinalUrl(HttpURLConnection c) {
        try { return c.getURL().toString(); } catch (Exception e) { return ""; }
    }
    private boolean isPlausibleAudioUrl(String url, String type) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase(Locale.US);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false;
        if (lower.equals("https://omnycontent.com") || lower.equals("http://omnycontent.com") || lower.equals("https://omnycontent.com/") || lower.equals("http://omnycontent.com/")) return false;
        String t = type == null ? "" : type.toLowerCase(Locale.US);
        return t.startsWith("audio/") || lower.contains(".mp3") || lower.contains(".m4a") || lower.contains("redirect.mp3");
    }
    private String readableSize(long bytes) { if (bytes > 1024*1024) return (bytes / (1024*1024)) + " MB"; if (bytes > 1024) return (bytes / 1024) + " KB"; return bytes + " B"; }
    private long downloadBytes() { File f = getExternalFilesDir(null); return sizeOf(f == null ? null : new File(f, "podcasts")); }
    private long sizeOf(File f) { if (f == null || !f.exists()) return 0; if (f.isFile()) return f.length(); long n = 0; File[] files = f.listFiles(); if (files != null) for (File c : files) n += sizeOf(c); return n; }
    private static String sha(String s) { try { MessageDigest md = MessageDigest.getInstance("SHA-256"); byte[] b = md.digest(s.getBytes("UTF-8")); StringBuilder out = new StringBuilder(); for (byte x : b) out.append(String.format("%02x", x)); return out.toString(); } catch (Exception e) { return String.valueOf(s.hashCode()); } }

    static class Feed { Podcast podcast; ArrayList<Episode> episodes; }

    static class AdRemovalCancelledException extends Exception {
        AdRemovalCancelledException() { super(); }
    }
    static class Podcast {
        String id, source, sourceId, title, publisher, description, feedUrl, directoryUrl, websiteUrl, artworkUrl, lastFeedRefreshAt;
        boolean subscribed;
    }
    static class Episode {
        String id, podcastId, guid, title, description, pubDate, enclosureUrl, enclosureType, localFilePath, downloadStatus = "not_downloaded", adSupportedStatus = "unknown", firstSeenAt;
        long enclosureLength;
        int durationSeconds, positionSeconds;
        boolean isNew = true, isListened;
        boolean downloaded() { return ("downloaded".equals(downloadStatus) || "processed".equals(downloadStatus)) && !TextUtils.isEmpty(localFilePath) && new File(localFilePath).exists(); }
    }
    static class CallLog {
        String label, url, finalUrl, status, headers, rawPreview, at;
        int rawLength;
    }

    static class Db extends SQLiteOpenHelper {
        Db(Context c) { super(c, "localpod.db", null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE podcasts (id TEXT PRIMARY KEY, source TEXT, source_id TEXT, title TEXT NOT NULL, publisher TEXT, description TEXT, feed_url TEXT NOT NULL UNIQUE, directory_url TEXT, website_url TEXT, artwork_url TEXT, explicit_flag INTEGER DEFAULT 0, ad_supported_status TEXT DEFAULT 'unknown', subscribed INTEGER DEFAULT 0, subscribed_at TEXT, last_feed_refresh_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            db.execSQL("CREATE TABLE episodes (id TEXT PRIMARY KEY, podcast_id TEXT NOT NULL, guid TEXT, title TEXT NOT NULL, description TEXT, pub_date TEXT, duration_seconds INTEGER, enclosure_url TEXT NOT NULL, enclosure_type TEXT, enclosure_length INTEGER, local_file_path TEXT, download_status TEXT DEFAULT 'not_downloaded', downloaded_at TEXT, download_expires_at TEXT, is_new INTEGER DEFAULT 1, is_listened INTEGER DEFAULT 0, listened_at TEXT, ad_supported_status TEXT DEFAULT 'unknown', first_seen_at TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            db.execSQL("CREATE TABLE playback_progress (episode_id TEXT PRIMARY KEY, podcast_id TEXT NOT NULL, position_seconds INTEGER DEFAULT 0, duration_seconds INTEGER, percent_complete REAL DEFAULT 0, last_played_at TEXT, completed_at TEXT)");
            db.execSQL("CREATE TABLE directory_cache (id TEXT PRIMARY KEY, query TEXT, source TEXT NOT NULL, source_id TEXT, title TEXT NOT NULL, publisher TEXT, feed_url TEXT, directory_url TEXT, description TEXT, genres TEXT, artwork_url TEXT, cached_at TEXT NOT NULL, expires_at TEXT NOT NULL)");
            db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at TEXT NOT NULL)");
            db.execSQL("CREATE TABLE feed_refreshes (podcast_id TEXT NOT NULL, refreshed_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS saved_episodes (episode_id TEXT PRIMARY KEY, saved_at TEXT NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS ad_removal_timing (id INTEGER PRIMARY KEY AUTOINCREMENT, engine TEXT NOT NULL, audio_length_seconds INTEGER NOT NULL, transcription_seconds INTEGER NOT NULL, detection_seconds INTEGER NOT NULL, render_seconds INTEGER NOT NULL, total_seconds INTEGER NOT NULL, created_at TEXT NOT NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
        String now() { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.format(new Date()); }
        String setting(String key, String def) { Cursor c = getReadableDatabase().rawQuery("SELECT value FROM settings WHERE key=?", new String[]{key}); try { return c.moveToFirst() ? c.getString(0) : def; } finally { c.close(); } }
        void setSetting(String key, String val) { ContentValues v = new ContentValues(); v.put("key", key); v.put("value", TextUtils.isEmpty(val) ? "0" : val); v.put("updated_at", now()); getWritableDatabase().insertWithOnConflict("settings", null, v, SQLiteDatabase.CONFLICT_REPLACE); }
        void cacheDirectory(Podcast p, String query, long t) {
            ContentValues v = new ContentValues(); v.put("id", p.id); v.put("query", query); v.put("source", p.source); v.put("source_id", p.sourceId); v.put("title", p.title); v.put("publisher", p.publisher); v.put("feed_url", p.feedUrl); v.put("directory_url", p.directoryUrl); v.put("description", p.description); v.put("artwork_url", p.artworkUrl); v.put("cached_at", now()); v.put("expires_at", String.valueOf(t + MainActivity.PODCAST_CACHE_TTL_MS)); getWritableDatabase().insertWithOnConflict("directory_cache", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
        ArrayList<Podcast> cachedDirectory() { ArrayList<Podcast> out = new ArrayList<>(); long nowMs = System.currentTimeMillis(); Cursor c = getReadableDatabase().rawQuery("SELECT id,source,source_id,title,publisher,feed_url,directory_url,description,artwork_url,cached_at,expires_at FROM directory_cache WHERE CAST(expires_at AS INTEGER)>? ORDER BY cached_at DESC LIMIT 50", new String[]{String.valueOf(nowMs)}); try { while(c.moveToNext()) { if (cacheRowFresh(c.getString(9), c.getString(10), nowMs)) out.add(podcastFromCache(c)); } } finally { c.close(); } return out; }
        ArrayList<Podcast> cachedDirectoryForQuery(String query) { ArrayList<Podcast> out = new ArrayList<>(); long nowMs = System.currentTimeMillis(); String key = TextUtils.isEmpty(query) ? "" : query.trim().toLowerCase(Locale.US); Cursor c = getReadableDatabase().rawQuery("SELECT id,source,source_id,title,publisher,feed_url,directory_url,description,artwork_url,cached_at,expires_at FROM directory_cache WHERE LOWER(query)=? AND CAST(expires_at AS INTEGER)>? ORDER BY cached_at DESC LIMIT 50", new String[]{key, String.valueOf(nowMs)}); try { while(c.moveToNext()) { if (cacheRowFresh(c.getString(9), c.getString(10), nowMs)) out.add(podcastFromCache(c)); } } finally { c.close(); } return out; }
        boolean cacheRowFresh(String cachedAt, String expiresAt, long nowMs) { try { if (Long.parseLong(expiresAt == null ? "" : expiresAt) <= nowMs) return false; } catch (Exception ignored) { return false; } Date cached = MainActivity.parseFeedOrIsoDate(cachedAt); return cached != null && nowMs - cached.getTime() <= MainActivity.PODCAST_CACHE_TTL_MS; }
        Podcast podcastFromCache(Cursor c) { Podcast p = new Podcast(); p.id=c.getString(0); p.source=c.getString(1); p.sourceId=c.getString(2); p.title=c.getString(3); p.publisher=c.getString(4); p.feedUrl=c.getString(5); p.directoryUrl=c.getString(6); p.description=c.getString(7); p.artworkUrl=c.getString(8); return p; }
        boolean canRefresh(String id) { long day = System.currentTimeMillis() - 24L*3600*1000; SQLiteDatabase w = getWritableDatabase(); w.delete("feed_refreshes", "refreshed_at<?", new String[]{String.valueOf(day)}); Cursor c = w.rawQuery("SELECT COUNT(*) FROM feed_refreshes WHERE podcast_id=?", new String[]{id}); try { c.moveToFirst(); return c.getInt(0) < 4; } finally { c.close(); } }
        void noteRefresh(String id) { ContentValues v = new ContentValues(); v.put("podcast_id", id); v.put("refreshed_at", System.currentTimeMillis()); getWritableDatabase().insert("feed_refreshes", null, v); }
        void saveFeed(Feed f) {
            SQLiteDatabase w = getWritableDatabase(); w.beginTransaction(); try {
                Podcast old = podcastByFeed(f.podcast.feedUrl); boolean sub = old != null && old.subscribed;
                ContentValues p = new ContentValues(); p.put("id", f.podcast.id); p.put("source", f.podcast.source); p.put("title", f.podcast.title); p.put("publisher", f.podcast.publisher); p.put("description", f.podcast.description); p.put("feed_url", f.podcast.feedUrl); p.put("website_url", f.podcast.websiteUrl); p.put("artwork_url", f.podcast.artworkUrl); p.put("subscribed", sub ? 1 : 0); p.put("last_feed_refresh_at", now()); p.put("created_at", now()); p.put("updated_at", now()); w.insertWithOnConflict("podcasts", null, p, SQLiteDatabase.CONFLICT_REPLACE);
                for (Episode e : f.episodes) { ContentValues v = new ContentValues(); v.put("id", e.id); v.put("podcast_id", e.podcastId); v.put("guid", e.guid); v.put("title", e.title); v.put("description", e.description); v.put("pub_date", e.pubDate); v.put("duration_seconds", e.durationSeconds); v.put("enclosure_url", e.enclosureUrl); v.put("enclosure_type", e.enclosureType); v.put("enclosure_length", e.enclosureLength); v.put("first_seen_at", now()); v.put("created_at", now()); v.put("updated_at", now()); w.insertWithOnConflict("episodes", null, v, SQLiteDatabase.CONFLICT_IGNORE); }
                noteRefresh(f.podcast.id); w.setTransactionSuccessful();
            } finally { w.endTransaction(); }
        }
        Podcast podcastByFeed(String feed) { Cursor c = getReadableDatabase().rawQuery("SELECT id,source,source_id,title,publisher,description,feed_url,directory_url,website_url,artwork_url,subscribed,last_feed_refresh_at FROM podcasts WHERE feed_url=?", new String[]{feed}); try { return c.moveToFirst() ? podcast(c) : null; } finally { c.close(); } }
        Podcast podcastById(String id) { Cursor c = getReadableDatabase().rawQuery("SELECT id,source,source_id,title,publisher,description,feed_url,directory_url,website_url,artwork_url,subscribed,last_feed_refresh_at FROM podcasts WHERE id=?", new String[]{id}); try { return c.moveToFirst() ? podcast(c) : null; } finally { c.close(); } }
        boolean podcastExists(String id) { Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM podcasts WHERE id=?", new String[]{id}); try { c.moveToFirst(); return c.getInt(0) > 0; } finally { c.close(); } }
        boolean podcastFeedFresh(Podcast p, long ttlMs) { if (p == null || TextUtils.isEmpty(p.lastFeedRefreshAt)) return false; Date parsed = MainActivity.parseFeedOrIsoDate(p.lastFeedRefreshAt); return parsed != null && System.currentTimeMillis() - parsed.getTime() <= ttlMs; }
        ArrayList<Podcast> subscriptions() { ArrayList<Podcast> out = new ArrayList<>(); Cursor c = getReadableDatabase().rawQuery("SELECT id,source,source_id,title,publisher,description,feed_url,directory_url,website_url,artwork_url,subscribed,last_feed_refresh_at FROM podcasts WHERE subscribed=1 ORDER BY title", null); try { while(c.moveToNext()) out.add(podcast(c)); } finally { c.close(); } return out; }
        Podcast podcast(Cursor c) { Podcast p = new Podcast(); p.id=c.getString(0); p.source=c.getString(1); p.sourceId=c.getString(2); p.title=c.getString(3); p.publisher=c.getString(4); p.description=c.getString(5); p.feedUrl=c.getString(6); p.directoryUrl=c.getString(7); p.websiteUrl=c.getString(8); p.artworkUrl=c.getString(9); p.subscribed=c.getInt(10)==1; p.lastFeedRefreshAt=c.getString(11); return p; }
        void setSubscribed(String id, boolean sub) { ContentValues v = new ContentValues(); v.put("subscribed", sub?1:0); v.put("subscribed_at", sub?now():null); getWritableDatabase().update("podcasts", v, "id=?", new String[]{id}); }
        ArrayList<Episode> episodesForPodcast(String pid, String filter, int limit) {
            return episodesForPodcast(pid, filter, limit, 0);
        }
        ArrayList<Episode> episodesForPodcast(String pid, String filter, int limit, int offset) {
            String where="e.podcast_id=?";
            ArrayList<String> args = new ArrayList<>();
            args.add(pid);
            if ("New".equals(filter)) where+=" AND e.is_new=1";
            else if ("Downloaded".equals(filter)) where+=" AND e.download_status IN ('downloaded','processed')";
            else if ("Not downloaded".equals(filter)) where+=" AND e.download_status NOT IN ('downloaded','processed')";
            else if ("Listened".equals(filter)) where+=" AND e.is_listened=1";
            else if ("Unlistened".equals(filter)) where+=" AND e.is_listened=0";
            else if ("In progress".equals(filter)) where+=" AND e.id IN (SELECT episode_id FROM playback_progress WHERE position_seconds>0 AND percent_complete<95)";
            return episodes(where, args.toArray(new String[0]), limit, offset);
        }
        int episodeCountForPodcast(String pid, String filter) {
            String where="e.podcast_id=?";
            ArrayList<String> args = new ArrayList<>();
            args.add(pid);
            if ("New".equals(filter)) where+=" AND e.is_new=1";
            else if ("Downloaded".equals(filter)) where+=" AND e.download_status IN ('downloaded','processed')";
            else if ("Not downloaded".equals(filter)) where+=" AND e.download_status NOT IN ('downloaded','processed')";
            else if ("Listened".equals(filter)) where+=" AND e.is_listened=1";
            else if ("Unlistened".equals(filter)) where+=" AND e.is_listened=0";
            else if ("In progress".equals(filter)) where+=" AND e.id IN (SELECT episode_id FROM playback_progress WHERE position_seconds>0 AND percent_complete<95)";
            Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM episodes e LEFT JOIN playback_progress p ON p.episode_id=e.id WHERE "+where, args.toArray(new String[0]));
            try { c.moveToFirst(); return c.getInt(0); } finally { c.close(); }
        }
        ArrayList<Episode> episodesByStatus(String status, int limit) {
            if ("downloaded".equals(status)) return episodes("e.download_status IN ('downloaded','processed')", null, limit);
            return episodes("e.id IN (SELECT episode_id FROM playback_progress WHERE position_seconds>0 AND percent_complete<95)", null, limit);
        }
        ArrayList<Episode> episodes(String where, String[] args, int limit) {
            return episodes(where, args, limit, 0);
        }
        ArrayList<Episode> episodes(String where, String[] args, int limit, int offset) {
            ArrayList<Episode> all = new ArrayList<>();
            Cursor c = getReadableDatabase().rawQuery("SELECT e.id,e.podcast_id,e.guid,e.title,e.description,e.pub_date,e.duration_seconds,e.enclosure_url,e.enclosure_type,e.enclosure_length,e.local_file_path,e.download_status,e.is_new,e.is_listened,e.ad_supported_status,COALESCE(p.position_seconds,0),COALESCE(p.duration_seconds,e.duration_seconds) FROM episodes e LEFT JOIN playback_progress p ON p.episode_id=e.id WHERE "+where, args);
            try {
                while(c.moveToNext()) all.add(episode(c));
            } finally {
                c.close();
            }
            Collections.sort(all, (a, b) -> {
                int byDate = Long.compare(MainActivity.episodeSortMillis(b.pubDate), MainActivity.episodeSortMillis(a.pubDate));
                if (byDate != 0) return byDate;
                String firstA = a.firstSeenAt == null ? "" : a.firstSeenAt;
                String firstB = b.firstSeenAt == null ? "" : b.firstSeenAt;
                return firstB.compareTo(firstA);
            });
            ArrayList<Episode> out = new ArrayList<>();
            int start = Math.max(0, offset);
            int end = limit <= 0 ? all.size() : Math.min(all.size(), start + limit);
            for (int i = start; i < end; i++) out.add(all.get(i));
            return out;
        }
        Episode episode(String id) { Cursor c = getReadableDatabase().rawQuery("SELECT e.id,e.podcast_id,e.guid,e.title,e.description,e.pub_date,e.duration_seconds,e.enclosure_url,e.enclosure_type,e.enclosure_length,e.local_file_path,e.download_status,e.is_new,e.is_listened,e.ad_supported_status,COALESCE(p.position_seconds,0),COALESCE(p.duration_seconds,e.duration_seconds) FROM episodes e LEFT JOIN playback_progress p ON p.episode_id=e.id WHERE e.id=?", new String[]{id}); try { return c.moveToFirst()?episode(c):null; } finally { c.close(); } }
        Episode episode(Cursor c) { Episode e = new Episode(); e.id=c.getString(0); e.podcastId=c.getString(1); e.guid=c.getString(2); e.title=c.getString(3); e.description=c.getString(4); e.pubDate=c.getString(5); e.durationSeconds=c.getInt(16); e.enclosureUrl=c.getString(7); e.enclosureType=c.getString(8); e.enclosureLength=c.getLong(9); e.localFilePath=c.getString(10); e.downloadStatus=c.getString(11); e.isNew=c.getInt(12)==1; e.isListened=c.getInt(13)==1; e.adSupportedStatus=c.getString(14); e.positionSeconds=c.getInt(15); return e; }
        void saveProgress(String eid, String pid, int pos, int dur) { ContentValues v = new ContentValues(); v.put("episode_id", eid); v.put("podcast_id", pid); v.put("position_seconds", pos); v.put("duration_seconds", dur); v.put("percent_complete", dur > 0 ? (pos * 100.0 / dur) : 0); v.put("last_played_at", now()); getWritableDatabase().insertWithOnConflict("playback_progress", null, v, SQLiteDatabase.CONFLICT_REPLACE); ContentValues e = new ContentValues(); e.put("is_new", 0); if (dur > 0 && pos * 100.0 / dur >= 95) { e.put("is_listened", 1); e.put("listened_at", now()); } getWritableDatabase().update("episodes", e, "id=?", new String[]{eid}); }
        void touchPlayed(String eid) { ContentValues e = new ContentValues(); e.put("is_new", 0); getWritableDatabase().update("episodes", e, "id=?", new String[]{eid}); }
        void markComplete(String eid, int dur) { saveProgress(eid, episode(eid).podcastId, dur, dur); markListened(eid, true); }
        void markListened(String id, boolean listened) { ContentValues v = new ContentValues(); v.put("is_listened", listened?1:0); v.put("is_new", 0); v.put("listened_at", listened?now():null); getWritableDatabase().update("episodes", v, "id=?", new String[]{id}); }
        void setDownloaded(String id, String path, int retentionDays) { ContentValues v = new ContentValues(); v.put("local_file_path", path); v.put("download_status", "downloaded"); v.put("downloaded_at", now()); v.put("download_expires_at", String.valueOf(System.currentTimeMillis()+Math.max(1, retentionDays)*24L*3600*1000)); getWritableDatabase().update("episodes", v, "id=?", new String[]{id}); }
        void setProcessedAudio(String id, String path, String adStatus) { ContentValues v = new ContentValues(); v.put("local_file_path", path); v.put("download_status", "processed"); v.put("ad_supported_status", adStatus); v.put("downloaded_at", now()); v.putNull("download_expires_at"); getWritableDatabase().update("episodes", v, "id=?", new String[]{id}); }
        void setAdStatus(String id, String adStatus) { ContentValues v = new ContentValues(); v.put("ad_supported_status", adStatus); getWritableDatabase().update("episodes", v, "id=?", new String[]{id}); }
        void removeDownload(String id) { ContentValues v = new ContentValues(); v.putNull("local_file_path"); v.put("download_status", "not_downloaded"); getWritableDatabase().update("episodes", v, "id=?", new String[]{id}); }
        void ensureTimingTable() { try { getWritableDatabase().execSQL("CREATE TABLE IF NOT EXISTS ad_removal_timing (id INTEGER PRIMARY KEY AUTOINCREMENT, engine TEXT NOT NULL, audio_length_seconds INTEGER NOT NULL, transcription_seconds INTEGER NOT NULL, detection_seconds INTEGER NOT NULL, render_seconds INTEGER NOT NULL, total_seconds INTEGER NOT NULL, created_at TEXT NOT NULL)"); } catch (Exception ignored) {} }
        void saveTimingRecord(String engine, long audioSec, long transSec, long detSec, long renSec, long totalSec) { try { ContentValues v = new ContentValues(); v.put("engine", engine); v.put("audio_length_seconds", audioSec); v.put("transcription_seconds", transSec); v.put("detection_seconds", detSec); v.put("render_seconds", renSec); v.put("total_seconds", totalSec); v.put("created_at", now()); getWritableDatabase().insert("ad_removal_timing", null, v); } catch (Exception ignored) {} }
        void clearDownloads(boolean listenedOnly, boolean all) { String where = all ? "e.download_status IN ('downloaded','processed')" : listenedOnly ? "e.download_status IN ('downloaded','processed') AND e.is_listened=1" : "e.download_status='downloaded' AND CAST(e.download_expires_at AS INTEGER)<?"; String[] args = all||listenedOnly ? null : new String[]{String.valueOf(System.currentTimeMillis())}; for (Episode e : episodes(where, args, 10000)) { if (!TextUtils.isEmpty(e.localFilePath)) new File(e.localFilePath).delete(); removeDownload(e.id); } }
        void ensureLibrarySchema() { try { getWritableDatabase().execSQL("CREATE TABLE IF NOT EXISTS saved_episodes (episode_id TEXT PRIMARY KEY, saved_at TEXT NOT NULL)"); } catch (Exception ignored) {} }
        void setEpisodeSaved(String id, boolean saved) { if (saved) { ContentValues v = new ContentValues(); v.put("episode_id", id); v.put("saved_at", now()); getWritableDatabase().insertWithOnConflict("saved_episodes", null, v, SQLiteDatabase.CONFLICT_REPLACE); } else { getWritableDatabase().delete("saved_episodes", "episode_id=?", new String[]{id}); } }
        boolean isSaved(String id) { Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM saved_episodes WHERE episode_id=?", new String[]{id}); try { c.moveToFirst(); return c.getInt(0) > 0; } finally { c.close(); } }
        ArrayList<Episode> savedEpisodes(int limit) { ArrayList<Episode> out = new ArrayList<>(); Cursor c = getReadableDatabase().rawQuery("SELECT e.id,e.podcast_id,e.guid,e.title,e.description,e.pub_date,e.duration_seconds,e.enclosure_url,e.enclosure_type,e.enclosure_length,e.local_file_path,e.download_status,e.is_new,e.is_listened,e.ad_supported_status,COALESCE(p.position_seconds,0),COALESCE(p.duration_seconds,e.duration_seconds) FROM saved_episodes s JOIN episodes e ON e.id=s.episode_id LEFT JOIN playback_progress p ON p.episode_id=e.id ORDER BY s.saved_at DESC LIMIT " + limit, null); try { while(c.moveToNext()) out.add(episode(c)); } finally { c.close(); } return out; }
        String saveServerAdFreeEpisode(String jobId, String episodeTitle, String podcastName, String downloadUrl, String releaseDate) { String safeJobId = TextUtils.isEmpty(jobId) ? MainActivity.sha(downloadUrl) : jobId; String epId = "server:" + safeJobId; String podName = TextUtils.isEmpty(podcastName) ? "Server ad-free library" : podcastName; String podcastId = "server-podcast-" + MainActivity.sha(podName).substring(0, 16); SQLiteDatabase w = getWritableDatabase(); ContentValues p = new ContentValues(); p.put("id", podcastId); p.put("source", "server"); p.put("source_id", podcastId); p.put("title", podName); p.put("publisher", "Ad-free server"); p.put("description", "Processed episodes available from the hosted ad-free library."); p.put("feed_url", "server://adfree/" + podcastId); p.put("subscribed", 1); p.put("last_feed_refresh_at", now()); p.put("created_at", now()); p.put("updated_at", now()); w.insertWithOnConflict("podcasts", null, p, SQLiteDatabase.CONFLICT_REPLACE); ContentValues e = new ContentValues(); e.put("id", epId); e.put("podcast_id", podcastId); e.put("guid", safeJobId); e.put("title", TextUtils.isEmpty(episodeTitle) ? "Processed episode" : episodeTitle); e.put("description", "Ad-free audio from the hosted processing server."); if (TextUtils.isEmpty(releaseDate)) e.putNull("pub_date"); else e.put("pub_date", releaseDate); e.put("duration_seconds", 0); e.put("enclosure_url", downloadUrl); e.put("enclosure_type", "audio/mpeg"); e.put("enclosure_length", 0); e.putNull("local_file_path"); e.put("download_status", "not_downloaded"); e.put("is_new", 0); e.put("is_listened", 0); e.put("ad_supported_status", "processed"); e.put("first_seen_at", now()); e.put("created_at", now()); e.put("updated_at", now()); w.insertWithOnConflict("episodes", null, e, SQLiteDatabase.CONFLICT_REPLACE); return epId; }        int countNew(String pid) { return count("episodes", "podcast_id=? AND is_new=1", new String[]{pid}); }
        int countDownloaded(String pid) { return count("episodes", "podcast_id=? AND download_status IN ('downloaded','processed')", new String[]{pid}); }
        int count(String table, String where, String[] args) { Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table+" WHERE "+where, args); try { c.moveToFirst(); return c.getInt(0); } finally { c.close(); } }
        String podcastTitle(String pid) { Cursor c = getReadableDatabase().rawQuery("SELECT title FROM podcasts WHERE id=?", new String[]{pid}); try { return c.moveToFirst()?c.getString(0):""; } finally { c.close(); } }
        Episode lastPlayed() { Cursor c = getReadableDatabase().rawQuery("SELECT episode_id FROM playback_progress WHERE position_seconds>0 ORDER BY last_played_at DESC LIMIT 1", null); try { return c.moveToFirst()?episode(c.getString(0)):null; } finally { c.close(); } }
        ArrayList<Episode> recentPlayed(int limit) { ArrayList<Episode> out = new ArrayList<>(); Cursor c = getReadableDatabase().rawQuery("SELECT episode_id FROM playback_progress WHERE position_seconds>0 ORDER BY last_played_at DESC LIMIT " + limit, null); try { while(c.moveToNext()) { Episode e = episode(c.getString(0)); if (e != null) out.add(e); } } finally { c.close(); } return out; }
        void removeFromPlaylist(String id) { setEpisodeSaved(id, false); if (id != null && id.startsWith("server:")) deleteLocalEpisodeMetadata(id); }
        void deleteLocalEpisodeMetadata(String id) { Episode e = episode(id); if (e != null && !TextUtils.isEmpty(e.localFilePath)) new File(e.localFilePath).delete(); getWritableDatabase().delete("saved_episodes", "episode_id=?", new String[]{id}); getWritableDatabase().delete("playback_progress", "episode_id=?", new String[]{id}); if (id != null && id.startsWith("server:")) getWritableDatabase().delete("episodes", "id=?", new String[]{id}); }
        Episode nextEpisode(Episode cur) { Cursor c = getReadableDatabase().rawQuery("SELECT id FROM episodes WHERE podcast_id=? AND pub_date < ? ORDER BY pub_date DESC LIMIT 1", new String[]{cur.podcastId, cur.pubDate == null ? "" : cur.pubDate}); try { return c.moveToFirst()?episode(c.getString(0)):null; } finally { c.close(); } }
    }
}
