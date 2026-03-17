package com.texcellsms.sms.service;

import com.texcellsms.sms.mapper.webportal.ScheduleNumberTaskMapper;
import com.texcellsms.sms.model.entity.SmsScheduleNumberTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.*;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SmsUploadService {

    @Autowired
    private ScheduleNumberTaskMapper numberTaskMapper;

    private static final String[] ALLOWED_EXTENSIONS = {"txt", "csv", "xlsx", "xls"};
    private static final int MIN_PHONE_LENGTH = 7;
    private static final int MAX_PHONE_LENGTH = 22;

    // ✅ 测试服务器路径（Linux 格式，对应 /home/ScheduleTask/Phone）
    public static final String REMOTE_PHONE_PATH = "\\\\192.168.128.247\\home\\ScheduleTask\\Phone\\";

    public List<String> parsePhoneFile(MultipartFile file) throws Exception {
        List<String> phoneList = new ArrayList<>();
        String fileName = file.getOriginalFilename();
        if (fileName == null) throw new Exception("文件名不能为空");

        String ext = getFileExtension(fileName).toLowerCase();
        boolean allowed = false;
        for (String e : ALLOWED_EXTENSIONS) {
            if (e.equals(ext)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) throw new Exception("仅支持 TXT / CSV / XLSX / XLS 格式");

        if (ext.equals("txt") || ext.equals("csv")) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim().replaceAll("[^0-9]", "");
                    if (isValidInternationalPhone(line)) {
                        phoneList.add(line);
                    }
                }
            }
        } else if (ext.equals("xlsx") || ext.equals("xls")) {
            try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
                Sheet sheet = wb.getSheetAt(0);
                for (Row row : sheet) {
                    Cell cell = row.getCell(0);
                    if (cell == null) continue;
                    String phone = cell.toString().trim().replaceAll("[^0-9]", "");
                    if (isValidInternationalPhone(phone)) {
                        phoneList.add(phone);
                    }
                }
            }
        }
        return phoneList.stream().distinct().collect(Collectors.toList());
    }

    public void savePreTask(String subAccount, String preTaskId, List<String> phoneList) throws Exception {
        SmsScheduleNumberTask task = new SmsScheduleNumberTask();
        task.setSubAccount(subAccount);
        String nowSec = String.valueOf(System.currentTimeMillis() / 1000);
        task.setSubmitTime(nowSec);
        task.setPreTaskId(preTaskId);
        long expireSec = (System.currentTimeMillis() / 1000) + 15 * 60;
        task.setValidTill(String.valueOf(expireSec));

        numberTaskMapper.insertScheduleNumberTask(
                task.getSubAccount(),
                task.getSubmitTime(),
                task.getPreTaskId(),
                task.getValidTill()
        );

        String userDirPath = REMOTE_PHONE_PATH + subAccount + "\\";
        File userDir = new File(userDirPath);
        if (!userDir.exists()) {
            userDir.mkdirs(); // 自动创建用户目录
        }

        File phoneFile = new File(userDir, preTaskId + ".txt");
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(phoneFile))) {
            for (int i = 0; i < phoneList.size(); i++) {
                bw.write(phoneList.get(i));
                if (i < phoneList.size() - 1) {
                    bw.newLine();
                }
            }
        }

        if (!phoneFile.exists() || phoneFile.length() == 0) {
            throw new Exception("号码文件上传测试服务器失败，请检查目录权限");
        }
    }

    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf(".");
        return lastDot == -1 ? "" : fileName.substring(lastDot + 1);
    }

    private boolean isValidInternationalPhone(String phone) {
        if (phone == null) return false;
        int len = phone.length();
        return len >= MIN_PHONE_LENGTH && len <= MAX_PHONE_LENGTH && phone.matches("^\\d+$");
    }
}