package ykd.ykd.job.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ykd.ykd.job.service.LiepinCampaignService;
import ykd.ykd.job.service.LiepinResumeService;
import ykd.ykd.job.task.LiepinJobTaskManager;
import ykd.ykd.llm.tools.DocumentTools;
import ykd.ykd.processor.UserContext;

/**
 * 猎聘求职工具的 Spring AI {@code @Tool} 集合。
 *
 * <p>14 个工具方法覆盖了完整的猎聘求职流程：
 * 登录 → 保存简历 → 搜索岗位 → AI 匹配 → 候选确认 → 创建/管理自动投递计划。
 *
 * <p>这些工具仅在 {@code liepin-auto-apply} Skill 激活时暴露给 LLM，
 * 普通聊天场景下 LLM 看不到这些方法（不会被误调）。
 *
 * <h3>工具分组</h3>
 * <ul>
 *   <li><b>登录</b> — openLiepinLogin、checkLiepinLoginStatus</li>
 *   <li><b>简历</b> — saveCurrentDocumentAsLiepinResume</li>
 *   <li><b>手动搜索</b> — searchLiepinJobs、listLiepinJobCandidates、
 *       confirmLiepinJobApplication、getLiepinJobTaskStatus、cancelLiepinJobTask</li>
 *   <li><b>自动投递</b> — createLiepinAutoApplyCampaign、startLiepinAutoApplyCampaign、
 *       pauseLiepinAutoApplyCampaign、stopLiepinAutoApplyCampaign、
 *       getLiepinAutoApplyCampaignStatus、listLiepinAutoApplications</li>
 * </ul>
 *
 * <h3>安全约束（在 SKILL.md 中定义）</h3>
 * 以下规则通过 Skill 执行说明注入 LLM，不在代码层强制：
 * <ul>
 *   <li>未经用户明确确认，不得启动自动投递</li>
 *   <li>"好的""知道了"等模糊表达不得视为启动授权</li>
 *   <li>不得绕过验证码、登录验证和平台风控</li>
 *   <li>工具返回失败时应如实告知，不得虚构成功</li>
 * </ul>
 */
@Component
public class LiepinJobTools {
    private final UserContext userContext;
    private final LiepinResumeService resumeService;
    private final LiepinCampaignService campaignService;
    private final LiepinJobTaskManager taskManager;

    public LiepinJobTools(UserContext userContext,
                          LiepinResumeService resumeService,
                          LiepinCampaignService campaignService,
                          LiepinJobTaskManager taskManager) {
        this.userContext = userContext;
        this.resumeService = resumeService;
        this.campaignService = campaignService;
        this.taskManager = taskManager;
    }

    // ── 登录 ──────────────────────────────────────────────────

    @Tool(description = "打开猎聘登录页面。仅当用户明确要求登录猎聘、打开猎聘登录页或登录状态失效时调用。")
    public String openLiepinLogin() {
        return taskManager.openLogin();
    }

    @Tool(description = "检查猎聘浏览器是否已登录。")
    public String checkLiepinLoginStatus() {
        return taskManager.loginStatus();
    }

    // ── 简历 ──────────────────────────────────────────────────

    @Tool(description = "把用户刚才发送并解析的PDF或Word文件保存为猎聘求职简历，同时保留原文件用于附件投递。用户明确说这是简历或要求设为求职简历时调用。")
    public String saveCurrentDocumentAsLiepinResume() {
        String userId = requireUserId();
        String content = DocumentTools.getCachedContent(userId);
        String fileName = DocumentTools.getCachedFileName(userId);
        byte[] originalBytes = DocumentTools.getCachedBytes(userId);
        if (content == null || content.isBlank()) {
            return "没有找到刚才解析的文件，请先发送简历文件。";
        }
        resumeService.save(userId, fileName, content, originalBytes);
        boolean attachmentAvailable = resumeService.find(userId).getFilePath() != null;
        return "已把\"" + fileName + "\"保存为当前猎聘求职简历。"
                + (attachmentAvailable ? "已保留原文件，可选择在线简历或附件简历投递。"
                : "已保存文本内容，但附件投递需要重新发送PDF、DOC或DOCX文件。");
    }

    // ── 手动搜索 ──────────────────────────────────────────────

    @Tool(description = "创建猎聘岗位搜索与简历匹配任务。任务后台执行，完成后主动推送候选岗位；该工具不会自动发送简历。")
    public String searchLiepinJobs(
            @ToolParam(description = "岗位关键词，例如Java后端、产品经理") String keyword,
            @ToolParam(description = "目标城市，例如杭州、上海，也可以直接传猎聘城市代码") String city,
            @ToolParam(description = "最低月薪，单位K；不限时填0") Integer minSalaryK,
            @ToolParam(description = "最高月薪，单位K；不限时填0") Integer maxSalaryK,
            @ToolParam(description = "是否排除外包、人力资源、劳务派遣和驻场岗位") boolean excludeOutsourcing) {
        return taskManager.startSearch(requireUserId(), keyword, city, minSalaryK, maxSalaryK, excludeOutsourcing);
    }

    @Tool(description = "查看当前用户最近一次猎聘求职任务的候选岗位。")
    public String listLiepinJobCandidates() {
        return taskManager.listLatestCandidates(requireUserId());
    }

    @Tool(description = "用户明确确认某个猎聘候选岗位后，打开该单个岗位并点击聊一聊。未经用户明确确认不得调用。")
    public String confirmLiepinJobApplication(
            @ToolParam(description = "候选列表中的序号，从1开始") int candidateIndex) {
        return taskManager.confirmApplication(requireUserId(), candidateIndex);
    }

    @Tool(description = "查看当前用户最近一次猎聘求职任务状态。")
    public String getLiepinJobTaskStatus() {
        return taskManager.latestStatus(requireUserId());
    }

    @Tool(description = "取消当前用户最近一次猎聘求职任务。")
    public String cancelLiepinJobTask() {
        return taskManager.cancelLatest(requireUserId());
    }

    // ── 自动投递 ──────────────────────────────────────────────

    @Tool(description = "创建猎聘全自动投递计划，但创建后不会立即运行，必须再由用户明确要求启动。计划会按匹配分数、每日限额、去重和间隔执行。")
    public String createLiepinAutoApplyCampaign(
            @ToolParam(description = "岗位关键词，例如Java后端") String keyword,
            @ToolParam(description = "目标城市或猎聘城市代码") String city,
            @ToolParam(description = "最低月薪K，不限填0") Integer minSalaryK,
            @ToolParam(description = "最高月薪K，不限填0") Integer maxSalaryK,
            @ToolParam(description = "最低简历匹配分数，建议85") Integer minMatchScore,
            @ToolParam(description = "每日最多成功发送简历数量") Integer dailyLimit,
            @ToolParam(description = "两轮自动搜索的间隔分钟数，最少5分钟") Integer intervalMinutes,
            @ToolParam(description = "是否排除外包岗位") boolean excludeOutsourcing,
            @ToolParam(description = "额外排除关键词，用逗号分隔；没有填空字符串") String excludedKeywords,
            @ToolParam(description = "简历发送方式：在线简历、附件简历或自动") String deliveryMode) {
        return campaignService.create(requireUserId(), keyword, city, minSalaryK, maxSalaryK,
                minMatchScore, dailyLimit, intervalMinutes, excludeOutsourcing,
                excludedKeywords, deliveryMode);
    }

    @Tool(description = "启动当前用户最近创建的猎聘全自动投递计划。只有用户明确说启动、开始自动投递时才调用。")
    public String startLiepinAutoApplyCampaign() {
        String result = campaignService.startLatest(requireUserId());
        taskManager.scanAutomaticCampaigns();
        return result;
    }

    @Tool(description = "暂停当前用户最近的猎聘全自动投递计划。")
    public String pauseLiepinAutoApplyCampaign() {
        return campaignService.pauseLatest(requireUserId());
    }

    @Tool(description = "永久停止当前用户最近的猎聘全自动投递计划。")
    public String stopLiepinAutoApplyCampaign() {
        return campaignService.stopLatest(requireUserId());
    }

    @Tool(description = "查看当前用户最近的猎聘全自动投递计划状态、今日成功数和限额。")
    public String getLiepinAutoApplyCampaignStatus() {
        return campaignService.latestStatus(requireUserId());
    }

    @Tool(description = "查看当前用户最近的猎聘自动投递记录。")
    public String listLiepinAutoApplications() {
        return campaignService.latestApplications(requireUserId());
    }

    // ── 内部辅助 ──────────────────────────────────────────────

    /** 从 ThreadLocal 获取当前微信用户 ID。 */
    private String requireUserId() {
        String userId = userContext.getCurrentUserId();
        if (userId == null || userId.isBlank()) throw new IllegalStateException("无法识别当前微信用户");
        return userId;
    }
}
