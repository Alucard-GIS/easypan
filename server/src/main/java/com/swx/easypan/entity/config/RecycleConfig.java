package com.swx.easypan.entity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "recycle")
public class RecycleConfig {

    /**
     * 回收站文件的有效恢复天数。
     */
    private Integer expireDays = 10; // 默认10天，若配置文件有值则覆盖

    /**
     * 定时任务每次自动清理的最大回收站顶层条目数。
     */
    private Integer clearBatchSize = 100; // 默认100条，若配置文件有值则覆盖
}
