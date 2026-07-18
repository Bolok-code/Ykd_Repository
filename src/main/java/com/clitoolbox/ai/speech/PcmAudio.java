package com.clitoolbox.ai.speech;

import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 单声道 16 位 PCM 的校验与 WAV 封装工具。
 */
public final class PcmAudio {
    private static final int PCM_ENCODE_TYPE = 1;
    private static final int PCM_BITS_PER_SAMPLE = 16;
    private static final int PCM_CHANNELS = 1;
    private static final int WAV_HEADER_BYTES = 44;

    private PcmAudio() {
    }

    public static void validate(GeneratedSpeech speech) {
        if (speech == null
                || speech.data() == null
                || speech.data().length == 0
                || speech.data().length % 2 != 0
                || speech.sampleRate() <= 0
                || speech.encodeType() != PCM_ENCODE_TYPE
                || speech.bitsPerSample() != PCM_BITS_PER_SAMPLE) {
            throw new CliException(
                    ErrorCode.INVALID_INPUT,
                    "SILK 编码只接受单声道 16 位 PCM 语音。");
        }
    }

    public static GeneratedSpeech asWav(GeneratedSpeech pcm) {
        validate(pcm);
        byte[] wav = new byte[Math.addExact(WAV_HEADER_BYTES, pcm.data().length)];
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + pcm.data().length);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) PCM_CHANNELS);
        header.putInt(pcm.sampleRate());
        header.putInt(pcm.sampleRate() * PCM_CHANNELS * PCM_BITS_PER_SAMPLE / 8);
        header.putShort((short) (PCM_CHANNELS * PCM_BITS_PER_SAMPLE / 8));
        header.putShort((short) PCM_BITS_PER_SAMPLE);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(pcm.data().length);
        header.put(pcm.data());
        return new GeneratedSpeech(
                wav,
                "bailian-reply.wav",
                pcm.playTimeMs(),
                pcm.sampleRate(),
                PCM_ENCODE_TYPE,
                PCM_BITS_PER_SAMPLE);
    }
}
