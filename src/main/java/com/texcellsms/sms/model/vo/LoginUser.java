package com.texcellsms.sms.model.vo;

import lombok.Data;

import java.io.Serializable;

@Data
public class LoginUser implements Serializable {

    private String Username;

    private String password;

    private String Token;

    private String timestamp;

    private String IP;

    private String UUID;

    private long createTime;

    private String sManualKey;

    private String sQRCode;

    private String faKey;
}
