package ykd.ykd.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import ykd.ykd.llm.service.LlmService;
import ykd.ykd.memory.mapper.ReminderTaskMapper;
import ykd.ykd.memory.model.ReminderTaskEntity;
import ykd.ykd.processor.UserContext;
import ykd.ykd.wxbot.WeixinBotService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnifiedReminderManagerTest {

    private UnifiedReminderManager manager;
    private ReminderTaskMapper mapper;
    private LlmService llmService;
    private ChatClient deepseekClient;
    private UserContext userContext;
    private WeixinBotService weixinBotService;

    @BeforeEach
    void setUp() {
        mapper = mock(ReminderTaskMapper.class);
        llmService = mock(LlmService.class);
        deepseekClient = mock(ChatClient.class);
        userContext = mock(UserContext.class);
        weixinBotService = mock(WeixinBotService.class);
        when(userContext.getCurrentUserId()).thenReturn("test-user");

        manager = new UnifiedReminderManager(llmService, deepseekClient, userContext, mapper, weixinBotService);
        manager.init();
    }

    @AfterEach
    void tearDown() {
        manager.stop();
    }

    // ── ONCE parsing ──────────────────────────────────────────

    @Test
    void shouldParseMinutesCorrectly() {
        String result = manager.scheduleOnce("u1", "开会", "10分钟后", false);
        assertThat(result).contains("已设置提醒", "10分钟");

        ArgumentCaptor<ReminderTaskEntity> captor = ArgumentCaptor.forClass(ReminderTaskEntity.class);
        verify(mapper).insert(captor.capture());
        ReminderTaskEntity entity = captor.getValue();
        assertThat(entity.getTaskType()).isEqualTo("ONCE");
        assertThat(entity.getDelaySeconds()).isEqualTo(600);
        assertThat(entity.getMessage()).isEqualTo("开会");
    }

    @Test
    void shouldParseHoursCorrectly() {
        manager.scheduleOnce("u1", "休息", "2小时后", false);

        ArgumentCaptor<ReminderTaskEntity> captor = ArgumentCaptor.forClass(ReminderTaskEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getDelaySeconds()).isEqualTo(7200);
    }

    @Test
    void shouldParseSecondsCorrectly() {
        manager.scheduleOnce("u1", "喝水", "30秒后", false);

        ArgumentCaptor<ReminderTaskEntity> captor = ArgumentCaptor.forClass(ReminderTaskEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getDelaySeconds()).isEqualTo(30);
    }

    @Test
    void shouldRejectInvalidOnceExpression() {
        String result = manager.scheduleOnce("u1", "test", "下周三", false);
        assertThat(result).contains("无法识别时间表达");
    }

    @Test
    void shouldSetNeedsProcessingFlag() {
        manager.scheduleOnce("u1", "查天气", "10分钟后", true);

        ArgumentCaptor<ReminderTaskEntity> captor = ArgumentCaptor.forClass(ReminderTaskEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getNeedsProcessing()).isEqualTo(1);
    }

    // ── ONCE firing ───────────────────────────────────────────

    @Test
    void shouldFireOnceTaskAndSendToWechat() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(inv -> { sent.countDown(); return true; })
                .when(weixinBotService).sendTextWithResult(eq("u1"), anyString());

        manager.scheduleOnce("u1", "快到了", "1秒后", false);
        assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();

        verify(weixinBotService).sendTextWithResult(eq("u1"), org.mockito.ArgumentMatchers.contains("快到了"));
        assertThat(manager.listTasks("u1")).isEqualTo("当前没有待执行的提醒");
    }

    @Test
    void shouldSendDirectlyWithoutLLM() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(inv -> { sent.countDown(); return true; })
                .when(weixinBotService).sendTextWithResult(eq("u1"), anyString());

        manager.scheduleOnce("u1", "直接提醒", "1秒后", false);
        assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();

        verify(weixinBotService).sendTextWithResult(eq("u1"), org.mockito.ArgumentMatchers.contains("直接提醒"));
    }

    @Test
    void shouldSendLLMResultWhenNeedsProcessing() throws Exception {
        when(llmService.chat(anyString(), eq(null), eq(deepseekClient), eq("u1")))
                .thenReturn("今天天气晴朗");
        doAnswer(inv -> {
            Runnable r = inv.getArgument(1);
            r.run();
            return null;
        }).when(userContext).executeAs(eq("u1"), any());

        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(inv -> { sent.countDown(); return null; })
                .when(weixinBotService).sendTextToUser(eq("u1"), anyString());

        manager.scheduleOnce("u1", "查天气", "1秒后", true);
        assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();

        verify(weixinBotService).sendTextToUser(eq("u1"), eq("今天天气晴朗"));
    }

    // ── DAILY parsing ─────────────────────────────────────────

    @Test
    void shouldParseDailyWithChineseColon() {
        manager.scheduleDaily("u1", "打卡", "每天早上8点", false);

        ArgumentCaptor<ReminderTaskEntity> captor = ArgumentCaptor.forClass(ReminderTaskEntity.class);
        verify(mapper).insert(captor.capture());
        ReminderTaskEntity entity = captor.getValue();
        assertThat(entity.getTaskType()).isEqualTo("DAILY");
        assertThat(entity.getCronExpression()).isEqualTo("0 0 8 * * ?");
    }

    @Test
    void shouldParseDailyWithMinutes() {
        manager.scheduleDaily("u1", "开会", "每天08:30", false);

        ArgumentCaptor<ReminderTaskEntity> captor = ArgumentCaptor.forClass(ReminderTaskEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getCronExpression()).isEqualTo("0 30 8 * * ?");
    }

    @Test
    void shouldHandleDailyWithChineseCharacterTime() {
        manager.scheduleDaily("u1", "吃药", "每日12点30", false);

        ArgumentCaptor<ReminderTaskEntity> captor = ArgumentCaptor.forClass(ReminderTaskEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getCronExpression()).isEqualTo("0 30 12 * * ?");
    }

    @Test
    void shouldRejectDailyWithoutTime() {
        String result = manager.scheduleDaily("u1", "test", "每天早上", false);
        assertThat(result).contains("无法识别每日时间");
    }

    // ── WEEKLY parsing ────────────────────────────────────────

    @Test
    void shouldParseWeeklySunday() {
        manager.scheduleWeekly("u1", "周报", "每周日早上8点", false);

        ArgumentCaptor<ReminderTaskEntity> captor = ArgumentCaptor.forClass(ReminderTaskEntity.class);
        verify(mapper).insert(captor.capture());
        ReminderTaskEntity entity = captor.getValue();
        assertThat(entity.getTaskType()).isEqualTo("WEEKLY");
        assertThat(entity.getCronExpression()).isEqualTo("0 0 8 ? * 7");
    }

    @Test
    void shouldParseWeeklyMonday() {
        manager.scheduleWeekly("u1", "站会", "每周一09:00", false);

        ArgumentCaptor<ReminderTaskEntity> captor = ArgumentCaptor.forClass(ReminderTaskEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getCronExpression()).isEqualTo("0 0 9 ? * 1");
    }

    @Test
    void shouldParseWeeklyFriday() {
        manager.scheduleWeekly("u1", "总结", "每周五17点30", false);

        ArgumentCaptor<ReminderTaskEntity> captor = ArgumentCaptor.forClass(ReminderTaskEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getCronExpression()).isEqualTo("0 30 17 ? * 5");
    }

    @Test
    void shouldParseWeeklySaturday() {
        manager.scheduleWeekly("u1", "锻炼", "每周六早上7点", false);

        ArgumentCaptor<ReminderTaskEntity> captor = ArgumentCaptor.forClass(ReminderTaskEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getCronExpression()).isEqualTo("0 0 7 ? * 6");
    }

    @Test
    void shouldParseWeeklyUsingTianCharacter() {
        manager.scheduleWeekly("u1", "买菜", "每周天早上9点", false);

        ArgumentCaptor<ReminderTaskEntity> captor = ArgumentCaptor.forClass(ReminderTaskEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getCronExpression()).isEqualTo("0 0 9 ? * 7");
    }

    @Test
    void shouldRejectWeeklyWithoutTime() {
        String result = manager.scheduleWeekly("u1", "test", "每周日", false);
        assertThat(result).contains("无法识别每周时间");
    }

    @Test
    void shouldRejectNonWeeklyExpression() {
        String result = manager.scheduleWeekly("u1", "test", "下周三8点", false);
        assertThat(result).contains("无法识别每周时间");
    }

    // ── INTERVAL parsing ──────────────────────────────────────

    @Test
    void shouldParseIntervalMinutes() {
        manager.scheduleInterval("u1", "喝水", "每5分钟", false);

        ArgumentCaptor<ReminderTaskEntity> captor = ArgumentCaptor.forClass(ReminderTaskEntity.class);
        verify(mapper).insert(captor.capture());
        ReminderTaskEntity entity = captor.getValue();
        assertThat(entity.getTaskType()).isEqualTo("INTERVAL");
        assertThat(entity.getIntervalSeconds()).isEqualTo(300);
    }

    @Test
    void shouldParseIntervalHours() {
        manager.scheduleInterval("u1", "休息", "每隔2小时", false);

        ArgumentCaptor<ReminderTaskEntity> captor = ArgumentCaptor.forClass(ReminderTaskEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getIntervalSeconds()).isEqualTo(7200);
    }

    @Test
    void shouldParseIntervalSeconds() {
        manager.scheduleInterval("u1", "心跳", "每10秒", false);

        ArgumentCaptor<ReminderTaskEntity> captor = ArgumentCaptor.forClass(ReminderTaskEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getIntervalSeconds()).isEqualTo(10);
    }

    @Test
    void shouldRejectInvalidIntervalExpression() {
        String result = manager.scheduleInterval("u1", "test", "每天", false);
        assertThat(result).contains("无法识别间隔时间");
    }

    @Test
    void shouldRejectZeroInterval() {
        String result = manager.scheduleInterval("u1", "test", "每0秒", false);
        assertThat(result).contains("不能少于1秒");
    }

    // ── List tasks ────────────────────────────────────────────

    @Test
    void shouldReturnEmptyForNoTasks() {
        assertThat(manager.listTasks("u1")).isEqualTo("当前没有待执行的提醒");
    }

    @Test
    void shouldListAllTaskTypes() {
        manager.scheduleOnce("u1", "once-msg", "10分钟后", false);
        manager.scheduleDaily("u1", "daily-msg", "每天早上8点", false);
        manager.scheduleWeekly("u1", "weekly-msg", "每周日早上9点", false);
        manager.scheduleInterval("u1", "interval-msg", "每5分钟", false);

        String list = manager.listTasks("u1");
        assertThat(list).contains("[单次]", "once-msg");
        assertThat(list).contains("[每天]", "daily-msg");
        assertThat(list).contains("[每周]", "weekly-msg");
        assertThat(list).contains("[间隔]", "interval-msg");
    }

    @Test
    void shouldOnlyListTasksForGivenUser() {
        manager.scheduleOnce("u1", "u1-task", "10分钟后", false);
        manager.scheduleOnce("u2", "u2-task", "20分钟后", false);

        String list = manager.listTasks("u1");
        assertThat(list).contains("u1-task");
        assertThat(list).doesNotContain("u2-task");
    }

    @Test
    void shouldMaintainInsertionOrder() {
        manager.scheduleOnce("u1", "first", "10分钟后", false);
        manager.scheduleOnce("u1", "second", "20分钟后", false);
        manager.scheduleOnce("u1", "third", "30分钟后", false);

        String list = manager.listTasks("u1");
        assertThat(list.indexOf("first")).isLessThan(list.indexOf("second"));
        assertThat(list.indexOf("second")).isLessThan(list.indexOf("third"));
    }

    // ── Cancel tasks ──────────────────────────────────────────

    @Test
    void shouldCancelTaskByIndex() {
        manager.scheduleOnce("u1", "task1", "10分钟后", false);
        manager.scheduleOnce("u1", "task2", "20分钟后", false);

        String result = manager.cancelByIndex("u1", 1);
        assertThat(result).contains("已取消", "task1");

        String list = manager.listTasks("u1");
        assertThat(list).contains("task2");
        assertThat(list).doesNotContain("task1");

        verify(mapper).cancelByTaskId(anyString());
    }

    @Test
    void shouldRejectInvalidCancelIndex() {
        manager.scheduleOnce("u1", "task1", "10分钟后", false);

        String result = manager.cancelByIndex("u1", 999);
        assertThat(result).contains("序号无效");
    }

    @Test
    void shouldCancelDailyTask() {
        manager.scheduleDaily("u1", "daily", "每天早上8点", false);

        String result = manager.cancelByIndex("u1", 1);
        assertThat(result).contains("已取消", "daily");
        assertThat(manager.listTasks("u1")).isEqualTo("当前没有待执行的提醒");
    }

    @Test
    void shouldCancelIntervalTask() {
        manager.scheduleInterval("u1", "interval", "每5分钟", false);

        String result = manager.cancelByIndex("u1", 1);
        assertThat(result).contains("已取消", "interval");
        assertThat(manager.listTasks("u1")).isEqualTo("当前没有待执行的提醒");
    }

    // ── Recovery ──────────────────────────────────────────────

    @Test
    void shouldRecoverOnceTask() {
        ReminderTaskEntity entity = new ReminderTaskEntity();
        entity.setTaskId("rec-once");
        entity.setUserId("u1");
        entity.setMessage("recovered-once");
        entity.setTaskType("ONCE");
        entity.setDelaySeconds(3600);
        entity.setCreatedAt("2026-07-30 14:00:00");
        entity.setNeedsProcessing(0);
        when(mapper.findAllActive()).thenReturn(List.of(entity));

        manager.recover();

        String list = manager.listTasks("u1");
        assertThat(list).contains("recovered-once");
        assertThat(list).contains("[单次]");
    }

    @Test
    void shouldRecoverDailyTaskWithCron() {
        ReminderTaskEntity entity = new ReminderTaskEntity();
        entity.setTaskId("rec-daily");
        entity.setUserId("u1");
        entity.setMessage("recovered-daily");
        entity.setTaskType("DAILY");
        entity.setCronExpression("0 0 8 * * ?");
        entity.setNeedsProcessing(0);
        when(mapper.findAllActive()).thenReturn(List.of(entity));

        manager.recover();

        String list = manager.listTasks("u1");
        assertThat(list).contains("recovered-daily");
        assertThat(list).contains("[每天]");
        assertThat(list).contains("0 0 8 * * ?");
    }

    @Test
    void shouldRecoverWeeklyTaskWithCron() {
        ReminderTaskEntity entity = new ReminderTaskEntity();
        entity.setTaskId("rec-weekly");
        entity.setUserId("u1");
        entity.setMessage("recovered-weekly");
        entity.setTaskType("WEEKLY");
        entity.setCronExpression("0 0 9 ? * SUN");
        entity.setNeedsProcessing(0);
        when(mapper.findAllActive()).thenReturn(List.of(entity));

        manager.recover();

        String list = manager.listTasks("u1");
        assertThat(list).contains("recovered-weekly");
        assertThat(list).contains("[每周]");
    }

    @Test
    void shouldRecoverIntervalTask() {
        ReminderTaskEntity entity = new ReminderTaskEntity();
        entity.setTaskId("rec-interval");
        entity.setUserId("u1");
        entity.setMessage("recovered-interval");
        entity.setTaskType("INTERVAL");
        entity.setIntervalSeconds(300);
        entity.setCreatedAt("2026-07-30 14:00:00");
        entity.setNeedsProcessing(0);
        when(mapper.findAllActive()).thenReturn(List.of(entity));

        manager.recover();

        String list = manager.listTasks("u1");
        assertThat(list).contains("recovered-interval");
        assertThat(list).contains("[间隔]");
    }

    @Test
    void shouldRecoverMultipleTaskTypesTogether() {
        List<ReminderTaskEntity> entities = new ArrayList<>();

        ReminderTaskEntity once = new ReminderTaskEntity();
        once.setTaskId("r1"); once.setUserId("u1"); once.setMessage("once");
        once.setTaskType("ONCE"); once.setDelaySeconds(7200);
        once.setCreatedAt("2026-07-30 14:00:00"); once.setNeedsProcessing(0);
        entities.add(once);

        ReminderTaskEntity daily = new ReminderTaskEntity();
        daily.setTaskId("r2"); daily.setUserId("u1"); daily.setMessage("daily");
        daily.setTaskType("DAILY"); daily.setCronExpression("0 0 8 * * ?"); daily.setNeedsProcessing(0);
        entities.add(daily);

        ReminderTaskEntity interval = new ReminderTaskEntity();
        interval.setTaskId("r3"); interval.setUserId("u1"); interval.setMessage("interval");
        interval.setTaskType("INTERVAL"); interval.setIntervalSeconds(600);
        interval.setCreatedAt("2026-07-30 14:00:00"); interval.setNeedsProcessing(0);
        entities.add(interval);

        when(mapper.findAllActive()).thenReturn(entities);

        manager.recover();

        String list = manager.listTasks("u1");
        assertThat(list).contains("[单次]", "[每天]", "[间隔]");
        assertThat(list).contains("once", "daily", "interval");
    }

    @Test
    void shouldSkipDailyRecoveryWithoutCronExpression() {
        ReminderTaskEntity entity = new ReminderTaskEntity();
        entity.setTaskId("bad-daily");
        entity.setUserId("u1");
        entity.setMessage("bad");
        entity.setTaskType("DAILY");
        entity.setCronExpression(null);
        entity.setNeedsProcessing(0);
        when(mapper.findAllActive()).thenReturn(List.of(entity));

        manager.recover();

        assertThat(manager.listTasks("u1")).isEqualTo("当前没有待执行的提醒");
    }

    // ── Interval firing ───────────────────────────────────────

    @Test
    void shouldScheduleIntervalShortDelay() {
        manager.scheduleInterval("u1", "喝水", "每3秒", false);
        assertThat(manager.listTasks("u1")).contains("喝水");
        assertThat(manager.listTasks("u1")).contains("[间隔]");
    }

    // ── Entity fields ─────────────────────────────────────────

    @Test
    void shouldPersistCorrectUserIdAndMessage() {
        manager.scheduleOnce("uid-123", "测试消息内容", "5分钟后", false);

        ArgumentCaptor<ReminderTaskEntity> captor = ArgumentCaptor.forClass(ReminderTaskEntity.class);
        verify(mapper).insert(captor.capture());
        ReminderTaskEntity entity = captor.getValue();
        assertThat(entity.getUserId()).isEqualTo("uid-123");
        assertThat(entity.getMessage()).isEqualTo("测试消息内容");
        assertThat(entity.getTimeExpression()).isEqualTo("5分钟后");
        assertThat(entity.getTaskId()).isNotNull().hasSize(8);
    }

    @Test
    void shouldPersistCronExpressionForWeekly() {
        manager.scheduleWeekly("u1", "周报", "每周日早上8点", false);

        ArgumentCaptor<ReminderTaskEntity> captor = ArgumentCaptor.forClass(ReminderTaskEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getCronExpression()).isEqualTo("0 0 8 ? * 7");
        assertThat(captor.getValue().getTaskType()).isEqualTo("WEEKLY");
    }

    // ── Direct send via WeixinBotService ──────────────────────

    @Test
    void shouldSendToWechatWhenFiring() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(inv -> { sent.countDown(); return null; })
                .when(weixinBotService).sendTextToUser(eq("u1"), anyString());

        manager.scheduleOnce("u1", "to-cancel", "1秒后", false);
        assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();

        verify(weixinBotService).sendTextToUser(eq("u1"), anyString());
    }

    // ── Cancel calls mapper ───────────────────────────────────

    @Test
    void shouldCallMapperCancelWhenCancelling() {
        manager.scheduleOnce("u1", "to-cancel", "10分钟后", false);
        manager.cancelByIndex("u1", 1);
        verify(mapper).cancelByTaskId(anyString());
    }

    @Test
    void shouldCallMapperCancelWhenOnceTaskFires() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        doAnswer(inv -> { sent.countDown(); return null; })
                .when(weixinBotService).sendTextToUser(eq("u1"), anyString());

        manager.scheduleOnce("u1", "auto-cancel", "1秒后", false);
        assertThat(sent.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);

        verify(mapper).cancelByTaskId(anyString());
    }
}
