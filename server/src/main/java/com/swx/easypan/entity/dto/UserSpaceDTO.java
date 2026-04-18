package com.swx.easypan.entity.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class UserSpaceDTO implements Serializable {

    //已使用空间
    private Long useSpace;
    //总空间
    private Long totalSpace;

    public UserSpaceDTO() {
    }

    public UserSpaceDTO(Long useSpace, Long totalSpace) {
        this.useSpace = useSpace;
        this.totalSpace = totalSpace;
    }
}
