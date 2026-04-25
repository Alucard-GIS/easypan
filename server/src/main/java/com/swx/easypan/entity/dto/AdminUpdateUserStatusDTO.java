package com.swx.easypan.entity.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class AdminUpdateUserStatusDTO {

    @NotBlank(message = "用户ID不能为空")
    private String userId;

    @NotNull(message = "用户状态不能为空")
    private Integer status;
}
