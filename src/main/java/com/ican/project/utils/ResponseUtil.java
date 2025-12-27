package com.ican.project.utils;

import com.alibaba.fastjson2.JSON;
import com.ican.project.model.common.Result;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;

public class ResponseUtil {
    public static void write(HttpServletResponse response, Result<?> model) throws IOException {
        //响应头设置JSON
        response.setContentType("application/json;charset=utf-8");
        //创建输出流对象
        PrintWriter writer = response.getWriter();
        //构建JSON格式的数据
        String json = JSON.toJSONString(model);
        //输出JSON格式的数据
        writer.println(json);
    }

    public static void write(HttpServletResponse response, Result<?> model,Integer status) throws IOException {
        //响应头设置JSON
        response.setContentType("application/json;charset=utf-8");
        //创建输出流对象
        PrintWriter writer = response.getWriter();
        //构建JSON格式的数据
        String json = JSON.toJSONString(model);
        //输出JSON格式的数据
        writer.println(json);

        response.setStatus(status);
    }
}