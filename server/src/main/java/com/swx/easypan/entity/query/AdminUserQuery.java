package com.swx.easypan.entity.query;

import lombok.Data;

@Data
public class AdminUserQuery {

    private Integer page = 1;
    private Integer limit = 20;

    private String email;

    private String nickname;

    private Integer status;
}
