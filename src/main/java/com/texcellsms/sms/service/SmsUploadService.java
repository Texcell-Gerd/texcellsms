package com.texcellsms.sms.service;

import com.texcellsms.sms.mapper.webportal.ScheduleNumberTaskMapper;
import com.texcellsms.sms.model.entity.SmsScheduleNumberTask;
import com.texcellsms.sms.util.SmsConstants;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SmsUploadService {

    @Autowired
    private ScheduleNumberTaskMapper numberTaskMapper;

    // 解析号码文件
    public List<String> parsePhoneFile(MultipartFile file) throws Exception {
        List<String> phoneList = new ArrayList<>();
        String fileName = file.getOriginalFilename();
        if (fileName.endsWith(".txt")) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.matches("^\\d+$") && line.length() > 7 && line.length() < 22) {
                        phoneList.add(line);
                    }
                }
            }
        } else if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
            try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
                Sheet sheet = wb.getSheetAt(0);
                for (Row row : sheet) {
                    Cell cell = row.getCell(0);
                    if (cell != null) {
                        String phone = cell.getStringCellValue().trim();
                        if (phone.matches("^\\d+$") && phone.length() > 7 && phone.length() < 22) {
                            phoneList.add(phone);
                        }
                    }
                }
            }
        }
        return phoneList.stream().distinct().collect(Collectors.toList());
    }

    // 保存预任务到数据库+文件
    public void savePreTask(String subAccount, String preTaskId, List<String> phoneList) throws Exception {
        // 1. 保存数据库记录
        SmsScheduleNumberTask task = new SmsScheduleNumberTask();
        task.setSubAccount(subAccount);

        // 提交时间 = 秒级时间戳
        String now = String.valueOf(System.currentTimeMillis() / 1000);
        task.setSubmitTime(now);

        task.setPreTaskId(preTaskId);

        // 有效期 15分钟（关键修复）
        long expire = (System.currentTimeMillis() / 1000) + 15 * 60;
        task.setValidTill(String.valueOf(expire));

        // 调用 mapper
        numberTaskMapper.insertScheduleNumberTask(
                task.getSubAccount(),
                task.getSubmitTime(),
                task.getPreTaskId(),
                task.getValidTill()
        );

        // 2. 保存号码文件
        String userPath = SmsConstants.PHONE_PATH + subAccount + "\\";
        File dir = new File(userPath);
        if (!dir.exists()) dir.mkdirs();
        File phoneFile = new File(dir, preTaskId + ".txt");
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(phoneFile))) {
            for (int i = 0; i < phoneList.size(); i++) {
                bw.write(phoneList.get(i));
                if (i < phoneList.size() - 1) bw.newLine();
            }
        }
    }
}