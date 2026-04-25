package com.swx.easypan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.swx.common.pojo.BizException;
import com.swx.common.pojo.ResultCode;
import com.swx.easypan.entity.config.AppConfig;
import com.swx.easypan.entity.config.RecycleConfig;
import com.swx.easypan.entity.constants.Constants;
import com.swx.easypan.entity.dto.*;
import com.swx.easypan.entity.enums.*;
import com.swx.easypan.entity.query.FileInfoQuery;
import com.swx.easypan.entity.vo.FileInfoVO;
import com.swx.easypan.pojo.FileInfo;
import com.swx.easypan.mapper.FileInfoMapper;
import com.swx.easypan.redis.RedisComponent;
import com.swx.easypan.service.FileInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.swx.easypan.utils.FfmpegUtil;
import com.swx.easypan.utils.StringTools;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.BeanUtils;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 文件信息表 服务实现类
 * </p>
 *
 * @author sw-code
 * @since 2023-05-19
 */
@Slf4j
@Service
public class FileInfoServiceImpl extends ServiceImpl<FileInfoMapper, FileInfo> implements FileInfoService {

    private final AppConfig appConfig;
    private final RedisComponent redisComponent;
    private final RecycleConfig recycleConfig;

    public FileInfoServiceImpl(AppConfig appConfig, RedisComponent redisComponent, RecycleConfig recycleConfig) {
        this.appConfig = appConfig;
        this.redisComponent = redisComponent;
        this.recycleConfig = recycleConfig;
    }

    /**
     * 分页查询文件信息
     * @param pageParam
     * @param query
     * @return
     */
    @Override
    public IPage<FileInfoVO> pageInfo(Page<FileInfo> pageParam, FileInfoQuery query) {
        IPage<FileInfo> iPage = baseMapper.selectPageInfo(pageParam, query);
        List<FileInfo> records = iPage.getRecords();
        List<FileInfoVO> fileInfoVOS = records.stream().map(item -> {
            FileInfoVO fileInfoVO = new FileInfoVO();
            BeanUtils.copyProperties(item, fileInfoVO);
            return fileInfoVO;
        }).collect(Collectors.toList());
        IPage<FileInfoVO> page = new Page<FileInfoVO>(pageParam.getCurrent(), pageParam.getSize(), iPage.getTotal());
        page.setRecords(fileInfoVOS);
        return page;
    }

    /**
     * 查询用户使用的空间
     *
     * @param userId 用户ID
     */
    @Override
    public Long getUseSpace(String userId) {
        return baseMapper.selectUseSpace(userId);
    }

    /**
     * 保存文件信息
     * @param userId  文件用户ID
     * @param fileId  文件ID
     * @param fileDTO 文件信息
     * @return
     */
    @Override
    public boolean saveFileInfo(String userId, String fileId, FileUploadDTO fileDTO) {
        // 获取当前年月作为文件目录
        String month = DateFormatUtils.format(new Date(), DateTimePatternEnum.YYYYMM.getPattern());
        //获取文件后缀
        String fileSuffix = StringTools.getFileSuffix(fileDTO.getFilename());
        //获取文件类型
        FileTypeEnums fileTypeEnums = FileTypeEnums.getBySuffix(fileSuffix);
        String realFileName = userId + fileId + fileSuffix;
        FileInfo fileInfo = new FileInfo();
        //新上传”必然是正常态：saveFileInfo 是新建记录，数据库字段默认应是“使用中”（0），所有不用手动赋值
        fileInfo.setId(fileId);
        fileInfo.setUserId(userId);
        fileInfo.setFileMd5(fileDTO.getFileMd5());
        fileInfo.setFilename(autoRename(fileDTO.getFilePid(), userId, fileDTO.getFilename()));
        fileInfo.setFilePath(month + "/" + realFileName);
        fileInfo.setFilePid(fileDTO.getFilePid());
        fileInfo.setFileCategory(fileTypeEnums.getCategory().getCategory());
        fileInfo.setFileType(fileTypeEnums.getType());
        fileInfo.setStatus(FileStatusEnums.TRANSFER.getStatus());
        fileInfo.setFolderType(FileFolderTypeEnums.FILE.getType());
        return save(fileInfo);
    }

    /**
     * 秒传/复用已有文件
     * @param userId  当前用户
     * @param fileId  当前文件ID
     * @param fileDTO 当前文件信息
     * @param dbFile  已存在文件信息
     * @return
     */
    @Override
    public boolean saveFileInfoFromFile(String userId, String fileId, FileUploadDTO fileDTO, FileInfo dbFile) {
        dbFile.setId(fileId);
        dbFile.setFilePid(fileDTO.getFilePid());
        dbFile.setUserId(userId);
        dbFile.setStatus(FileStatusEnums.USING.getStatus());
        dbFile.setFileMd5(fileDTO.getFileMd5());
        //复用dbFile对象，dbFile很可能来自历史记录，deleted 可能是回收站/已删除态，所以必须显式 setDeleted(USING) 做“恢复”
        dbFile.setDeleted(FileDelFlagEnums.USING.getFlag());
        dbFile.setFilename(autoRename(fileDTO.getFilePid(), userId, fileDTO.getFilename()));
        return save(dbFile);
    }

    /**
     * 获取文件路径
     *
     * @param id     文件ID
     * @param userId 用户ID
     */
    @Override
    public String getFilePath(String id, String userId) {
        String filePath = null;
        if (id.endsWith(".ts")) {
            String[] tsArray = id.split("_");
            String realFileId = tsArray[0];
            FileInfo fileInfo = this.getOne(new LambdaQueryWrapper<FileInfo>()
                    .eq(FileInfo::getId, realFileId)
                    .eq(FileInfo::getUserId, userId)
                    .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag()));
            String fileName = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + fileInfo.getFilePath();
            String folderPath = StringTools.getFilename(fileName);
            filePath = folderPath + "/" + id;
        } else {
            FileInfo fileInfo = this.getOne(new LambdaQueryWrapper<FileInfo>()
                    .eq(FileInfo::getId, id)
                    .eq(FileInfo::getUserId, userId)
                    .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag()));
            if (null == fileInfo) {
                throw new BizException("文件不存在");
            }
            if (FileCategoryEnums.VIDEO.getCategory().equals(fileInfo.getFileCategory())) {
                String fileName = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + fileInfo.getFilePath();
                String folderPath = StringTools.getFilename(fileName);
                filePath = folderPath + "/" + Constants.M3U8_NAME;
            } else {
                filePath = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + fileInfo.getFilePath();
            }
        }
        return filePath;
    }

    /**
     * 新建文件夹
     * @param folderDTO 目录信息
     * @param userId    用户ID
     * @return
     */
    @Override
    public FileInfoVO newFolder(NewFolderDTO folderDTO, String userId) {
        // 校验文件夹名
        String rename = autoRename(folderDTO.getFilePid(), userId, folderDTO.getFilename());
        // 构造属性
        FileInfo fileInfo = new FileInfo();
        fileInfo.setId(StringTools.getRandomString(Constants.LENGTH_10));
        fileInfo.setUserId(userId);
        fileInfo.setFilename(rename);
        fileInfo.setFilePid(folderDTO.getFilePid());
        fileInfo.setFolderType(FileFolderTypeEnums.FOLDER.getType());
        fileInfo.setStatus(FileStatusEnums.USING.getStatus());
        // 保存
        this.save(fileInfo);
        // 返回
        FileInfoVO fileInfoVO = new FileInfoVO();
        BeanUtils.copyProperties(fileInfo, fileInfoVO);
        return fileInfoVO;
    }

    /**
     * 根据ID列表查询目录信息
     *
     * @param ids ids
     */
    @Override
    public List<FileInfoVO> listFolderByIds(String[] ids) {
        LambdaQueryWrapper<FileInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(FileInfo::getId, FileInfo::getFilename)
                .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag())
                .in(FileInfo::getId, Arrays.asList(ids))
                .last("order by field(id, \"" + StringUtils.join(ids, "\",\"") + "\")");
        List<FileInfo> list = list(wrapper);
        List<FileInfoVO> fileInfoVOS = list.stream().map(item -> {
            FileInfoVO fileInfoVO = new FileInfoVO();
            BeanUtils.copyProperties(item, fileInfoVO);
            return fileInfoVO;
        }).collect(Collectors.toList());
        return fileInfoVOS;
    }

    /**
     * 文件重命名
     *
     * @param userId        用户ID
     * @param renameFileDTO 新文件信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileInfoVO rename(String userId, RenameFileDTO renameFileDTO) {
        FileInfo fileInfo = getOne(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getId, renameFileDTO.getId())
                .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag())
                .eq(FileInfo::getUserId, userId));
        if (null == fileInfo) {
            throw new BizException("文件不存在");
        }
        String filename = renameFileDTO.getFilename();
        if (FileFolderTypeEnums.FILE.getType().equals(fileInfo.getFolderType())) {
            filename = filename + StringTools.getFileSuffix(fileInfo.getFilename());
        }
        String rename = autoRename(fileInfo.getFilePid(), userId, filename);
        FileInfo dbFile = new FileInfo();
        dbFile.setFilename(rename);
        Boolean update = updateByMultiId(dbFile, renameFileDTO.getId(), userId);
        if (update) {
            fileInfo.setFilename(rename);
            fileInfo.setUpdateTime(LocalDateTime.now());
        }
        FileInfoVO fileInfoVO = new FileInfoVO();
        BeanUtils.copyProperties(fileInfo, fileInfoVO);
        return fileInfoVO;
    }

    /**
     * 移动文件
     * 把一批选中的文件/文件夹移动到目标目录 filePid 下，并在目标目录已存在同名时给被移动项自动改名以避免冲突。
     * @param userId      用户ID
     * @param moveFileDTO 移动文件信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeFileFolder(String userId, MoveFileDTO moveFileDTO) {
        //1.读取参数
        String filePid = moveFileDTO.getFilePid();
        String ids = moveFileDTO.getIds();

        //2.把选中的ID（ids）拆分成List，去掉空字符串，去重
        List<String> idList = Arrays.stream(ids.split(","))
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.toList());

        //3.基础校验:目标目录不能是本次选中的任意条目（文件/文件夹）
        if (idList.contains(filePid)) {
            throw new BizException("不能将文件移动到自身目录下");
        }
        //4.如果目标目录不是根目录，校验目标目录合法性
        if (!Constants.ZERO_STR.equals(filePid)) {
            // 不在根目录
            FileInfo targetFolder = getByMultiId(filePid, userId);
            if (null == targetFolder
                    || !FileDelFlagEnums.USING.getFlag().equals(targetFolder.getDeleted())
                    // 目标目录必须是文件夹
                    || !FileFolderTypeEnums.FOLDER.getType().equals(targetFolder.getFolderType())) {
                throw new BizException("正在尝试非法移动");
            }
        }
        //5.校验：选中的“文件夹”不能被移动到其子孙目录下（否则会形成环）
        validateNotMoveFolderIntoDescendant(userId, filePid, idList);
        //6.预取目标目录下已有名称，用于判重
        List<FileInfo> dbFile = list(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getFilePid, filePid)
                .eq(FileInfo::getUserId, userId)
                .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag()));
        //把目标目录下的文件列表转换成Map，key是filename，value是FileInfo对象，方便后续查重时获取同名文件信息
        Map<String, FileInfo> dbFilenameMap = new HashMap<>();
        for (FileInfo fileInfo : dbFile) {
            // 同名时覆盖旧值
            dbFilenameMap.put(fileInfo.getFilename(),fileInfo);
        }
        //7.查询要移动的条目
        LambdaQueryWrapper<FileInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileInfo::getUserId, userId)
                .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag())
                .in(StringUtils.isNotEmpty(ids), FileInfo::getId, Arrays.asList(ids.split(",")));
        List<FileInfo> selectFileList = list(wrapper);

        //8.逐个移动并处理重名
        for (FileInfo item : selectFileList) {
            FileInfo rootFileInfo = dbFilenameMap.get(item.getFilename());
            // 文件名已存在，重命名被还原的文件名
            FileInfo updateInfo = new FileInfo();
            if (null != rootFileInfo) {
                String rename = StringTools.rename(item.getFilename());
                updateInfo.setFilename(rename);
            }
            updateInfo.setFilePid(filePid);
            updateByMultiId(updateInfo, item.getId(), userId);
        }
    }


    /**
     * 校验：选中的“文件夹”不能被移动到其子孙目录下（否则会形成环）。
     */
    private void validateNotMoveFolderIntoDescendant(String userId, String targetPid, List<String> selectedIds) {

        if (Constants.ZERO_STR.equals(targetPid) || selectedIds == null || selectedIds.isEmpty()) {
            return;
        }

        // 只取选中的文件夹ID
        List<FileInfo> selected = list(new LambdaQueryWrapper<FileInfo>()
                .select(FileInfo::getId, FileInfo::getFolderType)
                .eq(FileInfo::getUserId, userId)
                .in(FileInfo::getId, selectedIds));

        Set<String> selectedFolderIds = selected.stream()
                .filter(f -> FileFolderTypeEnums.FOLDER.getType().equals(f.getFolderType()))
                .map(FileInfo::getId)
                .collect(Collectors.toSet());

        if (selectedFolderIds.isEmpty()) {
            return;
        }

        // 从目标目录开始向上爬父链，若碰到任一被移动的文件夹ID，则非法
        String cur = targetPid;
        Set<String> visited = new HashSet<>();
        while (StringUtils.isNotBlank(cur) && !Constants.ZERO_STR.equals(cur)) {
            if (!visited.add(cur)) {
                throw new BizException("目录结构异常，存在循环引用");
            }
            if (selectedFolderIds.contains(cur)) {
                throw new BizException("不能将文件夹移动到其子目录下");
            }
            FileInfo parent = getByMultiId(cur, userId);
            if (parent == null) {
                break;
            }
            cur = parent.getFilePid();
        }
    }



    /**
     * 创建下载链接
     *
     * @param userId 用户ID
     * @param id     文件ID
     */
    @Override
    public String createDownloadUrl(String userId, String id) {
        // 根据文件id和用户id + 正常文件区 + 可用状态：deleted/status 校验 + 文件类型校验：只能是文件不能是文件夹，查询文件信息
        FileInfo fileInfo = getOne(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getId, id)
                .eq(FileInfo::getUserId, userId)
                .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag())
                .eq(FileInfo::getStatus, FileStatusEnums.USING.getStatus())
                .eq(FileInfo::getFolderType, FileFolderTypeEnums.FILE.getType()));

        // 文件不存在抛异常
        if (fileInfo == null) {
            throw new BizException(ResultCode.PARAM_IS_INVALID);
        }

        //生成一个code
        String code = StringTools.getRandomString(Constants.LENGTH_50);
        //构造下载目标对象，封装code、文件名、文件路径等信息，后续下载接口会用到
        DownloadFileDTO fileDTO = new DownloadFileDTO();
        fileDTO.setCode(code);
        fileDTO.setFilename(fileInfo.getFilename());
        fileDTO.setFilePath(fileInfo.getFilePath());
        // 保存到Redis
        redisComponent.saveDownloadCode(code, fileDTO);
        return code;
    }

    /**
     * 将文件移入回收站
     *
     * @param userId 用户ID
     * @param ids    文件IDS，逗号分隔
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeFile2RecycleBatch(String userId, String ids) {

        //1.查出用户选中的正常文件
        LambdaQueryWrapper<FileInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileInfo::getUserId, userId)
                .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag())
                .in(StringUtils.isNotEmpty(ids), FileInfo::getId, Arrays.asList(ids.split(",")));
        List<FileInfo> dbFileList = list(wrapper);
        //1.1 如果查不到，直接返回：
        if (dbFileList.isEmpty()) {
            return;
        }

        //2.如果选中项里有文件夹，递归收集它下面所有正常子项 ID。
        //2.1 存储递归查找所有子项ID的列表
        ArrayList<String> delFileIdList = new ArrayList<>();
        //2.2 如果选中项里有文件夹，递归收集它下面所有正常子项 ID（包括子文件和子文件夹），但不会把选中项本身加入这个列表
        for (FileInfo fileInfo : dbFileList) {
            //2.3 选中项是文件夹，递归查找它下面的所有正常子项 ID
            if (FileFolderTypeEnums.FOLDER.getType().equals(fileInfo.getFolderType())) {
                findAllSubFileList(delFileIdList, userId, fileInfo.getId(), FileDelFlagEnums.USING.getFlag());
            }
        }

        //3.把收集到的子项ID列表批量更新为已删除（deleted置为2），但不更新选中项本身
        if (!delFileIdList.isEmpty()) {
            FileInfo delFileInfo = new FileInfo();
            delFileInfo.setDeleted(FileDelFlagEnums.DEL.getFlag());

            baseMapper.updateFileDelFlagBatch(
                    delFileInfo,
                    userId,
                    null,
                    delFileIdList,
                    FileDelFlagEnums.USING.getFlag()
            );
        }

        //4.把选中的顶层项更新为回收站，即deleted置为1
        List<String> recIds = Arrays.asList(ids.split(","));
        FileInfo recFileInfo = new FileInfo();
        recFileInfo.setDeleted(FileDelFlagEnums.RECYCLE.getFlag());
        recFileInfo.setRecoveryTime(LocalDateTime.now());
        baseMapper.updateFileDelFlagBatch(
                recFileInfo,
                userId,
                null,
                recIds,
                FileDelFlagEnums.USING.getFlag()
        );
    }

    /**
     * 递归收集指定文件夹及其所有子文件夹 ID。
     * <p>
     * 这个方法只收集文件夹 ID，不收集普通文件 ID。恢复文件夹时会用这些文件夹 ID 作为
     * file_pid 条件，批量恢复挂在这些目录下面的 DEL 子项。
     *
     * @param idList  收集结果，包含当前文件夹 ID 和所有子文件夹 ID
     * @param userId  当前用户 ID
     * @param id      当前要遍历的文件夹 ID
     * @param delFlag 需要过滤的删除状态；传 null 表示遍历时不按 deleted 状态过滤
     */
    public void findAllSubFolderList(List<String> idList, String userId, String id, Integer delFlag) {
        findAllSubFolderList(idList, userId, id, delFlag, new HashSet<>());
    }

    private void findAllSubFolderList(List<String> idList, String userId, String id, Integer delFlag, Set<String> visited) {
        // 防止脏数据形成父子循环时递归死循环。
        if (!visited.add(id)) {
            return;
        }
        // 当前 id 本身也是一个文件夹，恢复其直接子项时需要用 file_pid = 当前 id 匹配。
        idList.add(id);

        // 只查当前目录下的子文件夹；普通文件不需要加入 pid 列表。
        LambdaQueryWrapper<FileInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileInfo::getUserId, userId)
                .eq(FileInfo::getFilePid, id)
                .eq(delFlag != null, FileInfo::getDeleted, delFlag)
                .eq(FileInfo::getFolderType, FileFolderTypeEnums.FOLDER.getType());
        List<FileInfo> fileInfoList = list(wrapper);
        for (FileInfo fileInfo : fileInfoList) {
            findAllSubFolderList(idList, userId, fileInfo.getId(), delFlag, visited);
        }
    }

    /**
     * 递归收集指定文件夹下面的所有子项 ID，包括子文件和子文件夹。
     * @param idList 收集结果：包含所有子文件和子文件夹 ID，但不包含当前文件夹 ID
     * @param userId
     * @param id
     * @param delFlag
     */
    private void findAllSubFileList(List<String> idList, String userId, String id, Integer delFlag) {
        findAllSubFileList(idList, userId, id, delFlag, new HashSet<>());
    }

    private void findAllSubFileList(List<String> idList, String userId, String id, Integer delFlag, Set<String> visited) {
        if (!visited.add(id)) {
            return;
        }
        LambdaQueryWrapper<FileInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileInfo::getUserId, userId)
                .eq(FileInfo::getFilePid, id)
                .eq(delFlag != null, FileInfo::getDeleted, delFlag);
        List<FileInfo> fileInfoList = list(wrapper);
        for (FileInfo fileInfo : fileInfoList) {
            if (visited.contains(fileInfo.getId())) {
                continue;
            }
            idList.add(fileInfo.getId());
            if (FileFolderTypeEnums.FOLDER.getType().equals(fileInfo.getFolderType())) {
                findAllSubFileList(idList, userId, fileInfo.getId(), delFlag, visited);
            }
        }
    }

    /**
     * 从回收站恢复文件/文件夹。
     * <p>
     * 回收站只展示用户选中的顶层条目：顶层条目 deleted = RECYCLE；
     * 如果顶层条目是文件夹，它下面的子文件/子文件夹在删除时会被标记为 DEL。
     * 恢复文件夹时，需要先恢复其子树中的 DEL 子项，再恢复顶层文件夹本身。
     *
     * @param userId 当前用户 ID
     * @param ids    需要恢复的回收站顶层条目 ID，多个 ID 用逗号分隔
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recoverFileBatch(String userId, String ids) {
        // 1. 拆分前端传入的回收站条目 ID。
        List<String> idList = Arrays.asList(ids.split(","));

        // 2. 只查询当前用户、当前仍处于回收站状态的顶层条目。
        LambdaQueryWrapper<FileInfo> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileInfo::getUserId, userId)
                .eq(FileInfo::getDeleted, FileDelFlagEnums.RECYCLE.getFlag())
                .in(!ids.isEmpty(), FileInfo::getId, idList);
        List<FileInfo> fileInfoList = list(wrapper);

        // 2.1 回收站文件只能在有效期内恢复；过期条目需要等待系统自动清理，不允许再恢复。
        validateRecycleRecoverable(fileInfoList);

        // 3. 如果恢复的是文件夹，收集该文件夹及其所有子文件夹 ID。
        // 后续通过 file_pid in (...) 恢复挂在这些目录下的 DEL 子项。
        ArrayList<String> delFileSubFolderPidList = new ArrayList<>();
        for (FileInfo fileInfo : fileInfoList) {
            if (FileFolderTypeEnums.FOLDER.getType().equals(fileInfo.getFolderType())) {
                findAllSubFolderList(delFileSubFolderPidList, userId, fileInfo.getId(), null);
            }
        }

        // 4. 查询根目录已有正常文件/文件夹，用于处理恢复到根目录后的同名冲突。
        List<FileInfo> rootFileList = list(new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getUserId, userId)
                .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag())
                .eq(FileInfo::getFilePid, Constants.ZERO_STR));
        Map<String, FileInfo> rootFileMap = rootFileList.stream().collect(Collectors.toMap(FileInfo::getFilename, Function.identity(), (a, b) -> b));

        // 5. 先恢复文件夹下面的子项：只把 deleted = DEL 的记录恢复为 USING。
        // 这里使用 pidList，所以会恢复这些文件夹直接挂载的普通文件和子文件夹。
        if (!delFileSubFolderPidList.isEmpty()) {
            FileInfo fileInfo = new FileInfo();
            fileInfo.setDeleted(FileDelFlagEnums.USING.getFlag());
            baseMapper.updateFileDelFlagBatch(fileInfo, userId, delFileSubFolderPidList, null, FileDelFlagEnums.DEL.getFlag());
        }

        // 6. 再恢复用户选中的回收站顶层条目，并把它们放回根目录。
        FileInfo fileInfo = new FileInfo();
        fileInfo.setDeleted(FileDelFlagEnums.USING.getFlag());
        fileInfo.setFilePid(Constants.ZERO_STR);
        fileInfo.setUpdateTime(LocalDateTime.now());
        baseMapper.updateFileDelFlagBatch(fileInfo, userId, null, idList, FileDelFlagEnums.RECYCLE.getFlag());

        // 7. 如果根目录已存在同名正常条目，给恢复出来的顶层条目自动重命名。
        for (FileInfo info : fileInfoList) {
            FileInfo rootFileInfo = rootFileMap.get(info.getFilename());
            if (null != rootFileInfo) {
                FileInfo updateInfo = new FileInfo();
                updateInfo.setFilename(StringTools.rename(info.getFilename()));
                updateByMultiId(updateInfo, info.getId(), userId);
            }
        }
    }

    /**
     * 校验回收站顶层条目是否仍在可恢复期限内。
     */
    private void validateRecycleRecoverable(List<FileInfo> fileInfoList) {
        if (fileInfoList == null || fileInfoList.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (FileInfo fileInfo : fileInfoList) {
            LocalDateTime recoveryTime = fileInfo.getRecoveryTime();
            if (recoveryTime == null) {
                continue;
            }
            if (!recoveryTime.plusDays(recycleConfig.getExpireDays()).isAfter(now)) {
                throw new BizException("文件已超过回收站有效期，无法恢复");
            }
        }
    }

    @Override
    public Boolean delFileBatch(String userId, List<String> delFilePidList, List<String> idList, Integer oldDelFlag) {
        Integer deleted = baseMapper.delFileBatch(userId, delFilePidList, idList, oldDelFlag);
        return deleted != null && deleted > 0;
    }

    //文件合并和转码，异步执行
    public void transferFile(String fileId, String userId) {
        boolean transferSuccess = true;
        String targetFilePath = null;
        String cover = null;
        FileInfo fileInfo = getByMultiId(fileId, userId);
        if (fileInfo == null || !FileStatusEnums.TRANSFER.getStatus().equals(fileInfo.getStatus())) {
            return;
        }
        try {
            // 临时目录
            String tempFolderName = appConfig.getProjectFolder() + Constants.FILE_FOLDER_TEMP;
            String currentUserFolderName = userId + fileId;
            File fileFolder = new File(tempFolderName + currentUserFolderName);
            //获取文件后缀
            String fileSuffix = StringTools.getFileSuffix(fileInfo.getFilename());
            // 获取文件创建时间，格式化年月作为文件目录
            String month = fileInfo.getCreateTime().format(DateTimeFormatter.ofPattern(DateTimePatternEnum.YYYYMM.getPattern()));
            // 目标目录
            String targetFolderName = appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE;
            File targetFolder = new File(targetFolderName + "/" + month);
            if (!targetFolder.exists()) {
                targetFolder.mkdirs();
            }
            // 真实文件名（磁盘上存储的文件名）
            String realFilename = currentUserFolderName + fileSuffix;
            //文件在磁盘上存储的完整路径
            targetFilePath = targetFolder.getPath() + "/" + realFilename;
            // 合并文件
            union(fileFolder.getPath(), targetFilePath, fileInfo.getFilename(), true);
            // 获取文件类型
            Integer fileType = fileInfo.getFileType();
            // 如果文件的类型是视频
            if (FileTypeEnums.VIDEO.getType().equals(fileType)) {
                // 视频文件切割
                cutFile4Video(fileId, targetFilePath);
                // 生成视频缩略图（“缩略图”本质上就是文件在网盘列表、预览页里展示的小封面图。）
                cover = month + "/" + currentUserFolderName + Constants.IMAGE_PNG_SUFFIX;
                String coverPath = targetFolderName + "/" + cover;
                FfmpegUtil.createTargetThumbnail(new File(targetFilePath), Constants.LENGTH_150, new File(coverPath));
            } else if (FileTypeEnums.IMAGE.getType().equals(fileType)) {
                // 图片缩略图
                cover = month + "/" + realFilename.replace(".", "_.");
                String coverPath = targetFolderName + "/" + cover;
                Boolean created = FfmpegUtil.createThumbnailWidthFFmpeg(new File(targetFilePath), Constants.LENGTH_150, new File(coverPath), false);
                if (!created) {
                    FileUtils.copyFile(new File(targetFilePath), new File(coverPath));
                }
            }
        } catch (Exception e) {
            log.error("文件转码失败， 文件ID: {}, userId: {}", fileId, userId, e);
            transferSuccess = false;
        } finally {
            // 更新文件Size和封面
            FileInfo updateInfo = new FileInfo();
            if (targetFilePath == null) {
                updateInfo.setFileSize(0L);
            } else {
                updateInfo.setFileSize(new File(targetFilePath).length());
            }
            updateInfo.setFileCover(cover);
            updateInfo.setStatus(transferSuccess ? FileStatusEnums.USING.getStatus() : FileStatusEnums.TRANSFER_FAIL.getStatus());
            updateByMultiId(updateInfo, fileId, userId);
        }
    }

    /**
     * 多主键更新
     *
     * @param id     文件ID
     * @param userId 用户ID
     */
    private Boolean updateByMultiId(FileInfo fileInfo, String id, String userId) {
        return update(fileInfo, new LambdaUpdateWrapper<FileInfo>().eq(FileInfo::getId, id).eq(FileInfo::getUserId, userId));
    }

    /**
     * 多主键查询
     *
     * @param id     文件ID
     * @param userId 用户ID
     */
    private FileInfo getByMultiId(String id, String userId) {
        return getOne(new LambdaQueryWrapper<FileInfo>().eq(FileInfo::getId, id).eq(FileInfo::getUserId, userId));
    }

    /**
     * 文件合并
     *
     * @param dirPath    分片所在目录
     * @param toFilePath 合并目标文件
     * @param filename   合并文件名
     * @param delSource  是否删除分片文件
     */
    private void union(String dirPath, String toFilePath, String filename, Boolean delSource) {
        File dir = new File(dirPath);
        if (!dir.exists()) {
            throw new BizException("临时分片目录不存在");
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            throw new BizException("未找到可合并的分片文件");
        }
        File targetFile = new File(toFilePath);
        try (RandomAccessFile writeFile = new RandomAccessFile(targetFile, "rw")) {
            byte[] buffer = new byte[1024 * 10];
            for (int i = 0; i < files.length; i++) {
                File chunkFile = new File(dirPath + "/" + i);
                if (!chunkFile.exists()) {
                    throw new BizException("分片文件不存在:" + chunkFile.getName());
                }
                try (RandomAccessFile readFile = new RandomAccessFile(chunkFile, "r")) {
                    int len;
                    while ((len = readFile.read(buffer)) != -1) {
                        writeFile.write(buffer, 0, len);
                    }
                } catch (Exception e) {
                    log.error("合并分片失败, chunkFile:{}", chunkFile.getAbsolutePath(), e);
                    throw new BizException("合并分片失败");
                }
            }
        } catch (Exception e) {
            log.error("合并文件失败: {}", filename, e);
            throw new BizException("合并文件失败:" + filename);
        } finally {
            if (delSource && dir.exists()) {
                try {
                    FileUtils.deleteDirectory(dir);
                } catch (IOException e) {
                    log.error("删除临时分片目录失败, dirPath:{}", dirPath, e);
                }
            }
        }
    }

    /**
     * 在同一用户、同一父目录（filePid）下，如果已存在同名且未删除的文件（或文件夹），就把新名称自动改成不冲突的名称；否则原样返回。
     *
     * @param filePid  文件PID
     * @param userId   用户ID
     * @param filename 文件名
     */
    private String autoRename(String filePid, String userId, String filename) {
        int count = this.count(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getUserId, userId)
                .eq(FileInfo::getFilePid, filePid)
                .eq(FileInfo::getDeleted, FileDelFlagEnums.USING.getFlag())
                .eq(FileInfo::getFilename, filename));
        if (count > 0) {
            filename = StringTools.rename(filename);
        }
        return filename;
    }

    /**
     * 视频文件切片
     *
     * @param fileId        文件ID
     * @param videoFilePath 分片到目录
     */
    private void cutFile4Video(String fileId, String videoFilePath) {
        // 创建同名切片目录
        File tsFolder = new File(videoFilePath.substring(0, videoFilePath.lastIndexOf(".")));
        if (!tsFolder.exists()) {
            tsFolder.mkdirs();
        }
        // 生成ts
        String tsPath = tsFolder + "/" + Constants.TS_NAME;
        FfmpegUtil.transfer2ts(videoFilePath, tsPath);
        // 生成索引文件.m3u8和切片.ts文件
        String indexTs = tsFolder.getPath() + "/" + Constants.M3U8_NAME;
        FfmpegUtil.cutTs(tsPath, indexTs, tsFolder.getPath(), fileId);
        // 删除index.ts
        new File(tsPath).delete();
    }
}
