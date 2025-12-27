package com.ican.project.model.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {
    private Integer code;
    private String message;
    private T data;

    public Result(Integer code, String message) {
        this.code = code;
        this.message = message;
        this.data = null;
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(Code.SUCCESS,null,data);
    }

    public static <T> Result<T> authFail() {
        return new Result<>(Code.AUTH_FAILURE,"未登录");
    }
    public static <T> Result<T> authFail(String message) {
        return new Result<>(Code.AUTH_FAILURE,message);
    }


    public static <T> Result<T> accessFail() {
        return new Result<>(Code.ACCESS_FAILURE,"权限不足");
    }
    public static <T> Result<T> accessFail(String message) {
        return new Result<>(Code.ACCESS_FAILURE,message);
    }

    public static <T> Result<T> fail(Integer code, String message) {
        return new Result<>(code,message);
    }
}
