package com.ican.project.utils;

import com.ican.project.model.common.Constants;

import java.security.SecureRandom;
import java.util.Random;

public class CodeUtil {
    private static final Random RANDOM = new SecureRandom();

    public static String generateCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Constants.VerifyCode.LENGTH; i++) {
            // 随机生成 0-9 的数字并追加
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }
}