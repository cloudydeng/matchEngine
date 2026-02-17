package com.matching.load;

import com.matching.opinion.service.MarketVolumeMonitorService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OpinionLoadTester implements ApplicationRunner {

    private final MarketVolumeMonitorService monitorService;


    public OpinionLoadTester(MarketVolumeMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("应用启动 - 开始执行首次市场 volume 轮询...");

        try {
            // 直接调用服务中已有的轮询逻辑
            monitorService.pollAndCompareVolumes();
            log.info("首次市场 volume 轮询完成，已建立初始快照");
        } catch (Exception e) {
            log.error("应用启动时首次轮询市场 volume 失败", e);
        }
    }
}