package com.texcellsms.sms.util;

import lombok.Getter;
import org.apache.poi.ss.formula.functions.T;

@Getter
public enum SMSResponseMessage {
    SUCCECC("Operation successful",0), ERROR("Operation ERROR",301),PARAMETERERROR("The parameters are incomplete.",405),
    CACHEDBERROR("CacheDB stored procedure execution failed",502),IPERROR("Bound IP does not match",403),
    ACCOUNTLOCKEDERROR("The account is locked",603),ACCOUNTERROR("The account name or password is incorrect. Please contact the administrator",402),
    AUTHENTICATIONERROR("Failed to obtain two-step authentication data.",403),IPLOCKEDERROR("The IP has been locked due to excessive failed attempts.",480);
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
