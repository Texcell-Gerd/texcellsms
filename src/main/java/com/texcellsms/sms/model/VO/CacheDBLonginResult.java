package com.texcellsms.sms.model.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class CacheDBLonginResult implements Serializable {
    private String  Result;
    private String  WhitelistIP;
    private String  FAKey;
    private String  IsEnable;

    public CacheDBLonginResult(String result, String whitelistIP, String FAKey, String isEnable) {
        Result = result;
        WhitelistIP = whitelistIP;
        this.FAKey = FAKey;
        IsEnable = isEnable;
    }

    public CacheDBLonginResult(){

    }
}
