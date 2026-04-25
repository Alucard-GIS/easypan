package com.swx.easypan.entity.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
public class AdminUpdateUserSpaceDTO {

    @NotBlank(message = "用户ID不能为空")
    private String userId;

    @NotNull(message = "用户总空间不能为空")
    private Integer totalSpaceGb;
}
