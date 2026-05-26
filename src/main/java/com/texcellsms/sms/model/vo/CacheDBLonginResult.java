package com.texcellsms.sms.model.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class CacheDBLonginResult implements Serializable {
    private String  Result;
    private String  WhitelistIP;
    private String  FAKey;
    private String  IsEnable;
    private String Email;

    public CacheDBLonginResult(String result, String whitelistIP, String FAKey, String isEnable, String email) {
        Result = result;
        WhitelistIP = whitelistIP;
        this.FAKey = FAKey;
        IsEnable = isEnable;
        Email = email;
    }

    public CacheDBLonginResult(){

    }
}
