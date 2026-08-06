package ykd.ykd.processor;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import ykd.ykd.document.DocumentParsingService;
import ykd.ykd.job.service.LiepinResumeService;
import ykd.ykd.job.task.LiepinJobTaskManager;
import ykd.ykd.llm.service.LlmService;
import ykd.ykd.llm.tools.DocumentTools;
import ykd.ykd.skill.model.SkillDefinition;
import ykd.ykd.skill.selector.SkillSelectionResult;
import ykd.ykd.skill.selector.SkillSelector;
import ykd.ykd.task.ImageBatchManager;
import ykd.ykd.task.VideoTaskManager;

import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归测试：发文件后用户说"存入知识库"这类 Skill 命令时，
 * 文件缓存必须保留（addDocumentToKnowledgeBase 需要从缓存读文件），
 * 只有显式"退出追问"才清缓存。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageProcessorSkillCommandTest {

    @Mock
    private LlmService llmService;
    @Mock
    private ChatClient deepseekClient;
    @Mock
    private ChatClient agnesClient;
    @Mock
    private VideoTaskManager videoTaskManager;
    @Mock
    private ImageBatchManager imageBatchManager;
    @Mock
    private Queue<ProcessResult> voiceQueue;
    @Mock
    private DocumentParsingService documentParsingService;
    @Mock
    private LiepinResumeService liepinResumeService;
    @Mock
    private LiepinJobTaskManager liepinJobTaskManager;
    @Mock
    private SkillSelector skillSelector;

    private static final String USER_ID = "u1";
    private static final SkillDefinition KB_SKILL = new SkillDefinition(
            "knowledge-base", "知识库管理技能", "1.0.0", true,
            List.of("addDocumentToKnowledgeBase"), "instructions"
    );

    @Test
    void skillCommandShouldNotClearDocumentCache() {
        try (MockedStatic<DocumentTools> mocked = mockStatic(DocumentTools.class)) {
            mocked.when(() -> DocumentTools.hasCachedDocument(USER_ID)).thenReturn(true);
            when(skillSelector.select("存入知识库")).thenReturn(SkillSelectionResult.activate(KB_SKILL));
            when(llmService.chat(eq("存入知识库"), eq(List.of()), eq(deepseekClient), eq(USER_ID)))
                    .thenReturn("好的，正在处理");

            ProcessResult result = processor().process(textMessage("存入知识库"), mock(ILinkClient.class));

            // 缓存必须保留——否则 addDocumentToKnowledgeBase 读不到刚发的文件
            mocked.verify(() -> DocumentTools.clearCachedDocument(USER_ID), never());
            assertThat(result.text()).isEqualTo("好的，正在处理");
        }
    }

    @Test
    void stopFollowUpShouldClearDocumentCache() {
        try (MockedStatic<DocumentTools> mocked = mockStatic(DocumentTools.class)) {
            mocked.when(() -> DocumentTools.hasCachedDocument(USER_ID)).thenReturn(true);

            ProcessResult result = processor().process(textMessage("退出文件问答"), mock(ILinkClient.class));

            mocked.verify(() -> DocumentTools.clearCachedDocument(USER_ID), times(1));
            assertThat(result.text()).contains("已退出文件问答模式");
        }
    }

    private MessageProcessor processor() {
        return new MessageProcessor(
                llmService, deepseekClient, agnesClient,
                videoTaskManager, imageBatchManager, new UserContext(), voiceQueue,
                documentParsingService, liepinResumeService, liepinJobTaskManager, skillSelector);
    }

    private static WeixinMessage textMessage(String text) {
        WeixinMessage msg = new WeixinMessage();
        msg.setFrom_user_id(USER_ID);
        msg.setMessage_id(1L);
        msg.setItem_list(List.of(MessageItem.text(text)));
        return msg;
    }
}
