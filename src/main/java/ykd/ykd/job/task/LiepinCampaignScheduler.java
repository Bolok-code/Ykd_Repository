package ykd.ykd.job.task;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LiepinCampaignScheduler {
    private final LiepinJobTaskManager taskManager;

    public LiepinCampaignScheduler(LiepinJobTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Scheduled(fixedDelayString = "${liepin.auto-apply.scan-interval-ms:60000}")
    public void scan() {
        taskManager.scanAutomaticCampaigns();
    }
}