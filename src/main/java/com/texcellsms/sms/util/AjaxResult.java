package com.texcellsms.sms.util;

import java.util.HashMap;

/**
 * 操作消息提醒
 */
public class AjaxResult extends HashMap<String, Object>
{
    private static final long serialVersionUID = 1L;

    public static final String CODE_TAG = "Code";
    public static final String MSG_TAG = "msg";
    public static final String DATA_TAG = "data";

    public AjaxResult() {}

    public AjaxResult(int code, String msg)
    {
        super.put(CODE_TAG, code);
        super.put(MSG_TAG, msg);
    }

    public AjaxResult(int code, String msg, Object data)
    {
        super.put(CODE_TAG, code);
        super.put(MSG_TAG, msg);
        if (data != null)
        {
            super.put(DATA_TAG, data);
        }
    }

    @Override
    public AjaxResult put(String key, Object value)
    {
        super.put(key, value);
        return this;
    }

    // 成功
    public static AjaxResult success()
    {
        return AjaxResult.success(SMSResponseMessage.SUCCESS.getMessage());
    }

    public static AjaxResult success(Object data)
    {
        return AjaxResult.success(SMSResponseMessage.SUCCESS.getMessage(), data);
    }

    public static AjaxResult success(String msg)
    {
        return AjaxResult.success(msg, null);
    }

    public static AjaxResult success(String msg, Object data)
    {
        return new AjaxResult(SMSResponseMessage.SUCCESS.getCode(), msg, data);
    }

    // 失败
    public static AjaxResult error()
    {
        return AjaxResult.error(SMSResponseMessage.ERROR.getMessage());
    }

    public static AjaxResult error(String msg)
    {
        return AjaxResult.error(msg, null);
    }

    public static AjaxResult error(String msg, Object data)
    {
        return new AjaxResult(SMSResponseMessage.ERROR.getCode(), msg, data);
    }

    public static AjaxResult error(int code, String msg)
    {
        return new AjaxResult(code, msg, null);
    }
}