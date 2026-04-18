package com.swx.easypan.aspect;

import com.swx.common.pojo.BizException;
import com.swx.common.pojo.ResultCode;
import com.swx.easypan.annotation.LoginValidator;
import com.swx.easypan.entity.constants.Constants;
import com.swx.easypan.entity.vo.SessionWebUserVO;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.lang.reflect.Method;

@Aspect
@Component
public class LoginAspect {

    // 定义切点，匹配所有使用了LoginValidator注解的方法或者类
    @Pointcut("@annotation(com.swx.easypan.annotation.LoginValidator) || @within(com.swx.easypan.annotation.LoginValidator)")
    private void pointCut() {}

    @Before("pointCut()")
    public void interceptorDo(JoinPoint point) {
        // 获取目标对象
        Object target = point.getTarget();
        // 获取方法对象
        MethodSignature methodSignature = (MethodSignature) point.getSignature();
        Method method = methodSignature.getMethod();
        // 获取LoginValidator注解对象
        LoginValidator loginValidator = method.getAnnotation(LoginValidator.class);
        // 如果有，并且值为false，则不校验
        if (loginValidator !=null && !loginValidator.validated()) {
            return;
        }
        // 登陆校验
        // 获取请求对象
        ServletRequestAttributes requestAttributes = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes());
        // 如果请求对象为空，或者响应对象为空，则不校验
        if (requestAttributes == null || requestAttributes.getResponse() == null) {
            return;
        }
        HttpServletRequest request = requestAttributes.getRequest();
        HttpSession session = request.getSession();
        SessionWebUserVO userVo = (SessionWebUserVO) session.getAttribute(Constants.SESSION_KEY);
        if (null == userVo) {
            throw new BizException(ResultCode.LOGIN_AUTH_FAIL);
        }
        if (loginValidator != null && loginValidator.checkAdmin() && !userVo.getIsAdmin()) {
            throw new BizException(ResultCode.NO_PERMISSION);
        }
    }
}
