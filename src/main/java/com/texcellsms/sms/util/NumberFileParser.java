package com.texcellsms.sms.util;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.opencsv.CSVReader;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 号码解析工具：支持所有文件格式解析
 */
public class NumberFileParser {

    // 支持的文件后缀
    private static final Set<String> ALLOWED_EXT = new HashSet<>();
    // 最大文件大小 2MB
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024;

    static {
        ALLOWED_EXT.add("xlsx");
        ALLOWED_EXT.add("xls");
        ALLOWED_EXT.add("csv");
        ALLOWED_EXT.add("txt");
    }

    /**
     * 从文本解析号码（英文逗号/换行分隔）
     */
    public static List<String> parseFromText(String text) {
        List<String> numbers = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return numbers;
        }
        // 按逗号或换行拆分
        String[] parts = text.split("[,\\n]");
        for (String part : parts) {
            String clean = part.trim().replaceAll("[^0-9+]", ""); // 只保留数字和+
            if (!clean.isEmpty()) {
                numbers.add(clean);
            }
        }
        // 去重
        return new ArrayList<>(new HashSet<>(numbers));
    }

    /**
     * 从文件解析号码（修复EasyExcel调用错误）
     */
    public static List<String> parseFromFile(MultipartFile file) throws Exception {
        // 1. 基础校验
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小超过2MB限制");
        }

        // 2. 校验文件后缀
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.contains(".")) {
            throw new IllegalArgumentException("文件名无效，无法识别格式");
        }
        String ext = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        if (!ALLOWED_EXT.contains(ext)) {
            throw new IllegalArgumentException("不支持的文件格式，仅支持xlsx/xls/csv/txt");
        }

        // 3. 按格式解析
        List<String> numbers = new ArrayList<>();
        try (InputStream inputStream = file.getInputStream()) {
            switch (ext) {
                case "xlsx":
                case "xls":
                    // 修复核心：正确调用EasyExcel 3.x API
                    ExcelNumberListener listener = new ExcelNumberListener();
                    EasyExcel.read(inputStream, NumberData.class, listener)
                            .sheet() // 读取第一个sheet
                            .headRowNumber(1) // 第一行是表头
                            .doRead();
                    numbers = listener.getNumbers();
                    break;

                case "csv":
                    try (CSVReader reader = new CSVReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                        String[] line;
                        while ((line = reader.readNext()) != null) {
                            for (String cell : line) {
                                if (cell != null && !cell.trim().isEmpty()) {
                                    numbers.add(cell.trim());
                                }
                            }
                        }
                    }
                    break;

                case "txt":
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            String[] parts = line.split("[,\\n]");
                            for (String part : parts) {
                                if (part != null && !part.trim().isEmpty()) {
                                    numbers.add(part.trim());
                                }
                            }
                        }
                    }
                    break;
            }
        }

        // 4. 号码清洗和去重
        Set<String> uniqueNumbers = new HashSet<>();
        for (String num : numbers) {
            String cleanNum = num.replaceAll("[^0-9+]", "");
            if (!cleanNum.isEmpty()) {
                uniqueNumbers.add(cleanNum);
            }
        }

        return new ArrayList<>(uniqueNumbers);
    }

    /**
     * EasyExcel监听器（用于接收Excel解析结果）
     */
    private static class ExcelNumberListener extends AnalysisEventListener<NumberData> {
        private final List<String> numbers = new ArrayList<>();

        @Override
        public void invoke(NumberData data, AnalysisContext context) {
            // 处理每一行数据
            if (data != null && data.getNumber() != null && !data.getNumber().trim().isEmpty()) {
                numbers.add(data.getNumber().trim());
            }
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            // 解析完成后无需处理
        }

        public List<String> getNumbers() {
            return this.numbers;
        }
    }

    /**
     * Excel实体类（对应号码列）
     */
    public static class NumberData {
        private String number;

        public String getNumber() {
            return number;
        }

        public void setNumber(String number) {
            this.number = number;
        }
    }
}