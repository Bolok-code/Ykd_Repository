package ykd.ykd.job.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ykd.ykd.job.config.LiepinProperties;
import ykd.ykd.job.mapper.LiepinJobCampaignMapper;
import ykd.ykd.job.model.*;

@Service
public class LiepinCampaignService {
    private final LiepinJobCampaignMapper mapper;
    private final LiepinResumeService resumeService;
    private final LiepinApplicationService applicationService;
    private final LiepinProperties properties;

    public LiepinCampaignService(LiepinJobCampaignMapper mapper,
                                 LiepinResumeService resumeService,
                                 LiepinApplicationService applicationService,
                                 LiepinProperties properties) {
        this.mapper = mapper;
        this.resumeService = resumeService;
        this.applicationService = applicationService;
        this.properties = properties;
    }

    public String create(String userId,
                         String keyword,
                         String city,
                         Integer minSalaryK,
                         Integer maxSalaryK,
                         Integer minMatchScore,
                         Integer dailyLimit,
                         Integer intervalMinutes,
                         boolean excludeOutsourcing,
                         String excludedKeywords,
                         String deliveryMode) {
        LiepinResume resume = resumeService.find(userId);
        if (resume == null) {
            return "还没有求职简历，请先发送简历文件并设置为猎聘求职简历。";
        }
        ResumeDeliveryMode mode = ResumeDeliveryMode.parse(deliveryMode);
        if (mode == ResumeDeliveryMode.ATTACHMENT && !resumeService.hasUsableAttachment(resume)) {
            return "附件模式需要PDF、DOC或DOCX原文件。请重新发送简历文件并设为求职简历。";
        }
        int score = range(defaultValue(minMatchScore,
                properties.getAutoApply().getDefaultMinMatchScore()), 0, 100, "最低匹配分数");
        int limit = range(defaultValue(dailyLimit,
                properties.getAutoApply().getDefaultDailyLimit()), 1,
                properties.getAutoApply().getMaxDailyLimit(), "每日投递上限");
        int interval = range(defaultValue(intervalMinutes,
                properties.getAutoApply().getDefaultIntervalMinutes()), 5, 1440, "执行间隔分钟数");
        if (!StringUtils.hasText(keyword) || !StringUtils.hasText(city)) {
            throw new IllegalArgumentException("岗位关键词和城市不能为空");
        }

        LiepinJobCampaign campaign = new LiepinJobCampaign();
        campaign.setUserId(userId);
        campaign.setName(city.strip() + "-" + keyword.strip() + "自动投递");
        campaign.setResumeId(resume.getId());
        campaign.setDeliveryMode(mode.name());
        campaign.setKeyword(keyword.strip());
        campaign.setCity(city.strip());
        campaign.setMinSalaryK(normalizeSalary(minSalaryK));
        campaign.setMaxSalaryK(normalizeSalary(maxSalaryK));
        campaign.setMinMatchScore(score);
        campaign.setExcludeOutsourcing(excludeOutsourcing);
        campaign.setExcludedKeywords(excludedKeywords == null ? "" : excludedKeywords.strip());
        campaign.setDailyLimit(limit);
        campaign.setIntervalMinutes(interval);
        campaign.setStatus(LiepinCampaignStatus.CREATED.name());
        campaign.setMessage("计划已创建，等待用户启动");
        mapper.insert(campaign);
        return "已创建猎聘自动投递计划 #" + campaign.getId()
                + "：" + campaign.getName()
                + "，简历方式=" + mode
                + "，最低匹配=" + score
                + "分，每日最多=" + limit
                + "个。发送“启动猎聘自动投递”开始执行。";
    }

    public String startLatest(String userId) {
        if (!properties.isEnabled() || !properties.getAutoApply().isEnabled()) {
            return "猎聘自动投递尚未启用，请配置 LIEPIN_ENABLED=true 和 LIEPIN_AUTO_APPLY_ENABLED=true 后重启。";
        }
        LiepinJobCampaign campaign = requireLatest(userId);
        LiepinResume resume = resumeService.find(userId);
        if (resume == null) return "当前求职简历不存在，请重新保存简历。";
        ResumeDeliveryMode mode = ResumeDeliveryMode.parse(campaign.getDeliveryMode());
        if (mode == ResumeDeliveryMode.ATTACHMENT && !resumeService.hasUsableAttachment(resume)) {
            return "附件简历文件不存在，请重新发送并保存简历。";
        }
        mapper.start(campaign.getId(), userId, "计划已启动，等待后台执行");
        return "猎聘自动投递计划 #" + campaign.getId() + " 已启动。登录有效时自动执行；登录失效时会暂停并通知你。";
    }

    public String pauseLatest(String userId) {
        LiepinJobCampaign campaign = requireLatest(userId);
        mapper.updateStatus(campaign.getId(), userId, LiepinCampaignStatus.PAUSED.name(), "用户暂停计划");
        return "已暂停猎聘自动投递计划 #" + campaign.getId() + "。";
    }

    public String stopLatest(String userId) {
        LiepinJobCampaign campaign = requireLatest(userId);
        mapper.updateStatus(campaign.getId(), userId, LiepinCampaignStatus.STOPPED.name(), "用户停止计划");
        return "已停止猎聘自动投递计划 #" + campaign.getId() + "。";
    }

    public String latestStatus(String userId) {
        LiepinJobCampaign campaign = mapper.findLatestByUser(userId);
        if (campaign == null) return "暂无猎聘自动投递计划。";
        return "猎聘自动投递计划 #" + campaign.getId()
                + "｜" + campaign.getName()
                + "｜状态=" + campaign.getStatus()
                + "｜方式=" + campaign.getDeliveryMode()
                + "｜今日成功=" + applicationService.countTodaySuccessful(userId)
                + "/" + campaign.getDailyLimit()
                + "｜" + campaign.getMessage();
    }

    public String latestApplications(String userId) {
        return applicationService.latestSummary(userId);
    }

    private LiepinJobCampaign requireLatest(String userId) {
        LiepinJobCampaign campaign = mapper.findLatestByUser(userId);
        if (campaign == null) throw new IllegalStateException("暂无猎聘自动投递计划，请先创建计划");
        return campaign;
    }

    private int defaultValue(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }

    private int range(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + "必须在" + min + "到" + max + "之间");
        }
        return value;
    }

    private Integer normalizeSalary(Integer salary) {
        return salary == null || salary <= 0 ? null : salary;
    }
}