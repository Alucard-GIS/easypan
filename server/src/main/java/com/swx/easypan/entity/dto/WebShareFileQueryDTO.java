package com.swx.easypan.entity.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class WebShareFileQueryDTO {

    @NotBlank(message = "分享ID不能为空")
    private String shareId;

    private String filePid;

    private Integer page = 1;

    private Integer limit = 20;
}
