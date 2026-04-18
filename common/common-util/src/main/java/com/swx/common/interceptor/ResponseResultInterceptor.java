package com.swx.common.interceptor;

import com.swx.common.annotation.ResponseResult;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;

@Component
public class ResponseResultInterceptor implements HandlerInterceptor {
    //标记名称
    public static final String RESPONSE_RESULT_ANN = "RESPONSE-RESULT-ANN";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        //1. 判断当前拦截到的是不是一个 Controller 方法
        if (handler instanceof HandlerMethod) {
            final HandlerMethod handlerMethod = (HandlerMethod) handler; // 获取方法对象
            final Class<?> clazz = handlerMethod.getBeanType();
            final Method method = handlerMethod.getMethod();
            // 2. 查户口：看看类上有没有盖 @ResponseResult 印章
            if (clazz.isAnnotationPresent(ResponseResult.class)) {
                // 3. 贴便签：给这个 Request 对象塞入一个属性，告诉后面的流程“这兄弟需要包装”
                request.setAttribute(RESPONSE_RESULT_ANN, clazz.getAnnotation(ResponseResult.class));
            }
            // 4. 如果类上没有，再看看方法上有没有盖章
            else if (method.isAnnotationPresent(ResponseResult.class)) {
                request.setAttribute(RESPONSE_RESULT_ANN, method.getAnnotation(ResponseResult.class));
            }
        }
        // 5. 放行，去执行真正的 Controller 逻辑
        return true;
    }
}
