package com.swx.easypan.controller;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.swx.common.annotation.ResponseResult;
import com.swx.common.pojo.BizException;
import com.swx.easypan.annotation.LoginValidator;
import com.swx.easypan.entity.constants.Constants;
import com.swx.easypan.entity.dto.*;
import com.swx.easypan.entity.enums.FileDelFlagEnums;
import com.swx.easypan.entity.enums.FileFolderTypeEnums;
import com.swx.easypan.entity.vo.SessionWebUserVO;
import com.swx.easypan.entity.vo.FileInfoVO;
import com.swx.easypan.entity.vo.WebShareInfoVO;
import com.swx.easypan.pojo.FileShare;
import com.swx.easypan.pojo.FileInfo;
import com.swx.easypan.pojo.UserInfo;
import com.swx.easypan.redis.RedisComponent;
import com.swx.easypan.service.FileInfoService;
import com.swx.easypan.service.FileShareService;
import com.swx.easypan.service.UserInfoService;
import com.swx.easypan.utils.FileUtils;
import com.swx.easypan.utils.StringTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.validation.constraints.NotEmpty;
import java.io.UnsupportedEncodingException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author sw-code
 * @since 2023-07-14
 */
@RestController
@RequestMapping("/showShare")
@ResponseResult
@Validated
@Slf4j
public class WebShareController {

    private static final String SHARE_SESSION_PREFIX = "share_session_";

    private final UserInfoService userInfoService;
    private final FileInfoService fileInfoService;
    private final FileShareService fileShareService;
    private final RedisComponent redisComponent;
    private final com.swx.easypan.entity.config.AppConfig appConfig;

    public WebShareController(UserInfoService userInfoService,
                              FileInfoService fileInfoService,
                              FileShareService fileShareService,
                              RedisComponent redisComponent,
                              com.swx.easypan.entity.config.AppConfig appConfig) {
        this.userInfoService = userInfoService;
        this.fileInfoService = fileInfoService;
        this.fileShareService = fileShareService;
        this.redisComponent = redisComponent;
        this.appConfig = appConfig;
    }

    @LoginValidator(validated = false)
    @PostMapping("/getShareLoginInfo")
    public SessionWebUserVO getShareLoginInfo(HttpSession session) {
        return (SessionWebUserVO) session.getAttribute(Constants.SESSION_KEY);
    }

    @LoginValidator(validated = false)
    @PostMapping("/getShareInfo/{shareId}")
    public WebShareInfoVO getShareInfo(HttpSession session, @PathVariable("shareId") @NotEmpty String shareId) {
        FileShare share = getValidShare(shareId);
        FileInfo fileInfo = getValidSharedRootFile(share);
        UserInfo userInfo = getShareUser(share.getUserId());
        boolean codeChecked = hasShareAccess(session, share);
        if ((share.getCode() == null || share.getCode().trim().isEmpty()) && !codeChecked) {
            grantShareAccess(session, shareId);
            codeChecked = true;
        }
        if (codeChecked) {
            increaseBrowseCount(share);
        }
        return buildWebShareInfo(share, fileInfo, userInfo, codeChecked);
    }

    @LoginValidator(validated = false)
    @PostMapping("/checkShareCode")
    public WebShareInfoVO checkShareCode(HttpSession session, @Validated @RequestBody WebShareCodeDTO dto) {
        FileShare share = getValidShare(dto.getShareId());
        if (!Objects.equals(share.getCode(), dto.getCode())) {
            throw new BizException("提取码错误");
        }
        grantShareAccess(session, dto.getShareId());
        FileInfo fileInfo = getValidSharedRootFile(share);
        UserInfo userInfo = getShareUser(share.getUserId());
        increaseBrowseCount(share);
        return buildWebShareInfo(share, fileInfo, userInfo, true);
    }

    @LoginValidator(validated = false)
    @PostMapping("/loadFileList")
    public IPage<FileInfoVO> loadFileList(HttpSession session, @Validated @RequestBody WebShareFileQueryDTO dto) {
        FileShare share = validateShareAccess(session, dto.getShareId());
        FileInfo rootFile = getValidSharedRootFile(share);
        Page<FileInfo> pageParam = new Page<>(dto.getPage(), dto.getLimit());

        if (FileFolderTypeEnums.FILE.getType().equals(rootFile.getFolderType())) {
            if (dto.getFilePid() != null && !rootFile.getId().equals(dto.getFilePid()) && !"0".equals(dto.getFilePid())) {
                return emptyFilePage(pageParam);
            }
            FileInfoVO fileInfoVO = new FileInfoVO();
            BeanUtils.copyProperties(rootFile, fileInfoVO);
            Page<FileInfoVO> page = new Page<>(pageParam.getCurrent(), pageParam.getSize(), 1);
            page.setRecords(Collections.singletonList(fileInfoVO));
            return page;
        }

        String currentPid = dto.getFilePid();
        if (!StringUtils.hasText(currentPid) || "0".equals(currentPid)) {
            FileInfoVO fileInfoVO = new FileInfoVO();
            BeanUtils.copyProperties(rootFile, fileInfoVO);
            Page<FileInfoVO> page = new Page<>(pageParam.getCurrent(), pageParam.getSize(), 1);
            page.setRecords(Collections.singletonList(fileInfoVO));
            return page;
        }
        if (!rootFile.getId().equals(currentPid)) {
            assertFileInShareScope(rootFile, currentPid, share.getUserId());
        }
        com.swx.easypan.entity.query.FileInfoQuery query = new com.swx.easypan.entity.query.FileInfoQuery();
        query.setUserId(share.getUserId());
        query.setFilePid(currentPid);
        query.setDeleted(FileDelFlagEnums.USING.getFlag());
        return fileInfoService.pageInfo(pageParam, query);
    }

    @LoginValidator(validated = false)
    @PostMapping("/getFolderInfo")
    public List<FileInfoVO> getFolderInfo(HttpSession session, @Validated @RequestBody WebShareFolderQueryDTO dto) {
        FileShare share = validateShareAccess(session, dto.getShareId());
        FileInfo rootFile = getValidSharedRootFile(share);
        String[] ids = dto.getPath().split("/");
        for (String id : ids) {
            assertFileInShareScope(rootFile, id, share.getUserId());
        }
        return fileInfoService.listFolderByIds(ids);
    }

    @LoginValidator(validated = false)
    @GetMapping("/getImage/{shareId}/{fileId}")
    public void getImage(HttpSession session,
                         HttpServletResponse response,
                         @PathVariable("shareId") @NotEmpty String shareId,
                         @PathVariable("fileId") @NotEmpty String fileId) {
        FileShare share = validateShareAccess(session, shareId);
        FileInfo rootFile = getValidSharedRootFile(share);
        assertFileInShareScope(rootFile, fileId, share.getUserId());
        FileInfo fileInfo = fileInfoService.getOne(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getId, fileId)
                .eq(FileInfo::getUserId, share.getUserId())
                .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag()));
        if (fileInfo == null) {
            log.warn("share getImage file missing, shareId={}, fileId={}, rootFileId={}, userId={}",
                    shareId, fileId, rootFile.getId(), share.getUserId());
            throw new BizException("文件不存在");
        }
        String imageRelativePath = StringUtils.hasText(fileInfo.getFileCover()) ? fileInfo.getFileCover() : fileInfo.getFilePath();
        String imagePath = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + imageRelativePath;
        log.info("share getImage, shareId={}, fileId={}, rootFileId={}, userId={}, imagePath={}",
                shareId, fileId, rootFile.getId(), share.getUserId(), imagePath);
        FileUtils.writeImage(response, imagePath);
    }

    @LoginValidator(validated = false)
    @GetMapping("/getFile/{shareId}/{fileId}")
    public void getFile(HttpSession session,
                        HttpServletResponse response,
                        @PathVariable("shareId") @NotEmpty String shareId,
                        @PathVariable("fileId") @NotEmpty String fileId) {
        FileShare share = validateShareAccess(session, shareId);
        FileInfo rootFile = getValidSharedRootFile(share);
        assertFileInShareScope(rootFile, fileId, share.getUserId());
        String filePath = fileInfoService.getFilePath(fileId, share.getUserId());
        log.info("share getFile, shareId={}, fileId={}, rootFileId={}, userId={}, filePath={}",
                shareId, fileId, rootFile.getId(), share.getUserId(), filePath);
        com.swx.common.utils.FileUtil.readFile(response, filePath);
    }

    @LoginValidator(validated = false)
    @GetMapping("/ts/getVideoInfo/{shareId}/{fileId}")
    public void getVideoInfo(HttpSession session,
                             HttpServletResponse response,
                             @PathVariable("shareId") @NotEmpty String shareId,
                             @PathVariable("fileId") @NotEmpty String fileId) {
        FileShare share = validateShareAccess(session, shareId);
        FileInfo rootFile = getValidSharedRootFile(share);
        String realFileId = fileId.contains("_") ? fileId.substring(0, fileId.indexOf('_')) : fileId;
        assertFileInShareScope(rootFile, realFileId, share.getUserId());
        String filePath = fileInfoService.getFilePath(fileId, share.getUserId());
        log.info("share getVideoInfo, shareId={}, fileId={}, realFileId={}, rootFileId={}, userId={}, filePath={}",
                shareId, fileId, realFileId, rootFile.getId(), share.getUserId(), filePath);
        com.swx.common.utils.FileUtil.readFile(response, filePath);
    }

    @LoginValidator(validated = false)
    @GetMapping("/createDownloadUrl/{shareId}/{fileId}")
    public Map<String, String> createDownloadUrl(HttpSession session,
                                                 @PathVariable("shareId") @NotEmpty String shareId,
                                                 @PathVariable("fileId") @NotEmpty String fileId) {
        FileShare share = validateShareAccess(session, shareId);
        FileInfo rootFile = getValidSharedRootFile(share);
        assertFileInShareScope(rootFile, fileId, share.getUserId());
        FileInfo fileInfo = fileInfoService.getOne(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getId, fileId)
                .eq(FileInfo::getUserId, share.getUserId())
                .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag()));
        if (fileInfo == null || FileFolderTypeEnums.FOLDER.getType().equals(fileInfo.getFolderType())) {
            throw new BizException("文件不存在或不支持下载");
        }
        String code = StringTools.getRandomString(Constants.LENGTH_50);
        DownloadFileDTO fileDTO = new DownloadFileDTO(code, fileInfo.getFilename(), fileInfo.getFilePath());
        redisComponent.saveDownloadCode(code, fileDTO);
        increaseDownloadCount(share);
        Map<String, String> result = new HashMap<>();
        result.put("code", code);
        return result;
    }

    @LoginValidator(validated = false)
    @GetMapping("/download/{code}")
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

    @PostMapping("/save2MyPan")
    @LoginValidator
    public void save2MyPan(HttpSession session, @Validated @RequestBody WebShareSaveDTO dto) {
        SessionWebUserVO currentUser = (SessionWebUserVO) session.getAttribute(Constants.SESSION_KEY);
        FileShare share = validateShareAccess(session, dto.getShareId());
        FileInfo rootFile = getValidSharedRootFile(share);
        FileInfo targetFile = rootFile;
        if (dto.getFileId() != null && !dto.getFileId().trim().isEmpty()) {
            assertFileInShareScope(rootFile, dto.getFileId(), share.getUserId());
            targetFile = fileInfoService.getOne(new LambdaQueryWrapper<FileInfo>()
                    .eq(FileInfo::getId, dto.getFileId())
                    .eq(FileInfo::getUserId, share.getUserId())
                    .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag()));
            if (targetFile == null) {
                throw new BizException("分享文件不存在");
            }
        }

        String targetFolderId = (dto.getMyFolderId() == null || dto.getMyFolderId().trim().isEmpty()) ? "0" : dto.getMyFolderId();
        assertTargetFolder(currentUser.getId(), targetFolderId);

        long needSpace = calculateNeedSpace(share.getUserId(), targetFile);
        UserInfo currentUserInfo = userInfoService.getById(currentUser.getId());
        Long currentUseSpace = fileInfoService.getUseSpace(currentUser.getId());
        if (currentUserInfo.getTotalSpace() - currentUseSpace < needSpace) {
            throw new BizException(com.swx.common.pojo.ResultCode.OUT_OF_SPACE);
        }

        copySharedFileRecursive(share.getUserId(), targetFile, currentUser.getId(), targetFolderId);
        increaseSaveCount(share);

        Long newUseSpace = fileInfoService.getUseSpace(currentUser.getId());
        UserInfo updateInfo = new UserInfo();
        updateInfo.setId(currentUser.getId());
        updateInfo.setUseSpace(newUseSpace);
        userInfoService.updateById(updateInfo);

        UserSpaceDTO userSpaceDTO = new UserSpaceDTO();
        userSpaceDTO.setUseSpace(newUseSpace);
        userSpaceDTO.setTotalSpace(currentUserInfo.getTotalSpace());
        redisComponent.saveUserSpaceUse(currentUser.getId(), userSpaceDTO);
    }

    private WebShareInfoVO buildWebShareInfo(FileShare share, FileInfo fileInfo, UserInfo userInfo, boolean codeChecked) {
        WebShareInfoVO vo = new WebShareInfoVO();
        vo.setShareId(share.getId());
        vo.setFileId(fileInfo.getId());
        vo.setUserId(userInfo.getId());
        vo.setNickname(userInfo.getNickname());
        vo.setAvatar(userInfo.getQqAvatar());
        vo.setFilename(fileInfo.getFilename());
        vo.setFileCover(fileInfo.getFileCover());
        vo.setFolderType(fileInfo.getFolderType());
        vo.setFileCategory(fileInfo.getFileCategory());
        vo.setFileType(fileInfo.getFileType());
        vo.setStatus(fileInfo.getStatus());
        vo.setExpireTime(share.getExpireTime());
        vo.setNeedCode(share.getCode() != null && !share.getCode().trim().isEmpty());
        vo.setCodeChecked(codeChecked);
        return vo;
    }

    private FileShare getValidShare(String shareId) {
        FileShare share = fileShareService.getById(shareId);
        if (share == null) {
            throw new BizException("分享链接不存在");
        }
        if (share.getExpireTime() != null && !share.getExpireTime().isAfter(LocalDateTime.now())) {
            throw new BizException("分享链接已失效");
        }
        return share;
    }

    private FileInfo getValidSharedRootFile(FileShare share) {
        FileInfo fileInfo = fileInfoService.getOne(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getId, share.getFileId())
                .eq(FileInfo::getUserId, share.getUserId())
                .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag()));
        if (fileInfo == null) {
            throw new BizException("分享文件不存在");
        }
        return fileInfo;
    }

    private UserInfo getShareUser(String userId) {
        UserInfo userInfo = userInfoService.getById(userId);
        if (userInfo == null) {
            throw new BizException("分享用户不存在");
        }
        return userInfo;
    }

    private boolean hasShareAccess(HttpSession session, FileShare share) {
        return Boolean.TRUE.equals(session.getAttribute(SHARE_SESSION_PREFIX + share.getId()));
    }

    private void grantShareAccess(HttpSession session, String shareId) {
        session.setAttribute(SHARE_SESSION_PREFIX + shareId, true);
    }

    private FileShare validateShareAccess(HttpSession session, String shareId) {
        FileShare share = getValidShare(shareId);
        if (share.getCode() != null && !share.getCode().trim().isEmpty() && !hasShareAccess(session, share)) {
            throw new BizException("请先输入提取码");
        }
        if ((share.getCode() == null || share.getCode().trim().isEmpty()) && !hasShareAccess(session, share)) {
            grantShareAccess(session, shareId);
        }
        return share;
    }

    private void assertFileInShareScope(FileInfo rootFile, String targetId, String userId) {
        if (rootFile.getId().equals(targetId)) {
            return;
        }
        FileInfo current = fileInfoService.getOne(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getId, targetId)
                .eq(FileInfo::getUserId, userId)
                .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag()));
        if (current == null) {
            log.warn("share scope file missing, rootFileId={}, targetId={}, userId={}", rootFile.getId(), targetId, userId);
            throw new BizException("文件不存在");
        }
        Set<String> visited = new HashSet<>();
        while (current != null && visited.add(current.getId())) {
            if (rootFile.getId().equals(current.getFilePid())) {
                return;
            }
            current = fileInfoService.getOne(new LambdaQueryWrapper<FileInfo>()
                    .eq(FileInfo::getId, current.getFilePid())
                    .eq(FileInfo::getUserId, userId)
                    .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag()));
        }
        log.warn("share scope denied, rootFileId={}, targetId={}, userId={}", rootFile.getId(), targetId, userId);
        throw new BizException("无权访问该文件");
    }

    private IPage<FileInfoVO> emptyFilePage(Page<FileInfo> pageParam) {
        Page<FileInfoVO> page = new Page<>(pageParam.getCurrent(), pageParam.getSize(), 0);
        page.setRecords(Collections.emptyList());
        return page;
    }

    private void increaseBrowseCount(FileShare share) {
        FileShare updateInfo = new FileShare();
        updateInfo.setId(share.getId());
        updateInfo.setBrowseCount((share.getBrowseCount() == null ? 0 : share.getBrowseCount()) + 1);
        fileShareService.updateById(updateInfo);
        share.setBrowseCount(updateInfo.getBrowseCount());
    }

    private void increaseDownloadCount(FileShare share) {
        FileShare updateInfo = new FileShare();
        updateInfo.setId(share.getId());
        updateInfo.setDownloadCount((share.getDownloadCount() == null ? 0 : share.getDownloadCount()) + 1);
        fileShareService.updateById(updateInfo);
    }

    private void increaseSaveCount(FileShare share) {
        FileShare updateInfo = new FileShare();
        updateInfo.setId(share.getId());
        updateInfo.setSaveCount((share.getSaveCount() == null ? 0 : share.getSaveCount()) + 1);
        fileShareService.updateById(updateInfo);
    }

    private void assertTargetFolder(String userId, String targetFolderId) {
        if ("0".equals(targetFolderId)) {
            return;
        }
        FileInfo folder = fileInfoService.getOne(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getId, targetFolderId)
                .eq(FileInfo::getUserId, userId)
                .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag())
                .eq(FileInfo::getFolderType, FileFolderTypeEnums.FOLDER.getType()));
        if (folder == null) {
            throw new BizException("目标目录不存在");
        }
    }

    private long calculateNeedSpace(String shareUserId, FileInfo sourceFile) {
        if (FileFolderTypeEnums.FILE.getType().equals(sourceFile.getFolderType())) {
            return sourceFile.getFileSize() == null ? 0L : sourceFile.getFileSize();
        }
        List<FileInfo> children = fileInfoService.list(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getUserId, shareUserId)
                .eq(FileInfo::getFilePid, sourceFile.getId())
                .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag()));
        long total = 0L;
        for (FileInfo child : children) {
            total += calculateNeedSpace(shareUserId, child);
        }
        return total;
    }

    private void copySharedFileRecursive(String sourceUserId, FileInfo sourceFile, String targetUserId, String targetFolderId) {
        if (FileFolderTypeEnums.FILE.getType().equals(sourceFile.getFolderType())) {
            FileUploadDTO uploadDTO = new FileUploadDTO();
            uploadDTO.setFilePid(targetFolderId);
            uploadDTO.setFilename(sourceFile.getFilename());
            uploadDTO.setFileMd5(sourceFile.getFileMd5());
            FileInfo copySourceFile = new FileInfo();
            BeanUtils.copyProperties(sourceFile, copySourceFile);
            fileInfoService.saveFileInfoFromFile(targetUserId, StringTools.getRandomString(Constants.LENGTH_10), uploadDTO, copySourceFile);
            return;
        }

        NewFolderDTO folderDTO = new NewFolderDTO();
        folderDTO.setFilePid(targetFolderId);
        folderDTO.setFilename(sourceFile.getFilename());
        FileInfoVO folder = fileInfoService.newFolder(folderDTO, targetUserId);

        List<FileInfo> children = fileInfoService.list(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getUserId, sourceUserId)
                .eq(FileInfo::getFilePid, sourceFile.getId())
                .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag())
                .orderByDesc(FileInfo::getFolderType)
                .orderByAsc(FileInfo::getFilename));
        for (FileInfo child : children) {
            copySharedFileRecursive(sourceUserId, child, targetUserId, folder.getId());
        }
    }


}

