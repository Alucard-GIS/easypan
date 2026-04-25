package com.swx.easypan.entity.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class FileShareVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String fileId;

    private String userId;

    private Boolean validType;

    private String code;

    private Integer browseCount;

    private Integer saveCount;

    private Integer downloadCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    private String filename;

    private String fileCover;

    private Integer folderType;

    private Integer fileType;

    private Integer status;

    private Boolean effective;
}
