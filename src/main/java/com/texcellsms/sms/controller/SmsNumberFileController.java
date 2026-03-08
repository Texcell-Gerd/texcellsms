package com.texcellsms.sms.controller;


import com.texcellsms.sms.service.SmsNumberFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/sms/number-file")
@Tag(name = "号码文件管理", description = "上传号码文件，生成预任务ID")
@RequiredArgsConstructor
public class SmsNumberFileController {

    private static final Logger logger = LoggerFactory.getLogger(SmsNumberFileController.class);

    private final SmsNumberFileService smsNumberFileService;

    @PostMapping("/upload")
    @Operation(summary = "上传号码文件", description = "支持xlsx/xls/csv/txt，大小≤2MB，返回预任务ID和号码列表")
    public Map<String, Object> uploadNumberFile(
            @Parameter(description = "号码文件", required = true)
            @RequestParam("file") MultipartFile file) {

        Map<String, Object> result = new HashMap<>();

        try {
            SmsNumberFileService.UploadResult uploadResult = smsNumberFileService.uploadNumberFile(file);
            result.put("code", 0);
            result.put("msg", "上传成功");
            result.put("preTaskId", uploadResult.getPreTaskId());
            result.put("calleeCount", uploadResult.getCalleeCount());
            result.put("callee", uploadResult.getCallee()); // 可选：号码列表
        } catch (IllegalArgumentException e) {
            result.put("code", 400);
            result.put("msg", e.getMessage());
        } catch (Exception e) {
            logger.error("上传号码文件异常", e);
            result.put("code", 500);
            result.put("msg", "服务器内部错误");
        }

        return result;
    }
}