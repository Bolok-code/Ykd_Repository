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

@Service
public class LiepinApplicationService {
    private final LiepinApplicationRecordMapper mapper;

    public LiepinApplicationService(LiepinApplicationRecordMapper mapper) {
        this.mapper = mapper;
    }

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

    public boolean wasAlreadyProcessed(LiepinApplicationRecord record) {
        if (record == null || record.getStatus() == null) return false;
        LiepinApplicationStatus status = LiepinApplicationStatus.valueOf(record.getStatus());
        return status.isSuccessful()
                || status == LiepinApplicationStatus.CONTACTING
                || status == LiepinApplicationStatus.SENDING_RESUME;
    }

    public void markContacting(LiepinApplicationRecord record) {
        mapper.markContacting(record.getId());
        record.setStatus(LiepinApplicationStatus.CONTACTING.name());
    }

    public void complete(LiepinApplicationRecord record, LiepinApplicationResult result) {
        boolean successful = result.status().isSuccessful();
        mapper.updateResult(record.getId(), result.status().name(),
                successful ? null : result.message(), true, successful);
        record.setStatus(result.status().name());
    }

    public int countTodaySuccessful(String userId) {
        return mapper.countTodaySuccessful(userId);
    }

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