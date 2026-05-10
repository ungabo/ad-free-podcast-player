import { useEffect, useMemo, useState } from 'react'

const STORAGE_KEY = 'adfree-podcast-player-settings'

type SavedSettings = {
  openAiKey: string
  detectionMode: 'local' | 'hybrid' | 'openai'
  backend: 'parakeet' | 'whisper' | 'openai-whisper'
  removeOriginal: boolean
}

type PodcastResult = {
  collectionId: number
  collectionName: string
  artistName?: string
  feedUrl?: string
  artworkUrl600?: string
  artworkUrl100?: string
  trackCount?: number
  primaryGenreName?: string
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

function loadSavedSettings(): SavedSettings | null {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    return JSON.parse(raw) as SavedSettings
  } catch {
    return null
  }
}

function App() {
  const savedSettings = loadSavedSettings()
  const [openAiKey, setOpenAiKey] = useState(savedSettings?.openAiKey ?? '')
  const [detectionMode, setDetectionMode] = useState<'local' | 'hybrid' | 'openai'>(savedSettings?.detectionMode ?? 'local')
  const [backend, setBackend] = useState<'parakeet' | 'whisper' | 'openai-whisper'>(savedSettings?.backend ?? 'parakeet')
  const [removeOriginal, setRemoveOriginal] = useState(savedSettings?.removeOriginal ?? true)
  const [searchTerm, setSearchTerm] = useState('daily')
  const [isSearching, setIsSearching] = useState(false)
  const [searchError, setSearchError] = useState('')
  const [podcasts, setPodcasts] = useState<PodcastResult[]>([])
  const [activePodcast, setActivePodcast] = useState<PodcastResult | null>(null)
  const [episodes, setEpisodes] = useState<EpisodeResult[]>([])
  const [isLoadingEpisodes, setIsLoadingEpisodes] = useState(false)
  const [episodeError, setEpisodeError] = useState('')
  const [activeEpisode, setActiveEpisode] = useState<EpisodeResult | null>(null)
  const [audioUrl, setAudioUrl] = useState('')

  const [selectedFile, setSelectedFile] = useState('')
  const [isDesktop, setIsDesktop] = useState(false)
  const [desktopRoot, setDesktopRoot] = useState('')
  const [isRunning, setIsRunning] = useState(false)
  const [runError, setRunError] = useState('')
  const [logLines, setLogLines] = useState<string[]>([])
  const [lastResult, setLastResult] = useState<null | {
    backend: string
    outputDir: string
    editedAudio: string
    runSummary: string
  }>(null)

  useEffect(() => {
    let unsubscribe: (() => void) | undefined

    if (!window.desktopApi) {
      return
    }

    window.desktopApi.getCapabilities().then((capabilities) => {
      setIsDesktop(capabilities.isDesktop)
      setDesktopRoot(capabilities.adCutForgeRoot)
      setBackend('parakeet')
      setDetectionMode('local')
    })

    unsubscribe = window.desktopApi.onProcessingEvent((event) => {
      setLogLines((current) => [...current, `[${event.type}] ${event.line}`].slice(-120))
    })

    return () => {
      unsubscribe?.()
    }
  }, [])

  useEffect(() => {
    const payload: SavedSettings = {
      openAiKey,
      detectionMode,
      backend,
      removeOriginal,
    }
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
  }, [openAiKey, detectionMode, backend, removeOriginal])

  const isOpenAiEnabled = openAiKey.trim().length > 0
  const canRunDesktopJob = isDesktop && selectedFile.trim().length > 0 && !isRunning

  const activeAudioLabel = useMemo(() => {
    if (!activeEpisode) return 'No episode selected'
    return activeEpisode.trackName
  }, [activeEpisode])

  const formatDuration = (ms?: number) => {
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

  const runPodcastSearch = async () => {
    const term = searchTerm.trim()
    if (!term) {
      setSearchError('Enter a podcast name or topic.')
      return
    }

    setSearchError('')
    setEpisodeError('')
    setIsSearching(true)

    try {
      const url = `https://itunes.apple.com/search?media=podcast&limit=24&term=${encodeURIComponent(term)}`
      const response = await fetch(url)
      if (!response.ok) {
        throw new Error(`Search failed (${response.status})`)
      }

      const data = (await response.json()) as { results?: PodcastResult[] }
      const list = data.results ?? []
      setPodcasts(list)
      if (list.length > 0) {
        setActivePodcast(list[0])
      } else {
        setActivePodcast(null)
        setEpisodes([])
        setActiveEpisode(null)
        setAudioUrl('')
      }
    } catch (error) {
      setSearchError(error instanceof Error ? error.message : 'Search failed.')
    } finally {
      setIsSearching(false)
    }
  }

  const loadPodcastEpisodes = async (podcast: PodcastResult) => {
    setActivePodcast(podcast)
    setEpisodeError('')
    setIsLoadingEpisodes(true)

    try {
      const lookupUrl = `https://itunes.apple.com/lookup?id=${podcast.collectionId}&entity=podcastEpisode&limit=40`
      const response = await fetch(lookupUrl)
      if (!response.ok) {
        throw new Error(`Could not load episodes (${response.status})`)
      }
      const payload = (await response.json()) as { results?: Array<Record<string, unknown>> }
      const normalized = (payload.results ?? [])
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

      setEpisodes(normalized)
      if (normalized.length > 0) {
        const firstPlayable = normalized.find((ep) => ep.episodeUrl || ep.previewUrl) ?? normalized[0]
        setActiveEpisode(firstPlayable)
        setAudioUrl(firstPlayable.episodeUrl || firstPlayable.previewUrl || '')
      } else {
        setActiveEpisode(null)
        setAudioUrl('')
      }
    } catch (error) {
      setEpisodeError(error instanceof Error ? error.message : 'Could not fetch episodes.')
      setEpisodes([])
      setActiveEpisode(null)
      setAudioUrl('')
    } finally {
      setIsLoadingEpisodes(false)
    }
  }

  const chooseEpisode = (episode: EpisodeResult) => {
    setActiveEpisode(episode)
    setAudioUrl(episode.episodeUrl || episode.previewUrl || '')
  }

  const pickAudioFile = async () => {
    if (!window.desktopApi) {
      setRunError('File picking is only enabled in the Windows desktop shell right now.')
      return
    }

    setRunError('')
    const filePath = await window.desktopApi.pickAudioFile()
    if (filePath) {
      setSelectedFile(filePath)
    }
  }

  const runProcessing = async () => {
    if (!window.desktopApi) {
      setRunError('Processing is currently wired through the Windows desktop shell.')
      return
    }

    if (!selectedFile) {
      setRunError('Select an audio file first.')
      return
    }

    setRunError('')
    setLogLines([])
    setLastResult(null)
    setIsRunning(true)

    try {
      const result = await window.desktopApi.runProcessing({
        filePath: selectedFile,
        settings: {
          openAiApiKey: openAiKey,
          openAiModel: 'gpt-4o-mini',
          transcriptionBackend: backend,
          detectionMode,
          parakeetPythonPath: '',
          parakeetModel: 'nvidia/parakeet-tdt-0.6b-v3',
          removeOriginalAfterExport: removeOriginal,
          cacheTtlDays: 30,
        },
      })

      setLastResult({
        backend: result.backend,
        outputDir: result.outputDir,
        editedAudio: result.editedAudio,
        runSummary: result.runSummary,
      })
    } catch (error) {
      setRunError(error instanceof Error ? error.message : 'Processing failed.')
    } finally {
      setIsRunning(false)
    }
  }

  useEffect(() => {
    runPodcastSearch()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (!activePodcast) return
    loadPodcastEpisodes(activePodcast)
  }, [activePodcast?.collectionId])

  return (
    <div className="app-shell">
      <header className="topbar">
        <p className="kicker">Windows Desktop Player</p>
        <h1>Ad Free Podcast Player</h1>
        <p className="lede">
          Search podcasts, play episodes, and run ad removal when you want a cleaned local copy.
        </p>
      </header>

      <section className="player-layout">
        <div className="panel search-panel">
          <h2>Find podcasts</h2>
          <div className="search-row">
            <input
              type="text"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              placeholder="Search podcasts"
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  runPodcastSearch()
                }
              }}
            />
            <button type="button" onClick={runPodcastSearch} disabled={isSearching}>
              {isSearching ? 'Searching...' : 'Search'}
            </button>
          </div>
          {searchError ? <p className="error-text">{searchError}</p> : null}
          <div className="podcast-list">
            {podcasts.length === 0 ? <p className="tiny">No podcasts found yet.</p> : null}
            {podcasts.map((podcast) => (
              <button
                key={podcast.collectionId}
                type="button"
                className={`podcast-item ${activePodcast?.collectionId === podcast.collectionId ? 'active' : ''}`}
                onClick={() => setActivePodcast(podcast)}
              >
                <img
                  src={podcast.artworkUrl100 || podcast.artworkUrl600 || ''}
                  alt=""
                  onError={(e) => {
                    ;(e.currentTarget as HTMLImageElement).style.visibility = 'hidden'
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
            <span className="status neutral">{episodes.length} episodes</span>
          </div>

          {episodeError ? <p className="error-text">{episodeError}</p> : null}
          {isLoadingEpisodes ? <p className="tiny">Loading episodes...</p> : null}

          <div className="episode-list">
            {episodes.map((episode) => (
              <button
                key={episode.trackId}
                type="button"
                className={`episode-item ${activeEpisode?.trackId === episode.trackId ? 'active' : ''}`}
                onClick={() => chooseEpisode(episode)}
              >
                <strong>{episode.trackName}</strong>
                <small>
                  {episode.releaseDate ? new Date(episode.releaseDate).toLocaleDateString() : 'Date unknown'}
                  {' · '}
                  {formatDuration(episode.trackTimeMillis)}
                </small>
              </button>
            ))}
          </div>

          <div className="player-bar">
            <p className="now-playing">Now playing: <strong>{activeAudioLabel}</strong></p>
            <audio key={audioUrl} controls src={audioUrl} className="audio-player" />
            {!audioUrl ? <p className="tiny">This episode has no playable audio URL from iTunes.</p> : null}
          </div>
        </div>

        <div className="panel ad-panel">
          <h2>Ad remover</h2>
          <p className="tiny">
            Pick a local audio file and generate an ad-free copy.
          </p>

          <label>
            Selected local file
            <input type="text" value={selectedFile} readOnly placeholder="Choose an MP3 or other audio file" />
          </label>

          <div className="button-row">
            <button type="button" onClick={pickAudioFile}>Choose file</button>
            <button type="button" onClick={runProcessing} disabled={!canRunDesktopJob}>
              {isRunning ? 'Processing...' : 'Remove ads'}
            </button>
          </div>

          <label>
            Backend
            <select value={backend} onChange={(e) => setBackend(e.target.value as 'parakeet' | 'whisper' | 'openai-whisper')}>
              <option value="parakeet">Parakeet</option>
              <option value="whisper">Whisper local</option>
              <option value="openai-whisper">OpenAI Whisper API</option>
            </select>
          </label>

          <label>
            Detection mode
            <select value={detectionMode} onChange={(e) => setDetectionMode(e.target.value as 'local' | 'hybrid' | 'openai')}>
              <option value="local">Local</option>
              <option value="hybrid">Hybrid</option>
              <option value="openai">OpenAI only</option>
            </select>
          </label>

          <label>
            OpenAI API key (optional)
            <input
              type="password"
              placeholder="sk-..."
              value={openAiKey}
              onChange={(e) => setOpenAiKey(e.target.value)}
            />
          </label>

          <label className="inline-toggle">
            <input
              type="checkbox"
              checked={removeOriginal}
              onChange={(e) => setRemoveOriginal(e.target.checked)}
            />
            Remove original file after export
          </label>

          <div className="status-stack">
            <span className={isDesktop ? 'status on' : 'status off'}>
              {isDesktop ? 'Desktop bridge connected' : 'Desktop bridge offline'}
            </span>
            <span className={isOpenAiEnabled ? 'status on' : 'status neutral'}>
              {isOpenAiEnabled ? 'OpenAI key loaded' : 'OpenAI optional'}
            </span>
          </div>

          {desktopRoot ? <p className="tiny">Engine: {desktopRoot}</p> : null}
          {runError ? <p className="error-text">{runError}</p> : null}
          {lastResult ? (
            <div className="result-panel">
              <p><strong>Backend:</strong> {lastResult.backend}</p>
              <p><strong>Output folder:</strong> {lastResult.outputDir || 'Not reported'}</p>
              <p><strong>Edited audio:</strong> {lastResult.editedAudio || 'No cut audio produced'}</p>
              <p><strong>Run summary:</strong> {lastResult.runSummary || 'Not reported'}</p>
            </div>
          ) : null}

          <h3>Live process log</h3>
          <div className="log-panel">
            {logLines.length === 0 ? 'No process output yet.' : logLines.join('\n')}
          </div>
        </div>
      </section>

      <footer className="footer-note">
        This is a Windows desktop app. Podcast search uses iTunes public API, and ad removal runs locally via AdCutForge.
      </footer>
    </div>
  )
}

export default App
