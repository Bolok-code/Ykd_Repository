package ykd.ykd.llm.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.test.util.ReflectionTestUtils;
import ykd.ykd.llm.tools.*;
import ykd.ykd.memory.MemoryManagerService;
import ykd.ykd.skill.model.SkillDefinition;
import ykd.ykd.skill.registry.SkillRegistry;
import ykd.ykd.skill.selector.SkillSelectionResult;
import ykd.ykd.skill.selector.SkillSelector;
import ykd.ykd.skill.session.SkillSessionManager;
import ykd.ykd.skill.tool.SkillToolResolver;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归测试：用户在活跃技能模式内发模糊命中消息（如"确定投递"）时，
 * 必须保持技能模式、工具被锁定到该 Skill，而不是降级为普通对话。
 * 降级会导致工具解锁，模型拿不到真实投递工具而编造"已成功投递"。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmServiceImplTest {

    private static final String USER_ID = "u1";
    private static final SkillDefinition LIEPIN_SKILL = new SkillDefinition(
            "liepin-auto-apply", "猎聘自动投递", "1.0.0", true,
            List.of("createLiepinAutoApplyCampaign", "confirmLiepinApplication"), "instructions"
    );

    @Mock private SkillSelector skillSelector;
    @Mock private SkillToolResolver skillToolResolver;
    @Mock private SkillRegistry skillRegistry;
    @Mock private MemoryManagerService memoryManagerService;
    @Mock private WebSearchTools webSearchTools;
    @Mock private LinkTools linkTools;
    @Mock private WeatherTools weatherTools;
    @Mock private ImageTools imageTools;
    @Mock private VideoTools videoTools;
    @Mock private VoiceTools voiceTools;
    @Mock private ReminderTools reminderTools;
    @Mock private LocationTools locationTools;
    @Mock private CalculatorTools calculatorTools;
    @Mock private TranslateTools translateTools;
    @Mock private EmailTools emailTools;
    @Mock private DocumentTools documentTools;
    @Mock private ChatClient deepseekClient;
    @Mock private AssistantMessage assistantMessage;

    private SkillSessionManager skillSessionManager; // 真实实例，便于预置/断言会话状态
    private LlmServiceImpl llmService;
    private ChatClient.ChatClientRequestSpec requestSpec;

    @BeforeEach
    void setUp() {
        skillSessionManager = new SkillSessionManager();
        llmService = new LlmServiceImpl(
                mock(ReminderInterceptor.class), mock(HistoryClearInterceptor.class),
                skillSelector, skillToolResolver, skillRegistry, skillSessionManager, memoryManagerService,
                webSearchTools, linkTools, weatherTools, imageTools, videoTools, voiceTools,
                reminderTools, locationTools, calculatorTools, translateTools, emailTools, documentTools);
        // @Value 默认值在纯单测中不会注入，反射设入 TTL 以验证"会话保持"
        ReflectionTestUtils.setField(llmService, "skillTtlMs", 600_000L);

        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        ChatResponse chatResponse = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        when(deepseekClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.user(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(assistantMessage);
        when(assistantMessage.getText()).thenReturn("投递完成");
        when(memoryManagerService.getHistory(anyString())).thenReturn(List.of());
    }

    /** 把待确认记录的创建时间改旧，模拟几小时前的"待确认"。 */
    @SuppressWarnings("unchecked")
    private void agePending(String userId, long ageMs) {
        Map<String, SkillSessionManager.SkillSession> pendingMap =
                (Map<String, SkillSessionManager.SkillSession>)
                        ReflectionTestUtils.getField(skillSessionManager, "pendingSkills");
        pendingMap.put(userId, new SkillSessionManager.SkillSession(
                LIEPIN_SKILL.name(), System.currentTimeMillis() - ageMs));
    }

    @Test
    void confirmMessageInsideActiveSkillKeepsSkillMode() {
        // 用户上一轮已在猎聘技能会话中
        skillSessionManager.activate(USER_ID, LIEPIN_SKILL.name());
        when(skillRegistry.findEnabledByName(LIEPIN_SKILL.name())).thenReturn(Optional.of(LIEPIN_SKILL));
        // "确定投递" 被 Embedding 判为模糊命中（CONFIRM），但用户正在技能流程中
        when(skillSelector.select("确定投递")).thenReturn(SkillSelectionResult.confirm(LIEPIN_SKILL));
        when(skillToolResolver.resolve(LIEPIN_SKILL)).thenReturn(new ToolCallback[0]);

        String reply = llmService.chat("确定投递", List.of(), deepseekClient, USER_ID);

        assertThat(reply).isEqualTo("投递完成");
        // 工具必须被锁定到猎聘（激活路径）——修复前这里 resolve 不会被调用（降级为普通对话）
        verify(skillToolResolver).resolve(LIEPIN_SKILL);
        assertThat(skillSessionManager.get(USER_ID)).isNotNull();
        assertThat(skillSessionManager.getPending(USER_ID)).isNull();
    }

    @Test
    void confirmMessageWithoutActiveSessionStillAsksUser() {
        // 无活跃会话时，模糊命中应只记录待确认、返回 CONFIRM（先回问用户）
        when(skillSelector.select("取消任务")).thenReturn(SkillSelectionResult.confirm(LIEPIN_SKILL));
        when(assistantMessage.getText()).thenReturn("你想使用猎聘求职功能吗？我可以帮你搜索岗位。");

        String reply = llmService.chat("取消任务", List.of(), deepseekClient, USER_ID);

        assertThat(reply).isEqualTo("你想使用猎聘求职功能吗？我可以帮你搜索岗位。");
        verify(skillToolResolver, never()).resolve(any());
        assertThat(skillSessionManager.get(USER_ID)).isNull();
        // 模型询问了技能意图 → pending 保留，等待用户确认
        assertThat(skillSessionManager.getPending(USER_ID)).isNotNull();
        assertThat(skillSessionManager.getPending(USER_ID).skillName()).isEqualTo(LIEPIN_SKILL.name());
    }

    @Test
    void confirmRematchDoesNotAutoActivate() {
        // 上一轮已存在待确认的猎聘候选（如"每五秒给我发你好"残留的 pending）
        skillSessionManager.setPending(USER_ID, LIEPIN_SKILL.name());
        when(skillSelector.select("取消任务")).thenReturn(SkillSelectionResult.confirm(LIEPIN_SKILL));
        when(assistantMessage.getText()).thenReturn("你想使用猎聘求职功能吗？我可以帮你搜索岗位。");

        String reply = llmService.chat("取消任务", List.of(), deepseekClient, USER_ID);

        assertThat(reply).isEqualTo("你想使用猎聘求职功能吗？我可以帮你搜索岗位。");
        // 修复前："续谈确认"会直接激活猎聘并锁死工具 → resolve 被调用
        verify(skillToolResolver, never()).resolve(any());
        assertThat(skillSessionManager.get(USER_ID)).isNull();
        // 仍停留在"待确认"询问状态，而非激活
        assertThat(skillSessionManager.getPending(USER_ID)).isNotNull();
        assertThat(skillSessionManager.getPending(USER_ID).skillName()).isEqualTo(LIEPIN_SKILL.name());
    }

    @Test
    void pendingConsumedWhenAffirmativeRehitsConfirm() {
        // 用户上轮已被询问是否使用猎聘（pending 已记录），本轮"好"再次被 Embedding
        // 判为模糊命中（CONFIRM）——必须消费 pending 直接激活，而不是再次询问陷入循环。
        skillSessionManager.setPending(USER_ID, LIEPIN_SKILL.name());
        when(skillRegistry.findEnabledByName(LIEPIN_SKILL.name())).thenReturn(Optional.of(LIEPIN_SKILL));
        when(skillSelector.select("好")).thenReturn(SkillSelectionResult.confirm(LIEPIN_SKILL));
        when(skillToolResolver.resolve(LIEPIN_SKILL)).thenReturn(new ToolCallback[0]);

        String reply = llmService.chat("好", List.of(), deepseekClient, USER_ID);

        assertThat(reply).isEqualTo("投递完成");
        // 工具必须被锁定到猎聘（激活路径），而不是停留在待确认
        verify(skillToolResolver).resolve(LIEPIN_SKILL);
        assertThat(skillSessionManager.get(USER_ID)).isNotNull();
        assertThat(skillSessionManager.getPending(USER_ID)).isNull();
    }

    @Test
    void stalePendingIsNotConsumed() {
        // 几小时前的"待确认"即使收到"好"也不得激活——pending 带 TTL，过期即失效
        skillSessionManager.setPending(USER_ID, LIEPIN_SKILL.name());
        agePending(USER_ID, 2 * 600_000L);
        when(skillSelector.select("好")).thenReturn(SkillSelectionResult.none());

        String reply = llmService.chat("好", List.of(), deepseekClient, USER_ID);

        assertThat(reply).isEqualTo("投递完成");
        verify(skillToolResolver, never()).resolve(any());
        assertThat(skillSessionManager.get(USER_ID)).isNull();
        assertThat(skillSessionManager.getPending(USER_ID)).isNull();
    }

    @Test
    void affirmativeFollowUpsActivatePendingSkill() {
        // 肯定词开头 + 续句（含口语变体）都应视为对上一轮确认问题的肯定
        when(skillRegistry.findEnabledByName(LIEPIN_SKILL.name())).thenReturn(Optional.of(LIEPIN_SKILL));
        when(skillToolResolver.resolve(LIEPIN_SKILL)).thenReturn(new ToolCallback[0]);
        for (String msg : List.of("好嘞", "可以啊", "对，帮我投java")) {
            skillSessionManager.setPending(USER_ID, LIEPIN_SKILL.name());
            when(skillSelector.select(msg)).thenReturn(SkillSelectionResult.none());
            String reply = llmService.chat(msg, List.of(), deepseekClient, USER_ID);
            assertThat(reply).as("msg=%s", msg).isEqualTo("投递完成");
            assertThat(skillSessionManager.get(USER_ID)).as("msg=%s", msg).isNotNull();
            skillSessionManager.remove(USER_ID);
        }
        verify(skillToolResolver, times(3)).resolve(LIEPIN_SKILL);
    }

    @Test
    void jobSpecFollowUpActivatesPendingSkillOnConfirmRematch() {
        // 用户直接给出求职条件（"投java 杭州 10k左右的工作"）且消息再次模糊命中同一技能时，
        // 属于明确的确认续句，必须激活而不是再次询问
        skillSessionManager.setPending(USER_ID, LIEPIN_SKILL.name());
        when(skillRegistry.findEnabledByName(LIEPIN_SKILL.name())).thenReturn(Optional.of(LIEPIN_SKILL));
        when(skillSelector.select("投java 杭州 10k左右的工作"))
                .thenReturn(SkillSelectionResult.confirm(LIEPIN_SKILL));
        when(skillToolResolver.resolve(LIEPIN_SKILL)).thenReturn(new ToolCallback[0]);

        String reply = llmService.chat("投java 杭州 10k左右的工作", List.of(), deepseekClient, USER_ID);

        assertThat(reply).isEqualTo("投递完成");
        verify(skillToolResolver).resolve(LIEPIN_SKILL);
        assertThat(skillSessionManager.get(USER_ID)).isNotNull();
        assertThat(skillSessionManager.getPending(USER_ID)).isNull();
    }

    @Test
    void negativeFollowUpDoesNotActivatePendingSkill() {
        // "行吧，算了"含否定词，即使再次模糊命中同一技能也不得激活
        skillSessionManager.setPending(USER_ID, LIEPIN_SKILL.name());
        when(skillRegistry.findEnabledByName(LIEPIN_SKILL.name())).thenReturn(Optional.of(LIEPIN_SKILL));
        when(skillSelector.select("行吧，算了")).thenReturn(SkillSelectionResult.confirm(LIEPIN_SKILL));
        when(assistantMessage.getText()).thenReturn("你想使用猎聘求职功能吗？");

        String reply = llmService.chat("行吧，算了", List.of(), deepseekClient, USER_ID);

        assertThat(reply).isEqualTo("你想使用猎聘求职功能吗？");
        verify(skillToolResolver, never()).resolve(any());
        assertThat(skillSessionManager.get(USER_ID)).isNull();
        assertThat(skillSessionManager.getPending(USER_ID)).isNotNull();
    }

    @Test
    void confirmPromptForbidsNoToolClaims() {
        // CONFIRM 轮注入的系统消息必须明确禁止"没有该功能/工具不可用"话术，防止模型幻觉
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        when(requestSpec.messages(captor.capture())).thenReturn(requestSpec);
        when(skillSelector.select("取消任务")).thenReturn(SkillSelectionResult.confirm(LIEPIN_SKILL));
        when(assistantMessage.getText()).thenReturn("你想使用猎聘求职功能吗？");

        llmService.chat("取消任务", List.of(), deepseekClient, USER_ID);

        String joined = captor.getValue().stream()
                .map(Message::getText)
                .collect(Collectors.joining());
        assertThat(joined)
                .contains("没有该功能")
                .contains("工具不可用")
                .contains("不得编造");
    }

    @Test
    void reminderChatSkipsSkillSession() {
        // 用户正处于猎聘技能会话且有待确认状态，系统触发的提醒消息（skillEnabled=false）
        // 必须绕过技能路由：不锁定猎聘工具、不消费 pending、不改变技能会话
        skillSessionManager.activate(USER_ID, LIEPIN_SKILL.name());
        skillSessionManager.setPending(USER_ID, LIEPIN_SKILL.name());
        when(skillRegistry.findEnabledByName(LIEPIN_SKILL.name())).thenReturn(Optional.of(LIEPIN_SKILL));

        String reply = llmService.chat(
                "⏰ 定时提醒：发送杭州余杭区当地天气", List.of(), deepseekClient, USER_ID, null, false);

        assertThat(reply).isEqualTo("投递完成");
        verify(skillToolResolver, never()).resolve(any());
        assertThat(skillSessionManager.get(USER_ID)).isNotNull();
        assertThat(skillSessionManager.getPending(USER_ID)).isNotNull();
    }

    @Test
    void pendingClearedWhenModelDidNotAskOnConfirm() {
        // CONFIRM 轮模型直接处理了请求（未询问是否使用技能），pending 应被清掉，
        // 避免用户随后一句"好的"把无关消息误激活为技能模式
        when(skillSelector.select("每五秒给我发你好")).thenReturn(SkillSelectionResult.confirm(LIEPIN_SKILL));
        when(assistantMessage.getText()).thenReturn("已设置提醒，每隔5秒发送你好。");

        String reply = llmService.chat("每五秒给我发你好", List.of(), deepseekClient, USER_ID);

        assertThat(reply).isEqualTo("已设置提醒，每隔5秒发送你好。");
        verify(skillToolResolver, never()).resolve(any());
        assertThat(skillSessionManager.getPending(USER_ID)).isNull();
    }

    @Test
    void exactExitCommandExitsSkill() {
        skillSessionManager.activate(USER_ID, LIEPIN_SKILL.name());

        String reply = llmService.chat("退出猎聘", List.of(), deepseekClient, USER_ID);

        assertThat(reply).isEqualTo("已退出liepin-auto-apply技能模式，回到普通对话。");
        assertThat(skillSessionManager.get(USER_ID)).isNull();
        assertThat(skillSessionManager.getPending(USER_ID)).isNull();
        verify(skillSelector, never()).select(anyString());
    }

    @Test
    void prefixExitWithRemainderExitsSkillAndUsesDefaultTools() {
        // 模拟日志场景：用户用了知识库后直接说"退出知识库帮我查询杭州天气"。
        // 必须真正退出技能会话，剩余请求走普通对话（默认工具），
        // 且不能因消息里的"知识库"关键词重新激活技能。
        SkillDefinition kbSkill = new SkillDefinition(
                "knowledge-base", "知识库管理", "1.0.0", true,
                List.of("addDocumentToKnowledgeBase", "answerFromKnowledgeBase"), "instructions");
        skillSessionManager.activate(USER_ID, kbSkill.name());
        when(skillRegistry.findEnabledByName(kbSkill.name())).thenReturn(Optional.of(kbSkill));

        String reply = llmService.chat("退出知识库帮我查询杭州天气", List.of(), deepseekClient, USER_ID);

        assertThat(skillSessionManager.get(USER_ID)).isNull();
        assertThat(skillSessionManager.getPending(USER_ID)).isNull();
        verify(skillSelector, never()).select(anyString());
        verify(skillToolResolver, never()).resolve(any());
        assertThat(reply).startsWith("✅ 已退出knowledge-base技能模式。");
    }

    @Test
    void prefixExitWithoutActiveSessionProcessesRemainderNormally() {
        // 没有活跃技能会话时，"退出知识库帮我查天气"不应报错或重新激活，直接走普通对话
        String reply = llmService.chat("退出知识库帮我查天气", List.of(), deepseekClient, USER_ID);

        assertThat(reply).isEqualTo("投递完成");
        verify(skillSelector, never()).select(anyString());
        assertThat(skillSessionManager.get(USER_ID)).isNull();
    }

    @Test
    void campaignManagementPhraseDoesNotExitSkill() {
        // "退出投递计划"是猎聘计划管理指令，不是退出技能：技能会话应保持并继续走技能路由
        skillSessionManager.activate(USER_ID, LIEPIN_SKILL.name());
        when(skillRegistry.findEnabledByName(LIEPIN_SKILL.name())).thenReturn(Optional.of(LIEPIN_SKILL));
        when(skillSelector.select("退出投递计划")).thenReturn(SkillSelectionResult.none());
        when(skillToolResolver.resolve(LIEPIN_SKILL)).thenReturn(new ToolCallback[0]);

        String reply = llmService.chat("退出投递计划", List.of(), deepseekClient, USER_ID);

        assertThat(reply).isEqualTo("投递完成");
        assertThat(skillSessionManager.get(USER_ID)).isNotNull();
        verify(skillToolResolver).resolve(LIEPIN_SKILL);
    }
}
