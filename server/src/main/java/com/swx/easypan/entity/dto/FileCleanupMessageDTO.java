package com.swx.easypan.entity.dto;

import java.io.Serializable;

public class FileCleanupMessageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 物理文件内容标识，用于日志追踪；真正是否清理由发送方在入队前按 file_md5 引用数判断。
     */
    private String fileMd5;

    /**
     * 原始文件在 project.folder/file 下的相对路径。
     */
    private String filePath;

    /**
     * 封面图在 project.folder/file 下的相对路径，图片和视频可能存在。
     */
    private String fileCover;

    /**
     * 文件类型，用于判断是否需要额外删除视频切片目录。
     */
    private Integer fileType;

    public FileCleanupMessageDTO() {
    }

    public FileCleanupMessageDTO(String fileMd5, String filePath, String fileCover, Integer fileType) {
        this.fileMd5 = fileMd5;
        this.filePath = filePath;
        this.fileCover = fileCover;
        this.fileType = fileType;
    }

    public String getFileMd5() {
        return fileMd5;
    }

    public void setFileMd5(String fileMd5) {
        this.fileMd5 = fileMd5;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getFileCover() {
        return fileCover;
    }

    public void setFileCover(String fileCover) {
        this.fileCover = fileCover;
    }

    public Integer getFileType() {
        return fileType;
    }

    public void setFileType(Integer fileType) {
        this.fileType = fileType;
    }
}
