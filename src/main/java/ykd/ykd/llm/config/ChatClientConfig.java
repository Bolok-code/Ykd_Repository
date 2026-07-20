package ykd.ykd.llm.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ykd.ykd.processor.ProcessResult;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Configuration
public class ChatClientConfig {

    @Bean
    Queue<ProcessResult> voiceQueue() {
        return new ConcurrentLinkedQueue<>();
    }



    @Bean
    public ChatClient deepseekClient(DeepSeekChatModel deepSeekChatModel) {
        return ChatClient.builder(deepSeekChatModel)
                .defaultSystem("""
                        你是人工智能助手，拥有以下工具能力：
                        - 生成图片：当用户要求画图、生成图片、绘制、P图、创作图像时，必须调用 generateImage 工具
                        - 生成视频：当用户要求生成视频、创作视频、制作视频时，必须调用 generateVideo 工具
                        - 语音播报：当用户要求用语音回答、朗读、播报、读出来时，必须调用 speak 工具
                        - 查询天气：当用户询问天气时，调用 getWeather 工具
                        - 其他问题直接文字回答
                        重要规则：
                        1. 用户要求生成图片时，必须调用 generateImage 工具
                        2. 工具返回的图片URL必须原样输出，不得省略、不得改写、不得用文字描述替代
                        3. 回复格式："https://平台返回的完整图片URL"
                        """)
                .build();
    }

    @Bean
    public ChatClient agnesClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel)
                .defaultSystem("""
                        你是人工智能助手，拥有以下工具能力：
                        - 生成图片：当用户要求画图、生成图片、绘制、P图、创作图像时，必须调用 generateImage 工具。如果用户提供了参考图片，将图片URL传给工具做图生图
                        - 生成视频：当用户要求生成视频、创作视频、制作视频时，必须调用 generateVideo 工具
                        - 语音播报：当用户要求用语音回答、朗读、播报、读出来时，必须调用 speak 工具
                        - 查询天气：当用户询问天气时，调用 getWeather 工具
                        - 其他问题直接文字回答
                        重要规则：
                        1. 用户要求生成图片时，必须调用 generateImage 工具
                        2. 工具返回的图片URL必须原样输出，不得省略、不得改写、不得用文字描述替代
                        3. 回复格式："https://平台返回的完整图片URL"
                        """)
                .build();
    }


}
