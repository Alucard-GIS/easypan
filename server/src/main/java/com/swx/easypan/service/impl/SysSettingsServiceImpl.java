package com.swx.easypan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.swx.easypan.entity.constants.Constants;
import com.swx.easypan.entity.dto.SysSettingsDTO;
import com.swx.easypan.mapper.SysSettingsMapper;
import com.swx.easypan.pojo.SysSettings;
import com.swx.easypan.redis.RedisComponent;
import com.swx.easypan.service.SysSettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SysSettingsServiceImpl extends ServiceImpl<SysSettingsMapper, SysSettings> implements SysSettingsService {

    private final RedisComponent redisComponent;

    public SysSettingsServiceImpl(RedisComponent redisComponent) {
        this.redisComponent = redisComponent;
    }

    @Override
    public SysSettingsDTO getSysSettings() {
        SysSettingsDTO cache = redisComponent.getSysSettingsCache();
        if (cache != null) {
            return cache;
        }

        SysSettings settings = this.getOne(new LambdaQueryWrapper<SysSettings>()
                .eq(SysSettings::getStatus, 1)
                .orderByDesc(SysSettings::getVersion)
                .last("limit 1"));

        SysSettingsDTO dto;
        if (settings == null) {
            dto = buildDefaultSettings();
        } else {
            dto = toDto(settings);
        }

        redisComponent.saveSysSettingsCache(dto);
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveSysSettings(SysSettingsDTO dto, String remark) {
        SysSettings current = this.getOne(new LambdaQueryWrapper<SysSettings>()
                .eq(SysSettings::getStatus, 1)
                .orderByDesc(SysSettings::getVersion)
                .last("limit 1"));

        int nextVersion = current == null ? 1 : current.getVersion() + 1;

        if (current != null) {
            current.setStatus(Constants.ZERO);
            this.updateById(current);
        }

        SysSettings settings = new SysSettings();
        settings.setVersion(nextVersion);
        settings.setRegisterMailTitle(dto.getRegisterMailTitle());
        settings.setRegisterMailContent(dto.getRegisterEmailContent());
        settings.setUserInitSpace(dto.getUserInitSpace() == null ? null : dto.getUserInitSpace() * 1024L);
        settings.setStatus(1);
        settings.setRemark(remark);
        this.save(settings);

        redisComponent.removeSysSettingsCache();
    }

    private SysSettingsDTO toDto(SysSettings settings) {
        SysSettingsDTO dto = new SysSettingsDTO();
        dto.setRegisterMailTitle(settings.getRegisterMailTitle());
        dto.setRegisterEmailContent(settings.getRegisterMailContent());
        dto.setUserInitSpace(settings.getUserInitSpace() == null ? null : Math.toIntExact(settings.getUserInitSpace() / 1024L));
        return dto;
    }

    private SysSettingsDTO buildDefaultSettings() {
        return new SysSettingsDTO();
    }
}
