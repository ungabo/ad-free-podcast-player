import { useEffect, useMemo, useRef, useState } from 'react'

const STORAGE_KEY = 'adfree-web-settings'
const USER_KEY = 'adfree-user-id'
const ACTIVE_JOB_KEY = 'adfree-active-job-id'
const EPISODE_STATE_KEY = 'adfree-web-episode-state'
const DEFAULT_REMOTE_API_BASE = 'https://adsbegone.sitesindevelopment.com/adfree-api'
const isLocalDevHost =
  typeof window !== 'undefined' && ['localhost', '127.0.0.1', '::1'].includes(window.location.hostname)
const API_BASE = (
  import.meta.env.VITE_API_BASE_URL ?? (import.meta.env.DEV && isLocalDevHost ? DEFAULT_REMOTE_API_BASE : '/adfree-api')
).replace(/\/$/, '')
const DEFAULT_BACKEND: Backend = 'tunnel-parakeet'
const SETTINGS_VERSION = 2
const OPENAI_MODEL = 'gpt-5.5'
const APP_ICON_URL = './app-icon.svg'
const JOB_POLL_INTERVAL_MS = 2000
const JOB_SLOW_NOTICE_MS = 10 * 60 * 1000
const JOB_STATUS_TRANSIENT_RETRIES = 12

const SEARCH_CACHE_TTL = 12 * 60 * 60 * 1000
const EPISODE_CACHE_TTL = 12 * 60 * 60 * 1000
const EPISODE_PAGE_SIZE = 40

function getSearchCache(term: string): PodcastResult[] | null {
  try {
    const raw = window.localStorage.getItem(`adfree-search:${term.toLowerCase().trim()}`)
    if (!raw) return null
    const { ts, data } = JSON.parse(raw) as { ts: number; data: PodcastResult[] }
    if (Date.now() - ts > SEARCH_CACHE_TTL) return null
    return data
  } catch { return null }
}

function setSearchCache(term: string, data: PodcastResult[]): void {
  try {
    window.localStorage.setItem(
      `adfree-search:${term.toLowerCase().trim()}`,
      JSON.stringify({ ts: Date.now(), data })
    )
  } catch { /* ignore quota errors */ }
}

function getEpisodeCache(collectionId: number): EpisodeResult[] | null {
  try {
    const raw = window.localStorage.getItem(`adfree-episodes:${collectionId}`)
    if (!raw) return null
    const { ts, data } = JSON.parse(raw) as { ts: number; data: EpisodeResult[] }
    if (Date.now() - ts > EPISODE_CACHE_TTL) return null
    return data
  } catch { return null }
}

function setEpisodeCache(collectionId: number, data: EpisodeResult[]): void {
  try {
    window.localStorage.setItem(
      `adfree-episodes:${collectionId}`,
      JSON.stringify({ ts: Date.now(), data })
    )
  } catch { /* ignore quota errors */ }
}

function loadEpisodeStates(): Record<string, EpisodePlaybackState> {
  try {
    const raw = window.localStorage.getItem(EPISODE_STATE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed as Record<string, EpisodePlaybackState> : {}
  } catch {
    return {}
  }
}

function saveEpisodeStates(states: Record<string, EpisodePlaybackState>): void {
  try {
    window.localStorage.setItem(EPISODE_STATE_KEY, JSON.stringify(states))
  } catch {
    // Best-effort browser state only.
  }
}

function episodeStateKey(podcast: PodcastResult | null, episode: EpisodeResult): string {
  const podcastKey = podcast?.collectionId ? String(podcast.collectionId) : 'podcast'
  return `${podcastKey}:${episode.trackId}`
}

function canonicalMediaUrl(url: string | null | undefined): string {
  const trimmed = (url ?? '').trim()
  if (!trimmed) return ''
  try {
    const parsed = new URL(trimmed)
    parsed.hash = ''
    return parsed.toString().replace(/\/$/, '').toLowerCase()
  } catch {
    return trimmed.replace(/\/$/, '').toLowerCase()
  }
}

function mediaUrlWithoutQuery(url: string | null | undefined): string {
  const trimmed = (url ?? '').trim()
  if (!trimmed) return ''
  try {
    const parsed = new URL(trimmed)
    parsed.hash = ''
    parsed.search = ''
    return parsed.toString().replace(/\/$/, '').toLowerCase()
  } catch {
    return trimmed.split('?')[0].replace(/\/$/, '').toLowerCase()
  }
}

function normalizedTitleKey(value: string | null | undefined): string {
  return (value ?? '').toLowerCase().replace(/\s+/g, ' ').trim()
}

function deferEffect(callback: () => void | Promise<void>): number {
  return window.setTimeout(() => {
    void callback()
  }, 0)
}

type DetectionMode = 'openai'
type AppView = 'home' | 'search' | 'jobs' | 'adfree' | 'about'
type Backend = 'tunnel-parakeet'

type SavedSettings = {
  settingsVersion?: number
  detectionMode: DetectionMode
  backend: Backend
}

type PodcastResult = {
  collectionId: number
  collectionName: string
  artistName?: string
  feedUrl?: string
  artworkUrl600?: string
  artworkUrl100?: string
}

type EpisodeResult = {
  trackId: number
  trackName: string
  description?: string
  releaseDate?: string
  episodeUrl?: string
  previewUrl?: string
  trackTimeMillis?: number
}

type EpisodePlaybackState = {
  positionSeconds?: number
  durationSeconds?: number
  listened?: boolean
  saved?: boolean
  updatedAt?: number
}

type CreateJobResponse = {
  job_id: string
  status: string
  poll_url?: string
  download_url?: string
}

type JobStatusResponse = {
  job_id: string
  status: 'queued' | 'running' | 'completed' | 'failed' | 'cancelled'
  backend: string
  detection_mode: string
  openai_model: string
  source_url: string | null
  episode_title: string | null
  podcast_name: string | null
  progress: number
  duration_seconds: number | null
  error_message: string | null
  logs: string | null
  created_at: string
  updated_at: string
  started_at: string | null
  finished_at: string | null
  download_url: string | null
  transcript_url: string | null
  timestamped_transcript_url: string | null
  timestamps_url: string | null
  stats_url: string | null
  user_id: string | null
}

type JobRecord = JobStatusResponse

type User = {
  id: string
  name: string
  device_fingerprint: string | null
  created_at: string
}

type Subscription = {
  id: string
  user_id: string
  podcast_title: string
  podcast_author: string | null
  feed_url: string
  artwork_url: string | null
  collection_id: number | null
  added_at: string
}

type WorkerStatus = {
  running: boolean
  lock_present: boolean
  heartbeat_age_seconds: number | null
  queue_count: number
  stale_jobs_marked?: number
  start_command: string
  watchdog_command: string
  local_bridge?: {
    configured?: boolean
    reachable?: boolean
    url?: string
    message?: string
    backend_options?: string[]
  } | null
  heartbeat?: {
    state?: string
    time?: string
    mode?: string
  } | null
}

function formatDuration(ms?: number): string {
  if (!ms || ms <= 0) return 'Unknown length'
  const totalSeconds = Math.floor(ms / 1000)
  const h = Math.floor(totalSeconds / 3600)
  const m = Math.floor((totalSeconds % 3600) / 60)
  const s = totalSeconds % 60
  if (h > 0) {
    return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  }
  return `${m}:${String(s).padStart(2, '0')}`
}

function formatSecondsToHMS(seconds: number): string {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = Math.round(seconds % 60)
  if (h > 0) return `${h}h ${m}m ${s}s`
  if (m > 0) return `${m}m ${s}s`
  return `${s}s`
}

function formatTimestamp(iso: string | null): string {
  if (!iso) return '-'
  try {
    return new Date(iso).toLocaleString('en-US', { timeZone: 'America/New_York', dateStyle: 'medium', timeStyle: 'short' }) + ' ET'
  } catch {
    return iso
  }
}

function episodePlayableUrl(episode: EpisodeResult): string {
  return episode.episodeUrl || episode.previewUrl || ''
}

function toAbsoluteUrl(url: string): string {
  if (/^https?:\/\//i.test(url)) {
    return url
  }

  if (url.startsWith('/')) {
    return `${window.location.origin}${url}`
  }

  return `${window.location.origin}/${url}`
}

function apiUrl(path: string): string {
  return `${API_BASE}${path.startsWith('/') ? path : `/${path}`}`
}

function cleanJobText(value: string | null | undefined): string {
  const text = (value ?? '').trim()
  if (!text || /^(null|undefined|unknown episode)$/i.test(text)) return ''
  return text
    .replace(/^(after|before|ad[-\s]*free)[\s:_-]+/i, '')
    .trim()
}

function isHostOnlyTitle(value: string): boolean {
  const text = value.trim().toLowerCase()
  if (!text) return true
  if (/^(podtrac\.com|mgln\.ai|prfx\.byspotify\.com|traffic\.megaphone\.fm)$/i.test(text)) return true
  return /^[a-z0-9.-]+\.[a-z]{2,}$/i.test(text)
}

function jobDisplayTitle(job: Pick<JobStatusResponse, 'episode_title'>): string {
  return cleanJobText(job.episode_title)
}

function jobDisplayPodcast(job: Pick<JobStatusResponse, 'podcast_name'>): string {
  return cleanJobText(job.podcast_name)
}

function completedJobKey(job: JobRecord): string {
  const title = jobDisplayTitle(job).toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim()
  const podcast = jobDisplayPodcast(job).toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim()
  return `${podcast}|${title}`
}

function cleanCompletedJobs(jobs: JobRecord[]): JobRecord[] {
  const seen = new Set<string>()
  return [...jobs]
    .sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime())
    .filter((job) => {
      const title = jobDisplayTitle(job)
      if (!job.download_url || !title || isHostOnlyTitle(title)) return false
      const key = completedJobKey(job)
      if (!key.replace('|', '').trim() || seen.has(key)) return false
      seen.add(key)
      return true
    })
    .slice(0, 40)
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms)
  })
}

function App() {
  const [detectionMode] = useState<DetectionMode>('openai')
  const [backend] = useState<Backend>(DEFAULT_BACKEND)
  const [activeView, setActiveView] = useState<AppView>('home')

  const [searchTerm, setSearchTerm] = useState('')
  const [isSearching, setIsSearching] = useState(false)
  const [hasSearched, setHasSearched] = useState(false)
  const [searchError, setSearchError] = useState('')
  const [podcasts, setPodcasts] = useState<PodcastResult[]>([])
  const [activePodcast, setActivePodcast] = useState<PodcastResult | null>(null)

  const [episodes, setEpisodes] = useState<EpisodeResult[]>([])
  const [episodePage, setEpisodePage] = useState(0)
  const [isLoadingEpisodes, setIsLoadingEpisodes] = useState(false)
  const [episodeError, setEpisodeError] = useState('')
  const [activeEpisode, setActiveEpisode] = useState<EpisodeResult | null>(null)
  const [sourceAudioUrl, setSourceAudioUrl] = useState('')
  const [episodeStates, setEpisodeStates] = useState<Record<string, EpisodePlaybackState>>(() => loadEpisodeStates())

  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isPreparingPlayback, setIsPreparingPlayback] = useState(false)
  const [runError, setRunError] = useState('')
  const [logLines, setLogLines] = useState<string[]>([])
  const [jobStatus, setJobStatus] = useState<JobStatusResponse | null>(null)
  const [processedAudioUrl, setProcessedAudioUrl] = useState('')
  const [directDownloadUrl, setDirectDownloadUrl] = useState('')
  const [activeJobId, setActiveJobId] = useState<string>(() => window.localStorage.getItem(ACTIVE_JOB_KEY) ?? '')
  const [episodeJumpValue, setEpisodeJumpValue] = useState('1')
  const [startNoticeVisible, setStartNoticeVisible] = useState(false)

  const [liveJobs, setLiveJobs] = useState<JobRecord[]>([])
  const [completedJobs, setCompletedJobs] = useState<JobRecord[]>([])
  const [failedJobs, setFailedJobs] = useState<JobRecord[]>([])
  const visibleCompletedJobs = useMemo(() => cleanCompletedJobs(completedJobs), [completedJobs])
  const [selectedHistoryJob, setSelectedHistoryJob] = useState<JobRecord | null>(null)

  const [users, setUsers] = useState<User[]>([])
  const [currentUserId, setCurrentUserId] = useState<string>(
    () => window.localStorage.getItem(USER_KEY) ?? ''
  )
  const [subscriptions, setSubscriptions] = useState<Subscription[]>([])
  const [workerStatus, setWorkerStatus] = useState<WorkerStatus | null>(null)

  const processedUrlRef = useRef<string | null>(null)
  const searchRequestRef = useRef(0)

  const activeAudioLabel = useMemo(() => {
    if (!activeEpisode) return 'No episode selected'
    return activeEpisode.trackName
  }, [activeEpisode])

  const canSubmit = Boolean(activeEpisode && episodePlayableUrl(activeEpisode) && !isSubmitting)
  const windowsBridgeUnavailable = workerStatus?.local_bridge ? !workerStatus.local_bridge.reachable : false
  const totalEpisodePages = Math.max(1, Math.ceil(episodes.length / EPISODE_PAGE_SIZE))
  const currentEpisodePage = Math.min(episodePage, totalEpisodePages - 1)
  const visibleEpisodes = episodes.slice(
    currentEpisodePage * EPISODE_PAGE_SIZE,
    currentEpisodePage * EPISODE_PAGE_SIZE + EPISODE_PAGE_SIZE
  )
  const isBlockingLoading = isSearching || isLoadingEpisodes || isPreparingPlayback || startNoticeVisible
  const loadingTitle = startNoticeVisible ? 'Processing started' : isPreparingPlayback ? 'Downloading' : 'Loading'
  const loadingBody = startNoticeVisible
    ? "Ad removal can take a while. You'll get a notification when the ad-free version is ready."
    : isPreparingPlayback
      ? 'Preparing the ad-free episode for playback.'
      : 'Loading'
  const activeJobTitle = jobStatus ? jobDisplayTitle(jobStatus) : ''
  const activeJobPodcast = jobStatus ? jobDisplayPodcast(jobStatus) : ''
  const processingTitle = activeJobTitle || activeEpisode?.trackName || ''
  const processingPodcast = activeJobPodcast || activePodcast?.collectionName || ''
  const hasProcessingCard = Boolean(jobStatus || isSubmitting || logLines.length > 0)

  function setEpisodePageClamped(page: number): void {
    setEpisodePage(Math.max(0, Math.min(page, totalEpisodePages - 1)))
  }

  function jumpToEpisodePage(): void {
    const cleaned = episodeJumpValue.replace(/\D/g, '')
    setEpisodeJumpValue(cleaned)
    if (!cleaned) return
    const requested = Number.parseInt(cleaned, 10)
    if (!Number.isFinite(requested)) return
    setEpisodePageClamped(requested - 1)
  }

  useEffect(() => {
    const payload: SavedSettings = {
      settingsVersion: SETTINGS_VERSION,
      detectionMode,
      backend,
    }
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
  }, [detectionMode, backend])

  useEffect(() => {
    if (episodePage !== currentEpisodePage) {
      setEpisodePage(currentEpisodePage)
      return
    }
    setEpisodeJumpValue(String(currentEpisodePage + 1))
  }, [currentEpisodePage, episodePage])

  useEffect(() => {
    return () => {
      if (processedUrlRef.current) {
        URL.revokeObjectURL(processedUrlRef.current)
        processedUrlRef.current = null
      }
    }
  }, [])

  function appendLog(message: string): void {
    const stamp = new Date().toLocaleTimeString()
    setLogLines((current) => [...current, `${stamp}  ${message}`].slice(-180))
  }

  function setActiveJob(jobId: string): void {
    setActiveJobId(jobId)
    if (jobId) {
      window.localStorage.setItem(ACTIVE_JOB_KEY, jobId)
    } else {
      window.localStorage.removeItem(ACTIVE_JOB_KEY)
    }
  }

  async function loadPodcastEpisodes(podcast: PodcastResult): Promise<EpisodeResult[]> {
    setEpisodeError('')
    setIsLoadingEpisodes(true)

    try {
      const cached = getEpisodeCache(podcast.collectionId)
      let normalized: EpisodeResult[]

      if (cached) {
        normalized = cached
      } else {
        const lookupUrl = apiUrl(`/api/catalog/episodes?collection_id=${podcast.collectionId}`)
        const response = await fetch(lookupUrl)
        if (!response.ok) {
          throw new Error(`Could not load episodes (${response.status})`)
        }

        const payload = (await response.json()) as { results?: Array<Record<string, unknown>> }
        normalized = (payload.results ?? [])
          .filter((item) => item.wrapperType === 'podcastEpisode')
          .map((item) => ({
            trackId: Number(item.trackId),
            trackName: String(item.trackName ?? 'Untitled episode'),
            description: typeof item.description === 'string' ? item.description : undefined,
            releaseDate: typeof item.releaseDate === 'string' ? item.releaseDate : undefined,
            episodeUrl: typeof item.episodeUrl === 'string' ? item.episodeUrl : undefined,
            previewUrl: typeof item.previewUrl === 'string' ? item.previewUrl : undefined,
            trackTimeMillis: typeof item.trackTimeMillis === 'number' ? item.trackTimeMillis : undefined,
          }))
          .filter((item) => item.trackId > 0)
          .sort((a, b) => {
            const dateA = a.releaseDate ? Date.parse(a.releaseDate) : 0
            const dateB = b.releaseDate ? Date.parse(b.releaseDate) : 0
            return dateB - dateA
          })
        setEpisodeCache(podcast.collectionId, normalized)
      }

      setEpisodes(normalized)

      if (normalized.length > 0) {
        const firstPlayable = normalized.find((item) => episodePlayableUrl(item).length > 0) ?? normalized[0]
        setActiveEpisode(firstPlayable)
        setSourceAudioUrl(episodePlayableUrl(firstPlayable))
      } else {
        setActiveEpisode(null)
        setSourceAudioUrl('')
      }

      return normalized
    } catch (error) {
      setEpisodeError(error instanceof Error ? error.message : 'Could not fetch episodes.')
      setEpisodes([])
      setActiveEpisode(null)
      setSourceAudioUrl('')
      return []
    } finally {
      setIsLoadingEpisodes(false)
    }
  }

  async function selectPodcast(podcast: PodcastResult): Promise<EpisodeResult[]> {
    setActivePodcast(podcast)
    setEpisodePage(0)
    return loadPodcastEpisodes(podcast)
  }

  async function selectSubscription(sub: Subscription): Promise<void> {
    if (!sub.collection_id) {
      setSearchError('This favorite is missing its podcast catalog ID. Search for it once and favorite it again.')
      return
    }

    setSearchError('')
    setPodcasts([])
    setHasSearched(false)
    await selectPodcast({
      collectionId: sub.collection_id,
      collectionName: sub.podcast_title,
      artistName: sub.podcast_author ?? undefined,
      feedUrl: sub.feed_url,
      artworkUrl600: sub.artwork_url ?? undefined,
      artworkUrl100: sub.artwork_url ?? undefined,
    })
  }

  function clearPodcastSearch(): void {
    searchRequestRef.current += 1
    setSearchTerm('')
    setSearchError('')
    setPodcasts([])
    setHasSearched(false)
    setIsSearching(false)
  }

  async function runPodcastSearch(termOverride?: string): Promise<PodcastResult[]> {
    const term = (termOverride ?? searchTerm).trim()
    if (!term) {
      setSearchError('Enter a podcast name or topic.')
      setHasSearched(true)
      return []
    }

    setSearchError('')
    setEpisodeError('')
    setHasSearched(true)
    setIsSearching(true)
    const requestId = searchRequestRef.current + 1
    searchRequestRef.current = requestId

    try {
      const cached = getSearchCache(term)
      let list: PodcastResult[]

      if (cached) {
        list = cached
      } else {
        const url = apiUrl(`/api/catalog/search?term=${encodeURIComponent(term)}`)
        const response = await fetch(url)
        if (!response.ok) {
          throw new Error(`Search failed (${response.status})`)
        }
        const data = (await response.json()) as { results?: PodcastResult[] }
        list = data.results ?? []
        setSearchCache(term, list)
      }

      if (requestId !== searchRequestRef.current) {
        return []
      }

      setPodcasts(list)

      if (list.length > 0) {
        await selectPodcast(list[0])
      } else {
        setActivePodcast(null)
        setEpisodes([])
        setActiveEpisode(null)
        setSourceAudioUrl('')
      }

      return list
    } catch (error) {
      if (requestId === searchRequestRef.current) {
        setSearchError(error instanceof Error ? error.message : 'Search failed.')
      }
      return []
    } finally {
      if (requestId === searchRequestRef.current) {
        setIsSearching(false)
      }
    }
  }

  function chooseEpisode(episode: EpisodeResult): void {
    setActiveEpisode(episode)
    setSourceAudioUrl(episodePlayableUrl(episode))
  }

  function stateForEpisode(episode: EpisodeResult): EpisodePlaybackState {
    return episodeStates[episodeStateKey(activePodcast, episode)] ?? {}
  }

  function updateEpisodeState(episode: EpisodeResult, patch: EpisodePlaybackState): void {
    const key = episodeStateKey(activePodcast, episode)
    setEpisodeStates((current) => {
      const next = {
        ...current,
        [key]: {
          ...(current[key] ?? {}),
          ...patch,
          updatedAt: Date.now(),
        },
      }
      saveEpisodeStates(next)
      return next
    })
  }

  function toggleSavedEpisode(episode: EpisodeResult): void {
    const current = stateForEpisode(episode)
    updateEpisodeState(episode, { saved: !current.saved })
  }

  function adFreeJobForEpisode(episode: EpisodeResult): JobRecord | null {
    const mediaUrl = episodePlayableUrl(episode)
    const canonical = canonicalMediaUrl(mediaUrl)
    const noQuery = mediaUrlWithoutQuery(mediaUrl)
    const episodeTitle = normalizedTitleKey(episode.trackName)
    const podcastTitle = normalizedTitleKey(activePodcast?.collectionName)

    return visibleCompletedJobs.find((job) => {
      if (!job.download_url) return false
      const jobUrl = canonicalMediaUrl(job.source_url)
      const jobNoQuery = mediaUrlWithoutQuery(job.source_url)
      if (canonical && jobUrl && canonical === jobUrl) return true
      if (noQuery && jobNoQuery && noQuery === jobNoQuery) return true
      return Boolean(
        episodeTitle &&
        podcastTitle &&
        normalizedTitleKey(job.episode_title) === episodeTitle &&
        normalizedTitleKey(job.podcast_name) === podcastTitle
      )
    }) ?? null
  }

  function badgesForEpisode(episode: EpisodeResult): string[] {
    const state = stateForEpisode(episode)
    const badges: string[] = []
    if (state.listened) {
      badges.push('Listened')
    } else if ((state.positionSeconds ?? 0) > 10) {
      badges.push('In progress')
    }
    if (state.saved) badges.push('Saved')
    if (adFreeJobForEpisode(episode)) badges.push('Ad-free')
    return badges
  }

  function handleSourceAudioTimeUpdate(audio: HTMLAudioElement): void {
    if (!activeEpisode) return
    const durationSeconds = Number.isFinite(audio.duration) ? Math.max(0, audio.duration) : 0
    const positionSeconds = Number.isFinite(audio.currentTime) ? Math.max(0, audio.currentTime) : 0
    if (positionSeconds < 5 && durationSeconds <= 0) return
    const listened = durationSeconds > 0 && positionSeconds / durationSeconds >= 0.95
    updateEpisodeState(activeEpisode, {
      positionSeconds,
      durationSeconds,
      listened: listened || stateForEpisode(activeEpisode).listened,
    })
  }

  function handleSourceAudioEnded(): void {
    if (!activeEpisode) return
    const durationSeconds = activeEpisode.trackTimeMillis ? activeEpisode.trackTimeMillis / 1000 : undefined
    updateEpisodeState(activeEpisode, { listened: true, durationSeconds })
  }

  async function prepareProcessedPlayback(downloadPath: string): Promise<void> {
    const absolute = toAbsoluteUrl(downloadPath)
    setDirectDownloadUrl(absolute)
    setIsPreparingPlayback(true)

    try {
      const response = await fetch(absolute)
      if (!response.ok) {
        throw new Error(`Could not fetch processed audio (${response.status})`)
      }

      const blob = await response.blob()
      const objectUrl = URL.createObjectURL(blob)

      if (processedUrlRef.current) {
        URL.revokeObjectURL(processedUrlRef.current)
      }

      processedUrlRef.current = objectUrl
      setProcessedAudioUrl(objectUrl)
      appendLog('Processed audio is ready to play in-browser.')
    } finally {
      setIsPreparingPlayback(false)
    }
  }

  async function fetchLiveJobs(): Promise<void> {
    try {
      const userParam = currentUserId ? `&user_id=${encodeURIComponent(currentUserId)}` : ''
      const response = await fetch(apiUrl(`/api/jobs?status=queued,running&limit=20${userParam}`))
      if (!response.ok) return
      const data = (await response.json()) as { jobs: JobRecord[] }
      setLiveJobs(data.jobs ?? [])
    } catch {
      // Best-effort live monitoring
    }
  }

  async function fetchWorkerStatus(): Promise<void> {
    try {
      const response = await fetch(apiUrl('/api/worker/status'))
      if (!response.ok) return
      const data = (await response.json()) as WorkerStatus
      setWorkerStatus(data)
    } catch {
      // Best-effort daemon status
    }
  }

  async function killJob(jobId: string): Promise<void> {
    try {
      await fetch(apiUrl(`/api/jobs/${jobId}/cancel`), { method: 'POST' })
      void fetchLiveJobs()
    } catch {
      // ignore
    }
  }

  async function fetchCompletedJobs(): Promise<void> {
    try {
      const response = await fetch(apiUrl('/api/jobs?status=completed&limit=100'))
      if (!response.ok) return
      const data = (await response.json()) as { jobs: JobRecord[] }
      setCompletedJobs(data.jobs ?? [])
    } catch {
      // Best-effort history
    }
  }

  async function fetchFailedJobs(): Promise<void> {
    try {
      const userParam = currentUserId ? `&user_id=${encodeURIComponent(currentUserId)}` : ''
      const response = await fetch(apiUrl(`/api/jobs?status=failed,cancelled&limit=100${userParam}`))
      if (!response.ok) return
      const data = (await response.json()) as { jobs: JobRecord[] }
      setFailedJobs(data.jobs ?? [])
    } catch {
      // Best-effort
    }
  }

  async function fetchUsers(): Promise<void> {
    try {
      const response = await fetch(apiUrl('/api/users'))
      if (!response.ok) return
      const data = (await response.json()) as { users: User[] }
      const list = data.users ?? []
      setUsers(list)
      // If no user is selected yet, pick the first one (test account).
      if (!currentUserId && list.length > 0) {
        const id = list[0].id
        setCurrentUserId(id)
        window.localStorage.setItem(USER_KEY, id)
      }
    } catch {
      // Best-effort
    }
  }

  async function fetchSubscriptions(userId: string): Promise<void> {
    if (!userId) return
    try {
      const response = await fetch(apiUrl(`/api/users/${encodeURIComponent(userId)}/subscriptions`))
      if (!response.ok) return
      const data = (await response.json()) as { subscriptions: Subscription[] }
      setSubscriptions(data.subscriptions ?? [])
    } catch {
      // Best-effort
    }
  }

  async function toggleSubscription(podcast: PodcastResult): Promise<void> {
    if (!currentUserId) return
    const alreadySubbed = subscriptions.some(
      (s) => s.feed_url === podcast.feedUrl || s.collection_id === podcast.collectionId
    )
    if (alreadySubbed) {
      const sub = subscriptions.find(
        (s) => s.feed_url === podcast.feedUrl || s.collection_id === podcast.collectionId
      )
      if (!sub) return
      await fetch(
        apiUrl(`/api/users/${encodeURIComponent(currentUserId)}/subscriptions/${encodeURIComponent(sub.id)}`),
        { method: 'DELETE' }
      )
    } else {
      await fetch(
        apiUrl(`/api/users/${encodeURIComponent(currentUserId)}/subscriptions`),
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            podcast_title: podcast.collectionName,
            podcast_author: podcast.artistName ?? null,
            feed_url: podcast.feedUrl ?? '',
            artwork_url: podcast.artworkUrl100 ?? podcast.artworkUrl600 ?? null,
            collection_id: podcast.collectionId,
          }),
        }
      )
    }
    void fetchSubscriptions(currentUserId)
  }

  async function deleteJobById(jobId: string): Promise<void> {
    try {
      await fetch(apiUrl(`/api/jobs/${jobId}`), { method: 'DELETE' })
      void fetchCompletedJobs()
      void fetchFailedJobs()
      if (selectedHistoryJob?.job_id === jobId) setSelectedHistoryJob(null)
    } catch {
      // Best-effort
    }
  }

  async function clearAllJobs(status: 'completed' | 'failed' | 'cancelled' | 'all' = 'all'): Promise<void> {
    try {
      await fetch(apiUrl('/api/jobs/clear'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ user_id: currentUserId || undefined, status }),
      })
      void fetchCompletedJobs()
      void fetchFailedJobs()
      setSelectedHistoryJob(null)
    } catch {
      // Best-effort
    }
  }

  async function waitForJob(jobId: string): Promise<void> {
    let transientErrors = 0
    let slowNoticeShown = false
    const waitStartedAt = Date.now()

    while (true) {
      let response: Response | null = null
      let status: (Partial<JobStatusResponse> & { error?: string }) | null = null

      try {
        response = await fetch(apiUrl(`/api/jobs/${jobId}`))
        status = (await response.json()) as Partial<JobStatusResponse> & { error?: string }
      } catch (error) {
        if (transientErrors < JOB_STATUS_TRANSIENT_RETRIES) {
          transientErrors += 1
          const detail = error instanceof Error ? error.message : 'Network status check failed.'
          appendLog(`Status check temporarily failed: ${detail}. Retrying...`)
          await sleep(JOB_POLL_INTERVAL_MS)
          continue
        }
        throw error
      }

      if (!response || !status) {
        throw new Error('Status check failed before the server returned a response.')
      }

      if (!response.ok) {
        const detail = status.error || `Status check failed (${response.status})`
        if (response.status >= 500 && transientErrors < JOB_STATUS_TRANSIENT_RETRIES) {
          transientErrors += 1
          appendLog(`Status check temporarily failed: ${detail}. Retrying...`)
          await sleep(JOB_POLL_INTERVAL_MS)
          continue
        }
        throw new Error(detail)
      }

      transientErrors = 0
      if (!status.job_id || !status.status) {
        throw new Error('Status response was missing job details.')
      }
      const typedStatus = status as JobStatusResponse
      const nextLogs = typedStatus.logs ? typedStatus.logs.split('\n').filter((line) => line.trim().length > 0) : []
      setJobStatus(typedStatus)
      setLogLines((current) => {
        if (nextLogs.length === 0) {
          return current
        }
        if (current.length === nextLogs.length && current.every((line, index) => line === nextLogs[index])) {
          return current
        }
        return nextLogs.slice(-180)
      })
      appendLog(`Job ${jobId} status: ${typedStatus.status} (${Math.round(typedStatus.progress)}%)`)

      if (typedStatus.status === 'completed') {
        if (!typedStatus.download_url) {
          throw new Error('Job completed but download URL was missing.')
        }

        setActiveJob('')
        await prepareProcessedPlayback(typedStatus.download_url)
        return
      }

      if (typedStatus.status === 'failed' || typedStatus.status === 'cancelled') {
        setActiveJob('')
        throw new Error(typedStatus.error_message || `Job ${typedStatus.status}.`)
      }

      if (!slowNoticeShown && Date.now() - waitStartedAt >= JOB_SLOW_NOTICE_MS) {
        slowNoticeShown = true
        appendLog('Still processing on the server. You can leave this page open or come back later; the Ad-free list will update when it finishes.')
      }

      await sleep(JOB_POLL_INTERVAL_MS)
    }
  }

  async function submitEpisodeForProcessing(
    episode: EpisodeResult,
    sourceLabel: string,
    options?: { backend?: Backend; detectionMode?: DetectionMode; podcast?: PodcastResult | null }
  ): Promise<void> {
    const mediaUrl = episodePlayableUrl(episode)
    if (!mediaUrl) {
      setRunError('This episode does not expose an audio URL from iTunes.')
      return
    }

    setRunError('')
    setIsSubmitting(true)
    setJobStatus(null)
    setDirectDownloadUrl('')
    setProcessedAudioUrl('')
    setLogLines([])
    setActiveJob('')

    const effectiveBackend = options?.backend ?? backend
    const effectiveDetectionMode: DetectionMode = 'openai'

    if (workerStatus?.local_bridge && !workerStatus.local_bridge.reachable) {
      const message = 'Ads cannot be removed right now because the Windows processor is offline. Start WAMP and the Windows tunnel, then try again.'
      setRunError(message)
      appendLog(`Error: ${message}`)
      setIsSubmitting(false)
      return
    }

    appendLog(`Submitting ${sourceLabel} for ad removal.`)

    try {
      const form = new FormData()
      form.append('source_url', mediaUrl)
      form.append('backend', effectiveBackend)
      form.append('detection_mode', effectiveDetectionMode)
      form.append('openai_model', OPENAI_MODEL)
      if (currentUserId) {
        form.append('user_id', currentUserId)
      }
      // Pass episode metadata so the server can store it on the job.
      form.append('episode_title', episode.trackName)
      const podcastForJob = options?.podcast ?? activePodcast
      if (podcastForJob) {
        form.append('podcast_name', podcastForJob.collectionName)
      }

      const response = await fetch(apiUrl('/api/jobs'), {
        method: 'POST',
        body: form,
      })

      const createResult = (await response.json()) as Partial<CreateJobResponse> & { error?: string }
      if (!response.ok) {
        throw new Error(createResult.error || `Job submit failed (${response.status})`)
      }

      if (!createResult.job_id) {
        throw new Error('API did not return a job id.')
      }

      appendLog(`Job queued: ${createResult.job_id}`)
      setActiveJob(createResult.job_id)
      setStartNoticeVisible(true)
      window.setTimeout(() => setStartNoticeVisible(false), 3000)
      await waitForJob(createResult.job_id)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Processing failed.'
      setRunError(message)
      appendLog(`Error: ${message}`)
    } finally {
      setIsSubmitting(false)
    }
  }

  useEffect(() => {
    const liveKickoff = deferEffect(fetchLiveJobs)
    const workerKickoff = deferEffect(fetchWorkerStatus)
    const interval = setInterval(() => void fetchLiveJobs(), 5000)
    const workerInterval = setInterval(() => void fetchWorkerStatus(), 10000)
    return () => {
      clearTimeout(liveKickoff)
      clearTimeout(workerKickoff)
      clearInterval(interval)
      clearInterval(workerInterval)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const kickoff = deferEffect(() => {
      void fetchCompletedJobs()
      void fetchFailedJobs()
    })
    const interval = setInterval(() => { void fetchCompletedJobs(); void fetchFailedJobs() }, 30000)
    return () => {
      clearTimeout(kickoff)
      clearInterval(interval)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    const kickoff = deferEffect(fetchUsers)
    return () => clearTimeout(kickoff)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (!activeJobId) return

    const kickoff = deferEffect(() => waitForJob(activeJobId))
    return () => clearTimeout(kickoff)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (currentUserId) {
      window.localStorage.setItem(USER_KEY, currentUserId)
      const kickoff = deferEffect(() => {
        void fetchSubscriptions(currentUserId)
        void fetchLiveJobs()
        void fetchCompletedJobs()
        void fetchFailedJobs()
      })
      return () => clearTimeout(kickoff)
    }
    return undefined
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentUserId])

  useEffect(() => {
    if (jobStatus?.status === 'completed' || jobStatus?.status === 'failed') {
      const kickoff = deferEffect(() => {
        void fetchLiveJobs()
        void fetchCompletedJobs()
        void fetchFailedJobs()
      })
      return () => clearTimeout(kickoff)
    }
    return undefined
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jobStatus?.status])

  return (
    <div className="app-shell">
      <header id="home" className="topbar">
        <div className="brand-block">
          <h1>Ad Free Podcast Player</h1>
          <p className="topbar-subtitle">
            Windows processor {workerStatus?.local_bridge?.reachable ? 'available' : 'offline'}
          </p>
        </div>
        {users.length > 0 ? (
          <div className="user-selector-row">
            <label htmlFor="user-select">Account</label>
            <select
              id="user-select"
              value={currentUserId}
              onChange={(e) => setCurrentUserId(e.target.value)}
            >
              {users.map((u) => (
                <option key={u.id} value={u.id}>{u.name}</option>
              ))}
            </select>
          </div>
        ) : null}
      </header>

      <nav className="app-menu" aria-label="App menu">
        {([
          ['home', 'Home'],
          ['search', 'Search'],
          ['jobs', 'Jobs'],
          ['adfree', 'Ad-free'],
          ['about', 'About'],
        ] as const).map(([view, label]) => (
          <button
            key={view}
            type="button"
            className={activeView === view ? 'active' : ''}
            onClick={() => setActiveView(view)}
          >
            {label}
          </button>
        ))}
      </nav>

      {isBlockingLoading ? (
        <div className="loading-overlay" role="status" aria-live="polite">
          <div className="loading-card">
            <strong>{loadingTitle}</strong>
            <span>{loadingBody}</span>
          </div>
        </div>
      ) : null}

      {activeView === 'home' ? (
        <section className="home-dashboard">
          <div className="panel home-brand-panel">
            <img src={APP_ICON_URL} alt="" className="app-brand-image" />
            <div>
              <h2>Ad Free Podcast Player</h2>
              <p className="tiny">Search, queue, and play ad-free podcast episodes.</p>
              <button type="button" onClick={() => setActiveView('search')}>Search podcasts</button>
            </div>
          </div>

          <div className="panel">
            <div className="history-section-header">
              <h2 className="section-heading">Queued & Processing</h2>
              <button type="button" className="clear-all-btn" onClick={() => setActiveView('jobs')}>View all</button>
            </div>
            {liveJobs.length === 0 ? (
              <p className="tiny">No queued or processing conversions right now.</p>
            ) : (
              <div className="live-jobs-list">
                {liveJobs.slice(0, 4).map((job) => (
                  <div key={job.job_id} className="live-job-card">
                    <div className="live-job-main">
                      {jobDisplayPodcast(job) ? <span className="history-podcast-name">{jobDisplayPodcast(job)}</span> : null}
                      <strong>{jobDisplayTitle(job) || 'Conversion job'}</strong>
                    </div>
                    <div className="active-job-meta">
                      <span className={`status ${job.status === 'running' ? 'on' : 'neutral'}`}>{job.status}</span>
                      <span className="tiny">{Math.round(job.progress)}%</span>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          <div className="panel">
            <div className="history-section-header">
              <h2 className="section-heading">Recent Ad-free</h2>
              <button type="button" className="clear-all-btn" onClick={() => setActiveView('adfree')}>View all</button>
            </div>
            {visibleCompletedJobs.length === 0 ? (
              <p className="tiny">No completed conversions yet.</p>
            ) : (
              <div className="history-list">
                {visibleCompletedJobs.slice(0, 5).map((job) => (
                  <button key={job.job_id} type="button" className="history-item" onClick={() => setSelectedHistoryJob(job)}>
                    <div className="history-item-main">
                      {jobDisplayPodcast(job) ? <span className="history-podcast-name">{jobDisplayPodcast(job)}</span> : null}
                      <strong className="history-episode-title">{jobDisplayTitle(job)}</strong>
                    </div>
                    <span className="status on">Ready</span>
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className="panel">
            <h2 className="section-heading">Favorites</h2>
            {subscriptions.length === 0 ? (
              <p className="tiny">Search for podcasts and favorite them here.</p>
            ) : (
              <div className="sub-list">
                {subscriptions.slice(0, 6).map((sub) => (
                  <button
                    key={sub.id}
                    type="button"
                    className="sub-item"
                    onClick={() => {
                      setActiveView('search')
                      void selectSubscription(sub)
                    }}
                  >
                    {sub.artwork_url ? <img src={sub.artwork_url} alt="" className="sub-art" /> : null}
                    <span>{sub.podcast_title}</span>
                  </button>
                ))}
              </div>
            )}
          </div>
        </section>
      ) : null}

      {activeView === 'search' ? (
      <section className="player-layout">
        <div id="search" className="panel search-panel">
          <h2>Search</h2>

          {subscriptions.length > 0 ? (
            <div className="subscriptions-panel">
              <p className="panel-subheading">Your favorites</p>
              <div className="sub-list">
                {subscriptions.map((sub) => (
                  <button
                    key={sub.id}
                    type="button"
                    className="sub-item"
                    onClick={() => void selectSubscription(sub)}
                  >
                    {sub.artwork_url ? (
                      <img src={sub.artwork_url} alt="" className="sub-art" />
                    ) : null}
                    <span>{sub.podcast_title}</span>
                  </button>
                ))}
              </div>
            </div>
          ) : null}
          <div className="search-row">
            <div className="search-input-wrap">
              <input
                type="text"
                value={searchTerm}
                onChange={(event) => {
                  const nextValue = event.target.value
                  setSearchTerm(nextValue)
                  if (nextValue === '' && (podcasts.length > 0 || hasSearched || searchError)) {
                    clearPodcastSearch()
                  }
                }}
                placeholder="podcast name"
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    void runPodcastSearch()
                  }
                }}
              />
              {searchTerm || podcasts.length > 0 || hasSearched ? (
                <button
                  type="button"
                  className="search-clear-btn"
                  aria-label="Clear podcast search"
                  title="Clear podcast search"
                  onClick={clearPodcastSearch}
                >
                  x
                </button>
              ) : null}
            </div>
            <button type="button" onClick={() => void runPodcastSearch()} disabled={isSearching}>
              {isSearching ? 'Searching...' : 'Search'}
            </button>
          </div>

          {searchError ? <p className="error-text">{searchError}</p> : null}

          <div className="podcast-list">
            {hasSearched && podcasts.length === 0 && !searchError ? <p className="tiny">No podcasts found.</p> : null}
            {podcasts.slice(0, 8).map((podcast) => (
              <button
                key={podcast.collectionId}
                type="button"
                className={`podcast-item ${activePodcast?.collectionId === podcast.collectionId ? 'active' : ''}`}
                onClick={() => void selectPodcast(podcast)}
              >
                <img
                  src={podcast.artworkUrl100 || podcast.artworkUrl600 || ''}
                  alt=""
                  onError={(event) => {
                    ;(event.currentTarget as HTMLImageElement).style.visibility = 'hidden'
                  }}
                />
                <span>
                  <strong>{podcast.collectionName}</strong>
                  <small>{podcast.artistName || 'Unknown publisher'}</small>
                </span>
              </button>
            ))}
          </div>
        </div>

        <div className="panel episode-panel">
          <div className="episode-header">
            <div>
              <h2>{activePodcast?.collectionName || 'Episodes'}</h2>
              <p className="tiny">{activePodcast?.artistName || ''}</p>
            </div>
            <div className="episode-panel-actions">
              <span className="status neutral">{episodes.length} episodes</span>
              {activePodcast ? (
                <button
                  type="button"
                  className={`sub-toggle-btn ${subscriptions.some((s) => s.collection_id === activePodcast.collectionId || s.feed_url === activePodcast.feedUrl) ? 'subbed' : ''}`}
                  onClick={() => void toggleSubscription(activePodcast)}
                >
                  {subscriptions.some((s) => s.collection_id === activePodcast.collectionId || s.feed_url === activePodcast.feedUrl)
                    ? '* Favorited'
                    : '+ Favorite'}
                </button>
              ) : null}
            </div>
          </div>

          {episodeError ? <p className="error-text">{episodeError}</p> : null}
          {isLoadingEpisodes ? <p className="tiny">Loading episodes...</p> : null}

          <div className="episode-list">
            {visibleEpisodes.map((episode) => {
              const state = stateForEpisode(episode)
              const badges = badgesForEpisode(episode)
              return (
                <div
                  key={episode.trackId}
                  className={`episode-item ${activeEpisode?.trackId === episode.trackId ? 'active' : ''}`}
                >
                  <div
                    className="episode-item-main"
                    role="button"
                    tabIndex={0}
                    onClick={() => chooseEpisode(episode)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault()
                        chooseEpisode(episode)
                      }
                    }}
                  >
                    <strong>{episode.trackName}</strong>
                    <small>
                      {episode.releaseDate ? new Date(episode.releaseDate).toLocaleDateString() : 'Date unknown'}
                      {' - '}
                      {formatDuration(episode.trackTimeMillis)}
                    </small>
                    {badges.length > 0 ? (
                      <div className="episode-badges">
                        {badges.map((badge) => (
                          <span key={badge} className={`episode-badge ${badge.toLowerCase().replace(/\s+/g, '-')}`}>
                            {badge}
                          </span>
                        ))}
                      </div>
                    ) : null}
                  </div>
                  <button
                    type="button"
                    className={`episode-save-btn ${state.saved ? 'saved' : ''}`}
                    onClick={(event) => {
                      event.stopPropagation()
                      toggleSavedEpisode(episode)
                    }}
                  >
                    {state.saved ? 'Saved' : 'Save'}
                  </button>
                </div>
              )
            })}
          </div>
          {episodes.length > EPISODE_PAGE_SIZE ? (
            <>
              <div className="episode-pagination">
                <button
                  type="button"
                  className="page-btn"
                  disabled={currentEpisodePage === 0}
                  onClick={() => setEpisodePageClamped(currentEpisodePage - 1)}
                >Previous</button>
                <span className="page-info">{currentEpisodePage + 1}</span>
                <button
                  type="button"
                  className="page-btn"
                  disabled={currentEpisodePage >= totalEpisodePages - 1}
                  onClick={() => setEpisodePageClamped(currentEpisodePage + 1)}
                >Next</button>
              </div>
              <div className="jump-row">
                <label htmlFor="episode-jump">Jump to page</label>
                <input
                  id="episode-jump"
                  inputMode="numeric"
                  value={episodeJumpValue}
                  onBlur={jumpToEpisodePage}
                  onChange={(event) => setEpisodeJumpValue(event.target.value.replace(/\D/g, ''))}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') jumpToEpisodePage()
                  }}
                />
                <span className="tiny">of {totalEpisodePages}</span>
              </div>
            </>
          ) : null}

          <div className="player-bar">
            <p className="now-playing">
              Source episode: <strong>{activeAudioLabel}</strong>
            </p>
            <audio
              key={sourceAudioUrl}
              controls
              src={sourceAudioUrl}
              className="audio-player"
              onTimeUpdate={(event) => handleSourceAudioTimeUpdate(event.currentTarget)}
              onEnded={handleSourceAudioEnded}
            />
            {!sourceAudioUrl ? <p className="tiny">This episode has no playable audio URL from iTunes.</p> : null}
          </div>
        </div>

        <div className="panel processing-panel">
          <h2>Remove ads</h2>

          <div className="button-row">
            <button
              type="button"
              disabled={!canSubmit || isPreparingPlayback || windowsBridgeUnavailable}
              onClick={() => {
                if (!activeEpisode) return
                void submitEpisodeForProcessing(activeEpisode, activeEpisode.trackName)
              }}
            >
              {isSubmitting ? 'Processing...' : 'Remove ads from selected episode'}
            </button>
          </div>

          <div className="status-stack">
            {windowsBridgeUnavailable ? (
              <span className="status off">Ads cannot be removed right now</span>
            ) : null}
            {workerStatus?.local_bridge ? (
              <span className={workerStatus.local_bridge.reachable ? 'status on' : 'status off'}>
                Windows processor {workerStatus.local_bridge.reachable ? 'available' : 'offline'}
              </span>
            ) : null}
            {jobStatus ? (
              <span className={jobStatus.status === 'completed' ? 'status on' : 'status neutral'}>
                Job {jobStatus.status} ({Math.round(jobStatus.progress)}%)
              </span>
            ) : null}
          </div>

          {workerStatus ? (
            <div className={`worker-status-card ${workerStatus.running ? 'ok' : 'warn'}`}>
              <div className="worker-status-top">
                <strong>{workerStatus.running ? 'Daemon online' : 'Daemon needs attention'}</strong>
                <span className={workerStatus.running ? 'status on' : 'status off'}>
                  {workerStatus.heartbeat?.state ?? (workerStatus.running ? 'active' : 'offline')}
                </span>
              </div>
              <p className="tiny">
                Queue: {workerStatus.queue_count}
                {' | '}
                Heartbeat: {workerStatus.heartbeat_age_seconds == null ? 'not seen' : `${workerStatus.heartbeat_age_seconds}s ago`}
              </p>
              {workerStatus.local_bridge ? (
                <p className="tiny">
                  Windows processor: {workerStatus.local_bridge.reachable ? 'available' : 'offline'}
                </p>
              ) : null}
              {workerStatus.local_bridge && !workerStatus.local_bridge.reachable ? (
                <p className="tiny">
                  Ads cannot be removed right now. Start WAMP, then run the Windows scheduled task named AdFree Local Reverse Tunnel.
                </p>
              ) : null}
              {workerStatus.stale_jobs_marked ? (
                <p className="tiny">
                  Recovered {workerStatus.stale_jobs_marked} stalled conversion{workerStatus.stale_jobs_marked === 1 ? '' : 's'} as failed.
                </p>
              ) : null}
              {!workerStatus.running ? (
                <>
                  <p className="tiny">Start it over SSH with:</p>
                  <code className="worker-command">{workerStatus.start_command}</code>
                </>
              ) : null}
            </div>
          ) : null}

          {runError ? <p className="error-text">{runError}</p> : null}

          {hasProcessingCard ? (
            <div className="result-panel active-job-card">
              <div className="active-job-heading">
                {processingPodcast ? <span className="history-podcast-name">{processingPodcast}</span> : null}
                <strong className="active-job-title">{processingTitle || 'Ad removal job'}</strong>
              </div>
              <div className="active-job-meta">
                {jobStatus ? (
                  <span className={jobStatus.status === 'completed' ? 'status on' : 'status neutral'}>
                    {jobStatus.status}
                  </span>
                ) : (
                  <span className="status neutral">starting</span>
                )}
                {jobStatus ? <span className="tiny">{Math.round(jobStatus.progress)}%</span> : null}
                {jobStatus?.backend ? <span className="tiny">{jobStatus.backend}</span> : null}
                {jobStatus?.created_at ? <span className="tiny">Queued {formatTimestamp(jobStatus.created_at)}</span> : null}
              </div>
              <div className="progress-bar-track">
                <div
                  className="progress-bar-fill"
                  style={{ width: `${Math.max(2, Math.round(jobStatus?.progress ?? 2))}%` }}
                />
              </div>
              {jobStatus?.job_id ? <p><strong>Job id:</strong> {jobStatus.job_id}</p> : null}
              {jobStatus?.started_at ? <p><strong>Started:</strong> {formatTimestamp(jobStatus.started_at)}</p> : null}
              {jobStatus?.error_message ? <p className="error-text">{jobStatus.error_message}</p> : null}
              <h3>Live process log</h3>
              <div className="log-panel">
                {logLines.length === 0 ? 'No process output yet.' : [...logLines].reverse().join('\n')}
              </div>
            </div>
          ) : null}

          <h3>Processed output</h3>
          {isPreparingPlayback ? <p className="tiny">Preparing playback stream...</p> : null}
          {processedAudioUrl ? <audio controls src={processedAudioUrl} className="audio-player" /> : null}
          {directDownloadUrl ? (
            <p className="tiny">
              <a href={directDownloadUrl} target="_blank" rel="noreferrer">Download processed episode</a>
            </p>
          ) : null}

        </div>
      </section>
      ) : null}

      {activeView === 'jobs' ? (
      <section id="jobs" className="live-section">
        <h2 className="section-heading">Queued & Processing</h2>
        {liveJobs.length === 0 ? (
          <p className="tiny">No queued or processing conversions right now.</p>
        ) : (
          <div className="live-jobs-list">
            {liveJobs.map((job) => (
              <div key={job.job_id} className="live-job-card">
                <div className="live-job-top">
                  <span className={`status ${job.status === 'running' ? 'on' : 'neutral'}`}>
                    {job.status}
                  </span>
                  <span className="live-job-id">{job.job_id.slice(0, 8)}...</span>
                  <span className="tiny">{job.backend} / {job.detection_mode}</span>
                  <span className="live-job-pct">{Math.round(job.progress)}%</span>
                  <button
                    type="button"
                    className="kill-btn"
                    title="Kill this job"
                    onClick={() => void killJob(job.job_id)}
                  >
                    Kill
                  </button>
                </div>
                {jobDisplayTitle(job) || jobDisplayPodcast(job) ? (
                  <div className="live-job-main">
                    {jobDisplayPodcast(job) ? <span className="history-podcast-name">{jobDisplayPodcast(job)}</span> : null}
                    {jobDisplayTitle(job) ? <strong>{jobDisplayTitle(job)}</strong> : null}
                  </div>
                ) : null}
                {job.started_at ? (
                  <div className="live-job-started tiny">Started: {formatTimestamp(job.started_at)}</div>
                ) : job.created_at ? (
                  <div className="live-job-started tiny">Queued: {formatTimestamp(job.created_at)}</div>
                ) : null}
                <div className="progress-bar-track">
                  <div
                    className="progress-bar-fill"
                    style={{ width: `${Math.max(2, Math.round(job.progress))}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        )}
      </section>
      ) : null}

      {activeView === 'adfree' ? (
      <section id="ad-free" className="history-section">
        <div className="history-section-header">
          <h2 className="section-heading">Ad-free episodes</h2>
          {visibleCompletedJobs.length > 0 ? (
            <button type="button" className="clear-all-btn" onClick={() => void clearAllJobs('completed')}>
              Clear completed
            </button>
          ) : null}
        </div>
        {visibleCompletedJobs.length === 0 ? (
          <p className="tiny">No completed conversions yet.</p>
        ) : (
          <div className="history-list">
            {visibleCompletedJobs.map((job) => (
              <div key={job.job_id} className="history-item-wrap">
                <button
                  type="button"
                  className="history-item"
                  onClick={() => setSelectedHistoryJob(job)}
                >
                  <div className="history-item-main">
                    {jobDisplayPodcast(job) ? <span className="history-podcast-name">{jobDisplayPodcast(job)}</span> : null}
                    <strong className="history-episode-title">
                      {jobDisplayTitle(job)}
                    </strong>
                  </div>
                  <div className="history-item-meta">
                    <span className="tiny">{formatTimestamp(job.created_at)}</span>
                    {job.duration_seconds != null ? (
                      <span className="tiny">{formatSecondsToHMS(job.duration_seconds)}</span>
                    ) : null}
                    <span className="tiny history-job-id">{job.job_id.slice(0, 8)}</span>
                    {job.download_url ? (
                      <span className="status on">Ready</span>
                    ) : (
                      <span className="status neutral">No file</span>
                    )}
                  </div>
                </button>
                <button
                  type="button"
                  className="delete-job-btn"
                  title="Delete this job"
                  onClick={() => void deleteJobById(job.job_id)}
                >x</button>
              </div>
            ))}
          </div>
        )}
      </section>
      ) : null}

      {activeView === 'jobs' && failedJobs.length > 0 ? (
        <section className="history-section log-section">
          <div className="history-section-header">
            <h2 className="section-heading">Conversion log</h2>
            <button type="button" className="clear-all-btn" onClick={() => void clearAllJobs('all')}>
              Clear all
            </button>
          </div>
          <div className="history-list">
            {failedJobs.map((job) => (
              <div key={job.job_id} className="history-item-wrap">
                <button
                  type="button"
                  className="history-item history-item-failed"
                  onClick={() => setSelectedHistoryJob(job)}
                >
                  <div className="history-item-main">
                    {jobDisplayPodcast(job) ? <span className="history-podcast-name">{jobDisplayPodcast(job)}</span> : null}
                    <strong className="history-episode-title">
                      {jobDisplayTitle(job) || 'Conversion record'}
                    </strong>
                    {job.error_message ? <span className="history-error-msg">{job.error_message}</span> : null}
                  </div>
                  <div className="history-item-meta">
                    <span className={`status ${job.status === 'cancelled' ? 'neutral' : 'off'}`}>{job.status}</span>
                    <span className="tiny">{formatTimestamp(job.created_at)}</span>
                    <span className="tiny history-job-id">{job.job_id.slice(0, 8)}</span>
                  </div>
                </button>
                <button
                  type="button"
                  className="delete-job-btn"
                  title="Delete this record"
                  onClick={() => void deleteJobById(job.job_id)}
                >x</button>
              </div>
            ))}
          </div>
        </section>
      ) : null}

      {selectedHistoryJob ? (
        <div
          className="detail-overlay"
          role="dialog"
          aria-modal="true"
          onClick={(e) => { if (e.target === e.currentTarget) setSelectedHistoryJob(null) }}
        >
          <div className="detail-modal">
            <div className="detail-modal-header">
              <h2>Episode detail</h2>
              <button type="button" className="close-btn" onClick={() => setSelectedHistoryJob(null)}>x</button>
            </div>

            {selectedHistoryJob.episode_title ? (
              <p><strong>{selectedHistoryJob.episode_title}</strong></p>
            ) : null}
            {selectedHistoryJob.podcast_name ? (
              <p className="tiny">{selectedHistoryJob.podcast_name}</p>
            ) : null}

            <div className="result-panel">
              <p><strong>Job ID:</strong> {selectedHistoryJob.job_id}</p>
              <p><strong>Status:</strong> {selectedHistoryJob.status}</p>
              {selectedHistoryJob.duration_seconds != null ? (
                <p><strong>Processing time:</strong> {formatSecondsToHMS(selectedHistoryJob.duration_seconds)}</p>
              ) : null}
              <p><strong>Queued:</strong> {formatTimestamp(selectedHistoryJob.created_at)}</p>
              <p><strong>Started:</strong> {formatTimestamp(selectedHistoryJob.started_at)}</p>
              <p><strong>Finished:</strong> {formatTimestamp(selectedHistoryJob.finished_at)}</p>
              {selectedHistoryJob.source_url ? (
                <p><strong>Source:</strong> <span className="tiny">{selectedHistoryJob.source_url}</span></p>
              ) : null}
            </div>

            <div className="detail-links">
              {selectedHistoryJob.download_url ? (
                <a
                  href={toAbsoluteUrl(selectedHistoryJob.download_url)}
                  target="_blank"
                  rel="noreferrer"
                  className="detail-link-btn"
                >
                  Download ad-free audio
                </a>
              ) : null}
              {selectedHistoryJob.transcript_url ? (
                <a
                  href={toAbsoluteUrl(selectedHistoryJob.transcript_url)}
                  target="_blank"
                  rel="noreferrer"
                  className="detail-link-btn secondary"
                >
                  View transcript
                </a>
              ) : null}
              {selectedHistoryJob.timestamped_transcript_url ? (
                <a
                  href={toAbsoluteUrl(selectedHistoryJob.timestamped_transcript_url)}
                  target="_blank"
                  rel="noreferrer"
                  className="detail-link-btn secondary"
                >
                  Timestamped transcript
                </a>
              ) : null}
              {selectedHistoryJob.timestamps_url ? (
                <a
                  href={toAbsoluteUrl(selectedHistoryJob.timestamps_url)}
                  target="_blank"
                  rel="noreferrer"
                  className="detail-link-btn secondary"
                >
                  Ad timestamps (JSON)
                </a>
              ) : null}
              {selectedHistoryJob.stats_url ? (
                <a
                  href={toAbsoluteUrl(selectedHistoryJob.stats_url)}
                  target="_blank"
                  rel="noreferrer"
                  className="detail-link-btn secondary"
                >
                  Processing stats
                </a>
              ) : null}
            </div>

            {selectedHistoryJob.download_url ? (
              <audio
                controls
                src={toAbsoluteUrl(selectedHistoryJob.download_url)}
                className="audio-player"
              />
            ) : null}
          </div>
        </div>
      ) : null}

      {activeView === 'about' ? (
        <section id="about" className="history-section about-panel">
          <img src={APP_ICON_URL} alt="" className="about-app-image" />
          <h2 className="section-heading">About</h2>
          <p className="tiny">
            This app queues Windows tunnel processing through the PHP API. Completed files are served back from the PHP server.
          </p>
          <p className="tiny">
            Only remove ads from podcasts you have the rights to edit or listen to in this format.
          </p>
          <p className="tiny">
            Built {new Date(__BUILD_TIME__).toLocaleString('en-US', { timeZone: 'America/New_York', dateStyle: 'medium', timeStyle: 'short' })} ET
          </p>
        </section>
      ) : null}

      <footer className="footer-note">
        <a href="./downloads/ad-free-podcast-player-latest.apk" download>Download Android APK</a>
      </footer>
    </div>
  )
}

export default App
