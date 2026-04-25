package com.swx.easypan.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.swx.common.annotation.ResponseResult;
import com.swx.common.pojo.BizException;
import com.swx.easypan.annotation.LoginValidator;
import com.swx.easypan.entity.config.AppConfig;
import com.swx.easypan.entity.constants.Constants;
import com.swx.easypan.entity.dto.AdminUpdateUserSpaceDTO;
import com.swx.easypan.entity.dto.AdminUpdateUserStatusDTO;
import com.swx.easypan.entity.dto.DownloadFileDTO;
import com.swx.easypan.entity.dto.SysSettingsDTO;
import com.swx.easypan.entity.dto.UserSpaceDTO;
import com.swx.easypan.entity.enums.FileFolderTypeEnums;
import com.swx.easypan.entity.enums.UserStatusEnum;
import com.swx.easypan.entity.query.AdminUserQuery;
import com.swx.easypan.entity.query.FileInfoQuery;
import com.swx.easypan.entity.vo.AdminFileInfoVO;
import com.swx.easypan.entity.vo.AdminUserInfoVO;
import com.swx.easypan.entity.vo.FileInfoVO;
import com.swx.easypan.mq.producer.FileCleanupProducer;
import com.swx.easypan.pojo.FileInfo;
import com.swx.easypan.pojo.UserInfo;
import com.swx.easypan.redis.RedisComponent;
import com.swx.easypan.service.FileInfoService;
import com.swx.easypan.service.SysSettingsService;
import com.swx.easypan.service.UserInfoService;
import com.swx.easypan.service.impl.FileInfoServiceImpl;
import com.swx.easypan.utils.FileUtils;
import com.swx.easypan.utils.StringTools;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin")
@ResponseResult
@Validated
@LoginValidator(checkAdmin = true)
public class AdminController {

    private final FileInfoService fileInfoService;
    private final FileInfoServiceImpl fileInfoServiceImpl;
    private final UserInfoService userInfoService;
    private final SysSettingsService sysSettingsService;
    private final RedisComponent redisComponent;
    private final AppConfig appConfig;
    private final FileCleanupProducer fileCleanupProducer;

    public AdminController(FileInfoService fileInfoService,
                           FileInfoServiceImpl fileInfoServiceImpl,
                           UserInfoService userInfoService,
                           SysSettingsService sysSettingsService,
                           RedisComponent redisComponent,
                           AppConfig appConfig,
                           FileCleanupProducer fileCleanupProducer) {
        this.fileInfoService = fileInfoService;
        this.fileInfoServiceImpl = fileInfoServiceImpl;
        this.userInfoService = userInfoService;
        this.sysSettingsService = sysSettingsService;
        this.redisComponent = redisComponent;
        this.appConfig = appConfig;
        this.fileCleanupProducer = fileCleanupProducer;
    }

    @GetMapping("/file/list")
    public IPage<AdminFileInfoVO> loadFileList(FileInfoQuery query) {
        Page<FileInfo> pageParam = new Page<>(query.getPage(), query.getLimit());
        LambdaQueryWrapper<FileInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(org.springframework.util.StringUtils.hasText(query.getUserId()), FileInfo::getUserId, query.getUserId())
                .eq(org.springframework.util.StringUtils.hasText(query.getFileMd5()), FileInfo::getFileMd5, query.getFileMd5())
                .eq(org.springframework.util.StringUtils.hasText(query.getFilePid()), FileInfo::getFilePid, query.getFilePid())
                .like(org.springframework.util.StringUtils.hasText(query.getFilename()), FileInfo::getFilename, query.getFilename())
                .eq(query.getFileCategory() != null, FileInfo::getFileCategory, query.getFileCategory())
                .eq(query.getDeleted() != null, FileInfo::getDeleted, query.getDeleted())
                .orderByDesc(FileInfo::getUpdateTime);
        IPage<FileInfo> pageInfo = fileInfoService.page(pageParam, wrapper);
        List<String> userIds = pageInfo.getRecords().stream()
                .map(FileInfo::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<String, UserInfo> userMap = loadUserMap(userIds);

        Page<AdminFileInfoVO> result = new Page<>(pageParam.getCurrent(), pageParam.getSize(), pageInfo.getTotal());
        result.setRecords(pageInfo.getRecords().stream().map(item -> toAdminFileVo(item, userMap)).collect(Collectors.toList()));
        return result;
    }

    @GetMapping("/file/folderInfo")
    public List<FileInfoVO> getFolderInfo(@NotEmpty String path) {
        String[] ids = path.split("/");
        return fileInfoService.listFolderByIds(ids);
    }

    @GetMapping("/file/info/{id}")
    public AdminFileInfoVO getFileInfo(@PathVariable("id") @NotBlank String id) {
        FileInfo fileInfo = fileInfoService.getById(id);
        if (fileInfo == null) {
            throw new BizException("文件不存在");
        }
        Map<String, UserInfo> userMap = loadUserMap(Collections.singletonList(fileInfo.getUserId()));
        return toAdminFileVo(fileInfo, userMap);
    }

    @GetMapping("/file/createDownloadUrl/{id}")
    public Map<String, String> createDownloadUrl(@PathVariable("id") @NotBlank String id) {
        FileInfo fileInfo = fileInfoService.getById(id);
        if (fileInfo == null) {
            throw new BizException("文件不存在");
        }
        if (FileFolderTypeEnums.FOLDER.getType().equals(fileInfo.getFolderType())) {
            throw new BizException("文件夹暂不支持下载");
        }
        if (!org.springframework.util.StringUtils.hasText(fileInfo.getFilePath())) {
            throw new BizException("文件路径不存在");
        }
        String code = StringTools.getRandomString(Constants.LENGTH_50);
        DownloadFileDTO downloadFileDTO = new DownloadFileDTO(code, fileInfo.getFilename(), fileInfo.getFilePath());
        redisComponent.saveDownloadCode(code, downloadFileDTO);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("code", code);
        return result;
    }

    @LoginValidator(validated = false)
    @GetMapping("/file/download/{code}")
    public void download(HttpServletRequest request,
                         HttpServletResponse response,
                         @PathVariable("code") @NotEmpty String code) throws UnsupportedEncodingException {
        DownloadFileDTO fileDTO = redisComponent.getDownloadCode(code);
        if (fileDTO == null) {
            return;
        }
        String filePath = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + fileDTO.getFilePath();
        FileUtils.writeDownloadFile(response, request, fileDTO.getFilename(), filePath);
    }

    @DeleteMapping("/file/delete/{ids}")
    @Transactional(rollbackFor = Exception.class)
    public void deleteFile(@PathVariable("ids") @NotEmpty String ids) {
        List<String> topIds = Arrays.stream(ids.split(","))
                .filter(org.springframework.util.StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());
        if (topIds.isEmpty()) {
            return;
        }

        List<FileInfo> selectedFiles = fileInfoService.list(new LambdaQueryWrapper<FileInfo>()
                .in(FileInfo::getId, topIds));
        if (selectedFiles.isEmpty()) {
            return;
        }

        Map<String, DeleteContext> deleteContextMap = new LinkedHashMap<>();
        for (FileInfo fileInfo : selectedFiles) {
            DeleteContext context = deleteContextMap.computeIfAbsent(fileInfo.getUserId(), key -> new DeleteContext());
            context.topIds.add(fileInfo.getId());
            if (FileFolderTypeEnums.FOLDER.getType().equals(fileInfo.getFolderType())) {
                fileInfoServiceImpl.findAllSubFolderList(context.folderPidList, fileInfo.getUserId(), fileInfo.getId(), null);
            } else {
                context.deletingPhysicalFiles.add(fileInfo);
            }
        }

        for (Map.Entry<String, DeleteContext> entry : deleteContextMap.entrySet()) {
            String userId = entry.getKey();
            DeleteContext context = entry.getValue();
            if (!context.folderPidList.isEmpty()) {
                context.deletingPhysicalFiles.addAll(fileInfoService.list(new LambdaQueryWrapper<FileInfo>()
                        .eq(FileInfo::getUserId, userId)
                        .eq(FileInfo::getFolderType, FileFolderTypeEnums.FILE.getType())
                        .in(FileInfo::getFilePid, context.folderPidList)));
            }

            fileInfoService.delFileBatch(
                    userId,
                    context.folderPidList.isEmpty() ? null : context.folderPidList,
                    context.topIds,
                    null
            );
        }

        List<FileInfo> cleanupFiles = findUnreferencedPhysicalFiles(deleteContextMap.values().stream()
                .flatMap(item -> item.deletingPhysicalFiles.stream())
                .collect(Collectors.toList()));
        if (!cleanupFiles.isEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    for (FileInfo fileInfo : cleanupFiles) {
                        fileCleanupProducer.sendFileCleanupMessage(fileInfo);
                    }
                }
            });
        }

        for (String userId : deleteContextMap.keySet()) {
            refreshUserSpace(userId);
        }
    }

    @GetMapping("/user/list")
    public IPage<AdminUserInfoVO> loadUserList(AdminUserQuery query) {
        Page<UserInfo> pageParam = new Page<>(query.getPage(), query.getLimit());
        LambdaQueryWrapper<UserInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(org.springframework.util.StringUtils.hasText(query.getEmail()), UserInfo::getEmail, query.getEmail())
                .like(org.springframework.util.StringUtils.hasText(query.getNickname()), UserInfo::getNickname, query.getNickname())
                .eq(query.getStatus() != null, UserInfo::getStatus, query.getStatus())
                .orderByDesc(UserInfo::getCreateTime);
        IPage<UserInfo> pageInfo = userInfoService.page(pageParam, wrapper);
        Page<AdminUserInfoVO> result = new Page<>(pageParam.getCurrent(), pageParam.getSize(), pageInfo.getTotal());
        result.setRecords(pageInfo.getRecords().stream().map(this::toAdminUserVo).collect(Collectors.toList()));
        return result;
    }

    @PutMapping("/user/status")
    public void updateUserStatus(@Validated @RequestBody AdminUpdateUserStatusDTO dto) {
        if (!Objects.equals(dto.getStatus(), UserStatusEnum.ENABLE.status())
                && !Objects.equals(dto.getStatus(), UserStatusEnum.DISABLE.status())) {
            throw new BizException("用户状态不合法");
        }
        UserInfo userInfo = userInfoService.getById(dto.getUserId());
        if (userInfo == null) {
            throw new BizException("用户不存在");
        }
        UserInfo updateInfo = new UserInfo();
        updateInfo.setId(dto.getUserId());
        updateInfo.setStatus(dto.getStatus());
        userInfoService.updateById(updateInfo);
    }

    @PutMapping("/user/space")
    public void updateUserSpace(@Validated @RequestBody AdminUpdateUserSpaceDTO dto) {
        if (dto.getTotalSpaceGb() == null || dto.getTotalSpaceGb() <= 0) {
            throw new BizException("用户空间必须大于0");
        }
        UserInfo userInfo = userInfoService.getById(dto.getUserId());
        if (userInfo == null) {
            throw new BizException("用户不存在");
        }
        long totalSpace = dto.getTotalSpaceGb() * 1024L * Constants.MB;
        if (userInfo.getUseSpace() != null && totalSpace < userInfo.getUseSpace()) {
            throw new BizException("用户总空间不能小于已使用空间");
        }
        userInfoService.updateUserSpace(dto.getUserId(), null, totalSpace);
        refreshUserSpace(dto.getUserId());
    }

    @GetMapping("/settings")
    public SysSettingsDTO getSysSettings() {
        return sysSettingsService.getSysSettings();
    }

    @PutMapping("/settings")
    public void saveSysSettings(@Validated @RequestBody SysSettingsDTO dto) {
        sysSettingsService.saveSysSettings(dto, "管理员更新系统设置");
    }

    private Map<String, UserInfo> loadUserMap(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userInfoService.list(new LambdaQueryWrapper<UserInfo>().in(UserInfo::getId, userIds))
                .stream()
                .collect(Collectors.toMap(UserInfo::getId, item -> item));
    }

    private AdminFileInfoVO toAdminFileVo(FileInfo fileInfo, Map<String, UserInfo> userMap) {
        AdminFileInfoVO vo = new AdminFileInfoVO();
        BeanUtils.copyProperties(fileInfo, vo);
        UserInfo userInfo = userMap.get(fileInfo.getUserId());
        if (userInfo != null) {
            vo.setUserEmail(userInfo.getEmail());
            vo.setUserNickname(userInfo.getNickname());
        }
        return vo;
    }

    private AdminUserInfoVO toAdminUserVo(UserInfo userInfo) {
        AdminUserInfoVO vo = new AdminUserInfoVO();
        BeanUtils.copyProperties(userInfo, vo);
        vo.setAdmin(ArrayUtils.contains(appConfig.getEmails().split(","), userInfo.getEmail()));
        return vo;
    }

    private List<FileInfo> findUnreferencedPhysicalFiles(List<FileInfo> deletingPhysicalFiles) {
        Map<String, FileInfo> md5FileMap = new LinkedHashMap<>();
        for (FileInfo fileInfo : deletingPhysicalFiles) {
            if (fileInfo == null
                    || !org.springframework.util.StringUtils.hasText(fileInfo.getFileMd5())
                    || !org.springframework.util.StringUtils.hasText(fileInfo.getFilePath())
                    || !FileFolderTypeEnums.FILE.getType().equals(fileInfo.getFolderType())) {
                continue;
            }
            md5FileMap.putIfAbsent(fileInfo.getFileMd5(), fileInfo);
        }

        List<FileInfo> cleanupFiles = new ArrayList<>();
        for (Map.Entry<String, FileInfo> entry : md5FileMap.entrySet()) {
            int referenceCount = fileInfoService.count(new LambdaQueryWrapper<FileInfo>()
                    .eq(FileInfo::getFileMd5, entry.getKey()));
            if (referenceCount == 0) {
                cleanupFiles.add(entry.getValue());
            }
        }
        return cleanupFiles;
    }

    private void refreshUserSpace(String userId) {
        Long useSpace = fileInfoService.getUseSpace(userId);
        UserInfo updateInfo = new UserInfo();
        updateInfo.setId(userId);
        updateInfo.setUseSpace(useSpace);
        userInfoService.updateById(updateInfo);

        UserInfo userInfo = userInfoService.getById(userId);
        if (userInfo != null) {
            UserSpaceDTO userSpaceDTO = new UserSpaceDTO();
            userSpaceDTO.setUseSpace(useSpace);
            userSpaceDTO.setTotalSpace(userInfo.getTotalSpace());
            redisComponent.saveUserSpaceUse(userId, userSpaceDTO);
        }
    }

    private static class DeleteContext {
        private final List<String> topIds = new ArrayList<>();
        private final List<String> folderPidList = new ArrayList<>();
        private final List<FileInfo> deletingPhysicalFiles = new ArrayList<>();
    }
}
