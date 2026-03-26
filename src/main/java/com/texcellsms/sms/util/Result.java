package com.texcellsms.sms.util;

import lombok.Data;
import java.io.Serializable;

@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private int code;
    private String msg;
    private T data;

    // ===================== 构造方法 =====================
    public Result() {
    }

    public Result(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

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

    public static <T> Result<T> success(String message, T data) {
        Result<T> result = new Result<>();
        result.setCode(SMSResponseMessage.SUCCESS.getCode());
        result.setMsg(message);
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

    public static <T> Result<T> fail(SMSResponseMessage responseMessage, String customMessage) {
        Result<T> result = new Result<>();
        result.setCode(responseMessage.getCode());
        result.setMsg(customMessage);
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

    public static <T> Result<T> fail(String message) {
        Result<T> result = new Result<>();
        result.setCode(SMSResponseMessage.ERROR.getCode());
        result.setMsg(message);
        result.setData(null);
        return result;
    }

    // ===================== 参数错误 =====================
    public static <T> Result<T> paramError() {
        return fail(SMSResponseMessage.PARAMETERERROR);
    }

    public static <T> Result<T> paramError(String message) {
        return fail(SMSResponseMessage.PARAMETERERROR.getCode(), message);
    }

    // ===================== 查询相关错误（新增） =====================
    public static <T> Result<T> queryTimeRangeExceeded() {
        return fail(SMSResponseMessage.QUERY_TIME_RANGE_EXCEEDED);
    }

    public static <T> Result<T> queryTimeRangeExceeded(String customMessage) {
        return fail(SMSResponseMessage.QUERY_TIME_RANGE_EXCEEDED, customMessage);
    }

    public static <T> Result<T> queryDataTooLarge() {
        return fail(SMSResponseMessage.QUERY_DATA_TOO_LARGE);
    }

    public static <T> Result<T> exportDataTooLarge() {
        return fail(SMSResponseMessage.EXPORT_DATA_TOO_LARGE);
    }

    public static <T> Result<T> startTimeAfterEndTime() {
        return fail(SMSResponseMessage.START_TIME_AFTER_END_TIME);
    }

    public static <T> Result<T> queryResultEmpty() {
        return fail(SMSResponseMessage.QUERY_RESULT_EMPTY);
    }

    // ===================== 业务判断方法 =====================
    public boolean isSuccess() {
        return this.code == SMSResponseMessage.SUCCESS.getCode();
    }

    public boolean isFail() {
        return !isSuccess();
    }

    // ===================== 链式调用方法 =====================
    public Result<T> setCode(int code) {
        this.code = code;
        return this;
    }

    public Result<T> setMsg(String msg) {
        this.msg = msg;
        return this;
    }

    public Result<T> setData(T data) {
        this.data = data;
        return this;
    }

    @Override
    public String toString() {
        return "Result{" +
                "code=" + code +
                ", msg='" + msg + '\'' +
                ", data=" + data +
                '}';
    }
}