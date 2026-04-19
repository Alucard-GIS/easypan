package com.swx.easypan.mq.consumer;

import com.swx.easypan.entity.dto.FileProcessMessageDTO;
import com.swx.easypan.mq.constants.FileProcessMqConstants;
import com.swx.easypan.service.impl.FileInfoServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FileProcessConsumer {

    private final FileInfoServiceImpl fileInfoService;

    public FileProcessConsumer(FileInfoServiceImpl fileInfoService) {
        this.fileInfoService = fileInfoService;
    }

    @RabbitListener(queues = FileProcessMqConstants.FILE_PROCESS_QUEUE)
    public void consumeFileProcessMessage(FileProcessMessageDTO messageDTO) {
        if (messageDTO == null) {
            return;
        }
        log.info("开始消费文件处理任务,fileId:{}, userId:{}", messageDTO.getFileId(), messageDTO.getUserId());
        fileInfoService.transferFile(messageDTO.getFileId(), messageDTO.getUserId());
    }
}
