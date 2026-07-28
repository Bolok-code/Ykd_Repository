package ykd.ykd.job;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import ykd.ykd.job.mapper.LiepinJobPostingMapper;
import ykd.ykd.job.mapper.LiepinJobTaskMapper;
import ykd.ykd.job.mapper.LiepinResumeMapper;
import ykd.ykd.job.model.LiepinJobPosting;
import ykd.ykd.job.model.LiepinJobTask;
import ykd.ykd.job.model.LiepinResume;
import ykd.ykd.job.model.LiepinTaskStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@MybatisTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:./work/sqlite/conversation-test.db",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.datasource.hikari.maximum-pool-size=1"
})
@Sql(scripts = "classpath:db/sqlite/schema.sql")
class LiepinJobPersistenceTest {

    @Autowired
    private LiepinResumeMapper resumeMapper;

    @Autowired
    private LiepinJobTaskMapper taskMapper;

    @Autowired
    private LiepinJobPostingMapper postingMapper;

    @Test
    void storesResumeTaskAndCandidate() {
        String userId = "liepin-test-" + UUID.randomUUID();

        LiepinResume resume = new LiepinResume();
        resume.setUserId(userId);
        resume.setFileName("resume.pdf");
        resume.setContent("Java backend developer with Spring Boot experience");
        resumeMapper.upsert(resume);

        assertThat(resumeMapper.findByUserId(userId).getContent())
                .contains("Spring Boot");

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

        LiepinJobPosting posting = new LiepinJobPosting();
        posting.setTaskId(task.getId());
        posting.setExternalJobId("job-001");
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
        assertThat(postingMapper.findByTaskId(task.getId()))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getExternalJobId()).isEqualTo("job-001");
                    assertThat(saved.getRecruiterName()).isEqualTo("Ms Zhang");
                    assertThat(saved.getMatchScore()).isEqualTo(88);
                });
    }
}