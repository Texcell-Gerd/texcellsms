package com.texcellsms.sms.service;

import com.texcellsms.sms.mapper.webportal.ScheduleNumberTaskMapper;
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
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SmsUploadService {

    @Autowired
    private ScheduleNumberTaskMapper numberTaskMapper;

    private static final String[] ALLOW = {"txt", "csv", "xlsx", "xls"};
    private static final int MIN_LEN = 7;
    private static final int MAX_LEN = 22;
    private static final int MAX_PHONE_LIMIT = 200000;

    public List<String> parsePhone(MultipartFile file) throws Exception {
        List<String> list = new ArrayList<>();
        String name = file.getOriginalFilename();
        if (name == null) throw new Exception("文件名无效");
        String ext = getExt(name).toLowerCase();

        if (!isAllowed(ext)) throw new Exception("仅支持 txt/csv/xlsx/xls");

        if (ext.equals("txt") || ext.equals("csv")) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (list.size() >= MAX_PHONE_LIMIT) {
                        throw new Exception("号码数量超过限制，单次最多允许上传 200000 个号码");
                    }
                    String phone = line.trim().replaceAll("[^0-9]", "");
                    if (isValid(phone)) list.add(phone);
                }
            }
        } else {
            try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
                Sheet sheet = wb.getSheetAt(0);
                for (Row row : sheet) {
                    if (list.size() >= MAX_PHONE_LIMIT) {
                        throw new Exception("号码数量超过限制，单次最多允许上传 200000 个号码");
                    }
                    Cell c = row.getCell(0);
                    if (c == null) continue;
                    String phone = c.toString().trim().replaceAll("[^0-9]", "");
                    if (isValid(phone)) list.add(phone);
                }
            }
        }

        if (list.size() > MAX_PHONE_LIMIT) {
            throw new Exception("号码数量超过限制，单次最多允许上传 200000 个号码");
        }
        return list.stream().distinct().collect(Collectors.toList());
    }

    public void savePreTask(String subAccount, String preId, List<String> phones) throws Exception {
        if (phones == null || phones.isEmpty()) throw new Exception("号码不能为空");
        if (phones.size() > MAX_PHONE_LIMIT) {
            throw new Exception("号码数量超过限制，单次最多允许上传 200000 个号码");
        }

        // ====================== 文件上传路径 ======================
        String userDir = SmsSendService.PRE_PHONE_ROOT + subAccount + "/";
        File dir = new File(userDir);
        if (!dir.exists()) dir.mkdirs();

        File file = new File(dir, preId + ".txt");
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
            for (int i = 0; i < phones.size(); i++) {
                bw.write(phones.get(i));
                if (i < phones.size() - 1) bw.newLine();
            }
        }

        if (!file.exists() || file.length() == 0) {
            throw new Exception("预上传文件生成失败");
        }

        long nowSec = System.currentTimeMillis() / 1000;
        long validTill = nowSec + 15 * 60;
        numberTaskMapper.insertScheduleNumberTask(
                subAccount,
                String.valueOf(nowSec),
                preId,
                String.valueOf(validTill)
        );
    }

    private boolean isAllowed(String ext) {
        for (String s : ALLOW) if (s.equals(ext)) return true;
        return false;
    }

    private boolean isValid(String phone) {
        return phone != null && phone.length() >= MIN_LEN && phone.length() <= MAX_LEN && phone.matches("\\d+");
    }

    private String getExt(String name) {
        int dot = name.lastIndexOf(".");
        return dot == -1 ? "" : name.substring(dot + 1);
    }
}