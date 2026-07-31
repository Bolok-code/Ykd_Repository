package ykd.ykd.job.task;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 自动投递计划调度器。
 *
 * <p>通过 Spring {@link Scheduled} 以固定延迟扫描待执行的投递计划。
 * 扫描间隔由配置 {@code liepin.auto-apply.scan-interval-ms} 控制，默认 60 秒。
 *
 * <p>调度器本身只做轮询触发，实际执行逻辑在 {@link LiepinJobTaskManager#scanAutomaticCampaigns()} 中。
 * 该方法内部有 CAS 去重保护，防止重复排队。</p>
 */
@Component
public class LiepinCampaignScheduler {
    private final LiepinJobTaskManager taskManager;

    public LiepinCampaignScheduler(LiepinJobTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    /**
     * 每分钟扫描一次数据库中的到期计划，提交到单线程工作队列执行。
     * 两次扫描之间的延迟由 {@code fixedDelayString} 保证（上一次完成到下一次开始）。
     */
    @Scheduled(fixedDelayString = "${liepin.auto-apply.scan-interval-ms:60000}")
    public void scan() {
        taskManager.scanAutomaticCampaigns();
    }
}
