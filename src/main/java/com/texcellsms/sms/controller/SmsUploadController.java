package com.texcellsms.sms.controller;

import com.texcellsms.sms.service.SmsUploadService;
import com.texcellsms.sms.service.TokenService;
import com.texcellsms.sms.util.AjaxResult;
import com.texcellsms.sms.util.IdGenerator;
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
@RequestMapping("/api/eccom/sms")
public class SmsUploadController {

    @Autowired
    private SmsUploadService smsUploadService;

    /**
     * 上传接收号码，支持文件上传或手动输入号码
     */
    @PostMapping("/uploadCallee")
    public AjaxResult uploadCallee(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "numbers", required = false) String numbers,
            HttpServletRequest request) throws Exception {

        String subAccount = TokenService.getUsername();
        if (StringUtils.isEmpty(subAccount)) {
            return AjaxResult.error("Not logged in or session expired");
        }

        List<String> phoneList = new ArrayList<>();
        int totalLineCount = 0;

        if (file != null && !file.isEmpty()) {
            SmsUploadService.ParseResult result = smsUploadService.parsePhone(file);
            phoneList = result.getPhoneList();
            totalLineCount = result.getTotalLineCount();
        } else if (StringUtils.isNotEmpty(numbers)) {
            phoneList = Arrays.stream(numbers.split("[,\\n]"))
                    .map(String::trim)
                    .filter(s -> s.matches("^\\d+$") && s.length() >= 8 && s.length() <= 21)
                    .distinct()
                    .collect(Collectors.toList());
            totalLineCount = phoneList.size();
        }

        if (phoneList.isEmpty()) {
            return AjaxResult.error("No valid numbers detected");
        }

        String preTaskId = IdGenerator.generatePreTaskId();
        smsUploadService.savePreTask(subAccount, preTaskId, phoneList);

        Map<String, Object> data = new HashMap<>();
        data.put("PreTaskId", preTaskId);
        data.put("CalleeCount", phoneList.size());
        data.put("TotalLineCount", totalLineCount);
        data.put("Callee", phoneList);

        return AjaxResult.success(data);
    }

    /**
     * ✅ 批量上传接收号码（复制uploadCallee逻辑，支持最大30MB文件）
     * 支持file文件上传 或者 numbers文本输入；返回结构和uploadCallee保持完全一致
     */
    @PostMapping("/uploadCalleeBatch")
    public AjaxResult uploadCalleeBatch(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "numbers", required = false) String numbers,
            HttpServletRequest request) throws Exception {

        String subAccount = TokenService.getUsername();
        if (StringUtils.isEmpty(subAccount)) {
            return AjaxResult.error("Not logged in or session expired");
        }

        List<String> phoneList = new ArrayList<>();
        int totalLineCount = 0;

        if (file != null && !file.isEmpty()) {
            SmsUploadService.ParseResult result = smsUploadService.parsePhoneBatch(file);
            phoneList = result.getPhoneList();
            totalLineCount = result.getTotalLineCount();
        } else if (StringUtils.isNotEmpty(numbers)) {
            phoneList = Arrays.stream(numbers.split("[,\\n]"))
                    .map(String::trim)
                    .filter(s -> s.matches("^\\d+$") && s.length() >= 8 && s.length() <= 21)
                    .distinct()
                    .collect(Collectors.toList());
            totalLineCount = phoneList.size();
        }

        if (phoneList.isEmpty()) {
            return AjaxResult.error("No valid numbers detected");
        }

        String preTaskId = IdGenerator.generatePreTaskId();

        smsUploadService.savePreTask(subAccount, preTaskId, phoneList);

        Map<String, Object> data = new HashMap<>();
        data.put("PreTaskId", preTaskId);
        data.put("CalleeCount", phoneList.size());
        data.put("TotalLineCount", totalLineCount);
//        data.put("Callee", phoneList);

        return AjaxResult.success(data);
    }
}