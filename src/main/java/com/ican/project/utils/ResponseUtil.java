package com.ican.project.utils;

import com.alibaba.fastjson2.JSON;
import com.ican.project.model.common.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;

public class ResponseUtil {
    private static final Logger logger = LoggerFactory.getLogger(ResponseUtil.class);

    public static void write(HttpServletResponse response, Result<?> model) throws IOException {
        if (response == null) {
            logger.error("HttpServletResponse为空");
            throw new IllegalArgumentException("HttpServletResponse不能为空");
        }

        if (model == null) {
            logger.warn("Result模型为空，使用默认错误响应");
            model = Result.fail(500, "响应数据为空");
        }

        try {
            //响应头设置JSON
            response.setContentType("application/json;charset=utf-8");
            //创建输出流对象
            PrintWriter writer = response.getWriter();
            //构建JSON格式的数据
            String json = JSON.toJSONString(model);
            //输出JSON格式的数据
            writer.println(json);
            writer.flush();
        } catch (IOException e) {
            logger.error("写入响应流异常", e);
            throw e;
        }
    }

    public static void write(HttpServletResponse response, Result<?> model, Integer status) throws IOException {
        if (response == null) {
            logger.error("HttpServletResponse为空");
            throw new IllegalArgumentException("HttpServletResponse不能为空");
        }

        if (model == null) {
            logger.warn("Result模型为空，使用默认错误响应");
            model = Result.fail(500, "响应数据为空");
        }

        if (status == null) {
            logger.warn("HTTP状态码为空，使用默认状态码500");
            status = 500;
        }

        try {
            //响应头设置JSON
            response.setContentType("application/json;charset=utf-8");
            //设置HTTP状态码
            response.setStatus(status);
            //创建输出流对象
            PrintWriter writer = response.getWriter();
            //构建JSON格式的数据
            String json = JSON.toJSONString(model);
            //输出JSON格式的数据
            writer.println(json);
            writer.flush();
        } catch (IOException e) {
            logger.error("写入响应流异常", e);
            throw e;
        }
    }
}