package com.adfreepod.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Clock;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.DefaultAssetLoaderFactory;
import androidx.media3.transformer.DefaultDecoderFactory;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;

public class MainActivity extends Activity {
    private static final int BG = 0xfff4f7fb;
    private static final int SURFACE = 0xffffffff;
    private static final int BLUE = 0xff1769aa;
    private static final int BROWN = 0xff8b5e3c;
    private static final int TEAL = 0xff0f766e;
    private static final int CORAL = 0xffd94f70;
    private static final int INK = 0xff202124;
    private static final int MUTED = 0xff68707a;
    private static final int LINE = 0xffdce3ea;
    private static final int SOFT_BLUE = 0xffeaf3ff;
    private static final int SOFT_GREEN = 0xffeaf8f2;
    private static final int SOFT_RED = 0xffffedf1;
    private static final String ENGINE_VOSK   = "vosk";
    private static final String ENGINE_WHISPER = "whisper_local";
    private static final String ENGINE_OPENAI  = "openai";
    private static final String ENGINE_TUNNEL_WHISPER = "tunnel_whisper";
    private static final String ENGINE_TUNNEL_PARAKEET = "tunnel_parakeet";
    private static final int OPENAI_CHUNK_SECONDS = 12 * 60;
    private static final int LOCAL_CHUNK_SECONDS  = 3 * 60;
    private static final double AD_PADDING_BEFORE_SECONDS = 4.0;
    private static final double AD_PADDING_AFTER_SECONDS = 4.0;
    private static final double MIN_KEEP_RANGE_SECONDS = 0.75;
    private static final String SAMPLE_FEED_URL = "https://www.omnycontent.com/d/playlist/e73c998e-6e60-432f-8610-ae210140c5b1/2e824128-fbd5-4c9e-9a57-ae2f0056b0c4/66d98a23-900c-44b0-a40b-ae2f0056b0db/podcast.rss";
    private static final String CURRENT_AD_API_HOST = "adsbegone.sitesindevelopment.com";
    private static final String LEGACY_AD_API_HOST = "agitated-engelbart.74-208-203-194.plesk.page";
    private static final String DEFAULT_AD_API_BASE_URL = "https://" + CURRENT_AD_API_HOST + "/adfree-api";
    private static final int REMOTE_JOB_POLL_INTERVAL_MS = 10000;
    private static final int REMOTE_JOB_MAX_POLLS = 360;
    private static final String[] FILTERS = {"All", "New", "In progress", "Downloaded", "Not downloaded", "Listened", "Unlistened"};

    private static final String NOTIF_CH_REMOVAL = "ad_removal";
    private static final String NOTIF_CH_COMPLETE = "ad_complete";
    private static final String NOTIF_CH_PLAY    = "playback";
    private static final int    NOTIF_ID_REMOVAL = 1001;
    private static final int    NOTIF_ID_PLAY    = 1002;

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
    private LinearLayout root;
    private TextView status;
    private LinearLayout busyPanel;
    private ProgressBar busySpinner;
    private TextView busyText;
    private MediaPlayer player;
    private Episode currentEpisode;
    private boolean userSeeking;
    private NotificationManager notifMgr;
    private final ArrayList<CallLog> callLogs = new ArrayList<>();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            saveCurrentProgress();
            renderMiniPlayer(false);
            main.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        installCrashRecorder();
        db = new Db(this);
        notifMgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannels();
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 0);
        cleanupExpiredDownloads();
        safeScreen(() -> showHome());
        main.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        saveCurrentProgress();
    }

    @Override protected void onDestroy() {
        saveCurrentProgress();
        if (player != null) player.release();
        io.shutdownNow();
        super.onDestroy();
    }

    private void base(String title) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(108));
        scroll.addView(root);
        setContentView(scroll);

        TextView h = text(title, 30, INK, true);
        h.setPadding(0, dp(4), 0, dp(8));
        root.addView(h);
        TextView buildInfo = text("v" + BuildConfig.VERSION_NAME + " | " + BuildConfig.BUILD_TIMESTAMP_UTC, 10, MUTED, false);
        buildInfo.setPadding(0, 0, 0, dp(6));
        root.addView(buildInfo);
        LinearLayout nav = row();
        nav.setBackground(rounded(0xffffffff, dp(22), LINE));
        nav.setPadding(dp(4), dp(4), dp(4), dp(4));
        nav.addView(navButton("Home", v -> safeScreen(() -> showHome())));
        nav.addView(navButton("Search", v -> safeScreen(() -> showSearch())));
        nav.addView(navButton("Debug", v -> safeScreen(() -> showDebug())));
        nav.addView(navButton("Settings", v -> safeScreen(() -> showSettings())));
        root.addView(nav);

        status = text("", 14, MUTED, false);
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.setMargins(0, dp(10), 0, dp(4));
        status.setLayoutParams(statusLp);
        status.setBackground(rounded(SOFT_BLUE, dp(14), 0));
        root.addView(status);

        // ── Persistent ad-removal busy panel (survives tab switches via static state) ──
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
            setStatus("Ad removal cancelled. Transcript cache is preserved — re-tapping Remove ads will resume from the detection step.");
        });
        busyTopRow.addView(cancelAdBtn);
        busyPanel.addView(busyTopRow);
        busyPanel.setVisibility(adRunning ? View.VISIBLE : View.GONE);
        if (adRunning) refreshAdBusyPanel();
        root.addView(busyPanel);

        // ── Persistent completion cards (one per finished job, until dismissed) ──
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
                    ? cardEpTitle + " — ad removal complete"
                    : cardEpTitle + " — no ads detected";
                topRow.addView(text(msg, 14, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
                Button xBtn = new Button(this);
                xBtn.setText("✕");
                xBtn.setBackgroundColor(0);
                xBtn.setTextColor(MUTED);
                xBtn.setPadding(dp(8), 0, 0, 0);
                xBtn.setOnClickListener(v -> {
                    synchronized (completionCards) { completionCards.remove(cardRef); }
                    safeScreen(() -> showHome());
                });
                topRow.addView(xBtn);
                cc.addView(topRow);
                if (isOk) {
                    Episode cardEp = db.episode(cardEpId);
                    if (cardEp != null) {
                        LinearLayout acts = row();
                        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(-2, -2);
                        actLp.topMargin = dp(6);
                        acts.setLayoutParams(actLp);
                        acts.addView(button("▶ Play ad-free", v -> playEpisode(cardEp)));
                        cc.addView(acts);
                    }
                }
                root.addView(cc);
            }
        }

        renderMiniPlayer(true);
    }

    private void showHome() {
        base("Ad Free Podcast Player");
        setStatus("Independent, local-only podcast player. No account, backend, sync, background polling, or thumbnails.");
        root.addView(text(buildIdentity(), 13, MUTED, false));
        String crash = getSharedPreferences("crash", MODE_PRIVATE).getString("last", "");
        if (!TextUtils.isEmpty(crash)) {
            LinearLayout warning = card();
            warning.setBackground(rounded(SOFT_RED, dp(18), 0));
            warning.addView(text("Recovered from the last crash", 17, INK, true));
            warning.addView(text(crash, 13, INK, false));
            warning.addView(secondaryButton("Clear crash note", v -> {
                getSharedPreferences("crash", MODE_PRIVATE).edit().clear().apply();
                safeScreen(() -> showHome());
            }));
            root.addView(warning);
        }
        Episode resume = db.lastPlayed();
        if (resume != null) {
            section("Continue Listening");
            root.addView(episodeRow(resume, true));
        }
        section("Library");
        ArrayList<Podcast> podcasts = db.subscriptions();
        if (podcasts.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("No subscriptions yet", 18, INK, true));
            empty.addView(text("Search the public podcast directory, paste an RSS feed URL, or try the sample Omny feed.", 15, MUTED, false));
            LinearLayout actions = row();
            actions.addView(button("Search", v -> safeScreen(() -> showSearch())));
            actions.addView(secondaryButton("Sample feed", v -> showPodcast(SAMPLE_FEED_URL, true)));
            empty.addView(actions);
            root.addView(empty);
        }
        for (Podcast p : podcasts) {
            LinearLayout card = card();
            card.addView(text(p.title, 18, INK, true));
            card.addView(text(nullToEmpty(p.publisher), 14, MUTED, false));
            card.addView(text(db.countNew(p.id) + " new  |  " + db.countDownloaded(p.id) + " downloaded", 13, MUTED, false));
            LinearLayout actions = row();
            actions.addView(button("Open", v -> showPodcast(p.feedUrl, false)));
            actions.addView(button("Refresh", v -> showPodcast(p.feedUrl, true)));
            card.addView(actions);
            root.addView(card);
        }
        section("In Progress");
        ArrayList<Episode> progress = db.episodesByStatus("in_progress", 8);
        if (progress.isEmpty()) root.addView(emptyState("Nothing in progress yet."));
        for (Episode e : progress) root.addView(episodeRow(e, true));
        section("Downloads");
        ArrayList<Episode> downloads = db.episodesByStatus("downloaded", 8);
        if (downloads.isEmpty()) root.addView(emptyState("Downloaded episodes will appear here."));
        for (Episode e : downloads) root.addView(episodeRow(e, true));
    }

    private void showSearch() {
        base("Search");
        root.addView(text("Search finds public podcast RSS feeds using Apple's public podcast directory. It is only a directory lookup; playback and downloads come from each podcast's RSS enclosure URLs. If you already know an RSS URL, paste it below and skip search.", 15, MUTED, false));
        EditText q = input("Search podcasts");
        root.addView(q);
        Button go = button("Search public directory", v -> {
            hideKeyboard(q);
            String term = q.getText().toString().trim();
            if (term.isEmpty()) return;
            setStatus("Searching...");
            io.execute(() -> searchOnline(term));
        });
        root.addView(go);
        section("Add by RSS URL");
        EditText rss = input("https://example.com/podcast.rss");
        root.addView(rss);
        root.addView(button("Fetch RSS", v -> {
            hideKeyboard(rss);
            String url = rss.getText().toString().trim();
            if (url.isEmpty()) return;
            showPodcast(url, true);
        }));
        root.addView(secondaryButton("Try the sample Omny feed", v -> showPodcast(SAMPLE_FEED_URL, true)));
        section("Fresh Cache");
        root.addView(text("These are local search results saved on this device from previous searches. They expire after 30 days.", 14, MUTED, false));
        ArrayList<Podcast> cached = db.cachedDirectory();
        if (cached.isEmpty()) root.addView(emptyState("No cached directory results yet."));
        for (Podcast p : cached) root.addView(searchRow(p));

    }

    private String buildIdentity() {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), android.content.pm.PackageManager.PackageInfoFlags.of(0));
                    return "Build " + info.versionName + " (" + info.getLongVersionCode() + ")  |  " + getPackageName();
                }
                android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
                return "Build " + info.versionName + " (" + info.versionCode + ")  |  " + getPackageName();
            } catch (Exception ignored) {
                return "Package " + getPackageName();
            }
    }

    private void searchOnline(String term) {
        try {
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
                db.cacheDirectory(p, term, now);
                results.add(p);
            }
            postUi(() -> {
                base("Search Results");
                setStatus(results.size() + " result(s). Cache expires in 30 days.");
                root.addView(text("These results are directory entries. Opening one fetches the podcast's RSS feed and saves episodes locally.", 15, MUTED, false));
                for (Podcast p : results) root.addView(searchRow(p));
            });
        } catch (Exception e) {
            postUi(() -> showError("Search failed", "Check your connection and try again. You can still paste a direct RSS URL if you have one."));
        }
    }

    private View searchRow(Podcast p) {
        LinearLayout card = card();
        card.addView(text(p.title, 18, INK, true));
        card.addView(text(nullToEmpty(p.publisher), 14, MUTED, false));
        if (!TextUtils.isEmpty(p.description)) card.addView(text(p.description, 13, MUTED, false));
        card.addView(secondaryButton("Open feed", v -> {
            if (TextUtils.isEmpty(p.feedUrl)) showError("Missing RSS feed", "This directory result did not include a feed URL.");
            else showPodcast(p.feedUrl, true);
        }));
        return card;
    }

    private void showPodcast(String feedUrl, boolean forceRefresh) {
        feedUrl = normalizeUrl(feedUrl);
        if (TextUtils.isEmpty(feedUrl)) {
            showError("Missing RSS feed", "Paste a full RSS URL that starts with http:// or https://.");
            return;
        }
        base("Podcast");
        setStatus("Loading feed...");
        final String finalFeedUrl = feedUrl;
        Podcast cached = db.podcastByFeed(finalFeedUrl);
        if (cached != null && !forceRefresh) renderPodcast(cached);
        io.execute(() -> {
            try {
                if (!db.canRefresh(sha(finalFeedUrl))) {
                    postUi(() -> {
                        Podcast p = db.podcastByFeed(finalFeedUrl);
                        if (p != null) {
                            renderPodcast(p);
                            setStatus("This podcast was refreshed recently. Using cached episodes.");
                        } else {
                            showError("Refresh limit reached", "This feed was refreshed recently and no cached copy is available yet. Try again later.");
                        }
                    });
                    return;
                }
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
                        setStatus("Could not refresh this podcast feed. Using cached episodes.");
                    } else {
                        showError("Could not load this podcast feed", "Try again later or check the RSS URL. The feed must expose at least one playable audio enclosure.");
                    }
                });
            }
        });
    }

    private void renderPodcast(Podcast p) {
        if (p == null || TextUtils.isEmpty(p.id)) {
            showError("Podcast unavailable", "The feed loaded, but the app could not save enough podcast metadata to display it.");
            return;
        }
        base(TextUtils.isEmpty(p.title) ? "Podcast" : p.title);
        setStatus("Last refreshed: " + friendly(p.lastFeedRefreshAt));
        if (!TextUtils.isEmpty(p.publisher)) root.addView(text(p.publisher, 16, MUTED, false));
        String desc = strip(p.description);
        if (!TextUtils.isEmpty(desc)) root.addView(text(desc, 14, MUTED, false));
        Button sub = button(p.subscribed ? "Unsubscribe" : "Subscribe", v -> {
            db.setSubscribed(p.id, !p.subscribed);
            p.subscribed = !p.subscribed;
            renderPodcast(p);
        });
        root.addView(sub);
        LinearLayout actions = row();
        actions.addView(secondaryButton("Manual refresh", v -> showPodcast(p.feedUrl, true)));
        actions.addView(secondaryButton("About/legal", v -> legalDialog()));
        root.addView(actions);
        section("Episodes");
        root.addView(text("Showing a safe first page of recent episodes. Use filters to change the list.", 14, MUTED, false));
        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.VERTICAL);
        root.addView(filters);
        LinearLayout row1 = row();
        LinearLayout row2 = row();
        filters.addView(row1);
        filters.addView(row2);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);
        for (int i = 0; i < FILTERS.length; i++) {
            String f = FILTERS[i];
            Button b = secondaryButton(f, v -> renderEpisodeList(list, p, f));
            if (i < 4) row1.addView(b); else row2.addView(b);
        }
        renderEpisodeList(list, p, "All");
    }

    private void renderEpisodeList(LinearLayout list, Podcast p, String filter) {
        try {
            list.removeAllViews();
            ArrayList<Episode> episodes = db.episodesForPodcast(p.id, filter, 25);
            if (episodes.isEmpty()) {
                list.addView(emptyState("No episodes match this filter yet."));
                return;
            }
            list.addView(text(filter + " episodes", 16, INK, true));
            for (Episode e : episodes) {
                try {
                    list.addView(episodeRow(e, true));
                } catch (Exception rowError) {
                    list.addView(emptyState("One episode could not be displayed safely: " + rowError.getClass().getSimpleName() + " " + nullToEmpty(rowError.getMessage())));
                }
            }
            list.addView(text("Showing " + episodes.size() + " episode(s). More paging can be added after this screen is stable on-device.", 13, MUTED, false));
        } catch (Exception e) {
            list.removeAllViews();
            list.addView(emptyState("Episodes could not be displayed, but the app recovered instead of closing: " + e.getClass().getSimpleName() + " " + nullToEmpty(e.getMessage())));
        }
    }

    private View episodeRow(Episode e, boolean openable) {
        LinearLayout card = card();
        card.addView(text(e.title, 17, INK, true));
        String meta = (e.pubDate == null ? "" : e.pubDate) + "  " + indicators(e);
        card.addView(text(meta.trim(), 13, MUTED, false));
        LinearLayout actions = row();
        actions.addView(button(e.positionSeconds > 0 ? "Resume" : "Play", v -> playEpisode(e)));
        actions.addView(secondaryButton(e.downloaded() ? "Remove" : "Download", v -> {
            if (e.downloaded()) removeDownload(e); else downloadEpisode(e);
        }));
        if (e.downloaded()) {
            if ("processed".equals(e.adSupportedStatus)) {
                TextView pill = text("✓ Ads removed", 12, TEAL, false);
                pill.setPadding(dp(10), dp(6), dp(10), dp(6));
                pill.setBackground(rounded(SOFT_GREEN, dp(20), 0));
                actions.addView(pill);
            } else {
                actions.addView(secondaryButton(adQueue.contains(e.id) || e.id.equals(adCurrentId) ? "Queued…" : "Remove ads", v -> removeAds(e)));
            }
        }
        actions.addView(secondaryButton("Details", v -> safeScreen(() -> showEpisode(e.id))));
        card.addView(actions);
        if (openable) card.setOnClickListener(v -> safeScreen(() -> showEpisode(e.id)));
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
        root.addView(text(db.podcastTitle(e.podcastId), 15, MUTED, false));
        root.addView(text(indicators(e), 13, MUTED, false));
        root.addView(text("Audio enclosure URL", 13, MUTED, true));
        root.addView(code(e.enclosureUrl));
        String desc = strip(e.description);
        if (!TextUtils.isEmpty(desc)) root.addView(text(desc, 14, INK, false));
        LinearLayout actions = row();
        actions.addView(button(e.positionSeconds > 0 ? "Resume" : "Play", v -> playEpisode(e)));
        actions.addView(secondaryButton(e.isListened ? "Mark unlistened" : "Mark listened", v -> {
            db.markListened(e.id, !e.isListened);
            showEpisode(e.id);
        }));
        actions.addView(secondaryButton(e.downloaded() ? "Remove download" : "Download", v -> {
            if (e.downloaded()) removeDownload(e); else downloadEpisode(e);
        }));
        if ("processed".equals(e.adSupportedStatus)) {
            TextView pill = text("✓ Ads removed", 13, TEAL, false);
            pill.setPadding(dp(12), dp(7), dp(12), dp(7));
            pill.setBackground(rounded(SOFT_GREEN, dp(20), 0));
            actions.addView(pill);
        } else {
            actions.addView(secondaryButton(adQueue.contains(e.id) || e.id.equals(adCurrentId) ? "Queued…" : "Remove ads", v -> removeAds(e)));
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
        section("Ad Removal — Server Engine");
        String curEngine = db.setting("transcription_engine", ENGINE_OPENAI);
        if (ENGINE_VOSK.equals(curEngine)) curEngine = ENGINE_OPENAI;
        root.addView(text("Choose which server transcription backend to use when you tap Remove ads. The Windows tunnel options process on the WAMP machine through the hosted API and do not use OpenAI.", 14, MUTED, false));
        LinearLayout engineRow = new LinearLayout(this);
        engineRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        String[][] engineOpts = {
            {ENGINE_WHISPER, "Whisper\n(server)"},
            {ENGINE_OPENAI, "OpenAI\nWhisper"},
            {ENGINE_TUNNEL_WHISPER, "Win tunnel\nWhisper"},
            {ENGINE_TUNNEL_PARAKEET, "Win tunnel\nParakeet"}
        };
        for (String[] opt : engineOpts) {
            Button btn = new Button(this);
            btn.setText(opt[1]);
            btn.setLayoutParams(ep);
            btn.setPadding(8, 8, 8, 8);
            boolean selected = opt[0].equals(curEngine);
            if (selected) {
                btn.setBackgroundColor(BLUE);
                btn.setTextColor(0xffffffff);
            } else {
                btn.setBackgroundColor(LINE);
                btn.setTextColor(INK);
            }
            String engineKey = opt[0];
            btn.setOnClickListener(v -> { db.setSetting("transcription_engine", engineKey); showSettings(); });
            engineRow.addView(btn);
        }
        root.addView(engineRow);
        if (ENGINE_WHISPER.equals(curEngine)) {
            root.addView(text("Local Whisper requires a compatible server-side install before it can run.", 13, MUTED, false));
        } else if (ENGINE_TUNNEL_WHISPER.equals(curEngine) || ENGINE_TUNNEL_PARAKEET.equals(curEngine)) {
            root.addView(text("Windows tunnel processing requires WAMP and the reverse SSH tunnel on your Windows machine. Detection is local-only and no OpenAI key is sent.", 13, MUTED, false));
        } else {
            root.addView(text("OpenAI Whisper backend uses OpenAI transcription. Requires an OpenAI API key.", 13, MUTED, false));
        }
        section("Ad Removal API");
        root.addView(text("Set the base URL for your hosted ad-removal API. The app uploads downloaded audio, polls job status, and downloads the ad-free result.", 14, MUTED, false));
        settingText("Ad API base URL", "ad_api_base_url", DEFAULT_AD_API_BASE_URL);
        section("OpenAI Options");
        root.addView(text("Optional. If provided, the key/model are forwarded to the server API for OpenAI-based processing and detection.", 14, MUTED, false));
        settingText("OpenAI API key", "openai_api_key", "");
        settingText("OpenAI model", "openai_model", "gpt-4o-mini");
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
        root.addView(text("This app is an independent podcast player. Podcast audio, names, artwork, descriptions, and feeds belong to their respective publishers. The app streams and downloads episodes from public RSS feeds selected by the user. Downloaded episodes are stored only on this device for personal offline listening.", 14, INK, false));
    }

    private void showDebug() {
        base("Debug");
        setStatus("Recent network calls. This includes the called URL, final redirect URL, headers, and raw response preview.");
        root.addView(text("Use Search or open a feed, then come back here to inspect exactly what the app called and what came back.", 15, MUTED, false));
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
        for (CallLog log : copy) {
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

    private void settingText(String label, String key, String def) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, dp(4), 0, dp(4));
        wrapper.addView(text(label, 15, INK, false));
        EditText value = input(def);
        String existing = db.setting(key, def);
        if ("ad_api_base_url".equals(key)) {
            existing = normalizeApiBaseUrl(existing);
            if (TextUtils.isEmpty(existing)) {
                existing = normalizeApiBaseUrl(DEFAULT_AD_API_BASE_URL);
            }
        }
        value.setText(existing);
        wrapper.addView(value);
        wrapper.addView(button("Save", v -> {
            String saved = value.getText().toString().trim();
            if ("ad_api_base_url".equals(key)) {
                saved = normalizeApiBaseUrl(saved);
                if (TextUtils.isEmpty(saved)) {
                    saved = normalizeApiBaseUrl(DEFAULT_AD_API_BASE_URL);
                }
                value.setText(saved);
                setStatus("Ad API base URL saved.");
            }
            db.setSetting(key, saved);
        }));
        root.addView(wrapper);
    }

    private void playEpisode(Episode e) {
        try {
            saveCurrentProgress();
            if (player != null) player.release();
            player = new MediaPlayer();
            currentEpisode = db.episode(e.id);
            if (currentEpisode == null || TextUtils.isEmpty(currentEpisode.enclosureUrl)) {
                showError("Episode unavailable", "This episode does not have a playable audio URL.");
                return;
            }
            String source = currentEpisode.localFilePath;
            if (TextUtils.isEmpty(source) || !new File(source).exists()) source = currentEpisode.enclosureUrl;
            addCallLog("MEDIA PLAY", source, source, 0, "MediaPlayer.setDataSource", "", 0);
            player.setDataSource(source);
            player.setOnPreparedListener(mp -> {
                int pos = Math.max(0, currentEpisode.positionSeconds * 1000);
                if (pos > 0 && pos < mp.getDuration()) mp.seekTo(pos);
                mp.start();
                db.touchPlayed(currentEpisode.id);
                renderMiniPlayer(false);
                updatePlaybackNotification();
            });
            player.setOnCompletionListener(mp -> {
                db.markComplete(currentEpisode.id, mp.getDuration() / 1000);
                updatePlaybackNotification();
                if (settingBool("autoplay_next", false)) {
                    Episode next = db.nextEpisode(currentEpisode);
                    if (next != null) playEpisode(next);
                } else {
                    renderMiniPlayer(false);
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                setStatus("Playback failed. The podcast host may have rejected the request or the file may no longer exist.");
                return true;
            });
            player.prepareAsync();
            setStatus("Preparing audio...");
            updatePlaybackNotification();
        } catch (Exception ex) {
            setStatus("Playback failed. The podcast host may have rejected the request or the file may no longer exist.");
        }
    }

    private void renderMiniPlayer(boolean add) {
        if (root == null) return;
        if (currentEpisode == null) {
            return;
        }
        if (!add) return;
        section("Player");
        LinearLayout panel = card();
        panel.setBackground(rounded(0xffeef7ff, dp(18), 0));
        panel.addView(text(currentEpisode.title, 16, INK, true));
        SeekBar bar = new SeekBar(this);
        int dur = playerDurationSec();
        int pos = playerPositionSec();
        bar.setMax(dur);
        bar.setProgress(pos);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && player != null) player.seekTo(progress * 1000);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { userSeeking = false; saveCurrentProgress(); }
        });
        panel.addView(bar);
        LinearLayout controls = row();
        controls.addView(button(player != null && player.isPlaying() ? "Pause" : "Play", v -> {
            if (player == null) playEpisode(currentEpisode);
            else if (player.isPlaying()) { player.pause(); updatePlaybackNotification(); saveCurrentProgress(); safeScreen(() -> showHome()); }
            else { player.start(); updatePlaybackNotification(); safeScreen(() -> showHome()); }
        }));
        controls.addView(secondaryButton("Stop", v -> { if (player != null) { player.pause(); updatePlaybackNotification(); } saveCurrentProgress(); }));
        controls.addView(secondaryButton("Back", v -> seekBy(-settingInt("seek_back", 15))));
        controls.addView(secondaryButton("Next", v -> { Episode n = db.nextEpisode(currentEpisode); if (n != null) playEpisode(n); }));
        panel.addView(controls);
        CheckBox autoplay = new CheckBox(this);
        autoplay.setText("Autoplay next");
        autoplay.setChecked(settingBool("autoplay_next", false));
        autoplay.setOnCheckedChangeListener((b, checked) -> db.setSetting("autoplay_next", checked ? "1" : "0"));
        panel.addView(autoplay);
        root.addView(panel);
    }

    private void saveCurrentProgress() {
        if (player == null || currentEpisode == null || userSeeking) return;
        try {
            int pos = Math.max(0, player.getCurrentPosition() / 1000);
            int dur = Math.max(1, player.getDuration() / 1000);
            db.saveProgress(currentEpisode.id, currentEpisode.podcastId, pos, dur);
            currentEpisode.positionSeconds = pos;
            currentEpisode.durationSeconds = dur;
        } catch (Exception ignored) {}
    }

    private void seekBy(int seconds) {
        if (player == null) return;
        try {
            int target = Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + seconds * 1000));
            player.seekTo(target);
            saveCurrentProgress();
        } catch (Exception ignored) {}
    }

    private void downloadEpisode(Episode e) {
        setStatus("Downloading...");
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
                main.post(() -> setStatus("Download failed. The podcast host may have rejected the request or the file may no longer exist."));
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
        if (!e.downloaded()) {
            showError("Download required", "Download the episode first so the app can resolve the episode URL and save the ad-free copy offline.");
            return;
        }
        String engine = db.setting("transcription_engine", ENGINE_OPENAI);
        if (ENGINE_OPENAI.equals(engine) && TextUtils.isEmpty(db.setting("openai_api_key", ""))) {
            showError("OpenAI key required", "Save an OpenAI API key in Settings, or switch to Local Whisper after it is installed on the server.");
            return;
        }
        // Refresh re-queues processing and replaces any prior ad-free output.
        boolean isRefresh = "processed".equals(e.adSupportedStatus) || "no_ads_found".equals(e.adSupportedStatus);
        queueAdRemoval(e, isRefresh);
    }

    private String engineLabel(String engine) {
        if (ENGINE_WHISPER.equals(engine)) return "Whisper server";
        if (ENGINE_OPENAI.equals(engine)) return "OpenAI Whisper server";
        if (ENGINE_TUNNEL_WHISPER.equals(engine)) return "Windows tunnel Whisper";
        if (ENGINE_TUNNEL_PARAKEET.equals(engine)) return "Windows tunnel Parakeet";
        return "OpenAI Whisper server";
    }

    private void runAndroidAdRemoval(String episodeId) {
        adCancelled.set(false);
        Db localDb = db;
        if (localDb == null) { handleAdError(); return; }

        Episode episode = localDb.episode(episodeId);
        if (episode == null || TextUtils.isEmpty(episode.localFilePath)) {
            postUi(() -> showError("Episode unavailable", "The downloaded episode could not be found in local storage."));
            handleAdError(); return;
        }
        File source = new File(episode.localFilePath);
        if (!source.exists()) {
            postUi(() -> showError("File missing", "The local episode file is missing. Download it again before running ad removal."));
            handleAdError(); return;
        }

        String engine = localDb.setting("transcription_engine", ENGINE_OPENAI);
        String apiKey = localDb.setting("openai_api_key", "").trim();
        String openAiModel = localDb.setting("openai_model", "gpt-4o-mini").trim();
        String backend = apiBackendForEngine(engine);
        String detectionMode = apiDetectionModeForEngine(engine, !TextUtils.isEmpty(apiKey));
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

            JSONObject created = createRemoteAdRemovalJob(apiBase, sourceUrl, backend, detectionMode, apiKey, openAiModel);
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
            postUi(() -> refreshAdBusyPanel());
            updateAdNotification();

            File output = buildProcessedAudioPath(source);
            File renderTarget = source.getAbsolutePath().equals(output.getAbsolutePath())
                ? buildProcessedStagingPath(source) : output;
            downloadRemoteAudioFromApi(apiBase, downloadPath, renderTarget);
            output = finalizeProcessedAudioOutput(source, output, renderTarget);

            localDb.setProcessedAudio(episode.id, output.getAbsolutePath(), "processed");

            synchronized (completionCards) { completionCards.add(new String[]{episode.id, episode.title, "ok"}); }
            postCompletionNotification("Ad removal complete", episode.title + " is ready to play ad-free.");
            final String doneId = episode.id;
            postUi(() -> { refreshAdBusyPanel(); showEpisode(doneId); });
            processNextFromQueue();
        } catch (AdRemovalCancelledException cancelled) {
            if (!TextUtils.isEmpty(jobId)) cancelRemoteJobQuietly(apiBase, jobId);
            handleCancelled();
        } catch (Exception ex) {
            String body = TextUtils.isEmpty(ex.getMessage()) ? "Server ad removal failed." : ex.getMessage();
            if (isDnsLookupFailure(body)) {
                String savedApiBase = apiBaseUrlSetting(localDb);
                body = "DNS lookup failed for your Ad API host.\n\n"
                    + "Saved Ad API base URL:\n"
                    + savedApiBase
                    + "\n\nOpen Settings -> Ad API base URL, tap Save, and retry. "
                    + "If it still fails, set Android Private DNS to Automatic or Off.";
            }
            final String finalBody = body;
            postUi(() -> showError("Ad removal failed", finalBody));
            if (adCancelled.get() && !TextUtils.isEmpty(jobId)) cancelRemoteJobQuietly(apiBase, jobId);
            handleAdError();
        }
    }

    private String apiBackendForEngine(String engine) {
        if (ENGINE_OPENAI.equals(engine)) return "openai-whisper";
        if (ENGINE_WHISPER.equals(engine)) return "whisper";
        if (ENGINE_TUNNEL_WHISPER.equals(engine)) return "tunnel-whisper";
        if (ENGINE_TUNNEL_PARAKEET.equals(engine)) return "tunnel-parakeet";
        return "openai-whisper";
    }

    private String apiDetectionModeForEngine(String engine, boolean hasOpenAiKey) {
        if (ENGINE_OPENAI.equals(engine)) return "openai";
        if (ENGINE_TUNNEL_WHISPER.equals(engine) || ENGINE_TUNNEL_PARAKEET.equals(engine)) return "local";
        return hasOpenAiKey ? "hybrid" : "local";
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

            if (LEGACY_AD_API_HOST.equalsIgnoreCase(host)) {
                host = CURRENT_AD_API_HOST;
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

    private JSONObject createRemoteAdRemovalJob(String apiBase, String sourceUrl, String backend, String detectionMode, String apiKey, String openAiModel) throws Exception {
        String url = resolveApiUrl(apiBase, "/api/jobs");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(300000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        connection.setRequestProperty("User-Agent", "AdFreePodAndroid/1.0");

        StringBuilder form = new StringBuilder();
        appendFormField(form, "source_url", sourceUrl);
        appendFormField(form, "backend", backend);
        appendFormField(form, "detection_mode", detectionMode);
        appendFormField(form, "openai_model", TextUtils.isEmpty(openAiModel) ? "gpt-4o-mini" : openAiModel);
        if (!backend.startsWith("tunnel-") && !TextUtils.isEmpty(apiKey)) {
            appendFormField(form, "openai_api_key", apiKey);
        }

        try (OutputStream output = connection.getOutputStream()) {
            output.write(form.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        String body = readConnectionBody(connection);
        int code = connection.getResponseCode();
        if (code >= 400) {
            throw new RuntimeException("Create job failed: " + summarizeApiError(body, code));
        }
        return parseJsonObject(body, "create job response");
    }

    private JSONObject fetchRemoteJobStatus(String apiBase, String jobId) throws Exception {
        String url = resolveApiUrl(apiBase, "/api/jobs/" + URLEncoder.encode(jobId, "UTF-8"));
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(120000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "AdFreePodAndroid/1.0");
        String body = readConnectionBody(connection);
        int code = connection.getResponseCode();
        if (code >= 400) {
            throw new RuntimeException("Status check failed: " + summarizeApiError(body, code));
        }
        return parseJsonObject(body, "job status response");
    }

    private JSONObject waitForRemoteJobCompletion(String apiBase, String jobId) throws Exception {
        for (int poll = 0; poll < REMOTE_JOB_MAX_POLLS; poll++) {
            if (adCancelled.get()) throw new AdRemovalCancelledException();

            JSONObject status = fetchRemoteJobStatus(apiBase, jobId);
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

            Thread.sleep(REMOTE_JOB_POLL_INTERVAL_MS);
        }
        throw new RuntimeException("Timed out waiting for server processing to finish.");
    }

    private void cancelRemoteJobQuietly(String apiBase, String jobId) {
        try {
            String url = resolveApiUrl(apiBase, "/api/jobs/" + URLEncoder.encode(jobId, "UTF-8") + "/cancel");
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("User-Agent", "AdFreePodAndroid/1.0");
            try (OutputStream ignored = connection.getOutputStream()) { }
            readConnectionBody(connection);
        } catch (Exception ignored) {}
    }

    private void downloadRemoteAudioFromApi(String apiBase, String downloadPath, File target) throws Exception {
        String url = resolveApiUrl(apiBase, downloadPath);
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
        long total = connection.getContentLengthLong();
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
                    postUi(() -> refreshAdBusyPanel());
                    updateAdNotification();
                    lastUiUpdate = now;
                }
            }
        }

        if (!target.exists() || target.length() <= 0) {
            throw new RuntimeException("Server download produced an empty file.");
        }
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

    private ArrayList<File> createTranscriptionChunks(File source, File outputDir, int chunkSeconds) {
        if (outputDir.exists()) deleteRecursively(outputDir);
        outputDir.mkdirs();
        double totalSeconds = audioDurationSeconds(source, chunkSeconds);
        ArrayList<File> out = new ArrayList<>();
        int partIndex = 0;
        for (double start = 0.0; start < totalSeconds; start += chunkSeconds) {
            double end = Math.min(totalSeconds, start + chunkSeconds);
            File target = new File(outputDir, String.format(Locale.US, "chunk-%03d.m4a", partIndex + 1));
            exportSingleRange(source, start, end, target);
            if (target.exists()) out.add(target);
            partIndex++;
        }
        if (out.isEmpty()) throw new RuntimeException("No transcription chunks were created.");
        return out;
    }

    private ArrayList<TranscriptSegment> transcribeChunksWithOpenAi(ArrayList<File> chunks, String apiKey) throws Exception {
        ArrayList<TranscriptSegment> all = new ArrayList<>();
        double offsetSeconds = 0.0;
        int total = chunks.size();
        for (int i = 0; i < total; i++) {
            if (adCancelled.get()) throw new AdRemovalCancelledException();
            File chunk = chunks.get(i);
            final int ci = i + 1;
            adCurrentStatus = "Transcribing chunk " + ci + "/" + total + " (OpenAI Whisper)...";
            postUi(() -> refreshAdBusyPanel()); updateAdNotification();
            int segsBefore = all.size();
            ArrayList<TranscriptSegment> current = transcribeChunkWithOpenAi(chunk, apiKey, offsetSeconds);
            all.addAll(current);
            int newSegs = all.size() - segsBefore;
            addCallLog("OPENAI CHUNK " + ci + "/" + total, chunk.getName(), "", 0,
                "offset=" + String.format(Locale.US, "%.1f", offsetSeconds) + "s",
                newSegs + " segments transcribed", 0);
            offsetSeconds += audioDurationSeconds(chunk, OPENAI_CHUNK_SECONDS);
        }
        return all;
    }

    private ArrayList<TranscriptSegment> transcribeChunkWithOpenAi(File chunk, String apiKey, double offsetSeconds) throws Exception {
        String boundary = "----AdFreeBoundary" + System.currentTimeMillis();
        HttpURLConnection connection = (HttpURLConnection) new URL("https://api.openai.com/v1/audio/transcriptions").openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(300000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        try (DataOutputStream output = new DataOutputStream(connection.getOutputStream())) {
            writeMultipartField(output, boundary, "model", "whisper-1");
            writeMultipartField(output, boundary, "response_format", "verbose_json");
            writeMultipartField(output, boundary, "timestamp_granularities[]", "segment");
            writeMultipartFile(output, boundary, "file", chunk, "audio/mp4");
            output.writeBytes("--" + boundary + "--\r\n");
            output.flush();
        }

        String body = readConnectionBody(connection);
        if (connection.getResponseCode() >= 400) {
            throw new RuntimeException("Whisper API request failed: " + body);
        }

        JSONObject json = new JSONObject(body);
        JSONArray segments = json.optJSONArray("segments");
        ArrayList<TranscriptSegment> out = new ArrayList<>();
        if (segments == null) return out;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject item = segments.optJSONObject(i);
            if (item == null) continue;
            String text = item.optString("text", "").trim();
            double start = item.optDouble("start", -1);
            double end = item.optDouble("end", -1);
            if (TextUtils.isEmpty(text) || start < 0 || end <= start) continue;
            TranscriptSegment segment = new TranscriptSegment();
            segment.index = out.size();
            segment.start = offsetSeconds + start;
            segment.end = offsetSeconds + end;
            segment.text = text;
            out.add(segment);
        }
        return out;
    }

    private ArrayList<TranscriptAdRange> detectAdRangesWithOpenAi(ArrayList<TranscriptSegment> segments, String apiKey, String model) throws Exception {
        ArrayList<TranscriptAdRange> out = new ArrayList<>();
        int batchSize = 90;
        for (int startIndex = 0; startIndex < segments.size(); startIndex += batchSize) {
            if (adCancelled.get()) throw new AdRemovalCancelledException();
            int endIndex = Math.min(segments.size(), startIndex + batchSize);
            JSONArray payloadSegments = new JSONArray();
            for (int i = startIndex; i < endIndex; i++) {
                TranscriptSegment segment = segments.get(i);
                JSONObject row = new JSONObject();
                row.put("i", segment.index);
                row.put("start", round2(segment.start));
                row.put("end", round2(segment.end));
                row.put("text", segment.text.length() > 260 ? segment.text.substring(0, 257) + "..." : segment.text);
                payloadSegments.put(row);
            }

            JSONObject request = new JSONObject();
            request.put("model", model);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", "You identify inserted advertisement breaks in timestamped podcast transcripts. Return only likely ad ranges. Use exact timestamp spans and do not mark the real show as an ad."));
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", "Find ad breaks in these transcript segments and return JSON only with an ad_ranges array. Each item must contain start, end, confidence, and reason. Return an empty array if there are no ads.\n\n" + payloadSegments.toString()));
            request.put("messages", messages);
            request.put("temperature", 0);
            request.put("response_format", new JSONObject().put("type", "json_object"));

            HttpURLConnection connection = (HttpURLConnection) new URL("https://api.openai.com/v1/chat/completions").openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(180000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }

            String body = readConnectionBody(connection);
            if (connection.getResponseCode() >= 400) {
                throw new RuntimeException("OpenAI ad detection failed: " + body);
            }

            JSONObject response = new JSONObject(body);
            String content = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "{}");
            JSONObject parsed = new JSONObject(content);
            JSONArray ranges = parsed.optJSONArray("ad_ranges");
            if (ranges == null) continue;
            for (int i = 0; i < ranges.length(); i++) {
                JSONObject item = ranges.optJSONObject(i);
                if (item == null) continue;
                double start = item.optDouble("start", -1);
                double end = item.optDouble("end", -1);
                if (start < 0 || end <= start) continue;
                TranscriptAdRange range = new TranscriptAdRange();
                range.start = start;
                range.end = end;
                range.confidence = clampDouble(item.optDouble("confidence", 0.6), 0.0, 1.0);
                range.reason = item.optString("reason", "OpenAI ad detection");
                out.add(range);
            }
        }
        return out;
    }

    // ── Local ad detection (keyword pattern matching) ──────────────────────────

    private ArrayList<TranscriptAdRange> detectAdRangesLocally(ArrayList<TranscriptSegment> segments) {
        String[] adPhrases = {
            "sponsored by", "brought to you by", "this episode is brought",
            "today's sponsor", "our sponsor", "presenting sponsor",
            "advertisement", "advertising message", "a word from",
            "promo code", "use code", "use promo", "coupon code",
            "discount code", "affiliate code", "special offer",
            "limited time", "free trial", "sign up for free",
            "go to ", ".com/", "slash podcast", "link in the description",
            "squarespace", "betterhelp", "nordvpn", "expressvpn",
            "audible", "hellofresh", "bluechew", "hims ", "roman ",
            "support our show", "patreon.com",
            "this show is supported", "this podcast is supported"
        };
        ArrayList<TranscriptSegment> adSegs = new ArrayList<>();
        for (TranscriptSegment seg : segments) {
            String lower = seg.text.toLowerCase(Locale.US);
            for (String phrase : adPhrases) {
                if (lower.contains(phrase)) { adSegs.add(seg); break; }
            }
        }
        ArrayList<TranscriptAdRange> out = new ArrayList<>();
        if (adSegs.isEmpty()) return out;
        double start = adSegs.get(0).start;
        double end   = adSegs.get(0).end;
        for (int i = 1; i < adSegs.size(); i++) {
            TranscriptSegment seg = adSegs.get(i);
            if (seg.start - end < 120.0) {
                end = Math.max(end, seg.end);
            } else {
                TranscriptAdRange r = new TranscriptAdRange();
                r.start = start; r.end = end; r.confidence = 0.7; r.reason = "Keyword match";
                out.add(r);
                start = seg.start; end = seg.end;
            }
        }
        TranscriptAdRange last = new TranscriptAdRange();
        last.start = start; last.end = end; last.confidence = 0.7; last.reason = "Keyword match";
        out.add(last);
        return out;
    }

    // ── Vosk local transcription ───────────────────────────────────────────────

    private ArrayList<TranscriptSegment> transcribeChunksWithVosk(ArrayList<File> chunks) throws Exception {
        File modelDir = new File(getFilesDir(), "vosk-model-en");
        if (!new File(modelDir, "am/final.mdl").exists()) {
            postUi(() -> setStatus("Downloading Vosk speech model (~40 MB, once only)..."));
            downloadAndExtractVoskModel(modelDir);
        }
        org.vosk.Model voskModel = new org.vosk.Model(modelDir.getAbsolutePath());
        ArrayList<TranscriptSegment> all = new ArrayList<>();
        double chunkOffset = 0.0;
        try {
            for (int i = 0; i < chunks.size(); i++) {
                if (adCancelled.get()) throw new AdRemovalCancelledException();
                File chunk = chunks.get(i);
                final int ci = i + 1;
                final int total = chunks.size();
                adCurrentStatus = "Transcribing chunk " + ci + "/" + total + " (Vosk)...";
                postUi(() -> refreshAdBusyPanel()); updateAdNotification();
                postUi(() -> setStatus("Transcribing chunk " + ci + " of " + total + " with Vosk..."));
                org.vosk.Recognizer rec = new org.vosk.Recognizer(voskModel, 16000.0f);
                rec.setWords(true);
                int segsBefore = all.size();
                try {
                    short[] pcm = decodeToPcm16000(chunk);
                    byte[] bytes = shortsToBytes(pcm);
                    ArrayList<String> utterances = new ArrayList<>();
                    int pos = 0;
                    while (pos < bytes.length) {
                        int len = Math.min(8192, bytes.length - pos);
                        byte[] window = new byte[len];
                        System.arraycopy(bytes, pos, window, 0, len);
                        if (rec.acceptWaveForm(window, len)) {
                            utterances.add(rec.getResult());
                        }
                        pos += len;
                    }
                    utterances.add(rec.getFinalResult());
                    for (String json : utterances) {
                        parseVoskResult(json, chunkOffset, all);
                    }
                } finally {
                    rec.close();
                }
                int newSegs = all.size() - segsBefore;
                addCallLog("VOSK CHUNK " + ci + "/" + total, chunk.getName(), "", 0,
                    "offset=" + String.format(Locale.US, "%.1f", chunkOffset) + "s",
                    newSegs + " segments transcribed", 0);
                chunkOffset += audioDurationSeconds(chunk, LOCAL_CHUNK_SECONDS);
            }
        } finally {
            voskModel.close();
        }
        return all;
    }

    private void parseVoskResult(String json, double chunkOffset, ArrayList<TranscriptSegment> out) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray words = obj.optJSONArray("result");
            if (words == null || words.length() == 0) return;
            double segStart = -1, segEnd = -1;
            StringBuilder segText = new StringBuilder();
            for (int i = 0; i < words.length(); i++) {
                JSONObject word = words.getJSONObject(i);
                double ws = word.optDouble("start", 0);
                double we = word.optDouble("end", 0);
                String wt = word.optString("word", "").trim();
                if (TextUtils.isEmpty(wt)) continue;
                if (segStart < 0) {
                    segStart = ws; segEnd = we; segText.append(wt);
                } else if (ws - segStart > 12.0) {
                    addVoskSegment(chunkOffset, segStart, segEnd, segText.toString(), out);
                    segStart = ws; segEnd = we; segText = new StringBuilder(wt);
                } else {
                    segEnd = we; segText.append(" ").append(wt);
                }
            }
            if (segStart >= 0) addVoskSegment(chunkOffset, segStart, segEnd, segText.toString(), out);
        } catch (Exception ignored) {}
    }

    private void addVoskSegment(double chunkOffset, double start, double end, String text, ArrayList<TranscriptSegment> out) {
        String t = text.trim();
        if (TextUtils.isEmpty(t)) return;
        TranscriptSegment seg = new TranscriptSegment();
        seg.index = out.size();
        seg.start = chunkOffset + start;
        seg.end   = chunkOffset + end;
        seg.text  = t;
        out.add(seg);
    }

    private void downloadAndExtractVoskModel(File modelDir) throws Exception {
        String url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
        File zipFile = new File(getCacheDir(), "vosk-model.zip");
        downloadFileWithProgress(url, zipFile);
        postUi(() -> setStatus("Extracting Vosk model..."));
        File extractRoot = new File(getCacheDir(), "vosk-extract-tmp");
        deleteRecursively(extractRoot);
        extractRoot.mkdirs();
        extractZip(zipFile, extractRoot);
        zipFile.delete();
        File[] extracted = extractRoot.listFiles();
        File sourceDir = (extracted != null && extracted.length == 1 && extracted[0].isDirectory())
                ? extracted[0] : extractRoot;
        deleteRecursively(modelDir);
        if (!sourceDir.renameTo(modelDir)) {
            copyDir(sourceDir, modelDir);
            deleteRecursively(extractRoot);
        }
    }

    private void extractZip(File zipFile, File destDir) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            byte[] buf = new byte[65536];
            while ((entry = zis.getNextEntry()) != null) {
                File target = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    target.mkdirs();
                } else {
                    target.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(target)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void copyDir(File src, File dst) throws Exception {
        dst.mkdirs();
        File[] files = src.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) copyDir(f, new File(dst, f.getName()));
            else copyFile(f, new File(dst, f.getName()));
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    // ── Whisper.cpp local transcription ───────────────────────────────────────

    private ArrayList<TranscriptSegment> transcribeChunksWithWhisperCpp(ArrayList<File> chunks) throws Exception {
        if (!WhisperEngine.isAvailable()) {
            throw new RuntimeException("Whisper native library not available. The app must be compiled with NDK support (externalNativeBuild). Switch to Vosk in Settings or rebuild with NDK.");
        }
        File modelFile = new File(getFilesDir(), "whisper/ggml-base.en.bin");
        if (!modelFile.exists()) {
            postUi(() -> setStatus("Downloading Whisper base model (~142 MB, once only)..."));
            downloadWhisperModel(modelFile);
        }
        if (!WhisperEngine.nativeInit(modelFile.getAbsolutePath())) {
            throw new RuntimeException("Failed to load Whisper model from " + modelFile.getAbsolutePath());
        }
        ArrayList<TranscriptSegment> all = new ArrayList<>();
        double chunkOffset = 0.0;
        try {
            for (int i = 0; i < chunks.size(); i++) {
                if (adCancelled.get()) throw new AdRemovalCancelledException();
                File chunk = chunks.get(i);
                final int ci = i + 1;
                final int total = chunks.size();
                adCurrentStatus = "Transcribing chunk " + ci + "/" + total + " (Whisper)...";
                postUi(() -> refreshAdBusyPanel()); updateAdNotification();
                postUi(() -> setStatus("Transcribing chunk " + ci + " of " + total + " with Whisper..."));
                int segsBefore = all.size();
                short[] pcm = decodeToPcm16000(chunk);
                float[] floats = shortsToFloats(pcm);
                String json = WhisperEngine.nativeTranscribe(floats, chunkOffset);
                parseWhisperResult(json, all);
                int newSegs = all.size() - segsBefore;
                addCallLog("WHISPER CHUNK " + ci + "/" + total, chunk.getName(), "", 0,
                    "offset=" + String.format(Locale.US, "%.1f", chunkOffset) + "s",
                    newSegs + " segments transcribed", 0);
                chunkOffset += audioDurationSeconds(chunk, LOCAL_CHUNK_SECONDS);
            }
        } finally {
            WhisperEngine.nativeFree();
        }
        return all;
    }

    private void parseWhisperResult(String json, ArrayList<TranscriptSegment> out) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String text = obj.optString("text", "").trim();
                double start = obj.optDouble("start", -1);
                double end   = obj.optDouble("end",   -1);
                if (TextUtils.isEmpty(text) || start < 0 || end <= start) continue;
                TranscriptSegment seg = new TranscriptSegment();
                seg.index = out.size();
                seg.start = start; seg.end = end; seg.text = text;
                out.add(seg);
            }
        } catch (Exception ignored) {}
    }

    private void downloadWhisperModel(File modelFile) throws Exception {
        modelFile.getParentFile().mkdirs();
        File tmp = new File(getCacheDir(), "ggml-model.bin.tmp");
        downloadFileWithProgress("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin", tmp);
        if (!tmp.renameTo(modelFile)) {
            copyFile(tmp, modelFile);
            tmp.delete();
        }
    }

    // ── Shared: model download helper ────────────────────────────────────────

    private void downloadFileWithProgress(String urlStr, File dest) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(600000);
        conn.setInstanceFollowRedirects(true);
        long total = conn.getContentLengthLong();
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            long downloaded = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                downloaded += n;
                if (total > 0) {
                    long pct = downloaded * 100 / total;
                    postUi(() -> setStatus("Downloading model... " + pct + "%"));
                }
            }
        }
    }

    // ── Shared: PCM audio decoding ───────────────────────────────────────────

    private short[] decodeToPcm16000(File audioFile) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(audioFile.getAbsolutePath());
        int audioTrack = -1;
        MediaFormat trackFormat = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat fmt = extractor.getTrackFormat(i);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrack = i; trackFormat = fmt; break;
            }
        }
        if (audioTrack < 0) throw new RuntimeException("No audio track in " + audioFile.getName());
        extractor.selectTrack(audioTrack);

        final int[] srcRate  = {trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)};
        final int[] channels = {trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)};
        String mime = trackFormat.getString(MediaFormat.KEY_MIME);

        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(trackFormat, null, null, 0);
        codec.start();

        // Collect per-buffer mono chunks as we decode to avoid one giant ByteArrayOutputStream.
        ArrayList<short[]> monoChunks = new ArrayList<>();
        int totalMonoSamples = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;

        while (true) {
            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(10000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                    int size = extractor.readSampleData(inBuf, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            int outIdx = codec.dequeueOutputBuffer(info, 10000);
            if (outIdx >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    codec.releaseOutputBuffer(outIdx, false);
                    break;
                }
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                if (outBuf != null && info.size > 0) {
                    // Convert to shorts and downmix to mono immediately — no large byte buffer.
                    short[] buf = new short[info.size / 2];
                    outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(buf);
                    short[] mono;
                    if (channels[0] > 1) {
                        mono = new short[buf.length / channels[0]];
                        for (int i = 0; i < mono.length; i++) {
                            long sum = 0;
                            for (int c = 0; c < channels[0]; c++) sum += buf[i * channels[0] + c];
                            mono[i] = (short)(sum / channels[0]);
                        }
                    } else {
                        mono = buf;
                    }
                    monoChunks.add(mono);
                    totalMonoSamples += mono.length;
                }
                codec.releaseOutputBuffer(outIdx, false);
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outFmt = codec.getOutputFormat();
                if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                    srcRate[0]  = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    channels[0] = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            }
        }
        codec.stop();
        codec.release();
        extractor.release();

        // Combine mono chunks into one array, then free the list before resampling.
        short[] combined = new short[totalMonoSamples];
        int pos = 0;
        for (short[] chunk : monoChunks) { System.arraycopy(chunk, 0, combined, pos, chunk.length); pos += chunk.length; }
        monoChunks.clear();

        // Resample to 16000 Hz
        if (srcRate[0] != 16000) {
            combined = resampleLinear(combined, srcRate[0], 16000);
        }
        return combined;
    }

    private short[] resampleLinear(short[] input, int srcRate, int dstRate) {
        if (srcRate == dstRate) return input;
        int outLen = (int)((long)input.length * dstRate / srcRate);
        short[] out = new short[outLen];
        double step = (double)srcRate / dstRate;
        for (int i = 0; i < outLen; i++) {
            double pos  = i * step;
            int idx     = (int)pos;
            double frac = pos - idx;
            short a = input[Math.min(idx,     input.length - 1)];
            short b = input[Math.min(idx + 1, input.length - 1)];
            out[i] = (short)(a + frac * (b - a));
        }
        return out;
    }

    private byte[] shortsToBytes(short[] shorts) {
        ByteBuffer buf = ByteBuffer.allocate(shorts.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buf.asShortBuffer().put(shorts);
        return buf.array();
    }

    private float[] shortsToFloats(short[] shorts) {
        float[] out = new float[shorts.length];
        for (int i = 0; i < shorts.length; i++) out[i] = shorts[i] / 32768.0f;
        return out;
    }

    private ArrayList<TranscriptAdRange> mergeRanges(ArrayList<TranscriptAdRange> ranges, double bridgeGapSeconds, double minSeconds) {
        ArrayList<TranscriptAdRange> ordered = new ArrayList<>();
        for (TranscriptAdRange range : ranges) {
            if (range.end > range.start) ordered.add(range);
        }
        Collections.sort(ordered, (left, right) -> Double.compare(left.start, right.start));
        ArrayList<TranscriptAdRange> out = new ArrayList<>();
        for (TranscriptAdRange item : ordered) {
            if (out.isEmpty()) {
                out.add(item);
                continue;
            }
            TranscriptAdRange current = out.get(out.size() - 1);
            if (item.start <= current.end + bridgeGapSeconds) {
                current.end = Math.max(current.end, item.end);
                current.confidence = Math.max(current.confidence, item.confidence);
                if (!TextUtils.isEmpty(item.reason)) current.reason = current.reason + "; " + item.reason;
            } else {
                out.add(item);
            }
        }
        ArrayList<TranscriptAdRange> filtered = new ArrayList<>();
        for (TranscriptAdRange item : out) {
            if (item.end - item.start >= minSeconds) filtered.add(item);
        }
        return filtered;
    }

    private void renderAdFreeAudio(File source, File output, ArrayList<TranscriptAdRange> adRanges, double durationSeconds) {
        ArrayList<double[]> keepRanges = invertRanges(adRanges, durationSeconds);
        if (keepRanges.isEmpty()) throw new RuntimeException("No keepable audio remained after ad removal.");
        exportRangesComposition(source, keepRanges, output);
    }

    private ArrayList<double[]> invertRanges(ArrayList<TranscriptAdRange> adRanges, double durationSeconds) {
        ArrayList<double[]> keep = new ArrayList<>();
        double cursor = 0.0;
        for (TranscriptAdRange range : adRanges) {
            double start = clampDouble(range.start - AD_PADDING_BEFORE_SECONDS, 0.0, durationSeconds);
            double end = clampDouble(range.end + AD_PADDING_AFTER_SECONDS, 0.0, durationSeconds);
            if (start - cursor >= MIN_KEEP_RANGE_SECONDS) keep.add(new double[]{cursor, start});
            cursor = Math.max(cursor, end);
        }
        if (durationSeconds - cursor >= MIN_KEEP_RANGE_SECONDS) keep.add(new double[]{cursor, durationSeconds});
        return keep;
    }

    private File buildProcessedAudioPath(File source) {
        String name = source.getName();
        int dot = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        while (stem.endsWith(".noads") || stem.endsWith(".adfree")) {
            if (stem.endsWith(".noads")) stem = stem.substring(0, stem.length() - ".noads".length());
            else stem = stem.substring(0, stem.length() - ".adfree".length());
        }
        return new File(source.getParentFile(), stem + ".noads.m4a");
    }

    private File buildProcessedStagingPath(File source) {
        String name = buildProcessedAudioPath(source).getName();
        if (name.endsWith(".m4a")) {
            name = name.substring(0, name.length() - 4) + ".render.m4a";
        } else {
            name = name + ".render";
        }
        return new File(source.getParentFile(), name);
    }

    private File finalizeProcessedAudioOutput(File source, File finalOutput, File renderTarget) throws Exception {
        if (!renderTarget.getAbsolutePath().equals(finalOutput.getAbsolutePath())) {
            if (finalOutput.exists() && !finalOutput.delete()) {
                throw new RuntimeException("Could not replace the existing ad-free file.");
            }
            copyFile(renderTarget, finalOutput);
            if (!renderTarget.delete()) {
                throw new RuntimeException("Could not remove the temporary ad-free file.");
            }
        }
        if (!source.getAbsolutePath().equals(finalOutput.getAbsolutePath())) {
            source.delete();
        }
        return finalOutput;
    }

    private void exportSingleRange(File source, double startSeconds, double endSeconds, File output) {
        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(Uri.fromFile(source))
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs((long) (startSeconds * 1000.0))
                        .setEndPositionMs((long) (endSeconds * 1000.0))
                        .build())
                .build();
        EditedMediaItem edited = new EditedMediaItem.Builder(mediaItem).build();
        exportEditedItems(Collections.singletonList(edited), output);
    }

    private void exportRangesComposition(File source, ArrayList<double[]> ranges, File output) {
        ArrayList<EditedMediaItem> items = new ArrayList<>();
        for (double[] keep : ranges) {
            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(Uri.fromFile(source))
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs((long) (keep[0] * 1000.0))
                            .setEndPositionMs((long) (keep[1] * 1000.0))
                            .build())
                    .build();
            items.add(new EditedMediaItem.Builder(mediaItem).build());
        }
        exportEditedItems(items, output);
    }

    private void exportEditedItems(List<EditedMediaItem> items, File output) {
        if (output.exists()) output.delete();
        final RuntimeException[] errorHolder = new RuntimeException[1];
        final CountDownLatch latch = new CountDownLatch(1);
        // Transformer requires a Looper thread; post creation and start to the main thread,
        // then block the background worker until export finishes.
        main.post(() -> {
            try {
                DefaultDecoderFactory decoderFactory = new DefaultDecoderFactory.Builder(this)
                        .setEnableDecoderFallback(true)
                        .build();
                Transformer transformer = new Transformer.Builder(this)
                    .setAssetLoaderFactory(new DefaultAssetLoaderFactory(this, decoderFactory, Clock.DEFAULT))
                        .addListener(new Transformer.Listener() {
                            @Override
                            public void onCompleted(Composition composition, ExportResult exportResult) {
                                latch.countDown();
                            }
                            @Override
                            public void onError(Composition composition, ExportResult exportResult, ExportException exportException) {
                                errorHolder[0] = new RuntimeException(exportException);
                                latch.countDown();
                            }
                        })
                        .build();
                Composition composition = new Composition.Builder(new EditedMediaItemSequence(items)).build();
                transformer.start(composition, output.getAbsolutePath());
            } catch (RuntimeException e) {
                errorHolder[0] = e;
                latch.countDown();
            }
        });
        try {
            boolean finished = latch.await(30, TimeUnit.MINUTES);
            if (!finished) {
                throw new RuntimeException("Media export timed out after 30 minutes.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Media export interrupted.", e);
        }
        if (errorHolder[0] != null) throw errorHolder[0];
        if (!output.exists()) throw new RuntimeException("Media export did not create an output file.");
    }

    private double audioDurationSeconds(File file, double fallbackSeconds) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (!TextUtils.isEmpty(duration)) return Math.max(1.0, Double.parseDouble(duration) / 1000.0);
        } catch (Exception ignored) {
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
        return fallbackSeconds;
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

    private void writeMultipartField(DataOutputStream output, String boundary, String name, String value) throws Exception {
        output.writeBytes("--" + boundary + "\r\n");
        output.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.writeBytes("\r\n");
    }

    private void writeMultipartFile(DataOutputStream output, String boundary, String name, File file, String mimeType) throws Exception {
        output.writeBytes("--" + boundary + "\r\n");
        output.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + file.getName() + "\"\r\n");
        output.writeBytes("Content-Type: " + mimeType + "\r\n\r\n");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
        output.writeBytes("\r\n");
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
                    else if ("pubDate".equals(tag)) cur.pubDate = text.trim();
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
        b.setTextSize(14);
        b.setMinHeight(dp(44));
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setBackground(rounded(bg, dp(14), stroke));
        b.setOnClickListener(v -> {
            try { l.onClick(v); } catch (Exception e) { showError("Something went wrong", "The app recovered from an unexpected action error."); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, dp(4), dp(8), dp(4));
        b.setLayoutParams(lp);
        return b;
    }
    private Button navButton(String s, View.OnClickListener l) {
        Button b = styledButton(s, l, SOFT_BLUE, BROWN, 0);
        b.setMinHeight(dp(38));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
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
    private void section(String s) { TextView t = text(s, 19, INK, true); t.setPadding(0, dp(18), 0, dp(6)); root.addView(t); }
    // ── Notification channels ────────────────────────────────────────────────
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
            tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
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
            tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
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
            tap.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 1, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, NOTIF_CH_PLAY)
                : new Notification.Builder(this);
            b.setSmallIcon(playing ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause)
             .setContentTitle(currentEpisode.title)
             .setContentText(playing ? "Playing — tap to open" : "Paused — tap to open")
             .setOngoing(playing)
             .setContentIntent(pi);
            notifMgr.notify(NOTIF_ID_PLAY, b.build());
        } catch (Exception ignored) {}
    }

    // ── Ad removal queue ─────────────────────────────────────────────────────
    private void queueAdRemoval(Episode e, boolean forceRefresh) {
        if (forceRefresh) deleteTranscriptCache(e.id);
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

    // ── Transcript cache ─────────────────────────────────────────────────────
    private File transcriptCacheFile(String episodeId, String engine) {
        return new File(getFilesDir(), "transcripts/" + episodeId + "." + engine + ".json");
    }

    private void saveTranscriptCache(File f, ArrayList<TranscriptSegment> segs) {
        try {
            f.getParentFile().mkdirs();
            JSONArray arr = new JSONArray();
            for (TranscriptSegment s : segs) {
                JSONObject o = new JSONObject();
                o.put("i", s.index); o.put("s", s.start); o.put("e", s.end); o.put("t", s.text);
                arr.put(o);
            }
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {}
    }

    private ArrayList<TranscriptSegment> loadTranscriptCache(File f) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            }
            JSONArray arr = new JSONArray(bos.toString("UTF-8"));
            ArrayList<TranscriptSegment> segs = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                TranscriptSegment s = new TranscriptSegment();
                s.index = o.optInt("i"); s.start = o.optDouble("s"); s.end = o.optDouble("e"); s.text = o.optString("t");
                segs.add(s);
            }
            return segs;
        } catch (Exception e) { return null; }
    }

    private void deleteTranscriptCache(String episodeId) {
        for (String eng : new String[]{ENGINE_VOSK, ENGINE_WHISPER, ENGINE_OPENAI}) {
            transcriptCacheFile(episodeId, eng).delete();
        }
    }

    // ── Time estimation ──────────────────────────────────────────────────────
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

    private void setStatus(String s) { if (status != null) status.setText(s); }
    private void setWorking(boolean working, String message) {
        if (busyPanel != null) busyPanel.setVisibility((working || adRunning) ? View.VISIBLE : View.GONE);
        if (!TextUtils.isEmpty(message)) {
            setStatus(message);
            if (busyText != null) busyText.setText(message);
        }
        if (!working && !adRunning && notifMgr != null) notifMgr.cancel(NOTIF_ID_REMOVAL);
    }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    private void hideKeyboard(View v) { ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0); }
    private String indicators(Episode e) { ArrayList<String> a = new ArrayList<>(); if (e.isNew) a.add("New"); if (e.isListened) a.add("Listened"); else if (e.positionSeconds > 0) a.add("In progress"); if (e.downloaded()) a.add("Saved"); if ("processed".equals(e.adSupportedStatus)) a.add("Ad free"); else if ("no_ads_found".equals(e.adSupportedStatus)) a.add("No ads found"); else if ("supported".equals(e.adSupportedStatus)) a.add("AD"); return TextUtils.join("  |  ", a); }
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
    private String friendly(String iso) { return TextUtils.isEmpty(iso) ? "Never" : iso.replace("T", " ").replace("Z", " UTC"); }
    private void confirm(String msg, Runnable ok) { new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Yes", (d,w)->ok.run()).setNegativeButton("No", null).show(); }
    private void legalDialog() { new AlertDialog.Builder(this).setTitle("About LocalPod").setMessage("This app is an independent podcast player. It streams and downloads from official public RSS feed URLs selected by the user. It does not rehost, resell, alter, or claim ownership of podcast content.").setPositiveButton("OK", null).show(); }
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
        try { r.run(); } catch (Exception e) { showError("Something went wrong", "The app recovered instead of closing. Try the action again, or open Search and paste a direct RSS URL."); }
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
            actions.addView(button("Search", v -> safeScreen(() -> showSearch())));
            actions.addView(secondaryButton("Home", v -> safeScreen(() -> showHome())));
            card.addView(actions);
            root.addView(card);
        } catch (Exception ignored) {}
    }
    private int playerDurationSec() {
        try { if (player != null) return Math.max(1, player.getDuration() / 1000); } catch (Exception ignored) {}
        return Math.max(1, currentEpisode == null ? 1 : currentEpisode.durationSeconds);
    }
    private int playerPositionSec() {
        try { if (player != null) return Math.max(0, player.getCurrentPosition() / 1000); } catch (Exception ignored) {}
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

    // ── whisper.cpp JNI wrapper ───────────────────────────────────────────────
    // The native library is compiled via CMake (see app/src/main/cpp/).
    // If NDK is not installed or the build did not include native libs, isAvailable()
    // returns false and the Whisper engine option shows a message in Settings.
    static class WhisperEngine {
        private static Boolean sLoaded = null;
        static boolean isAvailable() {
            if (sLoaded == null) {
                try { System.loadLibrary("whisper_jni"); sLoaded = true; }
                catch (UnsatisfiedLinkError e) { sLoaded = false; }
            }
            return Boolean.TRUE.equals(sLoaded);
        }
        static native boolean nativeInit(String modelPath);
        static native String  nativeTranscribe(float[] samples, double offsetSeconds);
        static native void    nativeFree();
    }

    static class AdRemovalCancelledException extends Exception {
        AdRemovalCancelledException() { super(); }
    }
    static class TranscriptSegment {
        int index;
        double start;
        double end;
        String text;
    }
    static class TranscriptAdRange {
        double start;
        double end;
        double confidence;
        String reason;
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
            db.execSQL("CREATE TABLE IF NOT EXISTS ad_removal_timing (id INTEGER PRIMARY KEY AUTOINCREMENT, engine TEXT NOT NULL, audio_length_seconds INTEGER NOT NULL, transcription_seconds INTEGER NOT NULL, detection_seconds INTEGER NOT NULL, render_seconds INTEGER NOT NULL, total_seconds INTEGER NOT NULL, created_at TEXT NOT NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
        String now() { SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.format(new Date()); }
        String setting(String key, String def) { Cursor c = getReadableDatabase().rawQuery("SELECT value FROM settings WHERE key=?", new String[]{key}); try { return c.moveToFirst() ? c.getString(0) : def; } finally { c.close(); } }
        void setSetting(String key, String val) { ContentValues v = new ContentValues(); v.put("key", key); v.put("value", TextUtils.isEmpty(val) ? "0" : val); v.put("updated_at", now()); getWritableDatabase().insertWithOnConflict("settings", null, v, SQLiteDatabase.CONFLICT_REPLACE); }
        void cacheDirectory(Podcast p, String query, long t) {
            ContentValues v = new ContentValues(); v.put("id", p.id); v.put("query", query); v.put("source", p.source); v.put("source_id", p.sourceId); v.put("title", p.title); v.put("publisher", p.publisher); v.put("feed_url", p.feedUrl); v.put("directory_url", p.directoryUrl); v.put("description", p.description); v.put("artwork_url", p.artworkUrl); v.put("cached_at", now()); v.put("expires_at", String.valueOf(t + 30L*24*3600*1000)); getWritableDatabase().insertWithOnConflict("directory_cache", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
        ArrayList<Podcast> cachedDirectory() { ArrayList<Podcast> out = new ArrayList<>(); Cursor c = getReadableDatabase().rawQuery("SELECT id,source,source_id,title,publisher,feed_url,directory_url,description,artwork_url FROM directory_cache WHERE CAST(expires_at AS INTEGER)>? ORDER BY cached_at DESC LIMIT 50", new String[]{String.valueOf(System.currentTimeMillis())}); try { while(c.moveToNext()) out.add(podcastFromCache(c)); } finally { c.close(); } return out; }
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
        ArrayList<Podcast> subscriptions() { ArrayList<Podcast> out = new ArrayList<>(); Cursor c = getReadableDatabase().rawQuery("SELECT id,source,source_id,title,publisher,description,feed_url,directory_url,website_url,artwork_url,subscribed,last_feed_refresh_at FROM podcasts WHERE subscribed=1 ORDER BY title", null); try { while(c.moveToNext()) out.add(podcast(c)); } finally { c.close(); } return out; }
        Podcast podcast(Cursor c) { Podcast p = new Podcast(); p.id=c.getString(0); p.source=c.getString(1); p.sourceId=c.getString(2); p.title=c.getString(3); p.publisher=c.getString(4); p.description=c.getString(5); p.feedUrl=c.getString(6); p.directoryUrl=c.getString(7); p.websiteUrl=c.getString(8); p.artworkUrl=c.getString(9); p.subscribed=c.getInt(10)==1; p.lastFeedRefreshAt=c.getString(11); return p; }
        void setSubscribed(String id, boolean sub) { ContentValues v = new ContentValues(); v.put("subscribed", sub?1:0); v.put("subscribed_at", sub?now():null); getWritableDatabase().update("podcasts", v, "id=?", new String[]{id}); }
        ArrayList<Episode> episodesForPodcast(String pid, String filter, int limit) { String where="e.podcast_id=?"; ArrayList<String> args = new ArrayList<>(); args.add(pid); if ("New".equals(filter)) where+=" AND e.is_new=1"; else if ("Downloaded".equals(filter)) where+=" AND e.download_status='downloaded'"; else if ("Not downloaded".equals(filter)) where+=" AND e.download_status!='downloaded'"; else if ("Listened".equals(filter)) where+=" AND e.is_listened=1"; else if ("Unlistened".equals(filter)) where+=" AND e.is_listened=0"; else if ("In progress".equals(filter)) where+=" AND e.id IN (SELECT episode_id FROM playback_progress WHERE position_seconds>0 AND percent_complete<95)"; return episodes(where, args.toArray(new String[0]), limit); }
        ArrayList<Episode> episodesByStatus(String status, int limit) { if ("downloaded".equals(status)) return episodes("e.download_status='downloaded'", null, limit); return episodes("e.id IN (SELECT episode_id FROM playback_progress WHERE position_seconds>0 AND percent_complete<95)", null, limit); }
        ArrayList<Episode> episodes(String where, String[] args, int limit) { ArrayList<Episode> out = new ArrayList<>(); Cursor c = getReadableDatabase().rawQuery("SELECT e.id,e.podcast_id,e.guid,e.title,e.description,e.pub_date,e.duration_seconds,e.enclosure_url,e.enclosure_type,e.enclosure_length,e.local_file_path,e.download_status,e.is_new,e.is_listened,e.ad_supported_status,COALESCE(p.position_seconds,0),COALESCE(p.duration_seconds,e.duration_seconds) FROM episodes e LEFT JOIN playback_progress p ON p.episode_id=e.id WHERE "+where+" ORDER BY e.pub_date DESC LIMIT "+limit, args); try { while(c.moveToNext()) out.add(episode(c)); } finally { c.close(); } return out; }
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
        void clearDownloads(boolean listenedOnly, boolean all) { String where = all ? "e.download_status='downloaded'" : listenedOnly ? "e.download_status='downloaded' AND e.is_listened=1" : "e.download_status='downloaded' AND CAST(e.download_expires_at AS INTEGER)<?"; String[] args = all||listenedOnly ? null : new String[]{String.valueOf(System.currentTimeMillis())}; for (Episode e : episodes(where, args, 10000)) { if (!TextUtils.isEmpty(e.localFilePath)) new File(e.localFilePath).delete(); removeDownload(e.id); } }
        int countNew(String pid) { return count("episodes", "podcast_id=? AND is_new=1", new String[]{pid}); }
        int countDownloaded(String pid) { return count("episodes", "podcast_id=? AND download_status IN ('downloaded','processed')", new String[]{pid}); }
        int count(String table, String where, String[] args) { Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table+" WHERE "+where, args); try { c.moveToFirst(); return c.getInt(0); } finally { c.close(); } }
        String podcastTitle(String pid) { Cursor c = getReadableDatabase().rawQuery("SELECT title FROM podcasts WHERE id=?", new String[]{pid}); try { return c.moveToFirst()?c.getString(0):""; } finally { c.close(); } }
        Episode lastPlayed() { Cursor c = getReadableDatabase().rawQuery("SELECT episode_id FROM playback_progress WHERE position_seconds>0 ORDER BY last_played_at DESC LIMIT 1", null); try { return c.moveToFirst()?episode(c.getString(0)):null; } finally { c.close(); } }
        Episode nextEpisode(Episode cur) { Cursor c = getReadableDatabase().rawQuery("SELECT id FROM episodes WHERE podcast_id=? AND pub_date < ? ORDER BY pub_date DESC LIMIT 1", new String[]{cur.podcastId, cur.pubDate == null ? "" : cur.pubDate}); try { return c.moveToFirst()?episode(c.getString(0)):null; } finally { c.close(); } }
    }
}
