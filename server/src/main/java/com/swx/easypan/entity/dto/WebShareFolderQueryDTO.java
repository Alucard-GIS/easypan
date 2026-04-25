package com.swx.easypan.entity.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class WebShareFolderQueryDTO {

    @NotBlank(message = "分享ID不能为空")
    private String shareId;

    @NotBlank(message = "路径不能为空")
    private String path;
}
