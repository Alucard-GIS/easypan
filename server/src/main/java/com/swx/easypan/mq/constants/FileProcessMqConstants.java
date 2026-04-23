package com.swx.easypan.mq.constants;

public class FileProcessMqConstants {

    private FileProcessMqConstants() {
    }

    public static final String FILE_PROCESS_EXCHANGE = "easypan.file.process.exchange";

    // 文件上传后的转码/合并处理任务。
    public static final String FILE_PROCESS_QUEUE = "easypan.file.process.queue";
    public static final String FILE_PROCESS_ROUTING_KEY = "easypan.file.process";

    // 回收站彻底删除后的物理文件异步清理任务。
    public static final String FILE_CLEANUP_QUEUE = "easypan.file.cleanup.queue";
    public static final String FILE_CLEANUP_ROUTING_KEY = "easypan.file.cleanup";
}
