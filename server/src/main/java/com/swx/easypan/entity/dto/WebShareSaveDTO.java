package com.swx.easypan.entity.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class WebShareSaveDTO {

    @NotBlank(message = "分享ID不能为空")
    private String shareId;

    private String fileId;

    private String myFolderId = "0";
}
