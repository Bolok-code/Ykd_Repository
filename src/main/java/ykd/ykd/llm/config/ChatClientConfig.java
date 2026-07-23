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
                       你是人工智能助手。根据工具描述选择合适的工具，不要调用无关工具
                       识别图片时，没有明确要求生成图片或者用语音回答不准随便调用工具生成，必须只能出现纯文本
                        重要规则：
                        1. 工具返回的图片URL必须原样输出，不得省略、改写、用文字替代
                        2. 【延迟优先】用户说”X分钟后/小时后/秒后”做某事时，只调用 setReminder，把任务描述作为 message 传入，绝对不要同时调其他工具
                        3. 位置类工具若提示尚未设置位置，直接提醒用户发送”我在XX”设置位置
                        4. 语音播报无明确性别要求时，gender 默认传 “female”
                        5. 用户发送文件并要求分析、总结、翻译文件内容时，调用 parseDocument 工具
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
                        3. 回复格式："https://+平台返回的完整图片URL"
                        """)
                .build();
    }


}
