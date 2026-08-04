package ykd.ykd.job;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import ykd.ykd.job.mapper.*;
import ykd.ykd.job.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.datasource.hikari.maximum-pool-size=1"
})
@Sql(scripts = "classpath:db/sqlite/schema.sql")
class LiepinJobPersistenceTest {

    /** 每次 JVM 运行使用唯一 DB 文件，避免并发跑测试时共用 conversation-test.db 冲突 */
    private static final Path TEST_DB = Paths.get("target", "liepin-test-" + UUID.randomUUID() + ".db");

    @DynamicPropertySource
    static void dbProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + TEST_DB);
    }

    @AfterAll
    static void cleanUpDbFile() {
        try {
            Files.deleteIfExists(TEST_DB);
        } catch (IOException ignored) {
        }
    }

    @Autowired private LiepinResumeMapper resumeMapper;
    @Autowired private LiepinResumeAssetMapper resumeAssetMapper;
    @Autowired private LiepinJobTaskMapper taskMapper;
    @Autowired private LiepinJobPostingMapper postingMapper;
    @Autowired private LiepinJobCampaignMapper campaignMapper;
    @Autowired private LiepinApplicationRecordMapper applicationRecordMapper;

    @Test
    void storesResumeTaskAndCandidate() {
        String userId = "liepin-test-" + UUID.randomUUID();
        LiepinResume resume = saveResume(userId);
        assertThat(resume.getContent()).contains("Spring Boot");

        LiepinJobTask task = saveTask(userId);
        LiepinJobPosting posting = savePosting(task.getId(), "job-001");

        assertThat(postingMapper.findByTaskId(task.getId()))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getExternalJobId()).isEqualTo("job-001");
                    assertThat(saved.getRecruiterName()).isEqualTo("Ms Zhang");
                    assertThat(saved.getMatchScore()).isEqualTo(88);
                });
    }

    @Test
    void storesResumeAssetCampaignAndDeduplicatedApplication() {
        String userId = "liepin-auto-" + UUID.randomUUID();
        LiepinResume resume = saveResume(userId);

        LiepinResumeAsset asset = new LiepinResumeAsset();
        asset.setUserId(userId);
        asset.setFileName("resume.pdf");
        asset.setFilePath("D:/tmp/resume.pdf");
        asset.setFileType("pdf");
        asset.setFileSize(123L);
        asset.setFileHash("hash-" + UUID.randomUUID());
        resumeAssetMapper.upsert(asset);
        assertThat(resumeAssetMapper.findByUserId(userId).getFileType()).isEqualTo("pdf");

        LiepinJobCampaign campaign = new LiepinJobCampaign();
        campaign.setUserId(userId);
        campaign.setName("杭州-Java自动投递");
        campaign.setResumeId(resume.getId());
        campaign.setDeliveryMode(ResumeDeliveryMode.ATTACHMENT.name());
        campaign.setKeyword("Java");
        campaign.setCity("杭州");
        campaign.setMinMatchScore(85);
        campaign.setExcludeOutsourcing(true);
        campaign.setExcludedKeywords("外包");
        campaign.setDailyLimit(3);
        campaign.setIntervalMinutes(30);
        campaign.setStatus(LiepinCampaignStatus.CREATED.name());
        campaign.setMessage("test");
        campaignMapper.insert(campaign);
        assertThat(campaign.getId()).isNotNull();

        LiepinJobTask task = saveTask(userId);
        LiepinJobPosting posting = savePosting(task.getId(), "job-auto-001");
        LiepinApplicationRecord record = new LiepinApplicationRecord();
        record.setUserId(userId);
        record.setCampaignId(campaign.getId());
        record.setTaskId(task.getId());
        record.setPostingId(posting.getId());
        record.setExternalJobKey("id:job-auto-001");
        record.setJobName(posting.getJobName());
        record.setCompanyName(posting.getCompanyName());
        record.setResumeId(resume.getId());
        record.setDeliveryMode(ResumeDeliveryMode.ATTACHMENT.name());
        record.setStatus(LiepinApplicationStatus.PENDING.name());

        assertThat(applicationRecordMapper.insertIfAbsent(record)).isEqualTo(1);
        assertThat(applicationRecordMapper.insertIfAbsent(record)).isZero();
        LiepinApplicationRecord saved = applicationRecordMapper.findByUserAndJobKey(userId, "id:job-auto-001");
        applicationRecordMapper.markContacting(saved.getId());
        applicationRecordMapper.updateResult(saved.getId(), LiepinApplicationStatus.SUCCESS.name(), null, true, true);

        LiepinApplicationRecord completed = applicationRecordMapper.findByUserAndJobKey(userId, "id:job-auto-001");
        assertThat(completed.getAttemptCount()).isEqualTo(1);
        assertThat(completed.getStatus()).isEqualTo(LiepinApplicationStatus.SUCCESS.name());
        assertThat(applicationRecordMapper.countTodaySuccessful(userId)).isEqualTo(1);
    }

    private LiepinResume saveResume(String userId) {
        LiepinResume resume = new LiepinResume();
        resume.setUserId(userId);
        resume.setFileName("resume.pdf");
        resume.setContent("Java backend developer with Spring Boot experience");
        resumeMapper.upsert(resume);
        return resumeMapper.findByUserId(userId);
    }

    private LiepinJobTask saveTask(String userId) {
        LiepinJobTask task = new LiepinJobTask();
        task.setUserId(userId);
        task.setKeyword("Java backend");
        task.setCity("Hangzhou");
        task.setMinSalaryK(15);
        task.setMaxSalaryK(30);
        task.setExcludeOutsourcing(true);
        task.setStatus(LiepinTaskStatus.CREATED.name());
        task.setMessage("test");
        taskMapper.insert(task);
        assertThat(task.getId()).isNotNull();
        return task;
    }

    private LiepinJobPosting savePosting(long taskId, String externalId) {
        LiepinJobPosting posting = new LiepinJobPosting();
        posting.setTaskId(taskId);
        posting.setExternalJobId(externalId);
        posting.setJobName("Java Developer");
        posting.setCompanyName("Example Company");
        posting.setCompanyIndustry("Internet");
        posting.setRecruiterName("Ms Zhang");
        posting.setCity("Hangzhou");
        posting.setSalary("18-28K");
        posting.setDescription("Spring Boot microservices");
        posting.setJobUrl("https://www.liepin.com/job/test.shtml");
        posting.setMatchScore(88);
        posting.setMatchReason("Skills match");
        posting.setGreeting("Hello, may we discuss this position?");
        postingMapper.insert(posting);
        assertThat(posting.getId()).isNotNull();
        return posting;
    }
}