package com.swx.common.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swx.common.annotation.ResponseResult;
import com.swx.common.pojo.ErrorResult;
import com.swx.common.pojo.R;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import javax.servlet.http.HttpServletRequest;

/**
 * 使用 @ControllerAdvice & ResponseBodyAdvice
 * 拦截Controller方法默认返回参数，统一处理返回值/响应体
 * 当Controller 里的业务逻辑执行完，准备把数据发给前端时,ResponseBodyAdvice拦截所有的 Controller 响应
 */
@ControllerAdvice // 拦截所有的 Controller 响应
public class ResponseResultHandler implements ResponseBodyAdvice<Object> {

    // 标记名称
    public static final String RESPONSE_RESULT_ANN = "RESPONSE-RESULT-ANN";

    // 使用 Jackson 将对象转为 JSON 字符串
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();


    /**
     * 判断是否要执行下面的 beforeBodyWrite 方法，true为执行，false不执行，有注解标记的时候处理返回值
     */
    @Override
    public boolean supports(MethodParameter arg0, Class<? extends HttpMessageConverter<?>> arg1) {
        ServletRequestAttributes sra = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = sra.getRequest();

        // 找一下拦截器是不是贴过便签了？如果有便签，说明需要包装，返回 true
        ResponseResult responseResultAnn = (ResponseResult) request.getAttribute(RESPONSE_RESULT_ANN);
        return responseResultAnn == null ? false : true;
    }


    // 对返回值做包装处理，如果属于异常结果，则需要再包装
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        // 1. 异常处理：如果是全局异常拦截器返回的 ErrorResult，包成 R.fail()
        if (body instanceof ErrorResult) {
            ErrorResult error = (ErrorResult) body;
            return R.fail(error.getCode(), error.getMessage());
        }
        // 2. 防止重复包装：如果已经是 R 对象了，直接放行
        else if (body instanceof R) {
            return (R) body;
        }
        // 3. 如果返回的是 String，Spring 默认的 String 转换器会报错，必须手动转 JSON 字符串
        else if  (body instanceof String) {
            try {
                // 这里必须返回 String，否则会触发 StringHttpMessageConverter
                return OBJECT_MAPPER.writeValueAsString(R.success(body));
            } catch (JsonProcessingException e) {
                // 序列化异常时，退化为简单字符串，避免再次抛错
                return body;
            }
        }
        // 4. 其他类型统一包装为 R.success
        return R.success(body);
    }
}
