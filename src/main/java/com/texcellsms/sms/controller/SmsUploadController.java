package com.texcellsms.sms.controller;

import com.texcellsms.sms.service.SmsUploadService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.AjaxResult;
import com.texcellsms.sms.util.IdGenerator;
import com.texcellsms.sms.util.SmsConstants;
import com.texcellsms.sms.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/prepay/sms")
public class SmsUploadController {

    @Autowired
    private SmsUploadService smsUploadService;

    @PostMapping("/uploadCallee")
    public AjaxResult uploadCallee(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "numbers", required = false) String numbers,
            HttpServletRequest request) throws Exception {

        // 1. 获取当前登录用户
        String subAccount = TokenService.getUsername();
        if (StringUtils.isEmpty(subAccount)) {
            return AjaxResult.error("未登录或登录已过期");
        }

        // 2. 处理号码（文件/手动输入）
        List<String> phoneList = new ArrayList<>();
        if (file != null && !file.isEmpty()) {
            // 处理文件上传
            phoneList = smsUploadService.parsePhoneFile(file);
        } else if (StringUtils.isNotEmpty(numbers)) {
            // 处理手动输入号码（逗号/换行分隔）
            phoneList = Arrays.stream(numbers.split("[,\\n]"))
                    .map(String::trim)
                    .filter(s -> s.matches("^\\d+$") && s.length() > 7 && s.length() < 22)
                    .distinct()
                    .collect(Collectors.toList());
        }

        // 3. 校验数量上限
        if (phoneList.size() > SmsConstants.MAX_PHONE_COUNT) {
            return AjaxResult.error("号码数量超过上限：" + SmsConstants.MAX_PHONE_COUNT);
        }
        if (phoneList.isEmpty()) {
            return AjaxResult.error("未检测到有效号码");
        }

        // 4. 生成预任务ID并保存
        String preTaskId = IdGenerator.generatePreTaskId();
        smsUploadService.savePreTask(subAccount, preTaskId, phoneList);

        // ===================== 修复这里 =====================
        Map<String, Object> data = new HashMap<>();
        data.put("PreTaskId", preTaskId);
        data.put("CalleeCount", phoneList.size());
        data.put("Callee", phoneList);

        return AjaxResult.success(data);
        // ====================================================
    }
}
