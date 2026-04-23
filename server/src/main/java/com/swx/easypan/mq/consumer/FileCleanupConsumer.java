package com.swx.easypan.mq.consumer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swx.easypan.entity.config.AppConfig;
import com.swx.easypan.entity.constants.Constants;
import com.swx.easypan.entity.dto.FileCleanupMessageDTO;
import com.swx.easypan.entity.enums.FileTypeEnums;
import com.swx.easypan.mq.constants.FileProcessMqConstants;
import com.swx.easypan.pojo.FileInfo;
import com.swx.easypan.service.FileInfoService;
import com.swx.easypan.utils.StringTools;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Component
public class FileCleanupConsumer {

    private final AppConfig appConfig;
    private final FileInfoService fileInfoService;

    public FileCleanupConsumer(AppConfig appConfig, FileInfoService fileInfoService) {
        this.appConfig = appConfig;
        this.fileInfoService = fileInfoService;
    }

    /**
     * 消费物理文件清理任务。
     * <p>
     * 清理范围包括：原始文件、图片/视频封面；如果是视频，还会删除转码产生的 m3u8/ts 切片目录。
     * 删除操作使用 deleteIfExists，消息重复消费时也不会因为文件已不存在而失败。
     */
    @RabbitListener(queues = FileProcessMqConstants.FILE_CLEANUP_QUEUE)
    public void consumeFileCleanupMessage(FileCleanupMessageDTO messageDTO) {
        if (messageDTO == null || !StringUtils.hasText(messageDTO.getFilePath())) {
            return;
        }
        log.info("开始消费文件物理清理任务,fileMd5:{}, filePath:{}",
                messageDTO.getFileMd5(), messageDTO.getFilePath());
        try {
            if (isFileStillReferenced(messageDTO.getFileMd5())) {
                log.info("文件物理清理已跳过，fileMd5仍存在数据库引用,fileMd5:{}, filePath:{}",
                        messageDTO.getFileMd5(), messageDTO.getFilePath());
                return;
            }
            deleteRelativeFile(messageDTO.getFilePath());
            if (FileTypeEnums.VIDEO.getType().equals(messageDTO.getFileType())) {
                deleteRelativeDirectory(StringTools.getFilename(messageDTO.getFilePath()));
            }
            deleteRelativeFile(messageDTO.getFileCover());
        } catch (Exception e) {
            log.error("文件物理清理失败,fileMd5:{}, filePath:{}",
                    messageDTO.getFileMd5(), messageDTO.getFilePath(), e);
        }
    }

    // 消费消息时再次校验 file_md5 引用，避免延迟消息或极端并发导致仍被引用的磁盘文件被误删。
    private boolean isFileStillReferenced(String fileMd5) {
        if (!StringUtils.hasText(fileMd5)) {
            return true;
        }
        return fileInfoService.count(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getFileMd5, fileMd5)) > 0;
    }

    // 删除 project.folder/file 下的单个相对路径文件，例如原始文件或封面图。
    private void deleteRelativeFile(String relativePath) throws IOException {
        if (!StringUtils.hasText(relativePath)) {
            return;
        }
        Path targetPath = resolveFilePath(relativePath);
        if (Files.deleteIfExists(targetPath)) {
            log.info("物理文件已删除: {}", targetPath);
        }
    }

    // 删除 project.folder/file 下的相对路径目录，例如视频转码后的切片目录。
    private void deleteRelativeDirectory(String relativePath) throws IOException {
        if (!StringUtils.hasText(relativePath)) {
            return;
        }
        Path targetPath = resolveFilePath(relativePath);
        if (Files.exists(targetPath)) {
            FileUtils.deleteDirectory(targetPath.toFile());
            log.info("物理目录已删除: {}", targetPath);
        }
    }

    // 把数据库里的相对路径转换成真实路径，并限制只能清理 file 根目录下的内容，避免异常路径越界删除。
    private Path resolveFilePath(String relativePath) {
        Path basePath = Paths.get(appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE)
                .toAbsolutePath()
                .normalize();
        Path targetPath = basePath.resolve(relativePath).toAbsolutePath().normalize();
        if (!targetPath.startsWith(basePath)) {
            throw new IllegalArgumentException("非法文件清理路径: " + relativePath);
        }
        return targetPath;
    }
}
