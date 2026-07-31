package ykd.ykd.job.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ykd.ykd.job.mapper.LiepinApplicationRecordMapper;
import ykd.ykd.job.model.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 投递记录管理服务。
 *
 * <p>管理投递记录的完整生命周期：去重预留、状态流转（联系中→发送简历→完成）、
 * 今日统计和摘要生成。</p>
 *
 * <h3>去重机制</h3>
 * 每个职位通过 {@link #jobKey(LiepinJobPosting)} 生成唯一键：
 * 优先用猎聘外部 ID，否则对 URL+职位+公司做 SHA-256 哈希。
 * 插入时通过 {@code INSERT OR IGNORE} + {@code (user_id, external_job_key)} 约束保证幂等。
 */
@Service
public class LiepinApplicationService {
    private final LiepinApplicationRecordMapper mapper;

    public LiepinApplicationService(LiepinApplicationRecordMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 为指定职位置预留一条投递记录（去重）。
     * 如果该职位已存在记录则直接返回已有记录。
     *
     * @param userId   微信用户 ID
     * @param campaign 当前投递计划
     * @param task     搜索任务
     * @param posting  目标职位
     * @param resume   用户简历
     * @return 已有或新创建的投递记录
     */
    public LiepinApplicationRecord reserve(String userId,
                                            LiepinJobCampaign campaign,
                                            LiepinJobTask task,
                                            LiepinJobPosting posting,
                                            LiepinResume resume) {
        String jobKey = jobKey(posting);
        LiepinApplicationRecord existing = mapper.findByUserAndJobKey(userId, jobKey);
        if (existing != null) return existing;

        LiepinApplicationRecord record = new LiepinApplicationRecord();
        record.setUserId(userId);
        record.setCampaignId(campaign.getId());
        record.setTaskId(task.getId());
        record.setPostingId(posting.getId());
        record.setExternalJobKey(jobKey);
        record.setJobName(posting.getJobName());
        record.setCompanyName(posting.getCompanyName());
        record.setResumeId(resume.getId());
        record.setDeliveryMode(campaign.getDeliveryMode());
        record.setStatus(LiepinApplicationStatus.PENDING.name());
        mapper.insertIfAbsent(record);
        return mapper.findByUserAndJobKey(userId, jobKey);
    }

    /**
     * 判断记录是否已处理过（终态或进行中）。
     * 已成功的、已联系中的、正在发送简历的视为已处理。
     */
    public boolean wasAlreadyProcessed(LiepinApplicationRecord record) {
        if (record == null || record.getStatus() == null) return false;
        LiepinApplicationStatus status = LiepinApplicationStatus.valueOf(record.getStatus());
        return status.isSuccessful()
                || status == LiepinApplicationStatus.CONTACTING
                || status == LiepinApplicationStatus.SENDING_RESUME;
    }

    /** 标记记录为"联系中"状态，递增尝试次数。 */
    public void markContacting(LiepinApplicationRecord record) {
        mapper.markContacting(record.getId());
        record.setStatus(LiepinApplicationStatus.CONTACTING.name());
    }

    /**
     * 完成投递，更新最终状态。
     * 成功时同时更新 {@code resume_sent_at} 时间戳。
     */
    public void complete(LiepinApplicationRecord record, LiepinApplicationResult result) {
        boolean successful = result.status().isSuccessful();
        mapper.updateResult(record.getId(), result.status().name(),
                successful ? null : result.message(), true, successful);
        record.setStatus(result.status().name());
    }

    /** 统计用户今日成功投递次数（按本地日期）。 */
    public int countTodaySuccessful(String userId) {
        return mapper.countTodaySuccessful(userId);
    }

    /**
     * 生成今日投递摘要：今日成功数 + 最近 10 条记录。
     */
    public String latestSummary(String userId) {
        int today = countTodaySuccessful(userId);
        List<LiepinApplicationRecord> latest = mapper.findLatestByUser(userId, 10);
        StringBuilder message = new StringBuilder("今日猎聘自动投递成功 ")
                .append(today).append(" 个。\n最近记录：\n");
        if (latest.isEmpty()) return message.append("暂无投递记录").toString();
        for (LiepinApplicationRecord record : latest) {
            message.append("- ").append(record.getJobName())
                    .append("｜").append(record.getCompanyName())
                    .append("｜").append(record.getStatus())
                    .append("｜").append(record.getDeliveryMode()).append('\n');
        }
        return message.toString().stripTrailing();
    }

    /**
     * 生成职位唯一标识键，用于去重。
     * 优先使用猎聘外部 ID（{@code "id:<externalJobId>"}），
     * 没有时对 URL+职位名+公司名做 SHA-256（{@code "hash:<hex>"}）。
     */
    static String jobKey(LiepinJobPosting posting) {
        if (StringUtils.hasText(posting.getExternalJobId())) {
            return "id:" + posting.getExternalJobId().strip();
        }
        String source = String.join("|",
                value(posting.getJobUrl()), value(posting.getJobName()), value(posting.getCompanyName()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return "hash:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前JDK不支持SHA-256", e);
        }
    }

    private static String value(String value) {
        return value == null ? "" : value.strip();
    }
}
