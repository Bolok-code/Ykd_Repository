package ykd.ykd.llm.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.elevenlabs.ElevenLabsTextToSpeechOptions;
import org.springframework.ai.elevenlabs.api.ElevenLabsApi;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ykd.ykd.processor.ProcessResult;
import ykd.ykd.processor.VideoTaskManager;

import java.util.Queue;

/**
 * 语音合成工具，通过 Spring AI {@link TextToSpeechModel} 调用 ElevenLabs TTS，
 * 以文件形式发送音频。
 */
@Slf4j
@Component
public class VoiceTools {

    private final TextToSpeechModel speechModel;
    private final VideoTaskManager videoTaskManager;
    private final Queue<ProcessResult> voiceQueue;
    private final String voiceId;
    private final String model;

    public VoiceTools(@Qualifier("elevenLabsSpeechModel") TextToSpeechModel speechModel,
                      VideoTaskManager videoTaskManager,
                      Queue<ProcessResult> voiceQueue,
                      @Value("${spring.ai.elevenlabs.tts.voice-id}") String voiceId,
                      @Value("${spring.ai.elevenlabs.tts.model:eleven_turbo_v2_5}") String model) {
        this.speechModel = speechModel;
        this.videoTaskManager = videoTaskManager;
        this.voiceQueue = voiceQueue;
        this.voiceId = voiceId;
        this.model = model;
    }

    @Tool(description = "用语音朗读文字。当用户要求用语音回答、朗读、播报、读出来时调用此工具")
    public String speak(
            @ToolParam(description = "要朗读的文字内容") String text) {

        String userId = videoTaskManager.getCurrentUserId();
        log.info("[VoiceTools] 被调用: text={}, userId={}",
                text.length() > 100 ? text.substring(0, 100) + "..." : text, userId);

        try {
            ElevenLabsTextToSpeechOptions options = ElevenLabsTextToSpeechOptions.builder()
                    .voiceId(voiceId)
                    .outputFormat(ElevenLabsApi.OutputFormat.MP3_44100_128.getValue())
                    .modelId(model)
                    .build();
            byte[] audio = speechModel.call(new TextToSpeechPrompt(text, options))
                    .getResult().getOutput();
            voiceQueue.add(ProcessResult.voice(audio, userId));
            log.info("[VoiceTools] 语音合成成功: userId={}, size={}KB", userId, audio.length / 1024);
            return "语音已播报";
        } catch (Exception e) {
            log.error("[VoiceTools] 语音合成失败: userId={}, error={}", userId, e.getMessage(), e);
            return "语音合成失败：" + e.getMessage();
        }
    }
}
