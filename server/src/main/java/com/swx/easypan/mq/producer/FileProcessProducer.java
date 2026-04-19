package com.swx.easypan.mq.producer;

import com.swx.easypan.entity.dto.FileProcessMessageDTO;
import com.swx.easypan.mq.constants.FileProcessMqConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FileProcessProducer {

    private final RabbitTemplate rabbitTemplate;

    public FileProcessProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void sendFileProcessMessage(String fileId, String userId) {
        FileProcessMessageDTO messageDTO = new FileProcessMessageDTO(fileId, userId);
        rabbitTemplate.convertAndSend(
                FileProcessMqConstants.FILE_PROCESS_EXCHANGE,
                FileProcessMqConstants.FILE_PROCESS_ROUTING_KEY,
                messageDTO
        );
        log.info("文件处理任务已投递到RabbitMQ,fileId:{}, userId:{}", fileId, userId);
    }
}
