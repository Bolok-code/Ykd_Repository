package ykd.ykd.job.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ykd.ykd.job.mapper.LiepinResumeMapper;
import ykd.ykd.job.model.LiepinResume;

@Service
public class LiepinResumeService {
    private final LiepinResumeMapper resumeMapper;

    public LiepinResumeService(LiepinResumeMapper resumeMapper) {
        this.resumeMapper = resumeMapper;
    }

    public void save(String userId, String fileName, String content) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(content)) {
            throw new IllegalArgumentException("用户标识和简历内容不能为空");
        }
        LiepinResume resume = new LiepinResume();
        resume.setUserId(userId);
        resume.setFileName(fileName);
        resume.setContent(content);
        resumeMapper.upsert(resume);
    }

    public LiepinResume find(String userId) {
        return resumeMapper.findByUserId(userId);
    }

    public boolean looksLikeResume(String fileName, String content) {
        String source = (fileName == null ? "" : fileName) + "\n" + (content == null ? "" : content);
        int hits = 0;
        for (String keyword : new String[]{"简历", "教育经历", "工作经历", "项目经历", "专业技能", "求职意向", "个人信息"}) {
            if (source.contains(keyword)) {
                hits++;
            }
        }
        return hits >= 2 || (fileName != null && fileName.toLowerCase().contains("resume"));
    }
}