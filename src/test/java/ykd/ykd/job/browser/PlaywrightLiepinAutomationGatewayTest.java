package ykd.ykd.job.browser;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import ykd.ykd.job.config.LiepinProperties;
import ykd.ykd.job.model.LiepinJobPosting;

import static org.junit.jupiter.api.Assertions.*;

class PlaywrightLiepinAutomationGatewayTest {
    @Test
    void mapsSupportedCityNames() {
        assertEquals("070020", PlaywrightLiepinAutomationGateway.cityCode("杭州市"));
        assertEquals("020", PlaywrightLiepinAutomationGateway.cityCode("上海"));
        assertEquals("030", PlaywrightLiepinAutomationGateway.cityCode("天津市"));
        assertEquals("040", PlaywrightLiepinAutomationGateway.cityCode("重庆"));
        assertEquals("210040", PlaywrightLiepinAutomationGateway.cityCode("大连市"));
        assertEquals("060110", PlaywrightLiepinAutomationGateway.cityCode("060110"));
    }

    @Test
    void rejectsUnsupportedCity() {
        assertThrows(IllegalArgumentException.class,
                () -> PlaywrightLiepinAutomationGateway.cityCode("不存在的城市"));
    }

    @Test
    void checksSalaryIntersection() {
        assertTrue(PlaywrightLiepinAutomationGateway.salaryMatches("15-25K·13薪", 20, 30));
        assertFalse(PlaywrightLiepinAutomationGateway.salaryMatches("8-12K", 15, 30));
        assertTrue(PlaywrightLiepinAutomationGateway.salaryMatches("面议", 15, 30));
    }

    @Test
    void identifiesOutsourcingJobs() {
        LiepinJobPosting posting = new LiepinJobPosting();
        posting.setJobName("Java 开发");
        posting.setCompanyName("某某人力资源有限公司");
        posting.setDescription("驻场开发");
        assertTrue(PlaywrightLiepinAutomationGateway.isOutsourcing(posting));
    }

    @Test
    void parsesLiepinSearchApiResponse() throws Exception {
        PlaywrightLiepinAutomationGateway gateway = new PlaywrightLiepinAutomationGateway(
                new LiepinProperties(), new ObjectMapper());
        String body = """
                {"data":{"data":{"jobCardList":[{
                  "job":{"jobId":"job-1","title":"Java 后端","link":"/job/1.shtml",
                         "salary":"15-25k","dq":"杭州","requireEduLevel":"本科",
                         "requireWorkYears":"3-5年","refreshTime":"刚刚"},
                  "comp":{"compName":"示例科技","compIndustry":"互联网","compScale":"100-499人"},
                  "recruiter":{"recruiterName":"张女士","recruiterTitle":"招聘经理","imId":"im-1"}
                }]}}}
                """;

        var jobs = gateway.parseSearchResponse(body, "杭州");

        assertEquals(1, jobs.size());
        assertEquals("job-1", jobs.getFirst().getExternalJobId());
        assertEquals("Java 后端", jobs.getFirst().getJobName());
        assertEquals("示例科技", jobs.getFirst().getCompanyName());
        assertEquals("张女士", jobs.getFirst().getRecruiterName());
        assertEquals("https://www.liepin.com/job/1.shtml", jobs.getFirst().getJobUrl());
    }
}