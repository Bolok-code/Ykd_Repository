package ykd.ykd.llm.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.elevenlabs.ElevenLabsTextToSpeechOptions;
import org.springframework.ai.elevenlabs.api.ElevenLabsApi;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.processor.ProcessResult;
import ykd.ykd.processor.UserContext;

import java.util.Queue;

/**
 * 语音合成工具，通过 Spring AI {@link TextToSpeechModel} 调用 ElevenLabs TTS，
 * 以文件形式发送音频。
 */
@Component
public class VoiceTools {
    private static final Logger log = LoggerFactory.getLogger(VoiceTools.class);

    private final TextToSpeechModel speechModel;
    private final UserContext userContext;
    private final Queue<ProcessResult> voiceQueue;
    private final String voiceId;
    private final String malevoiceId;
    private final String model;

    public VoiceTools(@Qualifier("elevenLabsSpeechModel") TextToSpeechModel speechModel,
                      UserContext userContext,
                      Queue<ProcessResult> voiceQueue,
                      @Value("${spring.ai.elevenlabs.tts.voice-id}") String voiceId,
                      @Value("${spring.ai.elevenlabs.tts.male-voice-id}") String malevoiceId,
                      @Value("${spring.ai.elevenlabs.tts.model:eleven_turbo_v2_5}") String model) {
        this.speechModel = speechModel;
        this.userContext = userContext;
        this.voiceQueue = voiceQueue;
        this.voiceId = voiceId;
        this.model = model;
        this.malevoiceId = malevoiceId;


    }

    /**
     * 调用 ElevenLabs TTS 将文字合成为 MP3 音频，入队后由 {@code MessageProcessor} 消费发送。
     *
     * <p>在 {@code ChatClient.call()} 内部同步执行：TTS API 调用 → 音频字节入 {@code voiceQueue}
     * → 返回提示文字。音频不进入 LLM 对话流，通过队列旁路传递给消息处理层。</p>
     *
     * @param text   要朗读的文字内容
     * @param gender 音色性别，{@code "male"} 为男声，{@code "female"} 或为空为女声
     * @return 合成成功返回 {@code "语音已播报"}，失败返回错误提示
     */
    @Tool(description = "用语音朗读文字。当用户要求用语音回答、朗读、播报、读出来时调用此工具")
    public String speak(
            @ToolParam(description = "要朗读的文字内容") String text,
            @ToolParam(description = "语音性别：male(男声) 或 female(女声)。用户未指定时传 female", required = false)
            String gender
    ) {
        String userId = userContext.getCurrentUserId();
        log.info("[VoiceTools] 被调用: text={}, userId={}, gender={}",
                text.length() > 100 ? text.substring(0, 100) + "..." : text, userId, gender);

        String selectedVoiceId = resolveVoiceId(gender);

        /*
         * TTS 合成 → 入队 → 返回提示文字。
         * 音频字节不入 LLM 对话流，通过 voiceQueue 直接交给 MessageProcessor 发送。
         */
        try {
            ElevenLabsTextToSpeechOptions options = ElevenLabsTextToSpeechOptions.builder()
                    .voiceId(selectedVoiceId)
                    .outputFormat(ElevenLabsApi.OutputFormat.MP3_44100_128.getValue())
                    .modelId(model)
                    .build();
            byte[] audio = speechModel.call(new TextToSpeechPrompt(text, options))
                    .getResult().getOutput();
            voiceQueue.add(ProcessResult.voice(audio, userId));
            log.info("[VoiceTools] 语音合成成功: userId={}, voiceId={}, size={}KB",
                    userId, selectedVoiceId, audio.length / 1024);
            return "语音已播报";
        } catch (Exception e) {
            log.error("[VoiceTools] 语音合成失败: userId={}, error={}", userId, e.getMessage(), e);
            return "❌ " + ErrorCode.TTS_SYNTHESIS_FAILED.getDefaultMessage();
        }
    }

    /**
     * 根据性别参数选择对应的 ElevenLabs 音色 ID。
     *
     * @param gender 性别标识，{@code "male"} 选男声，其他选女声
     * @return ElevenLabs voice ID
     */
    private String resolveVoiceId(String gender) {
        if ("male".equalsIgnoreCase(gender)) {
            return malevoiceId;
        }
        return voiceId;
    }

}
