package com.swx.easypan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.swx.easypan.entity.dto.SysSettingsDTO;
import com.swx.easypan.pojo.SysSettings;

public interface SysSettingsService extends IService<SysSettings> {

    SysSettingsDTO getSysSettings();

    void saveSysSettings(SysSettingsDTO dto, String remark);
}
