package com.swx.easypan.entity.dto;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
import java.io.Serializable;

public class RenameFileDTO implements Serializable {

    @NotEmpty
    private String id;
    @NotEmpty
    @Pattern(regexp = "[^/\\\\]+", message = "文件名不能包含路径分隔符")
    private String filename;

    public RenameFileDTO() {
    }

    public RenameFileDTO(String id, String filename) {
        this.id = id;
        this.filename = filename;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }
}
