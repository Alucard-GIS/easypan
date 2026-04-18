package com.swx.easypan.entity.dto;

import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

@Data
public class FileUploadDTO {

    private String id;

    @NotEmpty
    private String filename;

    private String filePid;

    @NotEmpty
    private String fileMd5;

    //当前是第几个分片
    @NotNull
    private Integer chunkIndex;

    //总分片数
    @NotNull
    private Integer chunks;
}
