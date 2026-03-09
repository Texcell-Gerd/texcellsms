package com.texcellsms.sms.model.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 分析页查询结果DTO
 */
@Data
@Schema(name = "AnalyzeDTO", description = "分析页查询结果")
public class AnalyzeDTO extends BaseDTO{

    List<PItemDTO> pItem;
}