package com.swx.easypan.service.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swx.common.pojo.BizException;
import com.swx.common.pojo.ResultCode;
import com.swx.easypan.entity.config.AppConfig;
import com.swx.easypan.entity.constants.Constants;
import com.swx.easypan.entity.dto.FileUploadDTO;
import com.swx.easypan.entity.dto.UserSpaceDTO;
import com.swx.easypan.entity.enums.FileDelFlagEnums;
import com.swx.easypan.entity.enums.FileFolderTypeEnums;
import com.swx.easypan.entity.enums.FileStatusEnums;
import com.swx.easypan.entity.enums.UploadStatusEnums;
import com.swx.easypan.entity.vo.UploadResultVO;
import com.swx.easypan.mq.producer.FileCleanupProducer;
import com.swx.easypan.mq.producer.FileProcessProducer;
import com.swx.easypan.pojo.FileInfo;
import com.swx.easypan.pojo.UserInfo;
import com.swx.easypan.redis.RedisComponent;
import com.swx.easypan.service.FileInfoService;
import com.swx.easypan.service.UserInfoService;
import com.swx.easypan.service.impl.FileInfoServiceImpl;
import com.swx.easypan.utils.StringTools;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class UserFileServiceImpl implements UserFileService {

    private final RedisComponent redisComponent;
    private final FileInfoService fileInfoService;
    private final UserInfoService userInfoService;
    private final AppConfig appConfig;
    private final FileInfoServiceImpl fileInfoServiceImpl;
    private final FileProcessProducer fileProcessProducer;
    private final FileCleanupProducer fileCleanupProducer;

    public UserFileServiceImpl(RedisComponent redisComponent, FileInfoService fileInfoService, UserInfoService userInfoService,
                               AppConfig appConfig, FileInfoServiceImpl fileInfoServiceImpl, FileProcessProducer fileProcessProducer,
                               FileCleanupProducer fileCleanupProducer) {
        this.redisComponent = redisComponent;
        this.fileInfoService = fileInfoService;
        this.userInfoService = userInfoService;
        this.appConfig = appConfig;
        this.fileInfoServiceImpl = fileInfoServiceImpl;
        this.fileProcessProducer = fileProcessProducer;
        this.fileCleanupProducer = fileCleanupProducer;
    }

    /**
     * 获取用户空间使用情况。
     *
     * @param id 用户ID
     */
    @Override
    public UserSpaceDTO getUseSpace(String id) {
        UserSpaceDTO spaceDTO = redisComponent.getUserSpaceUse(id);
        if (null == spaceDTO) {
            spaceDTO = new UserSpaceDTO();
            Long useSpace = fileInfoService.getUseSpace(id);
            UserInfo userInfo = userInfoService.getById(id);
            spaceDTO.setUseSpace(useSpace);
            spaceDTO.setTotalSpace(userInfo.getTotalSpace());
            redisComponent.saveUserSpaceUse(id, spaceDTO);
        }
        return spaceDTO;
    }

    /**
     * 文件上传，支持分片上传和秒传。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UploadResultVO uploadFile(String userId, MultipartFile file, FileUploadDTO fileDTO) throws IOException {
        UploadResultVO resultVO = new UploadResultVO();
        File tempFileFolder = null;
        try {
            String fileId = fileDTO.getId();
            if (!StringUtils.hasText(fileId)) {
                fileId = StringTools.getRandomString(Constants.LENGTH_10);
            }
            resultVO.setId(fileId);

            UserSpaceDTO userSpaceUse = redisComponent.getUserSpaceUse(userId);
            if (userSpaceUse == null) {
                userSpaceUse = getUseSpace(userId);
            }
            if (fileDTO.getChunkIndex() == 0) {
                List<FileInfo> dbFileList = fileInfoService.list(new LambdaQueryWrapper<FileInfo>()
                        .eq(FileInfo::getFileMd5, fileDTO.getFileMd5())
                        .eq(FileInfo::getStatus, FileStatusEnums.USING.getStatus()));
                if (!dbFileList.isEmpty()) {
                    FileInfo dbFile = dbFileList.get(0);
                    if (dbFile.getFileSize() + userSpaceUse.getUseSpace() > userSpaceUse.getTotalSpace()) {
                        throw new BizException(ResultCode.OUT_OF_SPACE);
                    }
                    boolean save = fileInfoService.saveFileInfoFromFile(userId, fileId, fileDTO, dbFile);
                    if (!save) {
                        throw new BizException("文件保存失败");
                    }
                    resultVO.setStatus(UploadStatusEnums.UPLOAD_SECONDS.getCode());
                    updateUserSpace(userId, dbFile.getFileSize(), userSpaceUse);
                    return resultVO;
                }
            }

            Long currentTempSize = redisComponent.getFileTempSize(userId, fileId);
            if (file.getSize() + currentTempSize + userSpaceUse.getUseSpace() > userSpaceUse.getTotalSpace()) {
                throw new BizException(ResultCode.OUT_OF_SPACE);
            }
            String tempFolderName = appConfig.getProjectFolder() + Constants.FILE_FOLDER_TEMP;
            String currentUserFolderName = userId + fileId;

            tempFileFolder = new File(tempFolderName + currentUserFolderName);
            if (!tempFileFolder.exists()) {
                tempFileFolder.mkdirs();
            }
            File newFile = new File(tempFileFolder.getPath() + "/" + fileDTO.getChunkIndex());
            file.transferTo(newFile);
            if (fileDTO.getChunkIndex() < fileDTO.getChunks() - 1) {
                resultVO.setStatus(UploadStatusEnums.UPLOADING.getCode());
                redisComponent.saveFileTempSize(userId, fileId, file.getSize());
                return resultVO;
            }
            redisComponent.saveFileTempSize(userId, fileId, file.getSize());
            fileInfoService.saveFileInfo(userId, fileId, fileDTO);

            Long totalSize = redisComponent.getFileTempSize(userId, fileId);
            updateUserSpace(userId, totalSize, userSpaceUse);
            resultVO.setStatus(UploadStatusEnums.UPLOAD_FINISH.getCode());

            String finalFileId = fileId;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        fileProcessProducer.sendFileProcessMessage(finalFileId, userId);
                    } catch (Exception e) {
                        fileInfoServiceImpl.transferFile(finalFileId, userId);
                    }
                }
            });
            return resultVO;
        } catch (Exception e) {
            if (tempFileFolder != null) {
                FileUtils.deleteDirectory(tempFileFolder);
            }
            throw e;
        }
    }

    /**
     * 回收站彻底删除。
     * <p>
     * 这里只先删除数据库记录；物理文件需要在数据库中确认没有任何 file_info 记录再引用同一 file_md5 后，
     * 才会在事务提交后投递 RabbitMQ 异步清理任务。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delFileBatch(String userId, String ids, Boolean isAdmin) {
        List<String> idList = Arrays.asList(ids.split(","));
        LambdaQueryWrapper<FileInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileInfo::getUserId, userId)
                .eq(FileInfo::getDeleted, FileDelFlagEnums.RECYCLE.getFlag())
                .in(!idList.isEmpty(), FileInfo::getId, idList);
        List<FileInfo> fileInfoList = fileInfoService.list(wrapper);

        // 候选物理文件列表：这里只收集真实文件记录，文件夹本身没有对应的磁盘文件。
        List<FileInfo> deletingPhysicalFiles = new ArrayList<>();

        // 被彻底删除的文件夹及其子文件夹ID；后续用 file_pid in (...) 找到子树里的文件记录。
        List<String> delFileSubFolderPidList = new ArrayList<>();
        for (FileInfo fileInfo : fileInfoList) {
            if (FileFolderTypeEnums.FOLDER.getType().equals(fileInfo.getFolderType())) {
                fileInfoServiceImpl.findAllSubFolderList(delFileSubFolderPidList, userId, fileInfo.getId(), FileDelFlagEnums.DEL.getFlag());
            } else if (FileFolderTypeEnums.FILE.getType().equals(fileInfo.getFolderType())) {
                // 用户直接在回收站选中的普通文件，也是物理清理候选。
                deletingPhysicalFiles.add(fileInfo);
            }
        }
        if (!delFileSubFolderPidList.isEmpty()) {
            // 用户选中的是文件夹时，补充收集该文件夹子树下所有 DEL 状态的普通文件。
            deletingPhysicalFiles.addAll(fileInfoService.list(new LambdaQueryWrapper<FileInfo>()
                    .eq(FileInfo::getUserId, userId)
                    .eq(FileInfo::getDeleted, FileDelFlagEnums.DEL.getFlag())
                    .eq(FileInfo::getFolderType, FileFolderTypeEnums.FILE.getType())
                    .in(FileInfo::getFilePid, delFileSubFolderPidList)));
        }

        // 先删除子树中的 DEL 记录，再删除回收站顶层记录；此处只删除数据库记录，不碰磁盘。
        if (!delFileSubFolderPidList.isEmpty()) {
            fileInfoService.delFileBatch(userId, delFileSubFolderPidList, null, isAdmin ? null : FileDelFlagEnums.DEL.getFlag());
        }
        fileInfoService.delFileBatch(userId, null, idList, isAdmin ? null : FileDelFlagEnums.RECYCLE.getFlag());

        // 数据库删除执行后再做引用判断：如果同一个 file_md5 仍有任何 file_info 记录，说明磁盘文件仍被秒传/其它用户引用。
        List<FileInfo> cleanupFiles = findUnreferencedPhysicalFiles(deletingPhysicalFiles);
        if (!cleanupFiles.isEmpty()) {
            // 事务提交后再投递清理任务，避免事务回滚但物理文件已被删除。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    for (FileInfo fileInfo : cleanupFiles) {
                        fileCleanupProducer.sendFileCleanupMessage(fileInfo);
                    }
                }
            });
        }

        Long useSpace = fileInfoService.getUseSpace(userId);
        UserInfo userInfo = new UserInfo();
        userInfo.setId(userId);
        userInfo.setUseSpace(useSpace);
        userInfoService.updateById(userInfo);

        UserSpaceDTO userSpaceUse = redisComponent.getUserSpaceUse(userId);
        userSpaceUse.setUseSpace(useSpace);
        redisComponent.saveUserSpaceUse(userId, userSpaceUse);
    }

    /**
     * 根据 file_md5 判断物理文件是否仍被引用。
     * <p>
     * 注意：这个方法在数据库删除语句执行之后、事务提交之前调用；同一事务内可以看到本次删除后的记录状态。
     */
    private List<FileInfo> findUnreferencedPhysicalFiles(List<FileInfo> deletingPhysicalFiles) {
        Map<String, FileInfo> md5FileMap = new LinkedHashMap<>();
        for (FileInfo fileInfo : deletingPhysicalFiles) {
            if (fileInfo == null
                    || !StringUtils.hasText(fileInfo.getFileMd5())
                    || !StringUtils.hasText(fileInfo.getFilePath())
                    || !FileFolderTypeEnums.FILE.getType().equals(fileInfo.getFolderType())) {
                continue;
            }
            // 同一批删除中可能有多个记录引用同一 file_md5，只需要投递一次物理清理任务。
            md5FileMap.putIfAbsent(fileInfo.getFileMd5(), fileInfo);
        }

        List<FileInfo> cleanupFiles = new ArrayList<>();
        for (Map.Entry<String, FileInfo> entry : md5FileMap.entrySet()) {
            // 不过滤 deleted 状态：正常、回收站、DEL 子项都表示仍然引用这份磁盘文件。
            int referenceCount = fileInfoService.count(new LambdaQueryWrapper<FileInfo>()
                    .eq(FileInfo::getFileMd5, entry.getKey()));
            if (referenceCount == 0) {
                cleanupFiles.add(entry.getValue());
            }
        }
        return cleanupFiles;
    }

    /**
     * 更新用户空间，累加文件大小。
     *
     * @param userId       用户ID
     * @param fileSize     文件大小
     * @param userSpaceDTO Redis中的用户空间信息
     */
    private void updateUserSpace(String userId, Long fileSize, UserSpaceDTO userSpaceDTO) {
        Boolean update = userInfoService.updateUserSpace(userId, fileSize, null);
        if (!update) {
            throw new BizException(ResultCode.OUT_OF_SPACE);
        }
        userSpaceDTO.setUseSpace(userSpaceDTO.getUseSpace() + fileSize);
        redisComponent.saveUserSpaceUse(userId, userSpaceDTO);
    }
}
