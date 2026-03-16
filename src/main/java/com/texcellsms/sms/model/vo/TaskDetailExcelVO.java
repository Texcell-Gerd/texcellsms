package com.texcellsms.sms.model.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

@Data
public class TaskDetailExcelVO {
    @ExcelProperty("编号")
    private String submitMsgId;
    @ExcelProperty("发件人")
    private String caller;
    @ExcelProperty("接收号码")
    private String callee;
    @ExcelProperty("完成时间")
    private String recvDRTime;
    @ExcelProperty("发送结果")
    private String recvDRStatus;
    @ExcelProperty("原因说明")
    private String recvDRError;
    @ExcelProperty("计费数量")
    private Integer pkNumber;
    @ExcelProperty("拆分索引")
    private Integer pkTotal;
}