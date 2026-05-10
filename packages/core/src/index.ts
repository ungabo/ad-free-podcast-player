export type TranscriptionBackend = 'parakeet' | 'whisper' | 'openai-whisper';
export type AdDetectionMode = 'local' | 'hybrid' | 'openai';

export type AppPlatform = 'web' | 'windows' | 'android';

export interface ProcessingSettings {
  openAiApiKey: string;
  openAiModel: string;
  transcriptionBackend: TranscriptionBackend;
  detectionMode: AdDetectionMode;
  parakeetPythonPath: string;
  parakeetModel: string;
  removeOriginalAfterExport: boolean;
  cacheTtlDays: number;
}

export const DEFAULT_SETTINGS: ProcessingSettings = {
  openAiApiKey: '',
  openAiModel: 'gpt-4o-mini',
  transcriptionBackend: 'openai-whisper',
  detectionMode: 'hybrid',
  parakeetPythonPath: '',
  parakeetModel: 'nvidia/parakeet-tdt-0.6b-v3',
  removeOriginalAfterExport: true,
  cacheTtlDays: 30,
};

export function resolveDefaultSettings(platform: AppPlatform): ProcessingSettings {
  if (platform === 'windows') {
    return {
      ...DEFAULT_SETTINGS,
      transcriptionBackend: 'parakeet',
      detectionMode: 'local',
    };
  }

  if (platform === 'android') {
    return {
      ...DEFAULT_SETTINGS,
      transcriptionBackend: 'openai-whisper',
      detectionMode: 'hybrid',
    };
  }

  return DEFAULT_SETTINGS;
}

export function resolveBackendWithFallback(
  requested: TranscriptionBackend,
  capabilities: { parakeetAvailable: boolean; whisperAvailable: boolean },
): TranscriptionBackend {
  if (requested === 'parakeet' && capabilities.parakeetAvailable) {
    return 'parakeet';
  }

  if (requested === 'parakeet' && capabilities.whisperAvailable) {
    return 'whisper';
  }

  if (requested === 'whisper' && capabilities.whisperAvailable) {
    return 'whisper';
  }

  return 'openai-whisper';
}

export interface AdRange {
  start: number;
  end: number;
  confidence: number;
  reason: string;
  source: 'local' | 'openai';
}

export interface ProcessingJob {
  inputPath: string;
  outputPath: string;
  settings: ProcessingSettings;
}

export interface ProcessingResult {
  adRanges: AdRange[];
  transcriptPath?: string;
  outputPath?: string;
}
