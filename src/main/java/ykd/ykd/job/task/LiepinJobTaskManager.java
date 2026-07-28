package ykd.ykd.job.task;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import ykd.ykd.job.browser.LiepinAutomationGateway;
import ykd.ykd.job.browser.LiepinLoginRequiredException;
import ykd.ykd.job.config.LiepinProperties;
import ykd.ykd.job.mapper.LiepinJobCampaignMapper;
import ykd.ykd.job.mapper.LiepinJobPostingMapper;
import ykd.ykd.job.mapper.LiepinJobTaskMapper;
import ykd.ykd.job.model.*;
import ykd.ykd.job.service.LiepinApplicationService;
import ykd.ykd.job.service.LiepinJobMatchService;
import ykd.ykd.job.service.LiepinResumeService;
import ykd.ykd.processor.ProcessResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Slf4j
@Component
public class LiepinJobTaskManager {
    private final LiepinProperties properties;
    private final LiepinResumeService resumeService;
    private final LiepinJobTaskMapper taskMapper;
    private final LiepinJobPostingMapper postingMapper;
    private final LiepinJobCampaignMapper campaignMapper;
    private final LiepinApplicationService applicationService;
    private final LiepinJobMatchService matchService;
    private final LiepinAutomationGateway browser;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "liepin-job-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, AtomicBoolean> cancellations = new ConcurrentHashMap<>();
    private final AtomicBoolean automaticScanQueued = new AtomicBoolean(false);
    private final AtomicBoolean loginPageOpened = new AtomicBoolean(false);
    private volatile Consumer<ProcessResult> onCompleted;

    public LiepinJobTaskManager(LiepinProperties properties,
                                LiepinResumeService resumeService,
                                LiepinJobTaskMapper taskMapper,
                                LiepinJobPostingMapper postingMapper,
                                LiepinJobCampaignMapper campaignMapper,
                                LiepinApplicationService applicationService,
                                LiepinJobMatchService matchService,
                                @Lazy LiepinAutomationGateway browser) {
        this.properties = properties;
        this.resumeService = resumeService;
        this.taskMapper = taskMapper;
        this.postingMapper = postingMapper;
        this.campaignMapper = campaignMapper;
        this.applicationService = applicationService;
        this.matchService = matchService;
        this.browser = browser;
    }

    public void setOnCompleted(Consumer<ProcessResult> callback) {
        this.onCompleted = callback;
    }

    public String openLogin() {
        return runOnLiepinWorker(browser::openLogin);
    }

    public String loginStatus() {
        return runOnLiepinWorker(() -> browser.isLoggedIn()
                ? "猎聘已登录，可以开始搜索岗位；暂停中的自动投递会在下一次扫描时恢复。"
                : "猎聘尚未登录，请先打开登录页面完成登录。");
    }

    public void scanAutomaticCampaigns() {
        if (!properties.isEnabled() || !properties.getAutoApply().isEnabled()) return;
        if (!automaticScanQueued.compareAndSet(false, true)) return;
        executor.submit(() -> {
            try {
                runAutomaticCampaignScan();
            } catch (Exception e) {
                log.error("[LiepinJob] 自动投递扫描异常", e);
            } finally {
                automaticScanQueued.set(false);
            }
        });
    }

    public String startSearch(String userId, String keyword, String city,
                              Integer minSalaryK, Integer maxSalaryK, boolean excludeOutsourcing) {
        if (!properties.isEnabled()) {
            return "猎聘功能尚未启用，请先设置 LIEPIN_ENABLED=true 并重启项目。";
        }
        if (resumeService.find(userId) == null) {
            return "还没有保存简历。请先在微信发送 PDF、Word 或文本简历，再说“把刚才的文件设为求职简历”。";
        }
        LiepinJobTask task = createTask(userId, keyword, city, minSalaryK, maxSalaryK,
                excludeOutsourcing, "等待后台搜索");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancellations.put(task.getId(), cancelled);
        executor.submit(() -> runSearch(task, cancelled));
        return "已创建猎聘岗位搜索任务 #" + task.getId() + "，后台会搜索并用简历匹配。完成后机器人会主动把候选岗位发给你。";
    }

    public String listLatestCandidates(String userId) {
        LiepinJobTask task = taskMapper.findLatestByUser(userId);
        if (task == null) return "暂无猎聘求职任务。";
        return formatCandidates(task, postingMapper.findByTaskId(task.getId()));
    }

    public String confirmApplication(String userId, int candidateIndex) {
        LiepinJobTask task = taskMapper.findLatestByUser(userId);
        if (task == null) return "暂无可确认的猎聘求职任务。";
        if (!LiepinTaskStatus.WAITING_CONFIRMATION.name().equals(task.getStatus())
                && !LiepinTaskStatus.NEEDS_USER_ACTION.name().equals(task.getStatus())) {
            return "当前任务状态为 " + task.getStatus() + "，还不能提交岗位。";
        }
        List<LiepinJobPosting> jobs = postingMapper.findByTaskId(task.getId());
        if (candidateIndex < 1 || candidateIndex > jobs.size()) {
            return "候选序号无效，当前共有 " + jobs.size() + " 个岗位。";
        }
        LiepinJobPosting posting = jobs.get(candidateIndex - 1);
        postingMapper.updateStatus(posting.getId(), "SUBMITTING");
        update(task, LiepinTaskStatus.SUBMITTING, "正在提交候选岗位 #" + candidateIndex);
        executor.submit(() -> runApplication(task, posting));
        return "已确认候选岗位 " + candidateIndex + "，后台正在打开猎聘并发起沟通。结果会主动通知你。";
    }

    public String cancelLatest(String userId) {
        LiepinJobTask task = taskMapper.findLatestByUser(userId);
        if (task == null) return "暂无可取消的猎聘求职任务。";
        AtomicBoolean flag = cancellations.get(task.getId());
        if (flag != null) flag.set(true);
        update(task, LiepinTaskStatus.CANCELLED, "用户取消任务");
        return "已取消猎聘求职任务 #" + task.getId() + "。";
    }

    public String latestStatus(String userId) {
        LiepinJobTask task = taskMapper.findLatestByUser(userId);
        return task == null ? "暂无猎聘求职任务。"
                : "猎聘任务 #" + task.getId() + "：" + task.getStatus() + "，" + task.getMessage();
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }

    private void runAutomaticCampaignScan() {
        List<LiepinJobCampaign> due = campaignMapper.findDueRunning();
        List<LiepinJobCampaign> waitingLogin = campaignMapper.findLoginRequired();
        if (due.isEmpty() && waitingLogin.isEmpty()) return;

        boolean loggedIn;
        try {
            loggedIn = browser.isLoggedIn();
        } catch (Exception e) {
            log.warn("[LiepinJob] 检查登录状态失败: {}", e.getMessage());
            return;
        }
        if (!loggedIn) {
            for (LiepinJobCampaign campaign : due) pauseForLogin(campaign);
            if (!due.isEmpty()) {
                try {
                    browser.openLogin();
                } catch (Exception e) {
                    log.warn("[LiepinJob] 自动打开登录页失败: {}", e.getMessage());
                }
            }
            return;
        }

        loginPageOpened.set(false);
        for (LiepinJobCampaign campaign : waitingLogin) {
            campaignMapper.start(campaign.getId(), campaign.getUserId(), "登录已恢复，自动投递计划继续运行");
            notifyUser(campaign.getUserId(), "猎聘登录已恢复，自动投递计划 #" + campaign.getId() + " 将继续执行。 ");
        }
        for (LiepinJobCampaign campaign : campaignMapper.findDueRunning()) {
            runCampaign(campaign);
        }
    }

    private void runCampaign(LiepinJobCampaign campaign) {
        LiepinJobCampaign latest = campaignMapper.findById(campaign.getId());
        if (latest == null || !LiepinCampaignStatus.RUNNING.name().equals(latest.getStatus())) return;
        int alreadyToday = applicationService.countTodaySuccessful(latest.getUserId());
        int remaining = latest.getDailyLimit() - alreadyToday;
        if (remaining <= 0) {
            campaignMapper.finishRun(latest.getId(), LiepinCampaignStatus.RUNNING.name(),
                    "今日投递已达到上限 " + latest.getDailyLimit(), 0);
            return;
        }

        LiepinResume resume = resumeService.find(latest.getUserId());
        if (resume == null) {
            pauseCampaign(latest, LiepinCampaignStatus.FAILED, "求职简历不存在，请重新保存简历");
            return;
        }
        ResumeDeliveryMode mode = ResumeDeliveryMode.parse(latest.getDeliveryMode());
        if (mode == ResumeDeliveryMode.ATTACHMENT && !resumeService.hasUsableAttachment(resume)) {
            pauseCampaign(latest, LiepinCampaignStatus.FAILED, "附件简历不存在，请重新发送并保存简历");
            return;
        }

        LiepinJobTask task = createTask(latest.getUserId(), latest.getKeyword(), latest.getCity(),
                latest.getMinSalaryK(), latest.getMaxSalaryK(), latest.isExcludeOutsourcing(), "自动投递计划搜索");
        int attempted = 0;
        int succeeded = 0;
        int failed = 0;
        try {
            update(task, LiepinTaskStatus.SEARCHING, "自动投递计划正在搜索岗位");
            LiepinSearchRequest request = new LiepinSearchRequest(latest.getKeyword(), latest.getCity(),
                    latest.getMinSalaryK(), latest.getMaxSalaryK(), latest.isExcludeOutsourcing(),
                    properties.getMaxSearchResults());
            List<LiepinJobPosting> jobs = browser.search(request, () -> false);
            if (jobs.isEmpty()) {
                update(task, LiepinTaskStatus.FAILED, "没有找到符合条件的岗位");
                campaignMapper.finishRun(latest.getId(), LiepinCampaignStatus.RUNNING.name(),
                        "本轮没有找到符合条件的岗位", 0);
                return;
            }
            update(task, LiepinTaskStatus.ANALYZING, "正在匹配简历与岗位");
            for (LiepinJobPosting posting : jobs) {
                if (succeeded >= remaining) break;
                if (containsExcludedKeyword(posting, latest.getExcludedKeywords())) continue;
                posting.setTaskId(task.getId());
                boolean aiMatched = matchService.enrich(resume.getContent(), posting);
                postingMapper.insert(posting);
                if (!aiMatched || posting.getMatchScore() < latest.getMinMatchScore()) continue;

                LiepinApplicationRecord record = applicationService.reserve(
                        latest.getUserId(), latest, task, posting, resume);
                if (applicationService.wasAlreadyProcessed(record)) continue;
                attempted++;
                applicationService.markContacting(record);
                postingMapper.updateStatus(posting.getId(), "SUBMITTING_RESUME");
                LiepinApplicationResult result = browser.applyAndSendResume(posting, resume, mode, posting.getGreeting());
                applicationService.complete(record, result);
                postingMapper.updateStatus(posting.getId(), result.status().name());
                if (result.success()) {
                    succeeded++;
                } else {
                    failed++;
                }
                if (result.status().pausesCampaign()) {
                    LiepinCampaignStatus status = result.status() == LiepinApplicationStatus.RISK_CONTROL
                            ? LiepinCampaignStatus.RISK_CONTROL : LiepinCampaignStatus.LOGIN_REQUIRED;
                    pauseCampaign(latest, status, result.message());
                    update(task, LiepinTaskStatus.NEEDS_USER_ACTION, result.message());
                    return;
                }
            }

            int consecutive = succeeded > 0 ? 0 : latest.getConsecutiveFailures() + (failed > 0 ? 1 : 0);
            if (consecutive >= properties.getAutoApply().getMaxConsecutiveFailures()) {
                String message = "连续 " + consecutive + " 轮投递失败，计划已自动暂停，请检查页面或简历设置";
                pauseCampaign(latest, LiepinCampaignStatus.PAUSED, message);
                update(task, LiepinTaskStatus.NEEDS_USER_ACTION, message);
                return;
            }
            String summary = "本轮筛选 " + jobs.size() + " 个岗位，尝试 " + attempted
                    + " 个，成功 " + succeeded + " 个，失败 " + failed + " 个";
            campaignMapper.finishRun(latest.getId(), LiepinCampaignStatus.RUNNING.name(), summary, consecutive);
            update(task, succeeded > 0 ? LiepinTaskStatus.SUCCEEDED : LiepinTaskStatus.FAILED, summary);
            if (attempted > 0) notifyUser(latest.getUserId(), "猎聘自动投递计划 #" + latest.getId() + "：" + summary);
        } catch (LiepinLoginRequiredException e) {
            pauseForLogin(latest);
            update(task, LiepinTaskStatus.NEEDS_USER_ACTION, e.getMessage());
            try {
                browser.openLogin();
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            log.error("[LiepinJob] 自动投递计划执行失败: campaignId={}", latest.getId(), e);
            int consecutive = latest.getConsecutiveFailures() + 1;
            LiepinCampaignStatus status = consecutive >= properties.getAutoApply().getMaxConsecutiveFailures()
                    ? LiepinCampaignStatus.PAUSED : LiepinCampaignStatus.RUNNING;
            campaignMapper.finishRun(latest.getId(), status.name(), friendlyError(e), consecutive);
            update(task, LiepinTaskStatus.FAILED, friendlyError(e));
            notifyUser(latest.getUserId(), "猎聘自动投递计划 #" + latest.getId() + " 执行失败：" + friendlyError(e));
        }
    }

    private void pauseForLogin(LiepinJobCampaign campaign) {
        pauseCampaign(campaign, LiepinCampaignStatus.LOGIN_REQUIRED,
                "猎聘登录已失效，完成登录后系统会自动恢复计划");
    }

    private void pauseCampaign(LiepinJobCampaign campaign, LiepinCampaignStatus status, String message) {
        campaignMapper.updateStatus(campaign.getId(), campaign.getUserId(), status.name(), message);
        notifyUser(campaign.getUserId(), "猎聘自动投递计划 #" + campaign.getId() + " 已暂停：" + message);
    }

    private boolean containsExcludedKeyword(LiepinJobPosting posting, String rawKeywords) {
        if (rawKeywords == null || rawKeywords.isBlank()) return false;
        String source = String.join(" ", value(posting.getJobName()), value(posting.getCompanyName()),
                value(posting.getDescription()));
        return Arrays.stream(rawKeywords.split("[,，;；\\n]"))
                .map(String::strip).filter(value -> !value.isBlank()).anyMatch(source::contains);
    }

    private void runSearch(LiepinJobTask task, AtomicBoolean cancelled) {
        try {
            update(task, LiepinTaskStatus.SEARCHING, "正在猎聘搜索岗位");
            LiepinSearchRequest request = new LiepinSearchRequest(task.getKeyword(), task.getCity(),
                    task.getMinSalaryK(), task.getMaxSalaryK(), task.isExcludeOutsourcing(), properties.getMaxSearchResults());
            List<LiepinJobPosting> jobs = browser.search(request, cancelled::get);
            if (cancelled.get()) {
                update(task, LiepinTaskStatus.CANCELLED, "任务已取消");
                return;
            }
            if (jobs.isEmpty()) {
                update(task, LiepinTaskStatus.FAILED, "没有找到符合筛选条件的岗位");
                notifyUser(task.getUserId(), "猎聘任务 #" + task.getId() + " 没有找到符合条件的岗位，可尝试放宽城市、薪资或外包筛选。 ");
                return;
            }
            update(task, LiepinTaskStatus.ANALYZING, "正在使用 DeepSeek 匹配简历与岗位");
            String resume = resumeService.find(task.getUserId()).getContent();
            for (LiepinJobPosting posting : jobs) {
                if (cancelled.get()) break;
                posting.setTaskId(task.getId());
                matchService.enrich(resume, posting);
                postingMapper.insert(posting);
            }
            if (cancelled.get()) {
                update(task, LiepinTaskStatus.CANCELLED, "任务已取消");
                return;
            }
            update(task, LiepinTaskStatus.WAITING_CONFIRMATION, "候选岗位已生成，等待用户选择");
            notifyUser(task.getUserId(), formatCandidates(task, postingMapper.findByTaskId(task.getId())));
        } catch (LiepinLoginRequiredException e) {
            update(task, LiepinTaskStatus.NEEDS_USER_ACTION, e.getMessage());
            notifyUser(task.getUserId(), "猎聘任务 #" + task.getId() + " 暂停：" + e.getMessage());
        } catch (Exception e) {
            log.error("[LiepinJob] 搜索任务失败: taskId={}", task.getId(), e);
            update(task, LiepinTaskStatus.FAILED, e.getMessage());
            notifyUser(task.getUserId(), "猎聘任务 #" + task.getId() + " 失败：" + friendlyError(e));
        } finally {
            cancellations.remove(task.getId());
        }
    }

    private void runApplication(LiepinJobTask task, LiepinJobPosting posting) {
        try {
            LiepinApplicationResult result = browser.apply(posting, posting.getGreeting());
            if (result.success()) {
                postingMapper.updateStatus(posting.getId(), "SUBMITTED");
                update(task, LiepinTaskStatus.SUCCEEDED, result.message());
                notifyUser(task.getUserId(), "猎聘沟通结果：" + result.message());
            } else if (result.needsUserAction()) {
                postingMapper.updateStatus(posting.getId(), "NEEDS_USER_ACTION");
                update(task, LiepinTaskStatus.NEEDS_USER_ACTION, result.message());
                notifyUser(task.getUserId(), "猎聘任务需要你处理：" + result.message());
            } else {
                postingMapper.updateStatus(posting.getId(), "FAILED");
                update(task, LiepinTaskStatus.FAILED, result.message());
                notifyUser(task.getUserId(), "猎聘沟通失败：" + result.message());
            }
        } catch (Exception e) {
            log.error("[LiepinJob] 投递任务失败: taskId={}, postingId={}", task.getId(), posting.getId(), e);
            postingMapper.updateStatus(posting.getId(), "FAILED");
            update(task, LiepinTaskStatus.FAILED, e.getMessage());
            notifyUser(task.getUserId(), "猎聘沟通失败：" + friendlyError(e));
        }
    }

    private LiepinJobTask createTask(String userId, String keyword, String city,
                                     Integer minSalaryK, Integer maxSalaryK,
                                     boolean excludeOutsourcing, String message) {
        LiepinJobTask task = new LiepinJobTask();
        task.setUserId(userId);
        task.setKeyword(keyword);
        task.setCity(city);
        task.setMinSalaryK(normalizeSalary(minSalaryK));
        task.setMaxSalaryK(normalizeSalary(maxSalaryK));
        task.setExcludeOutsourcing(excludeOutsourcing);
        task.setStatus(LiepinTaskStatus.CREATED.name());
        task.setMessage(message);
        taskMapper.insert(task);
        return task;
    }

    private String formatCandidates(LiepinJobTask task, List<LiepinJobPosting> jobs) {
        StringBuilder text = new StringBuilder("猎聘候选岗位（任务 #").append(task.getId()).append("）\n");
        if (jobs.isEmpty()) return text.append("暂无候选岗位，当前状态：").append(task.getStatus()).toString();
        int limit = Math.min(jobs.size(), properties.getMaxSearchResults());
        for (int i = 0; i < limit; i++) {
            LiepinJobPosting job = jobs.get(i);
            text.append(i + 1).append(". ").append(job.getJobName()).append("｜").append(job.getCompanyName())
                    .append("｜").append(job.getSalary()).append("｜匹配 ").append(job.getMatchScore()).append(" 分\n")
                    .append("   ").append(job.getMatchReason()).append("\n");
        }
        return text.append("回复例如“确认投递第1个岗位”，系统才会打开页面并发送沟通语。每次只提交一个岗位。").toString();
    }

    private void update(LiepinJobTask task, LiepinTaskStatus status, String message) {
        task.setStatus(status.name());
        task.setMessage(message);
        taskMapper.updateStatus(task.getId(), task.getUserId(), status.name(), message);
    }

    private void notifyUser(String userId, String message) {
        Consumer<ProcessResult> callback = onCompleted;
        if (callback == null) {
            log.warn("[LiepinJob] 完成回调尚未注册: userId={}, message={}", userId, message);
            return;
        }
        callback.accept(ProcessResult.text(message, userId));
    }

    private Integer normalizeSalary(Integer salary) {
        return salary == null || salary <= 0 ? null : salary;
    }

    private String friendlyError(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? "未知错误，请查看日志" : e.getMessage();
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private <T> T runOnLiepinWorker(Callable<T> operation) {
        if ("liepin-job-worker".equals(Thread.currentThread().getName())) {
            try {
                return operation.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("猎聘浏览器操作失败：" + friendlyError(e), e);
            }
        }
        Future<T> future = executor.submit(operation);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("猎聘浏览器操作被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("猎聘浏览器操作失败：" + cause.getMessage(), cause);
        }
    }
}