export {}

declare global {
  interface Window {
    desktopApi?: {
      loadSettings: (filePath: string) => Promise<unknown>
      getCapabilities: () => Promise<{ isDesktop: boolean; adCutForgeRoot: string }>
      pickAudioFile: () => Promise<string | null>
      runProcessing: (payload: {
        filePath: string
        settings: {
          openAiApiKey: string
          openAiModel: string
          transcriptionBackend: 'whisper' | 'openai-whisper'
          detectionMode: 'local' | 'hybrid' | 'openai'
          removeOriginalAfterExport: boolean
          cacheTtlDays: number
        }
      }) => Promise<{
        ok: boolean
        exitCode: number | null
        backend: string
        pythonPath: string
        adCutForgeRoot: string
        outputDir: string
        editedAudio: string
        runSummary: string
        logs: string
      }>
      onProcessingEvent: (listener: (payload: { type: string; line: string }) => void) => () => void
    }
  }
}
