package ykd.ykd.job.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ykd.ykd.job.service.LiepinResumeService;
import ykd.ykd.job.task.LiepinJobTaskManager;
import ykd.ykd.llm.tools.DocumentTools;
import ykd.ykd.processor.UserContext;

@Component
public class LiepinJobTools {
    private final UserContext userContext;
    private final LiepinResumeService resumeService;
    private final LiepinJobTaskManager taskManager;

    public LiepinJobTools(UserContext userContext, LiepinResumeService resumeService, LiepinJobTaskManager taskManager) {
        this.userContext = userContext;
        this.resumeService = resumeService;
        this.taskManager = taskManager;
    }

    @Tool(description = "打开猎聘登录页面。仅当用户明确要求登录猎聘、打开猎聘登录页或登录状态失效时调用。")
    public String openLiepinLogin() {
        return taskManager.openLogin();
    }

    @Tool(description = "检查猎聘浏览器是否已登录。")
    public String checkLiepinLoginStatus() {
        return taskManager.loginStatus();
    }

    @Tool(description = "把用户刚才发送并解析的 PDF、Word 或文本文件保存为猎聘求职简历。用户明确说这是简历或要求设为求职简历时调用。")
    public String saveCurrentDocumentAsLiepinResume() {
        String userId = requireUserId();
        String content = DocumentTools.getCachedContent(userId);
        String fileName = DocumentTools.getCachedFileName(userId);
        if (content == null || content.isBlank()) {
            return "没有找到刚才解析的文件，请先发送简历文件。";
        }
        resumeService.save(userId, fileName, content);
        return "已把“" + fileName + "”保存为当前猎聘求职简历。后续岗位匹配会使用这份内容；真正投递时使用 猎聘 账号中的在线简历。";
    }

    @Tool(description = "创建 猎聘岗位搜索与简历匹配任务。任务后台执行，完成后主动推送候选岗位；不得直接批量投递。")
    public String searchLiepinJobs(
            @ToolParam(description = "岗位关键词，例如 Java 后端、产品经理") String keyword,
            @ToolParam(description = "目标城市，例如杭州、上海") String city,
            @ToolParam(description = "最低月薪，单位 K；不限时填 0") Integer minSalaryK,
            @ToolParam(description = "最高月薪，单位 K；不限时填 0") Integer maxSalaryK,
            @ToolParam(description = "是否排除外包、人力资源、劳务派遣和驻场岗位") boolean excludeOutsourcing) {
        return taskManager.startSearch(requireUserId(), keyword, city, minSalaryK, maxSalaryK, excludeOutsourcing);
    }

    @Tool(description = "查看当前用户最近一次猎聘求职任务的候选岗位。")
    public String listLiepinJobCandidates() {
        return taskManager.listLatestCandidates(requireUserId());
    }

    @Tool(description = "用户明确确认某个猎聘候选岗位后，打开该单个岗位并点击“聊一聊”。未经用户明确确认不得调用。")
    public String confirmLiepinJobApplication(
            @ToolParam(description = "候选列表中的序号，从 1 开始") int candidateIndex) {
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

    private String requireUserId() {
        String userId = userContext.getCurrentUserId();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("无法识别当前微信用户");
        }
        return userId;
    }
}