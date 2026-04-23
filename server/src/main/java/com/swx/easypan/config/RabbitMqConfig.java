package com.swx.easypan.config;

import com.swx.easypan.mq.constants.FileProcessMqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Bean
    public DirectExchange fileProcessExchange() {
        return new DirectExchange(FileProcessMqConstants.FILE_PROCESS_EXCHANGE, true, false);
    }

    // 文件处理队列：用于异步执行文件相关的耗时任务，如视频转码、封面图生成等。
    @Bean
    public Queue fileProcessQueue() {
        return new Queue(FileProcessMqConstants.FILE_PROCESS_QUEUE, true);
    }

    // 物理文件清理队列：彻底删除数据库记录后，异步删除无人引用的磁盘文件。
    @Bean
    public Queue fileCleanupQueue() {
        return new Queue(FileProcessMqConstants.FILE_CLEANUP_QUEUE, true);
    }

    @Bean
    public Binding fileProcessBinding(@Qualifier("fileProcessQueue") Queue fileProcessQueue,
                                      DirectExchange fileProcessExchange) {
        return BindingBuilder.bind(fileProcessQueue)
                .to(fileProcessExchange)
                .with(FileProcessMqConstants.FILE_PROCESS_ROUTING_KEY);
    }

    // 将清理队列绑定到同一个文件处理交换机，用独立 routing key 区分清理任务。
    @Bean
    public Binding fileCleanupBinding(@Qualifier("fileCleanupQueue") Queue fileCleanupQueue,
                                      DirectExchange fileProcessExchange) {
        return BindingBuilder.bind(fileCleanupQueue)
                .to(fileProcessExchange)
                .with(FileProcessMqConstants.FILE_CLEANUP_ROUTING_KEY);
    }

    @Bean
    public MessageConverter rabbitMqMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter rabbitMqMessageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(rabbitMqMessageConverter);
        return rabbitTemplate;
    }
}
