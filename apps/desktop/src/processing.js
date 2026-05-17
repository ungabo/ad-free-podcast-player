const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const DEFAULT_ADCUTFORGE_ROOT =
  process.env.ADCUTFORGE_ROOT || 'c:\\Users\\Gabe\\Documents\\Codex\\2026-05-08\\podcast ad remover';

function fileExists(filePath) {
  return Boolean(filePath) && fs.existsSync(filePath);
}

function resolveAdCutForgeRoot() {
  return DEFAULT_ADCUTFORGE_ROOT;
}

function resolvePythonPath(settings) {
  const requestedParakeetPython = settings?.parakeetPythonPath || '';
  const bundledParakeetPython = path.join(resolveAdCutForgeRoot(), 'parakeet-runtime', 'Scripts', 'python.exe');

  if (fileExists(requestedParakeetPython)) {
    return requestedParakeetPython;
  }

  if (fileExists(bundledParakeetPython)) {
    return bundledParakeetPython;
  }

  if (fileExists(process.env.PYTHON_EXE || '')) {
    return process.env.PYTHON_EXE;
  }

  return 'py';
}

function resolveBackend(settings) {
  const requestedParakeetPython = settings?.parakeetPythonPath || '';
  const bundledParakeetPython = path.join(resolveAdCutForgeRoot(), 'parakeet-runtime', 'Scripts', 'python.exe');
  if (!fileExists(requestedParakeetPython) && !fileExists(bundledParakeetPython)) {
    throw new Error('Parakeet runtime was not found on this Windows machine. Ad removal is unavailable until the Windows processor is installed.');
  }

  return 'parakeet';
}

function buildArgs(filePath, settings) {
  const adCutForgeRoot = resolveAdCutForgeRoot();
  const scriptPath = path.join(adCutForgeRoot, 'src', 'ad_cut_forge.py');
  const backend = resolveBackend(settings);
  const args = [];

  if (resolvePythonPath(settings) === 'py') {
    args.push('-3.11');
  }

  args.push(scriptPath, '--cli', '--overwrite', '--backend', backend);

  args.push('--detection-mode', 'openai');

  const openAiApiKey = process.env.OPENAI_API_KEY || '';
  if (!openAiApiKey.trim()) {
    throw new Error('OPENAI_API_KEY is not set on this Windows machine. Ad removal is unavailable until the Windows processor has the key.');
  }
  args.push('--openai-api-key', openAiApiKey);

  if (settings?.openAiModel) {
    args.push('--openai-model', settings.openAiModel);
  }

  if (backend === 'parakeet') {
    const parakeetPython = settings?.parakeetPythonPath || path.join(adCutForgeRoot, 'parakeet-runtime', 'Scripts', 'python.exe');
    if (fileExists(parakeetPython)) {
      args.push('--parakeet-python', parakeetPython);
    }
    if (settings?.parakeetModel) {
      args.push('--parakeet-model', settings.parakeetModel);
    }
  }

  args.push(filePath);
  return { scriptPath, args, backend };
}

function runProcessing(filePath, settings, onEvent) {
  const adCutForgeRoot = resolveAdCutForgeRoot();
  const pythonPath = resolvePythonPath(settings);
  const { scriptPath, args, backend } = buildArgs(filePath, settings);
  const startedAt = Date.now();

  const emitEvent = (event) => {
    onEvent({
      elapsedMs: Date.now() - startedAt,
      ...event,
    });
  };

  if (!fileExists(scriptPath)) {
    throw new Error(`AdCutForge script not found: ${scriptPath}`);
  }

  emitEvent({
    type: 'status',
    line: `Preparing ${path.basename(filePath)}`,
    stage: 'Preparing',
  });
  emitEvent({
    type: 'progress',
    line: 'Starting: 0%',
    stage: 'Starting',
    percent: 0,
  });
  emitEvent({
    type: 'status',
    line: `Launching ${backend} backend`,
    stage: `Launching ${backend}`,
  });

  const child = spawn(pythonPath, args, {
    cwd: adCutForgeRoot,
    windowsHide: true,
  });

  let combinedOutput = '';
  let outputDir = '';
  let editedAudio = '';
  let runSummary = '';

  const emitChunk = (stream, chunk) => {
    const text = chunk.toString();
    combinedOutput += text;
    for (const line of text.split(/\r?\n/)) {
      if (!line.trim()) continue;
      if (line.startsWith('Output folder: ')) outputDir = line.slice('Output folder: '.length).trim();
      if (line.startsWith('Edited audio: ')) editedAudio = line.slice('Edited audio: '.length).trim();
      if (line.startsWith('Run summary: ')) runSummary = line.slice('Run summary: '.length).trim();

      const progressMatch = line.match(/^(.*?):\s*(\d+(?:\.\d+)?)%$/);
      if (progressMatch) {
        emitEvent({
          type: 'progress',
          line,
          stage: progressMatch[1].trim(),
          percent: Number(progressMatch[2]),
        });
        continue;
      }

      emitEvent({ type: stream, line, stage: line });
    }
  };

  child.stdout.on('data', (chunk) => emitChunk('stdout', chunk));
  child.stderr.on('data', (chunk) => emitChunk('stderr', chunk));

  return new Promise((resolve, reject) => {
    child.on('error', reject);
    child.on('close', (code) => {
      const result = {
        ok: code === 0,
        exitCode: code,
        backend,
        pythonPath,
        adCutForgeRoot,
        outputDir,
        editedAudio,
        runSummary,
        logs: combinedOutput,
      };
      if (code === 0) {
        emitEvent({
          type: 'progress',
          line: 'Complete: 100%',
          stage: 'Complete',
          percent: 100,
        });
        resolve(result);
      } else {
        reject(new Error(combinedOutput || `Processing failed with exit code ${code}`));
      }
    });
  });
}

module.exports = {
  resolveAdCutForgeRoot,
  runProcessing,
};
