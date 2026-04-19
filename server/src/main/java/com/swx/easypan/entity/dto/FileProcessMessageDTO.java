package com.swx.easypan.entity.dto;

import java.io.Serializable;

public class FileProcessMessageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String fileId;
    private String userId;

    public FileProcessMessageDTO() {
    }

    public FileProcessMessageDTO(String fileId, String userId) {
        this.fileId = fileId;
        this.userId = userId;
    }

    public String getFileId() {
        return fileId;
    }

    public void setFileId(String fileId) {
        this.fileId = fileId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
