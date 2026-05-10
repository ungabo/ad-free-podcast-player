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
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

public class MainActivity extends Activity {
    private static final int BG = 0xfff4f7fb;
    private static final int SURFACE = 0xffffffff;
    private static final int BLUE = 0xff1769aa;
    private static final int TEAL = 0xff0f766e;
    private static final int CORAL = 0xffd94f70;
    private static final int INK = 0xff202124;
    private static final int MUTED = 0xff68707a;
    private static final int LINE = 0xffdce3ea;
    private static final int SOFT_BLUE = 0xffeaf3ff;
    private static final int SOFT_GREEN = 0xffeaf8f2;
    private static final int SOFT_RED = 0xffffedf1;
    private static final int OPENAI_CHUNK_SECONDS = 12 * 60;
    private static final double AD_PADDING_BEFORE_SECONDS = 4.0;
    private static final double AD_PADDING_AFTER_SECONDS = 4.0;
    private static final double MIN_KEEP_RANGE_SECONDS = 0.75;
    private static final String SAMPLE_FEED_URL = "https://www.omnycontent.com/d/playlist/e73c998e-6e60-432f-8610-ae210140c5b1/2e824128-fbd5-4c9e-9a57-ae2f0056b0c4/66d98a23-900c-44b0-a40b-ae2f0056b0db/podcast.rss";
    private static final String[] FILTERS = {"All", "New", "In progress", "Downloaded", "Not downloaded", "Listened", "Unlistened"};

    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private Db db;
    private LinearLayout root;
    private TextView status;
    private MediaPlayer player;
    private Episode currentEpisode;
    private boolean userSeeking;
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
        renderMiniPlayer(true);
    }

    private void showHome() {
        base("Ad Free Podcast Player");
        setStatus("Independent, local-only podcast player. No account, backend, sync, background polling, or thumbnails.");
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
        if (e.downloaded()) actions.addView(secondaryButton("processed".equals(e.adSupportedStatus) ? "Refresh ad-free" : "Remove ads", v -> removeAds(e)));
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
        actions.addView(secondaryButton("processed".equals(e.adSupportedStatus) ? "Refresh ad-free" : "Remove ads", v -> removeAds(e)));
        root.addView(actions);
    }

    private void showSettings() {
        base("Settings");
        CheckBox autoplay = new CheckBox(this);
        autoplay.setText("Autoplay next");
        autoplay.setChecked(settingBool("autoplay_next", false));
        autoplay.setOnCheckedChangeListener((b, checked) -> db.setSetting("autoplay_next", checked ? "1" : "0"));
        root.addView(autoplay);
        section("Ad Removal Defaults");
        root.addView(text("Windows defaults to Parakeet with Whisper fallback. Android will use OpenAI transcription and optional OpenAI ad analysis when a key is saved.", 14, MUTED, false));
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
        value.setText(db.setting(key, def));
        wrapper.addView(value);
        wrapper.addView(button("Save", v -> db.setSetting(key, value.getText().toString().trim())));
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
            });
            player.setOnCompletionListener(mp -> {
                db.markComplete(currentEpisode.id, mp.getDuration() / 1000);
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
            else if (player.isPlaying()) { player.pause(); saveCurrentProgress(); safeScreen(() -> showHome()); }
            else { player.start(); safeScreen(() -> showHome()); }
        }));
        controls.addView(secondaryButton("Stop", v -> { if (player != null) player.pause(); saveCurrentProgress(); }));
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
        String openAiKey = db.setting("openai_api_key", "");
        if (TextUtils.isEmpty(openAiKey)) {
            showError("OpenAI key required", "Save an OpenAI API key in Settings before running ad removal on Android.");
            return;
        }
        if (!e.downloaded()) {
            showError("Download required", "Download the episode first so Android can process the file locally.");
            return;
        }
        setStatus("Preparing local ad removal...");
        io.execute(() -> runAndroidAdRemoval(e.id));
    }

    private void runAndroidAdRemoval(String episodeId) {
        Episode episode = db.episode(episodeId);
        if (episode == null || TextUtils.isEmpty(episode.localFilePath)) {
            postUi(() -> showError("Episode unavailable", "The downloaded episode could not be found in local storage."));
            return;
        }

        File source = new File(episode.localFilePath);
        if (!source.exists()) {
            postUi(() -> showError("File missing", "The local episode file is missing. Download it again before running ad removal."));
            return;
        }

        String apiKey = db.setting("openai_api_key", "").trim();
        String model = db.setting("openai_model", "gpt-4o-mini").trim();

        File chunkDir = null;
        try {
            postUi(() -> setStatus("Chunking audio for transcription..."));
            chunkDir = new File(getCacheDir(), "adfree-" + episode.id);
            ArrayList<File> chunks = createTranscriptionChunks(source, chunkDir);

            postUi(() -> setStatus("Transcribing with OpenAI Whisper..."));
            ArrayList<TranscriptSegment> segments = transcribeChunksWithOpenAi(chunks, apiKey);
            if (segments.isEmpty()) throw new RuntimeException("No timestamped transcript segments were returned.");

            postUi(() -> setStatus("Finding ad ranges..."));
            ArrayList<TranscriptAdRange> adRanges = detectAdRangesWithOpenAi(segments, apiKey, TextUtils.isEmpty(model) ? "gpt-4o-mini" : model);
            adRanges = mergeRanges(adRanges, 4.0, 8.0);
            if (adRanges.isEmpty()) {
                db.setAdStatus(episode.id, "no_ads_found");
                postUi(() -> setStatus("No ad ranges detected for this episode."));
                return;
            }

            postUi(() -> setStatus("Rendering ad-free audio..."));
            File output = buildProcessedAudioPath(source);
            File renderTarget = source.getAbsolutePath().equals(output.getAbsolutePath()) ? buildProcessedStagingPath(source) : output;
            double durationSeconds = audioDurationSeconds(source, Math.max(1, episode.durationSeconds));
            renderAdFreeAudio(source, renderTarget, adRanges, durationSeconds);
            output = finalizeProcessedAudioOutput(source, output, renderTarget);

            db.setProcessedAudio(episode.id, output.getAbsolutePath(), "processed");
            postUi(() -> {
                setStatus("Ad-free audio saved locally and is now the default playback source for this episode.");
                showEpisode(episode.id);
            });
        } catch (Exception e) {
            String body = nullToEmpty(e.getMessage());
            if (TextUtils.isEmpty(body)) body = "The on-device ad-removal job failed.";
            final String finalBody = body;
            postUi(() -> showError("Ad removal failed", finalBody));
        } finally {
            deleteRecursively(chunkDir);
        }
    }

    private void cleanupExpiredDownloads() {
        if (db == null) return;
        db.clearDownloads(false, false);
    }

    private ArrayList<File> createTranscriptionChunks(File source, File outputDir) {
        if (outputDir.exists()) deleteRecursively(outputDir);
        outputDir.mkdirs();
        double totalSeconds = audioDurationSeconds(source, OPENAI_CHUNK_SECONDS);
        ArrayList<File> out = new ArrayList<>();
        int partIndex = 0;
        for (double start = 0.0; start < totalSeconds; start += OPENAI_CHUNK_SECONDS) {
            double end = Math.min(totalSeconds, start + OPENAI_CHUNK_SECONDS);
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
        for (int i = 0; i < chunks.size(); i++) {
            File chunk = chunks.get(i);
            final int chunkNumber = i + 1;
            postUi(() -> setStatus("Transcribing chunk " + chunkNumber + " of " + chunks.size() + "..."));
            ArrayList<TranscriptSegment> current = transcribeChunkWithOpenAi(chunk, apiKey, offsetSeconds);
            all.addAll(current);
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

    private void copyFile(File source, File target) throws Exception {
        try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
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
        Transformer transformer = new Transformer.Builder(this)
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
        try {
            latch.await();
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
        Button b = styledButton(s, l, SOFT_BLUE, BLUE, 0);
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
    private void setStatus(String s) { if (status != null) status.setText(s); }
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
            while (callLogs.size() > 30) callLogs.remove(callLogs.size() - 1);
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
        void clearDownloads(boolean listenedOnly, boolean all) { String where = all ? "e.download_status='downloaded'" : listenedOnly ? "e.download_status='downloaded' AND e.is_listened=1" : "e.download_status='downloaded' AND CAST(e.download_expires_at AS INTEGER)<?"; String[] args = all||listenedOnly ? null : new String[]{String.valueOf(System.currentTimeMillis())}; for (Episode e : episodes(where, args, 10000)) { if (!TextUtils.isEmpty(e.localFilePath)) new File(e.localFilePath).delete(); removeDownload(e.id); } }
        int countNew(String pid) { return count("episodes", "podcast_id=? AND is_new=1", new String[]{pid}); }
        int countDownloaded(String pid) { return count("episodes", "podcast_id=? AND download_status IN ('downloaded','processed')", new String[]{pid}); }
        int count(String table, String where, String[] args) { Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+table+" WHERE "+where, args); try { c.moveToFirst(); return c.getInt(0); } finally { c.close(); } }
        String podcastTitle(String pid) { Cursor c = getReadableDatabase().rawQuery("SELECT title FROM podcasts WHERE id=?", new String[]{pid}); try { return c.moveToFirst()?c.getString(0):""; } finally { c.close(); } }
        Episode lastPlayed() { Cursor c = getReadableDatabase().rawQuery("SELECT episode_id FROM playback_progress WHERE position_seconds>0 ORDER BY last_played_at DESC LIMIT 1", null); try { return c.moveToFirst()?episode(c.getString(0)):null; } finally { c.close(); } }
        Episode nextEpisode(Episode cur) { Cursor c = getReadableDatabase().rawQuery("SELECT id FROM episodes WHERE podcast_id=? AND pub_date < ? ORDER BY pub_date DESC LIMIT 1", new String[]{cur.podcastId, cur.pubDate == null ? "" : cur.pubDate}); try { return c.moveToFirst()?episode(c.getString(0)):null; } finally { c.close(); } }
    }
}
