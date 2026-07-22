package ykd.ykd.llm.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 语音服务：STT（微信 SILK → ElevenLabs 识别）。//已放弃使用 ilinksdk 语音转文字自动支持
 */
@Slf4j
@Service
public class VoiceService {

    private static final Path DECODER = Paths.get("src/main/resources/native/decoder");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public VoiceService(@Value("${spring.ai.elevenlabs.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 微信 SILK 语音 → PCM → WAV → ElevenLabs STT 识别。
     */
    public String speechToText(byte[] silkData) {
        Path silkFile = null;
        Path pcmFile = null;
        try {
            silkFile = Files.createTempFile("voice_", ".silk");
            Files.write(silkFile, silkData);

            pcmFile = Files.createTempFile("voice_", ".pcm");
            run(DECODER.toAbsolutePath().toString(), silkFile.toString(), pcmFile.toString());

            byte[] pcm = Files.readAllBytes(pcmFile);
            byte[] wav = pcmToWav(pcm, 24000);
            return callElevenLabs(wav);
        } catch (Exception e) {
            log.error("[VoiceService] STT 识别失败", e);
            return null;
        } finally {
            if (silkFile != null) try { Files.deleteIfExists(silkFile); } catch (Exception ignored) {}
            if (pcmFile != null) try { Files.deleteIfExists(pcmFile); } catch (Exception ignored) {}
        }
    }

    /**
     * PCM → WAV（直接加 RIFF header，无需 ffmpeg）。
     */
    private byte[] pcmToWav(byte[] pcm, int sampleRate) {
        int channels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int dataSize = pcm.length;

        ByteBuffer buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes());
        buf.putInt(36 + dataSize);
        buf.put("WAVE".getBytes());
        buf.put("fmt ".getBytes());
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) channels);
        buf.putInt(sampleRate);
        buf.putInt(byteRate);
        buf.putShort((short) (channels * bitsPerSample / 8));
        buf.putShort((short) bitsPerSample);
        buf.put("data".getBytes());
        buf.putInt(dataSize);
        buf.put(pcm);
        return buf.array();
    }

    private String callElevenLabs(byte[] audio) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("xi-api-key", apiKey);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() { return "voice.wav"; }
        });
        body.add("model_id", "scribe_v1");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "https://api.elevenlabs.io/v1/speech-to-text",
                new HttpEntity<>(body, headers), String.class);

        JsonNode json = objectMapper.readTree(response.getBody());
        String text = json.path("text").asText();
        log.info("[VoiceService] STT 识别成功: text={}", text.length() > 100 ? text.substring(0, 100) + "..." : text);
        return text;
    }

    private void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).start();
        int exit = p.waitFor();
        if (exit != 0) {
            String err = new String(p.getErrorStream().readAllBytes());
            throw new RuntimeException("exit " + exit + ": " + err);
        }
    }
}
