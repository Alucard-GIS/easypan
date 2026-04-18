package com.swx.easypan.annotation;

import java.lang.annotation.*;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LoginValidator {

    // 是否校验，默认为true
    boolean validated() default true;

    // 是否校验管理员权限，默认为false
    boolean checkAdmin() default false;
}
