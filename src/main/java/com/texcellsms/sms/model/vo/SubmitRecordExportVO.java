package com.texcellsms.sms.model.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 短信发送记录导出VO（EasyExcel专用）
 */
@Data
@Schema(name = "SubmitRecordExportVO", description = "短信发送记录导出VO")
public class SubmitRecordExportVO {
    @ExcelProperty("用户账号")
    @ColumnWidth(12)
    private String sourAccount;

    @ExcelProperty("提交消息ID")
    @ColumnWidth(18)
    private String submitMsgId;

    @ExcelProperty("提交时间")
    @ColumnWidth(20)
    private String submitMsgTime;

    @ExcelProperty("主叫号码")
    @ColumnWidth(12)
    private String caller;

    @ExcelProperty("被叫号码")
    @ColumnWidth(15)
    private String callee;

    @ExcelProperty("短信长度")
    @ColumnWidth(8)
    private String smLength;

    @ExcelProperty("实际费用")
    @ColumnWidth(12)
    private String actualChargeFee;

    @ExcelProperty("短信内容")
    @ColumnWidth(50)
    private String shortMessage;

    @ExcelProperty("MCC")
    @ColumnWidth(6)
    private String mcc;

    @ExcelProperty("MNC")
    @ColumnWidth(6)
    private String mnc;

    @ExcelProperty("回执状态")
    @ColumnWidth(12)
    private String innerReportState;

    @ExcelProperty("回执时间")
    @ColumnWidth(20)
    private String recvDRTime;
}