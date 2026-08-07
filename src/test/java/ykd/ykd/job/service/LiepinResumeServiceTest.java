package ykd.ykd.job.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 简历判定启发式回归测试。
 *
 * <p>防止"猎聘模块详解"这类含大量"简历"关键词的技术文档被误判为求职简历，
 * 从而覆盖真实简历并触发附件误删。</p>
 */
class LiepinResumeServiceTest {

    private final LiepinResumeService service = new LiepinResumeService(null, null, null);

    @Test
    void shouldDetectRealResume() {
        String content = """
                姓名：张云峰
                电话：13800138000
                求职意向：Java 后端开发
                教育经历：2019-2023 某大学 计算机
                工作经历：2023-2026 某公司 Java 开发
                项目经历：电商系统
                """;
        assertThat(service.looksLikeResume("JAVA工程师简历.docx", content)).isTrue();
    }

    @Test
    void shouldDetectResumeByNameWithSectionKeywords() {
        String content = """
                求职意向：产品经理
                工作经历：负责需求分析
                """;
        assertThat(service.looksLikeResume("产品经理-简历.pdf", content)).isTrue();
    }

    @Test
    void shouldRejectTechnicalDocAboutResumeModule() {
        String content = """
                # YKD Bot — 猎聘（Liepin）自动求职模块详解

                ## 目录

                调用链路：文件解析 → 简历匹配 → 自动投递
                数据库表：liepin_resume、liepin_application_record
                ```java
                resumeService.looksLikeResume(fileName, content);
                ```
                """;
        assertThat(service.looksLikeResume("YKD-Bot-猎聘模块详解.md", content)).isFalse();
    }

    @Test
    void shouldRejectDocContainingResumeWordsWithoutIdentity() {
        String content = "本文档介绍教育经历模块、工作经历模块、项目经历模块、专业技能模块的实现细节。";
        assertThat(service.looksLikeResume("项目说明.md", content)).isFalse();
    }

    @Test
    void shouldAcceptExplicitResumeFileName() {
        assertThat(service.looksLikeResume("resume.pdf", "hello")).isTrue();
    }
}
