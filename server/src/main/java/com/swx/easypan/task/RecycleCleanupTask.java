package com.swx.easypan.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.swx.easypan.entity.config.RecycleConfig;
import com.swx.easypan.entity.enums.FileDelFlagEnums;
import com.swx.easypan.pojo.FileInfo;
import com.swx.easypan.service.FileInfoService;
import com.swx.easypan.service.common.UserFileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class RecycleCleanupTask {

    private final FileInfoService fileInfoService;
    private final UserFileService userFileService;
    private final RecycleConfig recycleConfig;

    public RecycleCleanupTask(FileInfoService fileInfoService, UserFileService userFileService, RecycleConfig recycleConfig) {
        this.fileInfoService = fileInfoService;
        this.userFileService = userFileService;
        this.recycleConfig = recycleConfig;
    }

    /**
     * 定时清理过期回收站条目。
     * <p>
     * 这里只扫描回收站顶层条目（deleted = RECYCLE），然后按用户分组复用现有彻底删除链路，
     * 后续仍然会走 file_md5 引用判断和 RabbitMQ 异步物理清理。
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void clearExpiredRecycleFiles() {
        LocalDateTime expireBefore = LocalDateTime.now().minusDays(recycleConfig.getExpireDays());
        Page<FileInfo> page = new Page<>(1, recycleConfig.getClearBatchSize());
        Page<FileInfo> expiredPage = fileInfoService.page(page, new LambdaQueryWrapper<FileInfo>()
                .select(FileInfo::getId, FileInfo::getUserId, FileInfo::getRecoveryTime)
                .eq(FileInfo::getDeleted, FileDelFlagEnums.RECYCLE.getFlag())
                .isNotNull(FileInfo::getRecoveryTime)
                .le(FileInfo::getRecoveryTime, expireBefore)
                .orderByAsc(FileInfo::getRecoveryTime));

        List<FileInfo> expiredRecords = expiredPage.getRecords();
        if (CollectionUtils.isEmpty(expiredRecords)) {
            return;
        }

        Map<String, List<String>> userExpiredIdsMap = expiredRecords.stream()
                .collect(Collectors.groupingBy(FileInfo::getUserId,
                        Collectors.mapping(FileInfo::getId, Collectors.toList())));

        for (Map.Entry<String, List<String>> entry : userExpiredIdsMap.entrySet()) {
            String userId = entry.getKey();
            String ids = String.join(",", entry.getValue());
            try {
                userFileService.delFileBatch(userId, ids, false);
            } catch (Exception e) {
                log.error("自动清理过期回收站文件失败,userId:{}, ids:{}", userId, ids, e);
            }
        }
    }
}
