package com.swx.easypan.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_settings")
public class SysSettings {
    //主键
    @TableId(type = IdType.AUTO)
    private Long id;
    //版本号
    private Integer version;
    //注册邮件标题
    private String registerMailTitle;
    //注册邮件内容
    private String registerMailContent;
    //注册默认空间大小，单位MB
    private Long userInitSpace;
    //系统配置状态，0-关闭，1-开启
    private Integer status;
    //备注
    private String remark;
    //创建时间
    private LocalDateTime createTime;
    //更新时间
    private LocalDateTime updateTime;
}
