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
                        - 计算：当用户需要计算、求和、求积、换算等数学运算时，调用 calculate 工具
                        - 翻译：当用户输入外文需要翻译成中文，或需要将中文翻译成其他语言时，调用 translate 工具
                        - 生成外语回复：当用户和外国网友交流，输入中文提示要求生成对应语言回复时，调用 replyInLanguage 工具
                        - 生成图片：当用户要求画图、生成图片、绘制、P图、创作图像时，必须调用 generateImage 工具
                        - 生成视频：当用户要求生成视频、创作视频、制作视频时，必须调用 generateVideo 工具
                        - 语音播报：当用户要求用语音回答、朗读、播报、读出来时，必须调用 speak 工具
                        - 查询天气：当用户询问天气时，调用 getWeather 工具
                        - 语音播报：当用户要求用语音回答、朗读、播报、读出来时，必须调用 speak 工具。
                            如果用户要求男声/男生声音/男性声音，传 gender="male"；
                            如果用户要求女声/女生声音/女性声音或无明确性别要求，传 gender="female"。
                        - 其他问题直接文字回答
                        重要规则：
                        1. 用户要求生成图片时，必须调用 generateImage 工具，没有明确要求生成图片不要调用此工具
                        2. 工具返回的图片URL必须原样输出，不得省略、不得改写、不得用文字描述替代
                        3. 回复格式："https://平台返回的完整图片URL"
                        """)

                .build();
    }

    @Bean
    public ChatClient agnesClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel)
                .defaultSystem("""
                       你是识别图片的ai助手
                       识别图片时，没有明确要求生成图片或者用语音回答不准随便调用工具生成，必须只能出现纯文本
                        重要规则：
                        1. 用户明确要求生成图片时，必须调用 generateImage 工具
                        2. 工具返回的图片URL必须原样输出，不得省略、不得改写、不得用文字描述替代
                        3. 回复格式："https://平台返回的完整图片URL"
                        """)
                .build();
    }


}
