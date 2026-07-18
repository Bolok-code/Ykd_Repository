package com.clitoolbox.ai.speech;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PcmAudioTest {

    @Test
    void wrapsPcmAsPlayableMonoWav() {
        byte[] pcm = {1, 2, 3, 4};
        GeneratedSpeech source =
                new GeneratedSpeech(pcm, "test.pcm", 1, 24_000, 1, 16);

        GeneratedSpeech wav = PcmAudio.asWav(source);

        assertEquals("bailian-reply.wav", wav.fileName());
        assertEquals(48, wav.data().length);
        assertEquals(
                "RIFF",
                new String(wav.data(), 0, 4, StandardCharsets.US_ASCII));
        assertEquals(
                "WAVE",
                new String(wav.data(), 8, 4, StandardCharsets.US_ASCII));
        assertEquals(
                24_000,
                ByteBuffer.wrap(wav.data(), 24, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .getInt());
        assertArrayEquals(
                pcm,
                java.util.Arrays.copyOfRange(wav.data(), 44, wav.data().length));
    }
}
