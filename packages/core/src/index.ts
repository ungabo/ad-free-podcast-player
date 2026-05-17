export type TranscriptionBackend = 'parakeet';
export type AdDetectionMode = 'openai';

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
  openAiModel: 'gpt-5.5',
  transcriptionBackend: 'parakeet',
  detectionMode: 'openai',
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
      detectionMode: 'openai',
    };
  }

  if (platform === 'android') {
    return {
      ...DEFAULT_SETTINGS,
      transcriptionBackend: 'parakeet',
      detectionMode: 'openai',
    };
  }

  return DEFAULT_SETTINGS;
}

export function resolveBackendWithFallback(
  requested: TranscriptionBackend,
  capabilities: { parakeetAvailable: boolean },
): TranscriptionBackend {
  if (requested === 'parakeet' && capabilities.parakeetAvailable) {
    return 'parakeet';
  }

  throw new Error('Parakeet transcription is required. No alternate transcription backend is available.');
}

export interface AdRange {
  start: number;
  end: number;
  confidence: number;
  reason: string;
  source: 'openai';
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
