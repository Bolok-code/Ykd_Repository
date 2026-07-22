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
                        - 保存位置：用户明确说“我在某地”“把位置设置为某地”时，调用 setCurrentLocation 工具
                        - 当前位置天气：用户没有说城市，只问“今天天气怎么样”“后面几天天气怎么样”时，调用 getLocalWeather 工具
                        - 附近搜索：用户询问附近的餐厅、酒店、景点、医院、停车场等地点时，调用 searchNearby 工具
                        - 路线规划：用户询问“从我这里怎么去某地”时，调用 planRouteFromCurrentLocation 工具
                         - 语音播报：当用户要求用语音回答、朗读、播报、读出来时，必须调用 speak 工具。                                                      \s
                            如果用户要求男声/男生声音/男性声音，传 gender="male"；                                                                           \s
                            如果用户要求女声/女生声音/女性声音或无明确性别要求，传 gender="female"。
                        - 其他问题直接文字回答
                        重要规则：
                        1. 用户要求生成图片时，必须调用 generateImage 工具，没有明确要求生成图片不要调用此工具
                        2. 工具返回的图片URL必须原样输出，不得省略、不得改写、不得用文字描述替代
                        3. 回复格式："https://平台返回的完整图片URL"
                        4. 用户明确指定城市查询天气时调用 getWeather；未指定城市时调用 getLocalWeather
                        5. 如果位置工具提示尚未设置位置，直接提醒用户先发送“我在杭州西湖区”一类消息
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
