package ykd.ykd.wxbot;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import ykd.ykd.processor.MessageProcessor;
import ykd.ykd.task.UnifiedReminderManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多账号场景回归测试：
 * 异步推送（提醒等）必须路由到用户归属的 bot 账号，而不是随机挑一个；
 * 长时间未扫码的 pending session 必须被定时清理。
 */
class WeixinBotServiceTest {

    private WeixinBotService newService() {
        return new WeixinBotService(
                null,
                mock(MessageProcessor.class),
                mock(UnifiedReminderManager.class),
                mock(ApplicationEventPublisher.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, BotSession> sessions(WeixinBotService service) {
        return (Map<String, BotSession>) ReflectionTestUtils.getField(service, "sessions");
    }

    @SuppressWarnings("unchecked")
    private Map<String, BotSession> pendingSessions(WeixinBotService service) {
        return (Map<String, BotSession>) ReflectionTestUtils.getField(service, "pendingSessions");
    }

    @Test
    void asyncPushRoutesToOwningBotSession() {
        WeixinBotService service = newService();
        BotSession botA = mock(BotSession.class);
        BotSession botB = mock(BotSession.class);
        sessions(service).put("bot-a", botA);
        sessions(service).put("bot-b", botB);
        // 用户 user-x 之前给 bot-b 发过消息 → 归属 bot-b
        service.registerUser("bot-b", "user-x");
        when(botB.sendTextWithResult("user-x", "提醒")).thenReturn(true);

        boolean ok = service.sendTextWithResult("user-x", "提醒");

        assertThat(ok).isTrue();
        verify(botB).sendTextWithResult("user-x", "提醒");
        // 绝不能发给另一个 bot 账号
        verify(botA, never()).sendTextWithResult(anyString(), anyString());
        verify(botA, never()).safeSendText(anyString(), anyString());
    }

    @Test
    void unregisteredUserFallsBackToAnySession() {
        WeixinBotService service = newService();
        BotSession botA = mock(BotSession.class);
        sessions(service).put("bot-a", botA);
        when(botA.sendTextWithResult("user-y", "hi")).thenReturn(true);

        assertThat(service.sendTextWithResult("user-y", "hi")).isTrue();
        verify(botA).sendTextWithResult("user-y", "hi");
    }

    @Test
    void disconnectClearsUserMappings() {
        WeixinBotService service = newService();
        BotSession botA = mock(BotSession.class);
        sessions(service).put("bot-a", botA);
        service.registerUser("bot-a", "user-x");
        service.registerUser("bot-a", "user-z");

        service.disconnect("bot-a");

        verify(botA).close();
        verify(botA).deleteSession();
        @SuppressWarnings("unchecked")
        Map<String, String> mapping =
                (Map<String, String>) ReflectionTestUtils.getField(service, "userToBot");
        assertThat(mapping).isEmpty();
        assertThat(sessions(service)).isEmpty();
    }

    @Test
    void stalePendingSessionIsCleanedUp() {
        WeixinBotService service = newService();
        BotSession stale = mock(BotSession.class);
        when(stale.getCreatedAtMs()).thenReturn(System.currentTimeMillis() - 10 * 60 * 1000L);
        pendingSessions(service).put("bot-stale", stale);

        service.sweepStalePendingSessions();

        verify(stale).close();
        verify(stale).deleteSession();
        assertThat(pendingSessions(service)).isEmpty();
    }

    @Test
    void freshPendingSessionIsKept() {
        WeixinBotService service = newService();
        BotSession fresh = mock(BotSession.class);
        when(fresh.getCreatedAtMs()).thenReturn(System.currentTimeMillis());
        pendingSessions(service).put("bot-fresh", fresh);

        service.sweepStalePendingSessions();

        verify(fresh, never()).close();
        assertThat(pendingSessions(service)).containsKey("bot-fresh");
    }
}
