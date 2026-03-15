package com.texcellsms.sms.util;

import lombok.Data;
import java.io.Serializable;

@Data
public class Result<T> implements Serializable {

    private int code;
    private String msg;
    private T data;

    // ===================== 成功 =====================
    public static <T> Result<T> success() {
        return success(null);
    }

    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(SMSResponseMessage.SUCCESS.getCode());
        result.setMsg(SMSResponseMessage.SUCCESS.getMessage());
        result.setData(data);
        return result;
    }

    // ===================== 失败（直接使用枚举） =====================
    public static <T> Result<T> fail(SMSResponseMessage responseMessage) {
        Result<T> result = new Result<>();
        result.setCode(responseMessage.getCode());
        result.setMsg(responseMessage.getMessage());
        result.setData(null);
        return result;
    }

    // 兼容自定义消息
    public static <T> Result<T> fail(int code, String msg) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        result.setData(null);
        return result;
    }
}