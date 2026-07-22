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
                        - 查询天气：当用户询问天气时，调用 getWeather 工具
                        - 设置提醒：当用户要求定时提醒、延迟通知、闹钟时，调用 setReminder 工具
                        - 语音播报：当用户要求用语音回答、朗读、播报、读出来时，必须调用 speak 工具。
                          如果用户要求男声/男生声音/男性声音，传 gender="male"；
                          如果用户要求女声/女生声音/女性声音或无明确性别要求，传 gender="female"。
                        - 其他问题直接文字回答
                        重要规则：
                        1. 用户要求生成图片时，必须调用 generateImage 工具，没有明确要求生成图片不要调用此工具
                        2. 工具返回的图片URL必须原样输出，不得省略、不得改写、不得用文字描述替代
                        3. 回复格式："https://平台返回的完整图片URL"
                        4. 【延迟优先】用户说"X分钟后/小时后/秒后"做某事，这是延迟任务。你只能调用 setReminder，把任务描述作为 message 传入。绝对不要同时调其他工具（如 getWeather、generateImage）。
                          错误示例："10秒后查天气" → 先调 getWeather 再调 setReminder ❌
                          正确做法："10秒后查天气" → 只调 setReminder("10秒后", "查询杭州天气") ✅
                        """
                )

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
