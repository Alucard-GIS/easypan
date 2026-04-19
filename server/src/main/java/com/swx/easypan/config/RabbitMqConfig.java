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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Bean
    public DirectExchange fileProcessExchange() {
        return new DirectExchange(FileProcessMqConstants.FILE_PROCESS_EXCHANGE, true, false);
    }

    @Bean
    public Queue fileProcessQueue() {
        return new Queue(FileProcessMqConstants.FILE_PROCESS_QUEUE, true);
    }

    @Bean
    public Binding fileProcessBinding(Queue fileProcessQueue, DirectExchange fileProcessExchange) {
        return BindingBuilder.bind(fileProcessQueue)
                .to(fileProcessExchange)
                .with(FileProcessMqConstants.FILE_PROCESS_ROUTING_KEY);
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
