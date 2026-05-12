import { useEffect, useMemo, useRef, useState } from 'react'

const STORAGE_KEY = 'adfree-web-settings'
const USER_KEY = 'adfree-user-id'
const ACTIVE_JOB_KEY = 'adfree-active-job-id'
const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? '/adfree-api').replace(/\/$/, '')

const SEARCH_CACHE_TTL = 24 * 60 * 60 * 1000
const EPISODE_CACHE_TTL = 6 * 60 * 60 * 1000

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

type DetectionMode = 'local' | 'hybrid' | 'openai'
type Backend = 'whisper' | 'openai-whisper'

type SavedSettings = {
  openAiKey: string
  openAiModel: string
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
  progress: number
  error_message: string | null
  logs: string | null
  created_at: string
  updated_at: string
  started_at: string | null
  finished_at: string | null
  download_url: string | null
}

type JobRecord = JobStatusResponse & {
  source_url: string | null
  episode_title: string | null
  podcast_name: string | null
  duration_seconds: number | null
  transcript_url: string | null
  timestamped_transcript_url: string | null
  timestamps_url: string | null
  stats_url: string | null
  user_id: string | null
}

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
  start_command: string
  watchdog_command: string
  heartbeat?: {
    state?: string
    time?: string
    mode?: string
  } | null
}

function loadSavedSettings(): SavedSettings | null {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    return JSON.parse(raw) as SavedSettings
  } catch {
    return null
  }
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
  if (!iso) return '—'
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

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms)
  })
}

function App() {
  const savedSettings = loadSavedSettings()

  const [openAiKey, setOpenAiKey] = useState(savedSettings?.openAiKey ?? '')
  const [openAiModel, setOpenAiModel] = useState(savedSettings?.openAiModel ?? 'gpt-4o-mini')
  const [detectionMode, setDetectionMode] = useState<DetectionMode>(savedSettings?.detectionMode ?? 'hybrid')
  const [backend, setBackend] = useState<Backend>(
    savedSettings?.backend === 'whisper' ? 'whisper' : 'openai-whisper'
  )

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

  const [isSubmitting, setIsSubmitting] = useState(false)
  const [isPreparingPlayback, setIsPreparingPlayback] = useState(false)
  const [runError, setRunError] = useState('')
  const [logLines, setLogLines] = useState<string[]>([])
  const [jobStatus, setJobStatus] = useState<JobStatusResponse | null>(null)
  const [processedAudioUrl, setProcessedAudioUrl] = useState('')
  const [directDownloadUrl, setDirectDownloadUrl] = useState('')
  const [activeJobId, setActiveJobId] = useState<string>(() => window.localStorage.getItem(ACTIVE_JOB_KEY) ?? '')

  const [liveJobs, setLiveJobs] = useState<JobRecord[]>([])
  const [completedJobs, setCompletedJobs] = useState<JobRecord[]>([])
  const [failedJobs, setFailedJobs] = useState<JobRecord[]>([])
  const [selectedHistoryJob, setSelectedHistoryJob] = useState<JobRecord | null>(null)

  const [users, setUsers] = useState<User[]>([])
  const [currentUserId, setCurrentUserId] = useState<string>(
    () => window.localStorage.getItem(USER_KEY) ?? ''
  )
  const [subscriptions, setSubscriptions] = useState<Subscription[]>([])
  const [workerStatus, setWorkerStatus] = useState<WorkerStatus | null>(null)

  const processedUrlRef = useRef<string | null>(null)

  const activeAudioLabel = useMemo(() => {
    if (!activeEpisode) return 'No episode selected'
    return activeEpisode.trackName
  }, [activeEpisode])

  const canSubmit = Boolean(activeEpisode && episodePlayableUrl(activeEpisode) && !isSubmitting)
  const isOpenAiEnabled = openAiKey.trim().length > 0

  useEffect(() => {
    const payload: SavedSettings = {
      openAiKey,
      openAiModel,
      detectionMode,
      backend,
    }
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
  }, [openAiKey, openAiModel, detectionMode, backend])

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
      setSearchError(error instanceof Error ? error.message : 'Search failed.')
      return []
    } finally {
      setIsSearching(false)
    }
  }

  function chooseEpisode(episode: EpisodeResult): void {
    setActiveEpisode(episode)
    setSourceAudioUrl(episodePlayableUrl(episode))
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
      const userParam = currentUserId ? `&user_id=${encodeURIComponent(currentUserId)}` : ''
      const response = await fetch(apiUrl(`/api/jobs?status=completed&limit=100${userParam}`))
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
    for (let attempt = 0; attempt < 240; attempt += 1) {
      const response = await fetch(apiUrl(`/api/jobs/${jobId}`))
      const status = (await response.json()) as Partial<JobStatusResponse> & { error?: string }

      if (!response.ok) {
        const detail = status.error || `Status check failed (${response.status})`
        throw new Error(detail)
      }

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

      await sleep(2500)
    }

    throw new Error('Timed out waiting for the processing job to finish.')
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
    const effectiveDetectionMode = options?.detectionMode ?? detectionMode

    appendLog(`Submitting ${sourceLabel} with backend ${effectiveBackend} and detection ${effectiveDetectionMode}.`)

    try {
      const form = new FormData()
      form.append('source_url', mediaUrl)
      form.append('backend', effectiveBackend)
      form.append('detection_mode', effectiveDetectionMode)
      form.append('openai_model', openAiModel.trim() || 'gpt-4o-mini')
      if (openAiKey.trim()) {
        form.append('openai_api_key', openAiKey.trim())
      }
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
    void fetchLiveJobs()
    void fetchWorkerStatus()
    const interval = setInterval(() => void fetchLiveJobs(), 5000)
    const workerInterval = setInterval(() => void fetchWorkerStatus(), 10000)
    return () => {
      clearInterval(interval)
      clearInterval(workerInterval)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    void fetchCompletedJobs()
    void fetchFailedJobs()
    const interval = setInterval(() => { void fetchCompletedJobs(); void fetchFailedJobs() }, 30000)
    return () => clearInterval(interval)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    void fetchUsers()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (!activeJobId) return

    void waitForJob(activeJobId)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (currentUserId) {
      window.localStorage.setItem(USER_KEY, currentUserId)
      void fetchSubscriptions(currentUserId)
      void fetchLiveJobs()
      void fetchCompletedJobs()
      void fetchFailedJobs()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentUserId])

  useEffect(() => {
    if (jobStatus?.status === 'completed' || jobStatus?.status === 'failed') {
      void fetchLiveJobs()
      void fetchCompletedJobs()
      void fetchFailedJobs()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jobStatus?.status])

  return (
    <div className="app-shell">
      <header className="topbar">
        <p className="kicker">Linux Web Interface</p>
        <h1>Ad Free Podcast Player</h1>
        <p className="lede">
          Search for podcasts, pick an episode, and run server-side ad removal with transcripts and ad timestamps.
        </p>
        {users.length > 0 ? (
          <div className="user-selector-row">
            <label htmlFor="user-select">Account:</label>
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

      <section className="player-layout">
        <div className="panel search-panel">
          <h2>Find podcasts</h2>

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
            <input
              type="text"
              value={searchTerm}
              onChange={(event) => setSearchTerm(event.target.value)}
              placeholder="Search podcasts"
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  void runPodcastSearch()
                }
              }}
            />
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
            {episodes.slice(episodePage * 10, episodePage * 10 + 10).map((episode) => (
              <button
                key={episode.trackId}
                type="button"
                className={`episode-item ${activeEpisode?.trackId === episode.trackId ? 'active' : ''}`}
                onClick={() => chooseEpisode(episode)}
              >
                <strong>{episode.trackName}</strong>
                <small>
                  {episode.releaseDate ? new Date(episode.releaseDate).toLocaleDateString() : 'Date unknown'}
                  {' - '}
                  {formatDuration(episode.trackTimeMillis)}
                </small>
              </button>
            ))}
          </div>
          {episodes.length > 10 ? (
            <div className="episode-pagination">
              <button
                type="button"
                className="page-btn"
                disabled={episodePage === 0}
                onClick={() => setEpisodePage((p) => p - 1)}
              >&lt; Prev</button>
              <span className="page-info">{episodePage + 1} / {Math.ceil(episodes.length / 10)}</span>
              <button
                type="button"
                className="page-btn"
                disabled={(episodePage + 1) * 10 >= episodes.length}
                onClick={() => setEpisodePage((p) => p + 1)}
              >Next &gt;</button>
            </div>
          ) : null}

          <div className="player-bar">
            <p className="now-playing">
              Source episode: <strong>{activeAudioLabel}</strong>
            </p>
            <audio key={sourceAudioUrl} controls src={sourceAudioUrl} className="audio-player" />
            {!sourceAudioUrl ? <p className="tiny">This episode has no playable audio URL from iTunes.</p> : null}
          </div>
        </div>

        <div className="panel processing-panel">
          <h2>Server ad remover</h2>
          <p className="tiny">Configured API base: {API_BASE}</p>

          <div className="processing-field-row">
            <label>
              Backend
              <select value={backend} onChange={(event) => setBackend(event.target.value as Backend)}>
                <option value="openai-whisper">OpenAI Whisper API</option>
                <option value="whisper">Local Whisper (server install required)</option>
              </select>
            </label>
            <label>
              Detection
              <select value={detectionMode} onChange={(event) => setDetectionMode(event.target.value as DetectionMode)}>
                <option value="local">Local</option>
                <option value="hybrid">Hybrid</option>
                <option value="openai">OpenAI only</option>
              </select>
            </label>
          </div>

          <div className="processing-field-row">
            <label>
              OpenAI model
              <input
                type="text"
                value={openAiModel}
                onChange={(event) => setOpenAiModel(event.target.value)}
                placeholder="gpt-4o-mini"
              />
            </label>
            <label>
              OpenAI key (blank uses server key)
              <input
                type="password"
                placeholder="sk-..."
                value={openAiKey}
                onChange={(event) => setOpenAiKey(event.target.value)}
              />
            </label>
          </div>

          <div className="button-row">
            <button
              type="button"
              disabled={!canSubmit || isPreparingPlayback}
              onClick={() => {
                if (!activeEpisode) return
                void submitEpisodeForProcessing(activeEpisode, activeEpisode.trackName)
              }}
            >
              {isSubmitting ? 'Processing...' : 'Remove ads from selected episode'}
            </button>
          </div>

          <div className="status-stack">
            <span className="status on">Default backend: OpenAI Whisper</span>
            <span className={isOpenAiEnabled ? 'status on' : 'status neutral'}>
              {isOpenAiEnabled ? 'Browser key loaded' : 'Using server key if configured'}
            </span>
            {workerStatus ? (
              <span className={workerStatus.running ? 'status on' : 'status off'}>
                Worker {workerStatus.running ? 'running' : 'needs start'}
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
              {!workerStatus.running ? (
                <>
                  <p className="tiny">Start it over SSH with:</p>
                  <code className="worker-command">{workerStatus.start_command}</code>
                </>
              ) : null}
            </div>
          ) : null}

          {runError ? <p className="error-text">{runError}</p> : null}

          {jobStatus ? (
            <div className="result-panel">
              <p><strong>Job id:</strong> {jobStatus.job_id}</p>
              <p><strong>Backend:</strong> {jobStatus.backend}</p>
              <p><strong>Detection:</strong> {jobStatus.detection_mode}</p>
              <p><strong>Status:</strong> {jobStatus.status}</p>
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

          <h3>Live process log</h3>
          <div className="log-panel">
            {logLines.length === 0 ? 'No process output yet.' : [...logLines].reverse().join('\n')}
          </div>
        </div>
      </section>

      {liveJobs.length > 0 ? (
        <section className="live-section">
          <h2 className="section-heading">Active conversions</h2>
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
        </section>
      ) : null}

      <section className="history-section">
        <div className="history-section-header">
          <h2 className="section-heading">Ad-free episodes</h2>
          {completedJobs.length > 0 ? (
            <button type="button" className="clear-all-btn" onClick={() => void clearAllJobs('completed')}>
              Clear completed
            </button>
          ) : null}
        </div>
        {completedJobs.length === 0 ? (
          <p className="tiny">No completed conversions yet.</p>
        ) : (
          <div className="history-list">
            {completedJobs.map((job) => (
              <div key={job.job_id} className="history-item-wrap">
                <button
                  type="button"
                  className="history-item"
                  onClick={() => setSelectedHistoryJob(job)}
                >
                  <div className="history-item-main">
                    {job.podcast_name ? <span className="history-podcast-name">{job.podcast_name}</span> : null}
                    <strong className="history-episode-title">
                      {job.episode_title ?? (() => { try { return job.source_url ? new URL(job.source_url).hostname : null } catch { return null } })() ?? 'Unknown episode'}
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

      {failedJobs.length > 0 ? (
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
                    {job.podcast_name ? <span className="history-podcast-name">{job.podcast_name}</span> : null}
                    <strong className="history-episode-title">
                      {job.episode_title ?? (() => { try { return job.source_url ? new URL(job.source_url).hostname : null } catch { return null } })() ?? 'Unknown episode'}
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
              <p><strong>Backend:</strong> {selectedHistoryJob.backend} / {selectedHistoryJob.detection_mode}</p>
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

      <footer className="footer-note">
        This web app is wired to the Linux API worker stack and defaults to OpenAI Whisper + hybrid ad detection.
        {' · '}Built {new Date(__BUILD_TIME__).toLocaleString('en-US', { timeZone: 'America/New_York', dateStyle: 'medium', timeStyle: 'short' })} ET
      </footer>
    </div>
  )
}

export default App
