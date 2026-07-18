import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { encode, getDuration, isSilk } from "silk-wasm";

const SUPPORTED_SAMPLE_RATES = new Set([8000, 12000, 16000, 24000]);

export async function encodePcmToSilk(pcm, sampleRate) {
  if (!Buffer.isBuffer(pcm) && !(pcm instanceof Uint8Array)) {
    throw new TypeError("PCM input must be a Buffer or Uint8Array");
  }
  if (pcm.byteLength === 0 || pcm.byteLength % 2 !== 0) {
    throw new Error("PCM input must contain complete signed 16-bit samples");
  }
  if (!SUPPORTED_SAMPLE_RATES.has(sampleRate)) {
    throw new Error(
      `Unsupported SILK sample rate ${sampleRate}; use 8000, 12000, 16000 or 24000`,
    );
  }

  const result = await encode(pcm, sampleRate);
  const silk = Buffer.from(result.data);
  if (!isSilk(silk)) {
    throw new Error("silk-wasm returned an invalid SILK stream");
  }

  return {
    data: silk,
    durationMs: getDuration(silk),
    sampleRate,
  };
}

async function runCli(args) {
  if (args.length !== 3) {
    throw new Error(
      "Usage: node encode.mjs <input.pcm> <output.silk> <sampleRate>",
    );
  }

  const [inputArgument, outputArgument, sampleRateArgument] = args;
  const inputPath = resolve(inputArgument);
  const outputPath = resolve(outputArgument);
  const sampleRate = Number.parseInt(sampleRateArgument, 10);
  const pcm = await readFile(inputPath);
  const silk = await encodePcmToSilk(pcm, sampleRate);
  await writeFile(outputPath, silk.data);
  process.stdout.write(
    `${JSON.stringify({
      bytes: silk.data.byteLength,
      durationMs: silk.durationMs,
      sampleRate: silk.sampleRate,
    })}\n`,
  );
}

const isDirectExecution =
  process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);

if (isDirectExecution) {
  runCli(process.argv.slice(2)).catch((error) => {
    process.stderr.write(`${error?.stack ?? error}\n`);
    process.exitCode = 1;
  });
}
