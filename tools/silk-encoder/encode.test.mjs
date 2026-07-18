import assert from "node:assert/strict";
import test from "node:test";
import { encodePcmToSilk } from "./encode.mjs";

test("encodes one second of 24 kHz mono PCM to SILK", async () => {
  const sampleRate = 24000;
  const pcm = Buffer.alloc(sampleRate * 2);

  const result = await encodePcmToSilk(pcm, sampleRate);

  assert.ok(result.data.byteLength > 10);
  assert.equal(result.sampleRate, sampleRate);
  assert.ok(
    result.durationMs >= 980 && result.durationMs <= 1020,
    `unexpected duration: ${result.durationMs}`,
  );
});

test("rejects incomplete signed 16-bit PCM", async () => {
  await assert.rejects(
    () => encodePcmToSilk(Buffer.alloc(3), 24000),
    /complete signed 16-bit samples/,
  );
});
