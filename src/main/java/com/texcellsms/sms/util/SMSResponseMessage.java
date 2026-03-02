package com.texcellsms.sms.util;

import lombok.Getter;
import org.apache.poi.ss.formula.functions.T;

@Getter
public enum SMSResponseMessage {
    SUCCECC("操作成功",0), ERROR("操作失败",301);
    SMSResponseMessage(String message, int code, T date) {
        this.message = message;
        this.code = code;
        this.date = date;
    }
    SMSResponseMessage(String message, int code) {
        this.message = message;
        this.code = code;
    }

    private String message;
    private int code;
    private T date;
}
