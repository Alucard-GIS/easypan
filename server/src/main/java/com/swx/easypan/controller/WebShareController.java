package com.swx.easypan.controller;


import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.swx.common.annotation.ResponseResult;
import com.swx.easypan.annotation.LoginValidator;
import com.swx.easypan.entity.constants.Constants;
import com.swx.easypan.entity.dto.ShareDTO;
import com.swx.easypan.entity.vo.FileShareVo;
import com.swx.easypan.entity.vo.SessionWebUserVO;
import com.swx.easypan.pojo.FileShare;
import com.swx.easypan.service.FileInfoService;
import com.swx.easypan.service.FileShareService;
import com.swx.easypan.service.UserInfoService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import javax.validation.constraints.NotEmpty;

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
public class WebShareController {

    @Autowired
    private UserInfoService userInfoService;
    @Autowired
    private FileInfoService fileInfoService;
    @Autowired
    private FileShareService fileShareService;


}

