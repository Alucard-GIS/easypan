package com.swx.easypan.utils;

import com.swx.easypan.entity.constants.Constants;
import org.apache.commons.lang3.RandomStringUtils;

public class StringTools {

    /**
     * 生成随机数
     * @param count 位数
     */
    public static String getRandomNumber(Integer count) {
        return RandomStringUtils.random(count, false, true);
    }

    /**
     * 生成随机字符串
     * @param count
     * @return
     */
    public static String getRandomString(Integer count) {
        return RandomStringUtils.random(count, true, true);
    }

    /**
     * 重命名文件，格式：原文件名_随机字符串.后缀
     * @param filename
     * @return
     */
    public static String rename(String filename) {
        return getFilename(filename) + "_" + getRandomString(Constants.LENGTH_5) + getFileSuffix(filename);
    }

    /**
     * 获取文件名，不包含后缀
     * @param filename
     * @return
     */
    public static String getFilename(String filename) {
        int index = filename.lastIndexOf(".");
        if (index == -1) {
            return filename;
        }
        return filename.substring(0, index);
    }

    /**
     * 获取文件后缀，包括点
     * @param filename
     * @return
     */
    public static String getFileSuffix(String filename) {
        int index = filename.lastIndexOf(".");
        if (index == -1) {
            return "";
        }
        return filename.substring(index);
    }
}
