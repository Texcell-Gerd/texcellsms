package com.texcellsms.sms.model.DTO;

import lombok.Data;

@Data
public class SmsSendResponse {
    private String taskId;
    private int dcs;
    private int smLength;
    private int pkTotal;
    private int splitCount;
    private String error;
}