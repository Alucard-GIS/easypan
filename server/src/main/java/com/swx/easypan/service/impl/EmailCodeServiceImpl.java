package com.swx.easypan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.swx.common.pojo.BizException;
import com.swx.easypan.redis.RedisComponent;
import com.swx.easypan.entity.config.AppConfig;
import com.swx.easypan.entity.constants.Constants;
import com.swx.easypan.entity.dto.SysSettingsDTO;
import com.swx.easypan.pojo.EmailCode;
import com.swx.easypan.mapper.EmailCodeMapper;
import com.swx.easypan.service.EmailCodeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.swx.easypan.service.SysSettingsService;
import com.swx.easypan.utils.StringTools;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.mail.internet.MimeMessage;
import java.time.ZoneId;
import java.util.Date;

/**
 * <p>
 * 邮箱验证码服务实现类
 * </p>
 *
 * @author sw-code
 * @since 2023-05-17
 */
@Service
public class EmailCodeServiceImpl extends ServiceImpl<EmailCodeMapper, EmailCode> implements EmailCodeService {

    private final JavaMailSender javaMailSender;
    private final RedisComponent redisComponent;

    @Resource
    private AppConfig appConfig;
    @Resource
    private SysSettingsService sysSettingsService;

    public EmailCodeServiceImpl(JavaMailSender javaMailSender, RedisComponent redisComponent) {
        this.javaMailSender = javaMailSender;
        this.redisComponent = redisComponent;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void sendEmailCode(String email, Integer type) {
        if (redisComponent.hasEmailCodeLimit(email)) {
            throw new BizException("请求过于频繁，请1分钟后再试");
        }
        String code = StringTools.getRandomNumber(Constants.LENGTH_5);
        sendMailCode(email, code);
        redisComponent.saveEmailCode(email, code);
        redisComponent.saveEmailCodeLimit(email);
    }

    @Override
    public void checkCode(String email, String code) {
        String cacheCode = redisComponent.getEmailCode(email);
        if (cacheCode == null) {
            throw new BizException("邮箱验证码已失效");
        }
        if (!cacheCode.equals(code)) {
            throw new BizException("邮箱验证码错误");
        }
        redisComponent.removeEmailCode(email);
    }

    private void sendMailCode(String toEmail, String code) {
        try {
            //构造一份邮件
            MimeMessage message = javaMailSender.createMimeMessage();
            //使用MimeMessageHelper来构造邮件
            MimeMessageHelper helper = new MimeMessageHelper(message, true);
            //邮件发送人
            helper.setFrom(appConfig.getSendUsername());
            //邮件接收人 1个或多个
            helper.setTo(toEmail);
            //获取系统设置
            SysSettingsDTO sysSettingsDto = sysSettingsService.getSysSettings();
            //邮件主题
            helper.setSubject(sysSettingsDto.getRegisterMailTitle());
            //邮件内容
            helper.setText(String.format(sysSettingsDto.getRegisterEmailContent(), code));
            //邮件发送时间
            helper.setSentDate(new Date());
            //发送邮件
            javaMailSender.send(message);
        } catch (Exception e) {
            throw new BizException("邮件发送失败");
        }
    }
}
