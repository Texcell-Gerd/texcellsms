package com.texcellsms.sms.model.DTO;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 发送记录查询结果DTO（Submit_Bill主表）
 */
@Data
@Schema(name = "BaseDTO", description = "通用返回Dto层")
public class BaseDTO {

    @Schema(description = "错误代码", example = "0/1")
    private int Code;

    @Schema(description = "错误原因", example = "登录信息异常")
    private String ERROR;

    @Schema(description = "当前页", example = "1")
    private int PageCount;

    @Schema(description = "记录数", example = "45")
    private Integer Total;

}