package com.matching.opinion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MarketVolumeMonitorService {

    public void pollAndCompareVolumes() {
        log.info("Market volume monitor is not configured; skipping initial poll.");
    }
}
