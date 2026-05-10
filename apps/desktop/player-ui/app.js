const SAMPLE_OMNY_FEED = "https://www.omnycontent.com/d/playlist/e73c998e-6e60-432f-8610-ae210140c5b1/2e824128-fbd5-4c9e-9a57-ae2f0056b0c4/66d98a23-900c-44b0-a40b-ae2f0056b0db/podcast.rss";
const SMARTLESS_FEED = "https://feeds.simplecast.com/hNaFxXpO";
const BROWSER_STORE_KEY = "adfreepod.windows.v1";
const DEBUG_PREVIEW_LIMIT = 30000;
const DESCRIPTION_LIMIT = 2500;

let state = createDefaultState();
let route = "home";
let selectedPodcastId = "";
let selectedEpisodeId = "";
const runtime = {
  desktop: { connected: false, adCutForgeRoot: "", stateFilePath: "" },
  downloads: {},
  activity: {
    lines: [],
    lastHeartbeatAt: 0,
  },
  adRemover: {
    isRunning: false,
    logs: [],
    percent: 0,
    stage: "Idle",
    startedAt: 0,
    timerId: null,
  },
};
let desktopStateSaveQueue = Promise.resolve();
let lastStateSaveError = "";

const els = {
  title: document.querySelector("#screenTitle"),
  eyebrow: document.querySelector("#eyebrow"),
  activityPanel: document.querySelector("#activityPanel"),
  status: document.querySelector("#status"),
  screen: document.querySelector("#screen"),
  playerDock: document.querySelector("#playerDock"),
  sampleFeedBtn: document.querySelector("#sampleFeedBtn"),
  smartlessBtn: document.querySelector("#smartlessBtn"),
};

function createDefaultState() {
  return {
    podcasts: {},
    episodes: {},
    progress: {},
    settings: { autoplayNext: false, seekBack: 15, seekForward: 30, retentionDays: 30 },
    adRemover: {
      openAiKey: "",
      openAiModel: "gpt-4.1-mini",
      detectionMode: "local",
      backend: "parakeet",
      removeOriginal: true,
      selectedFile: "",
      episodeId: "",
      lastResult: null,
    },
    currentEpisodeId: "",
    debug: [],
  };
}

async function loadState() {
  const fallback = createDefaultState();
  try {
    let raw;
    if (window.desktopApi?.loadState) {
      raw = await window.desktopApi.loadState();
      if (!raw || typeof raw !== "object") {
        raw = JSON.parse(localStorage.getItem(BROWSER_STORE_KEY) || "{}");
        if (raw && typeof raw === "object" && Object.keys(raw).length) {
          const migrated = {
            ...fallback,
            ...raw,
            settings: { ...fallback.settings, ...(raw.settings || {}) },
            adRemover: { ...fallback.adRemover, ...(raw.adRemover || {}) },
          };
          compactState(migrated);
          await window.desktopApi.saveState(migrated);
          localStorage.removeItem(BROWSER_STORE_KEY);
          return migrated;
        }
      }
    } else {
      raw = JSON.parse(localStorage.getItem(BROWSER_STORE_KEY) || "{}");
    }
    const source = raw && typeof raw === "object" ? raw : {};
    const loaded = {
      ...fallback,
      ...source,
      settings: { ...fallback.settings, ...(source.settings || {}) },
      adRemover: { ...fallback.adRemover, ...(source.adRemover || {}) },
    };
    compactState(loaded);
    return loaded;
  } catch {
    return fallback;
  }
}

function saveState() {
  compactState(state);
  if (window.desktopApi?.saveState) {
    const snapshot = JSON.parse(JSON.stringify(state));
    desktopStateSaveQueue = desktopStateSaveQueue
      .catch(() => {})
      .then(() => window.desktopApi.saveState(snapshot))
      .catch((error) => {
        console.error("Failed to save player state", error);
        if (error.message !== lastStateSaveError) {
          lastStateSaveError = error.message;
          setStatus(`State save failed: ${error.message}`);
        }
      });
    return;
  }

  try {
    localStorage.setItem(BROWSER_STORE_KEY, JSON.stringify(state));
  } catch (error) {
    state.debug = state.debug.slice(0, 8).map((entry) => ({ ...entry, raw: truncate(entry.raw, 6000) }));
    for (const episode of Object.values(state.episodes)) episode.description = truncate(episode.description, 900);
    try {
      localStorage.removeItem(BROWSER_STORE_KEY);
      localStorage.setItem(BROWSER_STORE_KEY, JSON.stringify(state));
      setStatus("Local storage was full, so the app compacted debug previews and long descriptions.");
    } catch {
      state.debug = [];
      localStorage.removeItem(BROWSER_STORE_KEY);
      localStorage.setItem(BROWSER_STORE_KEY, JSON.stringify({ ...state, debug: [] }));
      setStatus("Local storage was full, so the app cleared the debug log.");
    }
  }
}

function compactState(target) {
  target.debug = (target.debug || []).slice(0, 20).map((entry) => ({
    ...entry,
    raw: truncate(entry.raw, DEBUG_PREVIEW_LIMIT),
  }));
  for (const podcast of Object.values(target.podcasts || {})) {
    podcast.description = truncate(podcast.description, DESCRIPTION_LIMIT);
  }
  for (const episode of Object.values(target.episodes || {})) {
    episode.description = truncate(episode.description, DESCRIPTION_LIMIT);
  }
}

function truncate(value, max) {
  const text = String(value || "");
  return text.length > max ? `${text.slice(0, max)}\n\n...truncated to avoid browser storage quota...` : text;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function stripHtml(value) {
  const div = document.createElement("div");
  div.innerHTML = value || "";
  return div.textContent.trim();
}

function setStatus(message, kind = "") {
  els.status.className = `status ${kind}`;
  els.status.textContent = message;
}

function pushActivityLine(message, type = "info") {
  if (!message) return;
  runtime.activity.lines.unshift({
    at: new Date().toLocaleTimeString(),
    message: String(message),
    type,
  });
  runtime.activity.lines = runtime.activity.lines.slice(0, 80);
  renderActivityPanel();
}

function formatBytes(bytes) {
  const value = Number(bytes) || 0;
  if (!value) return "0 B";
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  if (value < 1024 * 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(1)} MB`;
  return `${(value / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

function renderActivityPanel() {
  if (!els.activityPanel) return;

  const adState = adRemoverState();
  const activeDownloads = Object.values(runtime.downloads).filter((entry) => entry?.isRunning);
  const adEpisode = adState.episodeId ? state.episodes[adState.episodeId] : null;
  const secSinceHB = runtime.activity.lastHeartbeatAt
    ? Math.floor((Date.now() - runtime.activity.lastHeartbeatAt) / 1000)
    : null;
  const isAdRunning = runtime.adRemover.isRunning;

  els.activityPanel.className = isAdRunning
    ? "activityPanel activityPanel--adRunning"
    : (activeDownloads.length ? "activityPanel activityPanel--downloading" : "activityPanel");

  const sections = [];

  if (isAdRunning) {
    const percent = Math.max(0, Math.min(100, Number(runtime.adRemover.percent) || 0));
    const elapsedS = runtime.adRemover.startedAt ? Math.floor((Date.now() - runtime.adRemover.startedAt) / 1000) : 0;
    const elapsedText = formatRuntime(elapsedS);
    const etaText = percent > 1 && percent < 100 ? formatRuntime((elapsedS * (100 - percent)) / percent) : "--:--";

    // Heartbeat health
    let heartbeatHtml;
    if (secSinceHB === null) {
      heartbeatHtml = `<span class="hb hb--waiting">Waiting for first output...</span>`;
    } else if (secSinceHB < 30) {
      heartbeatHtml = `<span class="hb hb--ok">&#9679; Running — last output ${secSinceHB}s ago</span>`;
    } else if (secSinceHB < 120) {
      heartbeatHtml = `<span class="hb hb--slow">&#9650; No output for ${secSinceHB}s — still running (normal during transcription)</span>`;
    } else {
      heartbeatHtml = `<span class="hb hb--stale">&#9888; No output for ${secSinceHB}s — process may be stuck</span>`;
    }

    // Engine label
    const engineLabel = adState.backend === "parakeet" ? "Parakeet (NVIDIA, local GPU)"
      : adState.backend === "whisper" ? "Whisper (local CPU)"
      : adState.backend === "openai-whisper" ? "OpenAI Whisper API (cloud)"
      : escapeHtml(adState.backend || "unknown");

    // Recent ad-remover log lines (last 8, strip the [type] prefix noise for readability)
    const recentLogs = runtime.adRemover.logs.slice(-8).map((line) =>
      escapeHtml(line.replace(/^\[(stdout|stderr|status|progress)\]\s*/i, "").trim())
    ).filter(Boolean);

    // Pull out any error lines from recent logs
    const errorLines = runtime.adRemover.logs
      .filter((l) => /\[error\]|error:|failed|exception|traceback/i.test(l))
      .slice(-3)
      .map((l) => escapeHtml(l.replace(/^\[\w+\]\s*/, "").trim()));

    sections.push(`
      <div class="adRunPanel">
        <div class="adRunPanel__header">
          <span class="adRunPanel__spinner"></span>
          <strong>Removing ads</strong>
          <span class="adRunPanel__episode">${escapeHtml(adEpisode?.title || adState.selectedFile ? adState.selectedFile.split(/[\\/]/).pop() : "Selected file")}</span>
        </div>

        <div class="adRunPanel__meta">
          <span><em>Engine:</em> ${engineLabel}</span>
          <span><em>Detection:</em> ${escapeHtml(adState.detectionMode || "local")}</span>
          <span><em>Elapsed:</em> ${elapsedText}</span>
          <span><em>ETA:</em> ${etaText}</span>
        </div>

        <div class="adRunPanel__stepRow">
          <span class="adRunPanel__step">${escapeHtml(runtime.adRemover.stage || "Working")}</span>
          <span class="adRunPanel__pct">${Math.round(percent)}%</span>
        </div>
        <div class="adRunPanel__bar"><div class="adRunPanel__fill" style="width:${percent}%"></div></div>

        <div class="adRunPanel__hb">${heartbeatHtml}</div>

        ${errorLines.length ? `
          <div class="adRunPanel__errors">
            <strong>Issues detected:</strong>
            <pre>${errorLines.join("\n")}</pre>
          </div>` : ""}

        ${recentLogs.length ? `
          <div class="adRunPanel__logWrap">
            <div class="adRunPanel__logLabel">Recent output</div>
            <div class="adRunPanel__log">${recentLogs.join("\n")}</div>
          </div>` : ""}
      </div>
    `);
  }

  if (activeDownloads.length) {
    sections.push(`
      <div class="activitySection">
        <strong>Downloads in progress</strong>
        ${activeDownloads.map((entry) => {
          const ep = state.episodes[entry.episodeId] || {};
          const percent = Math.round(entry.percent || 0);
          const transferred = entry.totalBytes
            ? `${formatBytes(entry.downloadedBytes)} / ${formatBytes(entry.totalBytes)}`
            : formatBytes(entry.downloadedBytes);
          return `<div class="muted">${escapeHtml(ep.title || "Episode")} — ${percent}% (${transferred})</div>`;
        }).join("")}
      </div>
    `);
  }

  if (!isAdRunning) {
    const recent = runtime.activity.lines.slice(0, 6);
    if (recent.length) {
      sections.push(`
        <div class="activitySection">
          <strong>Recent activity</strong>
          <div class="activityLog">${recent.map((line) => `${escapeHtml(line.at)} | ${escapeHtml(line.message)}`).join("\n")}</div>
        </div>
      `);
    }
  }

  if (!sections.length) {
    els.activityPanel.innerHTML = "";
    return;
  }

  els.activityPanel.innerHTML = sections.join("");
}

function setRoute(next, args = {}) {
  route = next;
  if (args.podcastId) selectedPodcastId = args.podcastId;
  if (args.episodeId) selectedEpisodeId = args.episodeId;
  render();
}

function setTitle(title, eyebrow = "Windows desktop build") {
  els.title.textContent = title;
  els.eyebrow.textContent = eyebrow;
  document.querySelectorAll(".nav button").forEach((button) => {
    button.classList.toggle("active", button.dataset.route === route);
  });
}

function adRemoverState() {
  if (!state.adRemover) {
    state.adRemover = {
      openAiKey: "",
      openAiModel: "gpt-4.1-mini",
      detectionMode: "local",
      backend: "parakeet",
      removeOriginal: true,
      selectedFile: "",
      episodeId: "",
      lastResult: null,
    };
  }
  if (!state.adRemover.openAiModel) state.adRemover.openAiModel = "gpt-4.1-mini";
  return state.adRemover;
}

function updateAdLogPanel() {
  const logEl = document.querySelector("#adLogs");
  if (logEl) {
    logEl.textContent = runtime.adRemover.logs.length ? runtime.adRemover.logs.join("\n") : "No process output yet.";
  }
}

function formatRuntime(seconds) {
  const whole = Math.max(0, Math.floor(seconds || 0));
  const mins = Math.floor(whole / 60);
  const secs = whole % 60;
  return `${mins}:${String(secs).padStart(2, "0")}`;
}

function updateAdProgressPanel() {
  const percent = Math.max(0, Math.min(100, Number(runtime.adRemover.percent) || 0));
  const stageEl = document.querySelector("#adStageText");
  const percentEl = document.querySelector("#adPercentText");
  const elapsedEl = document.querySelector("#adElapsedText");
  const etaEl = document.querySelector("#adEtaText");
  const fillEl = document.querySelector("#adProgressFill");
  const statusEl = document.querySelector("#adLiveStatus");
  const elapsedSeconds = runtime.adRemover.startedAt ? (Date.now() - runtime.adRemover.startedAt) / 1000 : 0;
  const etaSeconds = percent > 0 ? (elapsedSeconds * (100 - percent)) / percent : 0;

  if (stageEl) stageEl.textContent = runtime.adRemover.stage || "Idle";
  if (percentEl) percentEl.textContent = `${Math.round(percent)}%`;
  if (elapsedEl) elapsedEl.textContent = formatRuntime(elapsedSeconds);
  if (etaEl) etaEl.textContent = percent > 0 && percent < 100 ? formatRuntime(etaSeconds) : "--:--";
  if (fillEl) fillEl.style.width = `${percent}%`;
  if (statusEl) {
    statusEl.textContent = runtime.adRemover.logs[runtime.adRemover.logs.length - 1] || "Waiting to start.";
  }
}

function startAdProgressTimer() {
  stopAdProgressTimer();
  runtime.adRemover.timerId = window.setInterval(() => {
    updateAdProgressPanel();
    // On any route other than the adremover screen, do a full re-render so
    // episode cards on home/podcast/episode pages stay updated with the
    // current ad-removal progress (removing-ads note, pill, etc.).
    if (route !== "adremover" && runtime.adRemover.isRunning) {
      render(); // render() calls renderActivityPanel() internally
    } else {
      renderActivityPanel();
    }
  }, 1000);
}

function stopAdProgressTimer() {
  if (runtime.adRemover.timerId) {
    window.clearInterval(runtime.adRemover.timerId);
    runtime.adRemover.timerId = null;
  }
}

async function initializeDesktopBridge() {
  if (!window.desktopApi) return;

  try {
    const capabilities = await window.desktopApi.getCapabilities();
    runtime.desktop.connected = Boolean(capabilities.isDesktop);
    runtime.desktop.adCutForgeRoot = capabilities.adCutForgeRoot || "";
    runtime.desktop.stateFilePath = capabilities.stateFilePath || "";
  } catch {
    runtime.desktop.connected = false;
  }

  window.desktopApi.onProcessingEvent((event) => {
    runtime.activity.lastHeartbeatAt = Date.now();
    runtime.adRemover.logs = [...runtime.adRemover.logs, `[${event.type}] ${event.line}`].slice(-120);
    if (typeof event.percent === "number") {
      runtime.adRemover.percent = event.percent;
    }
    if (event.stage) {
      runtime.adRemover.stage = event.stage;
    }
    pushActivityLine(`[ad-removal/${event.type}] ${event.line}`, event.type);
    updateAdLogPanel();
    updateAdProgressPanel();
    if (route !== "adremover" && runtime.adRemover.isRunning && event.stage) {
      setStatus(`${event.stage}: ${String(event.line || "Working...").replace(/\s+/g, " ").trim()}`);
    }
    if (route !== "adremover") {
      render();
    } else {
      renderActivityPanel();
    }
  });

  window.desktopApi.onDownloadEvent?.((event) => {
    runtime.activity.lastHeartbeatAt = Date.now();
    const episodeId = event.episodeId || "";
    if (episodeId) {
      const existing = runtime.downloads[episodeId] || { episodeId, isRunning: true, percent: 0, downloadedBytes: 0, totalBytes: 0, stage: "" };
      runtime.downloads[episodeId] = {
        ...existing,
        isRunning: event.type !== "error" && (event.percent ?? 0) < 100,
        percent: typeof event.percent === "number" ? event.percent : existing.percent,
        downloadedBytes: typeof event.downloadedBytes === "number" ? event.downloadedBytes : existing.downloadedBytes,
        totalBytes: typeof event.totalBytes === "number" ? event.totalBytes : existing.totalBytes,
        stage: event.stage || existing.stage,
        lastLine: event.line || existing.lastLine,
      };
      if (event.type === "error" || (typeof event.percent === "number" && event.percent >= 100)) {
        runtime.downloads[episodeId].isRunning = false;
      }
    }
    pushActivityLine(`[download/${event.type}] ${event.line || event.stage || "update"}`, event.type || "status");
    if (route !== "adremover") {
      render();
    } else {
      renderActivityPanel();
    }
  });

  if (route === "adremover") {
    renderAdRemover();
  }
  renderActivityPanel();
}

async function sha256(text) {
  const bytes = new TextEncoder().encode(text);
  const hash = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(hash)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function nowIso() {
  return new Date().toISOString();
}

async function callApi(label, url) {
  const response = await fetch(url);
  const text = await response.text();
  let payload;
  try {
    payload = JSON.parse(text);
  } catch {
    payload = { ok: response.ok, status: response.status, raw: text, headers: {} };
  }
  addDebug(label, url, payload);
  if (!response.ok || payload.ok === false) {
    throw new Error(payload.message || payload.error || `HTTP ${response.status}`);
  }
  return payload;
}

function addDebug(label, localUrl, payload) {
  state.debug.unshift({
    label,
    at: new Date().toLocaleTimeString(),
    localUrl,
    requestedUrl: payload.requestedUrl || "",
    finalUrl: payload.finalUrl || "",
    status: `${payload.status || ""} ${payload.statusText || ""}`.trim() || (payload.ok === false ? "failed" : "ok"),
    headers: payload.headers || {},
    raw: truncate(payload.raw || JSON.stringify(payload, null, 2), DEBUG_PREVIEW_LIMIT),
    rawLength: payload.rawLength ?? (payload.raw || "").length,
    sampleHex: payload.sampleHex || "",
    sampleText: payload.sampleText || "",
  });
  state.debug = state.debug.slice(0, 20);
  saveState();
}

function parseXml(raw) {
  const doc = new DOMParser().parseFromString(raw, "application/xml");
  const err = doc.querySelector("parsererror");
  if (err) throw new Error(err.textContent.trim());
  return doc;
}

function childText(node, localName) {
  for (const child of node.children || []) {
    if (child.localName === localName) return child.textContent.trim();
  }
  return "";
}

function childAttr(node, localName, attr) {
  for (const child of node.children || []) {
    if (child.localName === localName) return child.getAttribute(attr) || "";
  }
  return "";
}

function parseDuration(value) {
  if (!value) return 0;
  if (value.includes(":")) return value.split(":").reduce((total, part) => total * 60 + Number(part || 0), 0);
  return Math.floor(Number(value) || 0);
}

function formatDuration(seconds) {
  if (!seconds) return "";
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  return h ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}` : `${m}:${String(s).padStart(2, "0")}`;
}

function isPlausibleAudioUrl(url, type) {
  if (!url) return false;
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return false;
  }
  if (!/^https?:$/.test(parsed.protocol)) return false;
  if (!parsed.pathname || parsed.pathname === "/") return false;
  return /^audio\//i.test(type || "") || /mp3|m4a|aac|redirect/i.test(url);
}

async function parseFeed(feedUrl, raw) {
  const doc = parseXml(raw);
  const channel = doc.querySelector("channel");
  if (!channel) throw new Error("No channel found in RSS feed.");
  const podcastId = await sha256(feedUrl);
  const podcast = {
    id: podcastId,
    source: "rss",
    title: childText(channel, "title") || "Untitled podcast",
    publisher: childText(channel, "author") || childText(channel, "managingEditor"),
    description: truncate(childText(channel, "description"), DESCRIPTION_LIMIT),
    feedUrl,
    websiteUrl: childText(channel, "link"),
    artworkUrl: childAttr(channel, "image", "href") || "",
    subscribed: state.podcasts[podcastId]?.subscribed || false,
    lastFeedRefreshAt: nowIso(),
  };

  const episodes = [];
  const items = [...channel.querySelectorAll("item")];
  for (const item of items) {
    const enclosure = [...item.children].find((child) => child.localName === "enclosure");
    const enclosureUrl = enclosure?.getAttribute("url") || "";
    const enclosureType = enclosure?.getAttribute("type") || "";
    if (!isPlausibleAudioUrl(enclosureUrl, enclosureType)) continue;
    const guid = childText(item, "guid");
    const key = guid || enclosureUrl || `${childText(item, "title")}|${childText(item, "pubDate")}`;
    const id = await sha256(`${feedUrl}|${key}`);
    const old = state.episodes[id] || {};
    episodes.push({
      id,
      podcastId,
      guid,
      title: childText(item, "title") || "Untitled episode",
      description: truncate(childText(item, "description") || childText(item, "encoded") || childText(item, "summary"), DESCRIPTION_LIMIT),
      pubDate: childText(item, "pubDate"),
      durationSeconds: parseDuration(childText(item, "duration")),
      enclosureUrl,
      enclosureType,
      enclosureLength: Number(enclosure?.getAttribute("length") || 0),
      isNew: old.isNew ?? true,
      isListened: old.isListened ?? false,
      downloadStatus: old.downloadStatus || "not_downloaded",
      downloadedAt: old.downloadedAt || "",
      firstSeenAt: old.firstSeenAt || nowIso(),
    });
  }
  if (!episodes.length) throw new Error("This feed does not expose playable audio enclosures.");
  return { podcast, episodes };
}

function podcastEpisodes(podcastId, filter = "All") {
  return Object.values(state.episodes)
    .filter((episode) => episode.podcastId === podcastId)
    .filter((episode) => {
      const progress = state.progress[episode.id]?.positionSeconds || 0;
      const percent = state.progress[episode.id]?.percentComplete || 0;
      if (filter === "New") return episode.isNew;
      if (filter === "In progress") return progress > 0 && percent < 95;
      if (filter === "Downloaded") return episode.downloadStatus === "downloaded";
      if (filter === "Not downloaded") return episode.downloadStatus !== "downloaded";
      if (filter === "Listened") return episode.isListened;
      if (filter === "Unlistened") return !episode.isListened;
      return true;
    })
    .sort((a, b) => Date.parse(b.pubDate || 0) - Date.parse(a.pubDate || 0));
}

function episodeIndicators(episode) {
  const progress = state.progress[episode.id]?.positionSeconds || 0;
  const percent = state.progress[episode.id]?.percentComplete || 0;
  const downloadRuntime = runtime.downloads[episode.id];
  const pills = [];
  if (episode.isNew) pills.push("New");
  if (episode.isListened) pills.push("Listened");
  else if (progress > 0 && percent < 95) pills.push("In progress");
  if (episode.downloadStatus === "downloaded") pills.push("Downloaded");
  if (episode.downloadStatus === "downloading" || downloadRuntime?.isRunning) pills.push("Downloading...");
  if (episode.offlineRelativePath) pills.push("Offline saved");
  if (episode.adSupportedStatus === "processed") pills.push("Ad free");
  if (episode.adSupportedStatus === "no_ads_found") pills.push("No ads found");
  if (episode.adSupportedStatus === "processing") pills.push("Removing ads");
  return pills.map((pill) => `<span class="pill blue">${escapeHtml(pill)}</span>`).join("");
}

function episodeOperationMessage(episode) {
  const downloadRuntime = runtime.downloads[episode.id];
  if (episode.downloadStatus === "downloading" || downloadRuntime?.isRunning) {
    const percent = Math.round(downloadRuntime?.percent || 0);
    const bytes = downloadRuntime?.totalBytes
      ? `${formatBytes(downloadRuntime?.downloadedBytes || 0)} / ${formatBytes(downloadRuntime.totalBytes)}`
      : formatBytes(downloadRuntime?.downloadedBytes || 0);
    return `Downloading... ${percent}% (${bytes})`;
  }

  if (episode.adSupportedStatus === "processing") {
    return "removing ads. please wait. this could take some time";
  }

  if (episode.adSupportedStatus === "failed" && episode.adSupportedError) {
    return `Ad removal failed: ${episode.adSupportedError}`;
  }

  return "";
}

function episodeAudioUrl(episode) {
  if (episode.offlineRelativePath) {
    return `/api/offline-audio?path=${encodeURIComponent(episode.offlineRelativePath)}`;
  }
  return `/api/audio?url=${encodeURIComponent(episode.enclosureUrl)}`;
}

function isOfflineSavedEpisodeFile(filePath) {
  return Boolean(filePath) && Object.values(state.episodes).some((episode) => episode.offlineFilePath === filePath);
}

function findEpisodeBySourceFile(filePath) {
  if (!filePath) return null;
  return Object.values(state.episodes).find((episode) => episode.offlineFilePath === filePath) || null;
}

async function promoteEpisodeSourceToAdFree(episodeId, result, sourceLabel) {
  const episode = state.episodes[episodeId];
  if (!episode) {
    return result;
  }
  if (!window.desktopApi?.promoteEpisodeAudioSource) {
    throw new Error("Desktop source promotion is unavailable in this build.");
  }
  if (!result?.editedAudio) {
    throw new Error("The ad-removal run completed without an exported audio file.");
  }

  const promoted = await window.desktopApi.promoteEpisodeAudioSource({
    editedAudioPath: result.editedAudio,
    episodeId,
    title: episode.title || sourceLabel,
    previousSourcePath: episode.offlineFilePath || "",
  });

  episode.offlineFilePath = promoted.filePath;
  episode.offlineRelativePath = promoted.relativePath;
  episode.downloadStatus = "downloaded";
  episode.downloadedAt = nowIso();
  episode.adSupportedStatus = "processed";

  const adState = adRemoverState();
  adState.selectedFile = promoted.filePath;
  adState.episodeId = episodeId;

  return {
    ...result,
    outputDir: result.outputDir || promoted.filePath,
    editedAudio: promoted.filePath,
    promotedSourcePath: promoted.filePath,
    promotedSourceRelativePath: promoted.relativePath,
    removedPreviousSource: promoted.removedPreviousSource,
    removedSiblingFiles: promoted.removedSiblingFiles || [],
    keptPath: promoted.keptPath || promoted.filePath,
    runSummary: result.runSummary
      ? `${result.runSummary}\nEpisode playback now uses the ad-free copy.`
      : "Episode playback now uses the ad-free copy.",
  };
}

async function searchDirectory(term) {
  setStatus(`Searching public directory for "${term}"...`);
  const payload = await callApi("Directory search", `/api/search?term=${encodeURIComponent(term)}&limit=50`);
  const data = JSON.parse(payload.raw);
  const results = (data.results || []).filter((item) => item.feedUrl);
  setStatus(`Found ${results.length} directory result(s). Opening a result fetches its RSS feed.`);
  els.screen.querySelector("#searchResults").innerHTML = results.map((item, i) => `
    <article class="card compact">
      <h3>${i + 1}. ${escapeHtml(item.collectionName)}</h3>
      <div class="muted">${escapeHtml(item.artistName || "")} ${item.primaryGenreName ? `| ${escapeHtml(item.primaryGenreName)}` : ""}</div>
      <div class="code">${escapeHtml(item.feedUrl)}</div>
      <button data-open-feed="${escapeHtml(item.feedUrl)}">Open Feed</button>
    </article>
  `).join("");
}

async function fetchAndSaveFeed(feedUrl) {
  setStatus("Fetching RSS feed...");
  const payload = await callApi("RSS fetch", `/api/fetch?url=${encodeURIComponent(feedUrl)}`);
  const { podcast, episodes } = await parseFeed(feedUrl, payload.raw);
  state.podcasts[podcast.id] = { ...state.podcasts[podcast.id], ...podcast };
  for (const episode of episodes) state.episodes[episode.id] = { ...state.episodes[episode.id], ...episode };
  saveState();
  setStatus(`Loaded ${episodes.length} episode(s) from ${podcast.title}.`);
  setRoute("podcast", { podcastId: podcast.id });
}

function subscribePodcast(podcastId, subscribed) {
  state.podcasts[podcastId].subscribed = subscribed;
  state.podcasts[podcastId].subscribedAt = subscribed ? nowIso() : "";
  saveState();
  render();
}

function render() {
  if (route === "home") renderHome();
  if (route === "search") renderSearch();
  if (route === "podcast") renderPodcast();
  if (route === "episode") renderEpisode();
  if (route === "player") renderPlayerScreen();
  if (route === "adremover") renderAdRemover();
  if (route === "settings") renderSettings();
  if (route === "debug") renderDebug();
  renderActivityPanel();
  renderPlayerDock();
}

function renderHome() {
  setTitle("Home");
  setStatus("Independent, local-first podcast player. Library and progress stay on this device.");
  const subscriptions = Object.values(state.podcasts).filter((p) => p.subscribed);
  const current = state.currentEpisodeId ? state.episodes[state.currentEpisodeId] : null;
  els.screen.innerHTML = `
    ${current ? `<section class="card"><h2>Continue Listening</h2>${episodeCard(current)}</section>` : ""}
    <h2>Library</h2>
    ${subscriptions.length ? `<div class="grid">${subscriptions.map(podcastCard).join("")}</div>` : `
      <div class="card">
        <h3>No subscriptions yet</h3>
        <p class="muted">Search the public directory, paste an RSS feed URL, or use a sample feed.</p>
        <div class="buttonRow">
          <button data-route-jump="search">Search</button>
          <button class="ghost" data-load-feed="${SAMPLE_OMNY_FEED}">Sample Omny Feed</button>
          <button class="ghost" data-load-feed="${SMARTLESS_FEED}">SmartLess Feed</button>
        </div>
      </div>`}
    <h2>In Progress</h2>
    <div class="episodeList">${Object.values(state.episodes).filter(e => (state.progress[e.id]?.positionSeconds || 0) > 0 && (state.progress[e.id]?.percentComplete || 0) < 95).slice(0, 8).map(episodeCard).join("") || `<div class="card muted">Nothing in progress yet.</div>`}</div>
    <h2>Downloads</h2>
    <div class="episodeList">${Object.values(state.episodes).filter(e => e.downloadStatus === "downloaded").slice(0, 8).map(episodeCard).join("") || `<div class="card muted">Downloaded episodes will appear here after you use Download MP3.</div>`}</div>
  `;
  wireCommonActions();
}

function podcastCard(podcast) {
  const countNew = podcastEpisodes(podcast.id, "New").length;
  const countDownloaded = podcastEpisodes(podcast.id, "Downloaded").length;
  return `
    <article class="card">
      <h3>${escapeHtml(podcast.title)}</h3>
      <div class="muted">${escapeHtml(podcast.publisher || "Publisher unknown")}</div>
      <p>${countNew} new | ${countDownloaded} downloaded</p>
      <div class="buttonRow">
        <button data-open-podcast="${podcast.id}">Open</button>
        <button class="ghost" data-refresh-feed="${escapeHtml(podcast.feedUrl)}">Refresh</button>
      </div>
    </article>
  `;
}

function renderSearch() {
  setTitle("Search");
  setStatus("Search uses Apple's public podcast directory only to discover RSS feed URLs.");
  els.screen.innerHTML = `
    <section class="twoCol">
      <div class="card">
        <h2>Find Podcasts</h2>
        <label>Search public directory
          <div class="row">
            <input id="searchTerm" value="SmartLess">
            <button id="searchBtn">Search</button>
          </div>
        </label>
        <p class="muted">Directory results expose feed URLs. The audio still comes from each RSS feed's enclosure URLs.</p>
      </div>
      <div class="card">
        <h2>Add RSS URL</h2>
        <label>RSS feed URL
          <textarea id="rssInput">${SAMPLE_OMNY_FEED}</textarea>
        </label>
        <div class="buttonRow">
          <button id="fetchRssBtn">Fetch RSS</button>
          <button class="ghost" data-fill-feed="${SMARTLESS_FEED}">SmartLess</button>
          <button class="ghost" data-fill-feed="${SAMPLE_OMNY_FEED}">Omny Sample</button>
        </div>
      </div>
    </section>
    <h2>Results</h2>
    <div id="searchResults" class="grid"></div>
  `;
  document.querySelector("#searchBtn").addEventListener("click", async () => {
    try { await searchDirectory(document.querySelector("#searchTerm").value.trim()); } catch (e) { setStatus(`Search failed: ${e.message}`); }
  });
  document.querySelector("#fetchRssBtn").addEventListener("click", async () => {
    try { await fetchAndSaveFeed(document.querySelector("#rssInput").value.trim()); } catch (e) { setStatus(`RSS fetch failed: ${e.message}`); }
  });
  wireCommonActions();
}

function renderPodcast() {
  const podcast = state.podcasts[selectedPodcastId];
  if (!podcast) {
    setRoute("home");
    return;
  }
  setTitle(podcast.title, "Podcast detail");
  setStatus(`Last refreshed: ${podcast.lastFeedRefreshAt || "Never"}`);
  const filter = sessionStorage.getItem("episodeFilter") || "All";
  const episodes = podcastEpisodes(podcast.id, filter);
  els.screen.innerHTML = `
    <section class="card">
      <h2>${escapeHtml(podcast.title)}</h2>
      <div class="muted">${escapeHtml(podcast.publisher || "Publisher unknown")}</div>
      <p>${escapeHtml(stripHtml(podcast.description || "")).slice(0, 900)}</p>
      <div class="code">${escapeHtml(podcast.feedUrl)}</div>
      <div class="buttonRow">
        <button data-subscribe="${podcast.id}">${podcast.subscribed ? "Unsubscribe" : "Subscribe"}</button>
        <button class="ghost" data-refresh-feed="${escapeHtml(podcast.feedUrl)}">Manual refresh</button>
      </div>
    </section>
    <h2>Episodes</h2>
    <div class="buttonRow">${["All", "New", "In progress", "Downloaded", "Not downloaded", "Listened", "Unlistened"].map(f => `<button class="${f === filter ? "" : "ghost"}" data-filter="${f}">${f}</button>`).join("")}</div>
    <div class="episodeList">${episodes.slice(0, 60).map(episodeCard).join("") || `<div class="card muted">No episodes match this filter.</div>`}</div>
  `;
  wireCommonActions();
}

function episodeCard(episode) {
  const podcast = state.podcasts[episode.podcastId];
  const progress = state.progress[episode.id]?.positionSeconds || 0;
  const title = escapeHtml(episode.title);
  const downloadRuntime = runtime.downloads[episode.id];
  const isDownloading = episode.downloadStatus === "downloading" || downloadRuntime?.isRunning;
  const isProcessingThisEpisode = runtime.adRemover.isRunning && episode.offlineFilePath && adRemoverState().selectedFile === episode.offlineFilePath;
  const operationMessage = episodeOperationMessage(episode);
  return `
    <article class="card compact">
      <h3>${title}</h3>
      <div class="muted">${escapeHtml(podcast?.title || "")} ${episode.pubDate ? `| ${escapeHtml(episode.pubDate)}` : ""} ${episode.durationSeconds ? `| ${formatDuration(episode.durationSeconds)}` : ""}</div>
      <div>${episodeIndicators(episode)}</div>
      ${operationMessage ? `<p class="opNote">${escapeHtml(operationMessage)}</p>` : ""}
      <div class="buttonRow">
        <button data-play="${episode.id}">${progress ? "Resume" : "Play"}</button>
        <button class="ghost" data-details="${episode.id}">Details</button>
        <button class="ghost" data-save-offline="${episode.id}" ${isDownloading ? "disabled" : ""}>${episode.offlineRelativePath ? "Saved offline" : "Save offline"}</button>
        <button data-download-remove="${episode.id}" ${isDownloading || isProcessingThisEpisode ? "disabled" : ""}>${isDownloading ? "Downloading..." : isProcessingThisEpisode ? "Removing ads..." : "Download and remove ads"}</button>
        ${episode.offlineFilePath ? `<button data-remove-ads-now="${episode.id}" ${isProcessingThisEpisode ? "disabled" : ""}>${isProcessingThisEpisode ? "Processing..." : "Remove ads"}</button>` : ""}
        ${episode.offlineFilePath ? `<button class="ghost" data-use-offline-ad="${episode.id}">Open in ad remover</button>` : ""}
        ${episode.offlineFilePath ? `<button class="danger" title="Delete downloaded file" data-delete-offline="${episode.id}">🗑 Delete file</button>` : ""}
        <a class="downloadLink" href="/api/download?url=${encodeURIComponent(episode.enclosureUrl)}&filename=${encodeURIComponent(episode.title)}" data-download="${episode.id}"><button type="button" class="ghost">Download MP3</button></a>
      </div>
    </article>
  `;
}

function renderEpisode() {
  const episode = state.episodes[selectedEpisodeId];
  if (!episode) {
    setRoute("home");
    return;
  }
  const podcast = state.podcasts[episode.podcastId];
  const downloadRuntime = runtime.downloads[episode.id];
  const isDownloading = episode.downloadStatus === "downloading" || downloadRuntime?.isRunning;
  const operationMessage = episodeOperationMessage(episode);
  setTitle("Episode", podcast?.title || "Episode detail");
  setStatus("Episode detail shows the exact audio enclosure URL used for playback and download.");
  els.screen.innerHTML = `
    <section class="card">
      <h2>${escapeHtml(episode.title)}</h2>
      <div class="muted">${escapeHtml(podcast?.title || "")} ${episode.pubDate ? `| ${escapeHtml(episode.pubDate)}` : ""}</div>
      <div>${episodeIndicators(episode)}</div>
      ${operationMessage ? `<p class="opNote">${escapeHtml(operationMessage)}</p>` : ""}
      <h3>Audio enclosure URL</h3>
      <div class="code">${escapeHtml(episode.enclosureUrl)}</div>
      <div class="buttonRow">
        <button data-play="${episode.id}">Play / Resume</button>
        <button class="ghost" data-save-offline="${episode.id}" ${isDownloading ? "disabled" : ""}>${episode.offlineRelativePath ? "Saved offline" : "Save offline"}</button>
        <button data-download-remove="${episode.id}" ${isDownloading || episode.adSupportedStatus === "processing" ? "disabled" : ""}>${isDownloading ? "Downloading..." : episode.adSupportedStatus === "processing" ? "Removing ads..." : "Download and remove ads"}</button>
        ${episode.offlineFilePath ? `<button data-remove-ads-now="${episode.id}" ${runtime.adRemover.isRunning && adRemoverState().selectedFile === episode.offlineFilePath ? "disabled" : ""}>${runtime.adRemover.isRunning && adRemoverState().selectedFile === episode.offlineFilePath ? "Processing..." : "Remove ads"}</button>` : ""}
        ${episode.offlineFilePath ? `<button class="ghost" data-use-offline-ad="${episode.id}">Open in ad remover</button>` : ""}
        ${episode.offlineFilePath ? `<button class="danger" title="Delete downloaded file" data-delete-offline="${episode.id}">🗑 Delete file</button>` : ""}
        <a href="/api/download?url=${encodeURIComponent(episode.enclosureUrl)}&filename=${encodeURIComponent(episode.title)}" data-download="${episode.id}"><button type="button" class="ghost">Download MP3</button></a>
        <button class="ghost" data-head="${episode.enclosureUrl}">HEAD check</button>
        <button class="ghost" data-probe="${episode.enclosureUrl}">Probe first 4 KB</button>
        <button class="ghost" data-mark-listened="${episode.id}">${episode.isListened ? "Mark unlistened" : "Mark listened"}</button>
      </div>
      <h3>Description</h3>
      <p>${escapeHtml(stripHtml(episode.description || ""))}</p>
    </section>
  `;
  wireCommonActions();
}

function renderPlayerScreen() {
  setTitle("Player");
  setStatus("The player streams through the local proxy so redirects and range requests can be debugged.");
  const episode = state.currentEpisodeId ? state.episodes[state.currentEpisodeId] : null;
  els.screen.innerHTML = episode ? `<section class="card">${episodeCard(episode)}</section>` : `<div class="card muted">No episode selected yet.</div>`;
  wireCommonActions();
}

function renderSettings() {
  setTitle("Settings");
  setStatus(runtime.desktop.stateFilePath ? `State file: ${runtime.desktop.stateFilePath}` : "Settings are stored locally on this device.");
  els.screen.innerHTML = `
    <section class="card">
      <h2>Playback</h2>
      <label><input type="checkbox" id="autoplayNext" ${state.settings.autoplayNext ? "checked" : ""}> Autoplay next episode</label>
      <label>Seek back seconds <input id="seekBack" type="number" value="${state.settings.seekBack}"></label>
      <label>Seek forward seconds <input id="seekForward" type="number" value="${state.settings.seekForward}"></label>
      <label>Download retention days <input id="retentionDays" type="number" value="${state.settings.retentionDays}"></label>
      <div class="buttonRow">
        <button id="saveSettings">Save settings</button>
        <button class="ghost" id="compactStorage">Compact storage</button>
        <button class="ghost" id="clearDebugFromSettings">Clear debug log</button>
        <button class="danger" id="clearLocalData">Clear local app data</button>
      </div>
      <p class="muted">${runtime.desktop.stateFilePath ? `Desktop state file: ${escapeHtml(runtime.desktop.stateFilePath)}` : "Browser fallback storage is active for this session."}</p>
    </section>
    <section class="card">
      <h2>Legal / About</h2>
      <p>This app is an independent podcast player. Podcast audio, names, artwork, descriptions, and feeds belong to their respective publishers. The app streams and downloads episodes from public RSS feeds selected by the user. Downloaded episodes are saved locally from official feed enclosure URLs for personal offline listening.</p>
    </section>
  `;
  document.querySelector("#saveSettings").addEventListener("click", () => {
    state.settings.autoplayNext = document.querySelector("#autoplayNext").checked;
    state.settings.seekBack = Number(document.querySelector("#seekBack").value || 15);
    state.settings.seekForward = Number(document.querySelector("#seekForward").value || 30);
    state.settings.retentionDays = Number(document.querySelector("#retentionDays").value || 30);
    saveState();
    setStatus("Settings saved.");
  });
  document.querySelector("#compactStorage").addEventListener("click", () => {
    compactState(state);
    saveState();
    setStatus("Storage compacted. Raw debug previews and long descriptions were trimmed.");
  });
  document.querySelector("#clearDebugFromSettings").addEventListener("click", () => {
    state.debug = [];
    saveState();
    setStatus("Debug log cleared.");
  });
  document.querySelector("#clearLocalData").addEventListener("click", async () => {
    if (!confirm("Clear podcasts, episodes, progress, and debug logs from this device?")) return;
    if (window.desktopApi?.clearState) {
      await window.desktopApi.clearState();
    }
    localStorage.removeItem(BROWSER_STORE_KEY);
    sessionStorage.removeItem("episodeFilter");
    location.reload();
  });
}

async function pickAdRemoverFile() {
  if (!window.desktopApi) {
    setStatus("Ad remover file picking is available only in the Windows desktop build.");
    return;
  }

  const filePath = await window.desktopApi.pickAudioFile();
  if (filePath) {
    adRemoverState().selectedFile = filePath;
    adRemoverState().episodeId = "";
    saveState();
    renderAdRemover();
  }
}

async function saveEpisodeOffline(episodeId) {
  const episode = state.episodes[episodeId];
  if (!episode) return;

  if (episode.downloadStatus === "downloading" || runtime.downloads[episodeId]?.isRunning) {
    setStatus(`Download already in progress for ${episode.title}.`);
    return;
  }

  if (episode.offlineFilePath && episode.offlineRelativePath && episode.downloadStatus === "downloaded") {
    setStatus(`Already saved offline: ${episode.title}`);
    return;
  }

  episode.downloadStatus = "downloading";
  runtime.downloads[episodeId] = {
    episodeId,
    isRunning: true,
    percent: 0,
    downloadedBytes: 0,
    totalBytes: 0,
    stage: "Starting",
    lastLine: "Preparing download",
  };
  saveState();
  render();
  setStatus(`Downloading ${episode.title}...`);
  pushActivityLine(`Download started for ${episode.title}`);

  try {
    let payload;
    if (window.desktopApi?.downloadEpisodeOffline) {
      payload = await window.desktopApi.downloadEpisodeOffline({
        url: episode.enclosureUrl,
        title: episode.title,
        keyHint: episode.id,
        episodeId,
      });
    } else {
      payload = await callApi(
        "Save offline episode",
        `/api/save-offline?url=${encodeURIComponent(episode.enclosureUrl)}&filename=${encodeURIComponent(episode.title)}&key=${encodeURIComponent(episode.id)}`,
      );
    }

    episode.offlineRelativePath = payload.relativePath;
    episode.offlineFilePath = payload.filePath;
    episode.downloadStatus = "downloaded";
    episode.downloadedAt = nowIso();
    runtime.downloads[episodeId] = {
      ...(runtime.downloads[episodeId] || {}),
      episodeId,
      isRunning: false,
      percent: 100,
      downloadedBytes: payload.size || runtime.downloads[episodeId]?.downloadedBytes || 0,
      totalBytes: payload.size || runtime.downloads[episodeId]?.totalBytes || 0,
      stage: "Download complete",
      lastLine: "Download complete",
    };
    saveState();
    setStatus(`Saved offline: ${episode.title}`);
    pushActivityLine(`Download complete for ${episode.title}`);
    render();
    return payload;
  } catch (error) {
    episode.downloadStatus = "not_downloaded";
    runtime.downloads[episodeId] = {
      ...(runtime.downloads[episodeId] || {}),
      episodeId,
      isRunning: false,
      stage: "Download failed",
      lastLine: error.message,
    };
    saveState();
    setStatus(`Save offline failed: ${error.message}`);
    pushActivityLine(`Download failed for ${episode.title}: ${error.message}`, "error");
    render();
    throw error;
  }
}

async function downloadAndRemoveAdsForEpisode(episodeId) {
  const episode = state.episodes[episodeId];
  if (!episode) return;
  if (runtime.adRemover.isRunning && adRemoverState().episodeId === episodeId) {
    setStatus(`Ad removal is already running for ${episode.title}.`);
    return;
  }

  if (!episode.offlineFilePath || episode.downloadStatus !== "downloaded") {
    await saveEpisodeOffline(episodeId);
  }

  await removeAdsForEpisode(episodeId);
}

async function deleteOfflineEpisodeFile(episodeId) {
  const episode = state.episodes[episodeId];
  if (!episode?.offlineFilePath) {
    setStatus("This episode has no downloaded file to delete.");
    return;
  }
  if (episode.downloadStatus === "downloading" || runtime.downloads[episodeId]?.isRunning) {
    setStatus("Wait for the active download to finish before deleting the file.");
    return;
  }
  if (episode.adSupportedStatus === "processing") {
    setStatus("Wait for ad removal to finish before deleting the file.");
    return;
  }
  if (!confirm(`Delete downloaded file for \"${episode.title}\"?`)) return;

  if (window.desktopApi?.deleteOfflineAudioSource) {
    await window.desktopApi.deleteOfflineAudioSource({ filePath: episode.offlineFilePath });
  }
  episode.offlineFilePath = "";
  episode.offlineRelativePath = "";
  episode.downloadStatus = "not_downloaded";
  episode.adSupportedStatus = "";
  episode.adSupportedError = "";
  saveState();
  setStatus(`Deleted downloaded file for ${episode.title}.`);
  pushActivityLine(`Deleted offline file for ${episode.title}`);
  render();
}

function useEpisodeInAdRemover(episodeId) {
  const episode = state.episodes[episodeId];
  if (!episode?.offlineFilePath) {
    setStatus("Save the episode offline before sending it to the ad remover.");
    return;
  }
  const adState = adRemoverState();
  adState.selectedFile = episode.offlineFilePath;
  adState.episodeId = episode.id;
  saveState();
  setRoute("adremover");
  setStatus(`Loaded ${episode.title} into the ad remover.`);
}

async function runAdRemoval(options = {}) {
  const { preserveRoute = false, sourceLabel = "the selected local file", episodeId = "" } = options;
  const adState = adRemoverState();
  if (!window.desktopApi) {
    setStatus("Ad removal is available only in the Windows desktop build.");
    return;
  }

  if (runtime.adRemover.isRunning) {
    setStatus("An ad-removal run is already in progress.");
    return;
  }

  if (!adState.selectedFile) {
    setStatus("Choose a local audio file first.");
    return;
  }

  const targetEpisode = episodeId
    ? state.episodes[episodeId] || null
    : adState.episodeId
      ? state.episodes[adState.episodeId] || null
      : findEpisodeBySourceFile(adState.selectedFile);
  const targetEpisodeId = targetEpisode?.id || "";
  if (targetEpisode) {
    targetEpisode.adSupportedStatus = "processing";
    targetEpisode.adSupportedError = "";
  }
  const removeOriginalAfterExport = adState.removeOriginal && !isOfflineSavedEpisodeFile(adState.selectedFile);

  runtime.adRemover.isRunning = true;
  runtime.adRemover.logs = [];
  runtime.adRemover.percent = 0;
  runtime.adRemover.stage = "Launching processor";
  runtime.adRemover.startedAt = Date.now();
  if (adState.removeOriginal && !removeOriginalAfterExport) {
    runtime.adRemover.logs.push("[info] Preserving offline-saved source audio. Remove-original is disabled for cached episodes.");
  }
  startAdProgressTimer();
  adState.lastResult = null;
  saveState();
  pushActivityLine(`Ad removal started for ${sourceLabel}. Engine: ${adState.backend}, detection: ${adState.detectionMode}`);
  if (!preserveRoute || route === "adremover") {
    renderAdRemover();
  }
  setStatus(`Running ad removal on ${sourceLabel}...`);

  try {
    const result = await window.desktopApi.runProcessing({
      filePath: adState.selectedFile,
      settings: {
        openAiApiKey: adState.openAiKey,
        openAiModel: adState.openAiModel || "gpt-4.1-mini",
        transcriptionBackend: adState.backend,
        detectionMode: adState.detectionMode,
        parakeetPythonPath: "",
        parakeetModel: "nvidia/parakeet-tdt-0.6b-v3",
        removeOriginalAfterExport,
        cacheTtlDays: 30,
      },
    });
    adState.lastResult = targetEpisodeId
      ? await promoteEpisodeSourceToAdFree(targetEpisodeId, result, sourceLabel)
      : result;
    if (targetEpisode) {
      targetEpisode.adSupportedStatus = "processed";
      targetEpisode.adSupportedError = "";
    }
    saveState();
    if (adState.lastResult?.promotedSourcePath) {
      const removedCount = Array.isArray(adState.lastResult.removedSiblingFiles)
        ? adState.lastResult.removedSiblingFiles.length
        : 0;
      pushActivityLine(`Ad-free file active for ${sourceLabel}. Cleanup removed ${removedCount} older file(s).`);
    }
    setStatus(
      targetEpisodeId
        ? `Ad-free export finished. ${sourceLabel} now plays from the cleaned file. Confirmed: only the ad-free file is kept for this episode.`
        : `Ad-free export finished for ${sourceLabel}.`,
    );
  } catch (error) {
    if (targetEpisode) {
      targetEpisode.adSupportedStatus = "failed";
      targetEpisode.adSupportedError = error.message;
    }
    runtime.adRemover.stage = "Failed";
    runtime.adRemover.logs = [...runtime.adRemover.logs, `[error] ${error.message}`].slice(-120);
    pushActivityLine(`Ad removal failed for ${sourceLabel}: ${error.message}`, "error");
    updateAdLogPanel();
    updateAdProgressPanel();
    setStatus(`Ad removal failed for ${sourceLabel}: ${error.message}`);
  } finally {
    runtime.adRemover.isRunning = false;
    if (runtime.adRemover.percent < 100) {
      runtime.adRemover.percent = adState.lastResult ? 100 : runtime.adRemover.percent;
    }
    stopAdProgressTimer();
    updateAdProgressPanel();
    if (!preserveRoute || route === "adremover") {
      renderAdRemover();
    } else {
      render();
    }
    renderActivityPanel();
  }
}

async function removeAdsForEpisode(episodeId) {
  const episode = state.episodes[episodeId];
  if (!episode?.offlineFilePath) {
    setStatus("Save the episode offline before removing ads from it.");
    return;
  }

  const adState = adRemoverState();
  adState.selectedFile = episode.offlineFilePath;
  adState.episodeId = episode.id;
  saveState();
  await runAdRemoval({ preserveRoute: true, sourceLabel: episode.title, episodeId: episode.id });
}

function renderAdRemover() {
  const adState = adRemoverState();
  setTitle("Ad remover", "Desktop audio tool");
  setStatus(
    runtime.desktop.connected
      ? "Pick a local file to generate an ad-free copy."
      : "Ad remover requires the Windows desktop build.",
  );

  els.screen.innerHTML = `
    <section class="twoCol">
      <div class="card">
        <h2>Process local audio</h2>
        <p class="muted">This is the ad-removal component inside the player. It works on local audio files and writes a cleaned copy beside the analysis output.</p>
        <label>Selected local file
          <input id="adSelectedFile" value="${escapeHtml(adState.selectedFile || "")}" readonly>
        </label>
        <div class="buttonRow">
          <button id="adPickFile">Choose file</button>
          <button id="adRunButton" ${!runtime.desktop.connected || runtime.adRemover.isRunning ? "disabled" : ""}>${runtime.adRemover.isRunning ? "Processing..." : "Remove ads"}</button>
        </div>
        <label>Transcription backend
          <select id="adBackend">
            <option value="parakeet" ${adState.backend === "parakeet" ? "selected" : ""}>Parakeet</option>
            <option value="whisper" ${adState.backend === "whisper" ? "selected" : ""}>Whisper local</option>
            <option value="openai-whisper" ${adState.backend === "openai-whisper" ? "selected" : ""}>OpenAI Whisper API</option>
          </select>
        </label>
        <label>Detection mode
          <select id="adDetectionMode">
            <option value="local" ${adState.detectionMode === "local" ? "selected" : ""}>Local</option>
            <option value="hybrid" ${adState.detectionMode === "hybrid" ? "selected" : ""}>Hybrid</option>
            <option value="openai" ${adState.detectionMode === "openai" ? "selected" : ""}>OpenAI only</option>
          </select>
        </label>
        <label>OpenAI model — type the model ID directly (e.g. gpt-4.1-mini, gpt-4.5, o3, o4-mini)
          <input id="adOpenAiModel" value="${escapeHtml(adState.openAiModel || "gpt-4.1-mini")}" placeholder="gpt-4.1-mini">
        </label>
        <label>OpenAI API key (optional)
          <input id="adOpenAiKey" type="password" value="${escapeHtml(adState.openAiKey || "")}" placeholder="sk-...">
        </label>
        <label><input id="adRemoveOriginal" type="checkbox" ${adState.removeOriginal ? "checked" : ""}> Remove original file after export</label>
        <p class="muted">${runtime.desktop.adCutForgeRoot ? `Engine path: ${escapeHtml(runtime.desktop.adCutForgeRoot)}` : "Engine path will appear when the desktop bridge is ready."}</p>
      </div>
      <div class="card">
        <h2>Stage</h2>
        <div class="progressCard">
          <div class="row progressMeta">
            <strong id="adStageText">${escapeHtml(runtime.adRemover.stage || "Idle")}</strong>
            <span id="adPercentText">${Math.round(runtime.adRemover.percent || 0)}%</span>
          </div>
          <div class="progressBar"><div id="adProgressFill" class="progressFill" style="width:${Math.max(0, Math.min(100, runtime.adRemover.percent || 0))}%"></div></div>
          <div class="statGrid">
            <div><span class="muted statLabel">Elapsed</span><strong id="adElapsedText">${formatRuntime(runtime.adRemover.startedAt ? (Date.now() - runtime.adRemover.startedAt) / 1000 : 0)}</strong></div>
            <div><span class="muted statLabel">Time left</span><strong id="adEtaText">--:--</strong></div>
          </div>
          <p id="adLiveStatus" class="muted">${escapeHtml(runtime.adRemover.logs[runtime.adRemover.logs.length - 1] || "Waiting to start.")}</p>
        </div>
        <h2>Result</h2>
        ${adState.lastResult ? `
          <p><strong>Backend:</strong> ${escapeHtml(adState.lastResult.backend || "")}</p>
          <p><strong>Output folder:</strong></p>
          <div class="code">${escapeHtml(adState.lastResult.outputDir || "")}</div>
          <p><strong>Edited audio:</strong></p>
          <div class="code">${escapeHtml(adState.lastResult.editedAudio || "")}</div>
          ${adState.lastResult.keptPath ? `<p><strong>Kept episode source:</strong></p><div class="code">${escapeHtml(adState.lastResult.keptPath)}</div>` : ""}
          ${Array.isArray(adState.lastResult.removedSiblingFiles) ? `<p><strong>Removed old episode files:</strong> ${adState.lastResult.removedSiblingFiles.length}</p>` : ""}
          <p><strong>Run summary:</strong></p>
          <div class="code">${escapeHtml(adState.lastResult.runSummary || "")}</div>
        ` : `<p class="muted">No ad-removal run completed yet.</p>`}
        <h2>Live process log</h2>
        <div id="adLogs" class="code">${escapeHtml(runtime.adRemover.logs.length ? runtime.adRemover.logs.join("\n") : "No process output yet.")}</div>
      </div>
    </section>
  `;

  document.querySelector("#adPickFile")?.addEventListener("click", () => {
    pickAdRemoverFile().catch((error) => setStatus(`File pick failed: ${error.message}`));
  });

  document.querySelector("#adRunButton")?.addEventListener("click", () => {
    adState.backend = document.querySelector("#adBackend").value;
    adState.detectionMode = document.querySelector("#adDetectionMode").value;
    adState.openAiModel = document.querySelector("#adOpenAiModel").value;
    adState.openAiKey = document.querySelector("#adOpenAiKey").value.trim();
    adState.removeOriginal = document.querySelector("#adRemoveOriginal").checked;
    saveState();
    runAdRemoval().catch((error) => setStatus(`Ad removal failed: ${error.message}`));
  });

  updateAdProgressPanel();
  updateAdLogPanel();
}

function renderDebug() {
  setTitle("Debug");
  setStatus("Every API, feed, and audio check made by the desktop app is listed here. Large raw bodies are previewed to avoid storage limits.");
  els.screen.innerHTML = `
    <div class="buttonRow">
      <button id="clearDebug" class="danger">Clear debug log</button>
      <button id="compactDebug" class="ghost">Compact stored data</button>
    </div>
    <div class="logList">
      ${state.debug.length ? state.debug.map((entry, index) => `
        <details ${index === 0 ? "open" : ""}>
          <summary>${escapeHtml(entry.label)} | ${escapeHtml(entry.status)} | ${escapeHtml(entry.at)}</summary>
          <h3>Local app call</h3>
          <div class="code">${escapeHtml(entry.localUrl)}</div>
          <h3>Requested target</h3>
          <div class="code">${escapeHtml(entry.requestedUrl)}</div>
          ${entry.finalUrl && entry.finalUrl !== entry.requestedUrl ? `<h3>Final URL</h3><div class="code">${escapeHtml(entry.finalUrl)}</div>` : ""}
          <h3>Headers</h3>
          <div class="code">${escapeHtml(JSON.stringify(entry.headers, null, 2))}</div>
          <h3>Raw output preview (${escapeHtml(entry.rawLength)} chars)</h3>
          <div class="code">${escapeHtml(String(entry.raw || "").slice(0, 90000))}</div>
          ${entry.sampleHex ? `<h3>Sample hex</h3><div class="code">${escapeHtml(entry.sampleHex)}</div>` : ""}
        </details>
      `).join("") : `<div class="card muted">No debug calls logged yet.</div>`}
    </div>
  `;
  document.querySelector("#clearDebug")?.addEventListener("click", () => {
    state.debug = [];
    saveState();
    renderDebug();
  });
  document.querySelector("#compactDebug")?.addEventListener("click", () => {
    compactState(state);
    saveState();
    renderDebug();
  });
}

function playEpisode(episodeId) {
  const episode = state.episodes[episodeId];
  if (!episode) return;
  episode.isNew = false;
  state.currentEpisodeId = episodeId;
  saveState();
  addDebug("MEDIA PLAY", `/api/audio?url=${encodeURIComponent(episode.enclosureUrl)}`, {
    status: "pending",
    requestedUrl: episode.enclosureUrl,
    finalUrl: "",
    headers: { note: "HTML audio element streams through local proxy." },
    raw: "",
    rawLength: 0,
  });
  renderPlayerDock();
  const audio = document.querySelector("#dockAudio");
  const progress = state.progress[episodeId]?.positionSeconds || 0;
  audio.src = episodeAudioUrl(episode);
  audio.addEventListener("loadedmetadata", () => {
    if (progress && progress < audio.duration - 5) audio.currentTime = progress;
  }, { once: true });
  audio.play().catch((error) => setStatus(`Playback failed: ${error.message}`));
}

function renderPlayerDock() {
  const episode = state.currentEpisodeId ? state.episodes[state.currentEpisodeId] : null;
  if (!episode) {
    els.playerDock.classList.add("hidden");
    els.playerDock.innerHTML = "";
    document.documentElement.style.setProperty("--player-dock-height", "0px");
    return;
  }
  const podcast = state.podcasts[episode.podcastId];
  els.playerDock.classList.remove("hidden");
  els.playerDock.innerHTML = `
    <div class="row">
      <div style="flex:1">
        <strong>${escapeHtml(episode.title)}</strong>
        <div class="muted">${escapeHtml(podcast?.title || "")}</div>
      </div>
      <button class="ghost" id="dockBack">Back ${state.settings.seekBack}</button>
      <button class="ghost" id="dockForward">Forward ${state.settings.seekForward}</button>
      <button class="ghost" id="dockDetails">Details</button>
    </div>
    <audio id="dockAudio" controls preload="metadata"></audio>
  `;
  const audio = document.querySelector("#dockAudio");
  audio.src = episodeAudioUrl(episode);
  audio.addEventListener("timeupdate", () => {
    const duration = audio.duration || episode.durationSeconds || 0;
    state.progress[episode.id] = {
      episodeId: episode.id,
      podcastId: episode.podcastId,
      positionSeconds: Math.floor(audio.currentTime || 0),
      durationSeconds: Math.floor(duration || 0),
      percentComplete: duration ? (audio.currentTime * 100 / duration) : 0,
      lastPlayedAt: nowIso(),
    };
    if (duration && audio.currentTime * 100 / duration >= 95) {
      episode.isListened = true;
      episode.isNew = false;
    }
    saveState();
  });
  audio.addEventListener("ended", () => {
    episode.isListened = true;
    saveState();
    if (state.settings.autoplayNext) {
      const next = podcastEpisodes(episode.podcastId).find((candidate) => Date.parse(candidate.pubDate || 0) < Date.parse(episode.pubDate || 0));
      if (next) playEpisode(next.id);
    }
  });
  document.querySelector("#dockBack").addEventListener("click", () => audio.currentTime = Math.max(0, audio.currentTime - state.settings.seekBack));
  document.querySelector("#dockForward").addEventListener("click", () => audio.currentTime = Math.min(audio.duration || Infinity, audio.currentTime + state.settings.seekForward));
  document.querySelector("#dockDetails").addEventListener("click", () => setRoute("episode", { episodeId: episode.id }));
  window.requestAnimationFrame(() => {
    document.documentElement.style.setProperty("--player-dock-height", `${els.playerDock.offsetHeight}px`);
  });
}

function wireCommonActions() {
  document.querySelectorAll("[data-route-jump]").forEach((el) => el.addEventListener("click", () => setRoute(el.dataset.routeJump)));
  document.querySelectorAll("[data-open-podcast]").forEach((el) => el.addEventListener("click", () => setRoute("podcast", { podcastId: el.dataset.openPodcast })));
  document.querySelectorAll("[data-details]").forEach((el) => el.addEventListener("click", () => setRoute("episode", { episodeId: el.dataset.details })));
  document.querySelectorAll("[data-play]").forEach((el) => el.addEventListener("click", () => playEpisode(el.dataset.play)));
  document.querySelectorAll("[data-load-feed]").forEach((el) => el.addEventListener("click", async () => {
    try { await fetchAndSaveFeed(el.dataset.loadFeed); } catch (e) { setStatus(`Feed failed: ${e.message}`); }
  }));
  document.querySelectorAll("[data-refresh-feed]").forEach((el) => el.addEventListener("click", async () => {
    try { await fetchAndSaveFeed(el.dataset.refreshFeed); } catch (e) { setStatus(`Refresh failed: ${e.message}`); }
  }));
  document.querySelectorAll("[data-open-feed]").forEach((el) => el.addEventListener("click", async () => {
    try { await fetchAndSaveFeed(el.dataset.openFeed); } catch (e) { setStatus(`Feed failed: ${e.message}`); }
  }));
  document.querySelectorAll("[data-fill-feed]").forEach((el) => el.addEventListener("click", () => {
    document.querySelector("#rssInput").value = el.dataset.fillFeed;
  }));
  document.querySelectorAll("[data-subscribe]").forEach((el) => el.addEventListener("click", () => {
    const podcast = state.podcasts[el.dataset.subscribe];
    podcast.subscribed = !podcast.subscribed;
    saveState();
    render();
  }));
  document.querySelectorAll("[data-filter]").forEach((el) => el.addEventListener("click", () => {
    sessionStorage.setItem("episodeFilter", el.dataset.filter);
    renderPodcast();
  }));
  document.querySelectorAll("[data-download]").forEach((el) => el.addEventListener("click", () => {
    const episode = state.episodes[el.dataset.download];
    if (!episode) return;
    episode.downloadStatus = "downloaded";
    episode.downloadedAt = nowIso();
    addDebug("DOWNLOAD MP3", el.getAttribute("href"), {
      status: "browser download",
      requestedUrl: episode.enclosureUrl,
      headers: { note: "Download streamed through local proxy with Content-Disposition attachment." },
      raw: "",
      rawLength: 0,
    });
    saveState();
  }));
  document.querySelectorAll("[data-save-offline]").forEach((el) => el.addEventListener("click", () => {
    saveEpisodeOffline(el.dataset.saveOffline).catch((error) => setStatus(`Save offline failed: ${error.message}`));
  }));
  document.querySelectorAll("[data-download-remove]").forEach((el) => el.addEventListener("click", () => {
    downloadAndRemoveAdsForEpisode(el.dataset.downloadRemove).catch((error) => setStatus(`Download/remove failed: ${error.message}`));
  }));
  document.querySelectorAll("[data-use-offline-ad]").forEach((el) => el.addEventListener("click", () => {
    useEpisodeInAdRemover(el.dataset.useOfflineAd);
  }));
  document.querySelectorAll("[data-remove-ads-now]").forEach((el) => el.addEventListener("click", () => {
    removeAdsForEpisode(el.dataset.removeAdsNow).catch((error) => setStatus(`Ad removal failed: ${error.message}`));
  }));
  document.querySelectorAll("[data-delete-offline]").forEach((el) => el.addEventListener("click", () => {
    deleteOfflineEpisodeFile(el.dataset.deleteOffline).catch((error) => setStatus(`Delete failed: ${error.message}`));
  }));
  document.querySelectorAll("[data-head]").forEach((el) => el.addEventListener("click", async () => {
    try {
      const payload = await callApi("Audio HEAD", `/api/head?url=${encodeURIComponent(el.dataset.head)}`);
      setStatus(`HEAD ${payload.status}: ${payload.headers["content-type"] || "unknown type"} | ${payload.headers["content-length"] || "unknown length"} bytes`);
    } catch (e) {
      setStatus(`HEAD failed: ${e.message}`);
    }
  }));
  document.querySelectorAll("[data-probe]").forEach((el) => el.addEventListener("click", async () => {
    try {
      const payload = await callApi("Audio probe", `/api/probe?url=${encodeURIComponent(el.dataset.probe)}`);
      setStatus(`Probe ${payload.status}: ${payload.headers["content-type"] || "unknown type"} | ${payload.sampleBytes} sample bytes`);
    } catch (e) {
      setStatus(`Probe failed: ${e.message}`);
    }
  }));
  document.querySelectorAll("[data-mark-listened]").forEach((el) => el.addEventListener("click", () => {
    const episode = state.episodes[el.dataset.markListened];
    episode.isListened = !episode.isListened;
    episode.isNew = false;
    saveState();
    render();
  }));
}

document.querySelectorAll(".nav button").forEach((button) => {
  button.addEventListener("click", () => setRoute(button.dataset.route));
});
els.sampleFeedBtn.addEventListener("click", () => fetchAndSaveFeed(SAMPLE_OMNY_FEED).catch((e) => setStatus(`Feed failed: ${e.message}`)));
els.smartlessBtn.addEventListener("click", () => fetchAndSaveFeed(SMARTLESS_FEED).catch((e) => setStatus(`Feed failed: ${e.message}`)));

async function initializeApp() {
  setStatus("Loading local library...");
  els.screen.innerHTML = '<div class="card muted">Loading local library...</div>';
  state = await loadState();
  await initializeDesktopBridge();
  render();
}

initializeApp().catch((error) => {
  console.error("Failed to initialize player UI", error);
  setStatus(`App startup failed: ${error.message}`);
  render();
});
