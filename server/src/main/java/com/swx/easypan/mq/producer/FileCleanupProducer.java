package com.swx.easypan.mq.producer;

import com.swx.easypan.entity.dto.FileCleanupMessageDTO;
import com.swx.easypan.mq.constants.FileProcessMqConstants;
import com.swx.easypan.pojo.FileInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FileCleanupProducer {

    private final RabbitTemplate rabbitTemplate;

    public FileCleanupProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 投递物理文件清理消息。
     * <p>
     * 这里不直接删除磁盘文件，只把需要清理的物理路径信息发到 RabbitMQ；
     * 调用方应保证已经确认该 file_md5 在数据库中没有任何 file_info 记录引用。
     */
    public void sendFileCleanupMessage(FileInfo fileInfo) {
        FileCleanupMessageDTO messageDTO = new FileCleanupMessageDTO(
                fileInfo.getFileMd5(),
                fileInfo.getFilePath(),
                fileInfo.getFileCover(),
                fileInfo.getFileType()
        );
        rabbitTemplate.convertAndSend(
                FileProcessMqConstants.FILE_PROCESS_EXCHANGE,
                FileProcessMqConstants.FILE_CLEANUP_ROUTING_KEY,
                messageDTO
        );
        log.info("文件物理清理任务已投递到RabbitMQ,fileMd5:{}, filePath:{}",
                fileInfo.getFileMd5(), fileInfo.getFilePath());
    }
}
