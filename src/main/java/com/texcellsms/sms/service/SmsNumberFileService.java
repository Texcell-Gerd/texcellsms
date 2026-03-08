package com.texcellsms.sms.service;


import com.texcellsms.sms.mapper.prepay.SmsNumberFileMapper;
import com.texcellsms.sms.util.NumberFileParser;
import com.texcellsms.sms.util.SmsUtil;
import com.texcellsms.sms.util.UserUtils;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SmsNumberFileService {

    private static final Logger logger = LoggerFactory.getLogger(SmsNumberFileService.class);
    private final SmsNumberFileMapper smsNumberFileMapper;

    // 从配置文件读取上传目录
    @Value("${sms.upload.number-file-path:/data/sms/number_files/}")
    private String uploadBasePath;

    private static final SimpleDateFormat DATE_FOLDER_FORMAT = new SimpleDateFormat("yyyyMMdd");

    /**
     * 上传号码文件，返回PreTaskId和号码列表
     */
    public UploadResult uploadNumberFile(MultipartFile file) throws Exception {
        // 1. 解析号码
        List<String> numbers = NumberFileParser.parseFromFile(file);
        if (numbers.isEmpty()) {
            throw new IllegalArgumentException("文件中未解析到有效号码");
        }

        // 2. 生成PreTaskId
        String preTaskId = SmsUtil.generateTaskId();
        String subAccount = UserUtils.getCurrentSubAccount();

        // 3. 保存文件到服务器
        String dateFolder = DATE_FOLDER_FORMAT.format(new Date());
        String fileName = file.getOriginalFilename();
        String storedFileName = preTaskId + "_" + fileName; // 避免文件名冲突
        Path uploadPath = Paths.get(uploadBasePath, dateFolder);
        Files.createDirectories(uploadPath); // 自动创建目录
        Path filePath = uploadPath.resolve(storedFileName);
        Files.copy(file.getInputStream(), filePath);

        // 4. 插入数据库记录
        int totalCount = numbers.size();
        int insert = smsNumberFileMapper.insertNumberFileRecord(
                preTaskId,
                subAccount,
                fileName,
                filePath.toString(),
                totalCount
        );
        if (insert != 1) {
            // 插入失败，删除已保存的文件
            Files.deleteIfExists(filePath);
            throw new RuntimeException("保存号码文件记录到数据库失败");
        }

        logger.info("号码文件上传完成：preTaskId={}, account={}, 号码数={}, filePath={}",
                preTaskId, subAccount, totalCount, filePath);

        // 5. 返回结果
        UploadResult result = new UploadResult();
        result.setPreTaskId(preTaskId);
        result.setCalleeCount(totalCount);
        result.setCallee(numbers); // 可选：返回号码列表，或只返回PreTaskId
        return result;
    }

    // 上传结果DTO
    @lombok.Data
    public static class UploadResult {
        private String preTaskId;
        private int calleeCount;
        private List<String> callee;
    }
}