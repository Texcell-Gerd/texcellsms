package com.texcellsms.sms.service;

import com.texcellsms.sms.mapper.webportal.ScheduleNumberTaskMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SmsUploadService {

    @Autowired
    private ScheduleNumberTaskMapper numberTaskMapper;

    private static final String[] ALLOW = {"txt", "csv", "xlsx", "xls"};
    private static final int MIN_LEN = 8;
    private static final int MAX_LEN = 21;

    // ✅ 小文件限制（原有接口 uploadCallee）
    private static final long MAX_FILE_SIZE_SMALL = 3 * 1024 * 1024L; // 3MB

    // ✅ 大文件限制（批量接口 uploadCalleeBatch）
    private static final long MAX_FILE_SIZE_BATCH = 20 * 1024 * 1024L; // 20MB

    /**
     * 解析结果内部类
     */
    public static class ParseResult {
        private List<String> phoneList;
        private int totalLineCount;

        public ParseResult(List<String> phoneList, int totalLineCount) {
            this.phoneList = phoneList;
            this.totalLineCount = totalLineCount;
        }

        public List<String> getPhoneList() { return phoneList; }
        public int getTotalLineCount() { return totalLineCount; }
    }

    /**
     * 解析上传文件中的手机号（支持 TXT/CSV/Excel）
     */
    public ParseResult parsePhone(MultipartFile file) throws Exception {
        if (file.getSize() > MAX_FILE_SIZE_SMALL) {
            throw new Exception("File size cannot exceed 3MB");
        }
        return parseCommon(file);
    }

    public ParseResult parsePhoneBatch(MultipartFile file) throws Exception {
        if (file.getSize() > MAX_FILE_SIZE_BATCH) {
            throw new Exception("File size cannot exceed 20MB");
        }
        return parseCommon(file);
    }


    /**
     * 公共解析逻辑，Excel、CSV解析方法
     */
    private ParseResult parseCommon(MultipartFile file) throws Exception {
        List<String> list = new ArrayList<>();
        int totalLineCount = 0;

        String name = file.getOriginalFilename();
        if (name == null) throw new Exception("Invalid file name");
        String ext = getExt(name).toLowerCase();

        if (!isAllowed(ext)) throw new Exception("Only txt/csv/xlsx/xls files are supported");

        if ("txt".equals(ext)) {
            // txt 一行一个号码
            try (BufferedReader br = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    totalLineCount++;
                    String phone = line.trim().replaceAll("[^0-9]", "");
                    if (isValid(phone)) list.add(phone);
                }
            }
        } else if ("csv".equals(ext)) {
            InputStream originalIs = file.getInputStream();
            byte[] fileBytes = toByteArray(originalIs);
            originalIs.close();

            Charset charset = detectCsvCharset(fileBytes);

            try (InputStream bais = new ByteArrayInputStream(fileBytes);
                 BufferedReader br = new BufferedReader(new InputStreamReader(bais, charset));
                 CSVParser parser = CSVFormat.DEFAULT.withSkipHeaderRecord().parse(br)) {

                for (CSVRecord record : parser) {
                    totalLineCount++;
                    if (record.size() < 1) continue;
                    String cellValue = record.get(0);
                    String phone = cellValue.trim().replaceAll("[^0-9]", "");
                    if (isValid(phone)) {
                        list.add(phone);
                    }
                }
            }
        } else {
            // ========== Excel：处理数字单元格，避免长数字丢失精度 & 公式空单元格NPE ==========
            try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
                Sheet sheet = wb.getSheetAt(0);
                for (Row row : sheet) {
                    totalLineCount++;
                    Cell c = row.getCell(0);
                    if (c == null) continue;
                    String cellText = getCellSafeText(c);
                    String phone = cellText.trim().replaceAll("[^0-9]", "");
                    if (isValid(phone)) list.add(phone);
                }
            }
        }

        List<String> distinctList = list.stream().distinct().collect(Collectors.toList());
        return new ParseResult(distinctList, totalLineCount);
    }


    /**
     * Java8：读取InputStream转byte[]，替代 readAllBytes()
     */
    private byte[] toByteArray(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = is.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        return bos.toByteArray();
    }


    /**
     * ✅ Excel单元格安全读取
     */
    private String getCellSafeText(Cell cell) {
        if (cell == null) return "";
        CellType cellType = cell.getCellType();
        if(cellType == CellType.FORMULA){
            cellType = cell.getCachedFormulaResultType();
        }
        switch (cellType) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                if(DateUtil.isCellDateFormatted(cell)){
                    return cell.getDateCellValue().toString();
                }else{
                    DataFormatter formatter = new DataFormatter();
                    return formatter.formatCellValue(cell).trim();
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                return "";
        }
    }

    /**
     * CSV编码探测，入参 byte[]
     */
    private Charset detectCsvCharset(byte[] fileBytes) {
        // 判断UTF‑8 BOM
        if (fileBytes.length >= 3
                && fileBytes[0] == (byte)0xEF
                && fileBytes[1] == (byte)0xBB
                && fileBytes[2] == (byte)0xBF) {
            return StandardCharsets.UTF_8;
        }
        // 业务策略：优先UTF‑8；Windows导出GBK可自行调整
        return StandardCharsets.UTF_8;
    }


    /**
     * 保存预上传任务（15分钟有效期）
     */
    public void savePreTask(String subAccount, String preId, List<String> phones) throws Exception {
        if (phones == null || phones.isEmpty()) throw new Exception("Recipient numbers cannot be empty");

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
            throw new Exception("Failed to generate temporary upload file");
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