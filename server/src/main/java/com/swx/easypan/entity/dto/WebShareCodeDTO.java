package com.swx.easypan.entity.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class WebShareCodeDTO {

    @NotBlank(message = "分享ID不能为空")
    private String shareId;

    @NotBlank(message = "提取码不能为空")
    private String code;
}
