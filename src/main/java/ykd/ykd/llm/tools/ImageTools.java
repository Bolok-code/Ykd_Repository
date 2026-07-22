package ykd.ykd.llm.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ykd.ykd.exception.ErrorCode;

@Component
public class ImageTools {
    private static final Logger log = LoggerFactory.getLogger(ImageTools.class);

    private final OpenAiImageModel agnesImageModel;

    public ImageTools(OpenAiImageModel agnesImageModel) {
        this.agnesImageModel = agnesImageModel;
    }

    @Tool(description = "生成图片并返回图片URL。当用户要求画图、生成图片、绘制、创作图像、P图时调用此工具。用户提供参考图时传imageUrl做图生图")
    public String generateImage(
            @ToolParam(description = "图片描述或编辑指令，如'一只猫在太空'") String prompt,
            @ToolParam(description = "参考图片URL，无参考图时传null", required = false) String imageUrl) {
        long start = System.currentTimeMillis();
        log.info("[ImageTool] 被调用: prompt={}, hasRefImage={}", prompt, imageUrl != null);
        try {
            ImagePrompt imagePrompt = new ImagePrompt(prompt,
                    OpenAiImageOptions.builder()
                            .model("agnes-image-2.1-flash")
                            .height(1024)
                            .width(1024)
                            .build());
            var response = agnesImageModel.call(imagePrompt);
            String url = response.getResult().getOutput().getUrl();
            long elapsed = System.currentTimeMillis() - start;
            log.info("[ImageTool] 生成成功: elapsed={}ms, url={}", elapsed, url);
            return "图片已生成，请在回复中原样输出以下URL：\n" + url;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[ImageTool] 生成失败: elapsed={}ms, prompt={}, error={}", elapsed, prompt, e.getMessage(), e);
            return "❌ " + ErrorCode.IMAGE_GENERATE_FAILED.getDefaultMessage();
        }
    }
}
