package com.texcellsms.sms.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 批量生成插入SQL的工具类
 * 读取本地号码文件，生成insert.sql文件
 */
public class SqlGeneratorUtil {

    private static final String TABLE_NAME = "tbl_APITask";
    private static final String APITASK_ID = "2026080719141654200138201";
    private static final String CALLER = "SMSINFO";
    private static final String SUB_ACCOUNT = "CTU433BT";
    private static final String VENDOR_ID = "410999";
    private static final int DESTROY_TIME = 1786158722;
    private static final int PK_TOTAL = 1;
    private static final int PK_NUMBER = 1;
    private static final String SUBMIT_ACK = "0";
    private static final String DLRSEND_STATUS = "0";
    private static final int DLRTY_TIME = 0;

    public static void main(String[] args) {
        // 配置参数
        String inputFilePath = "D:\\phone_numbers.txt";  // 号码文件路径，每行一个号码
        String outputSqlFilePath = "D:\\insert.sql";     // 输出的SQL文件路径
        String apiTaskId = "2026080719141654200138201"; // APITaskId，可自定义
        String subAccount = "CTU433BT";                 // 子账号
        String vendorId = "410999";                     // 供应商ID

        generateInsertSql(inputFilePath, outputSqlFilePath, apiTaskId, subAccount, vendorId);
    }

    /**
     * 生成插入SQL文件
     *
     * @param inputFilePath  号码文件路径
     * @param outputSqlFilePath 输出SQL文件路径
     * @param apiTaskId      APITaskId
     * @param subAccount     子账号
     * @param vendorId       供应商ID
     */
    public static void generateInsertSql(String inputFilePath, String outputSqlFilePath,
                                         String apiTaskId, String subAccount, String vendorId) {
        // 获取当前时间
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String currentTime = sdf.format(new Date());

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(inputFilePath), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(new FileOutputStream(outputSqlFilePath), StandardCharsets.UTF_8))) {

            // 写入SQL头部注释
            writer.write("-- 批量插入SQL，生成时间: " + currentTime);
            writer.newLine();
            writer.write("-- APITaskId: " + apiTaskId);
            writer.newLine();
            writer.write("-- 共 " + getLineCount(inputFilePath) + " 条记录");
            writer.newLine();
            writer.newLine();

            // 写入SET语句（可选，用于调试）
            writer.write("SET @currentTime = '" + currentTime + "';");
            writer.newLine();
            writer.newLine();

            String line;
            int count = 0;
            boolean firstRow = true;
            StringBuilder batchSql = new StringBuilder();
            int batchSize = 100; // 每批100条

            // 开始构建INSERT语句
            String insertHeader = "INSERT INTO `" + TABLE_NAME + "` " +
                    "(`UNID`, `APITaskId`, `SubmitMsgId`, `Caller`, `Callee`, `SubmitMsgTime`, " +
                    "`SubmitAck`, `RecvDRTime`, `RecvDRStatus`, `RecvDRError`, `SubAccount`, " +
                    "`DestroyTime`, `DLRSendStatus`, `DLRTryTime`, `VendorID`, `PKTotal`, `PKNumber`) VALUES \n";

            writer.write(insertHeader);

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                    continue;
                }

                // 验证号码格式（简单验证）
                if (!line.matches("\\d+")) {
                    System.err.println("跳过非法号码: " + line);
                    continue;
                }

                count++;

                // 生成SubmitMsgId（8位随机十六进制）
                String submitMsgId = generateRandomHex(8);

                // 构建单条记录
                String row = "(" +
                        "NULL, " +
                        "'" + apiTaskId + "', " +
                        "'" + submitMsgId + "', " +
                        "'" + CALLER + "', " +
                        "'" + line + "', " +
                        "'" + currentTime + "', " +
                        "'" + SUBMIT_ACK + "', " +
                        "'" + currentTime + "', " +  // RecvDRTime = 当前时间
                        "'DELIVRD', " +
                        "'000', " +
                        "'" + subAccount + "', " +
                        DESTROY_TIME + ", " +
                        DLRSEND_STATUS + ", " +
                        DLRTY_TIME + ", " +
                        "'" + vendorId + "', " +
                        PK_TOTAL + ", " +
                        PK_NUMBER +
                        ")";

                if (count % batchSize == 0) {
                    // 批次结束，写入最后一行带分号
                    writer.write(row + ";");
                    writer.newLine();
                    writer.newLine();
                    // 如果是最后一批，不需要再写INSERT HEADER
                    if (reader.ready()) {
                        writer.write(insertHeader);
                    }
                } else {
                    // 批次内，逗号分隔
                    writer.write(row + ",");
                    writer.newLine();
                }

                // 每1000条打印进度
                if (count % 1000 == 0) {
                    System.out.println("已生成 " + count + " 条SQL");
                }
            }

            // 如果最后不是整批结束，需要处理最后的逗号
            // 由于上面的逻辑，每批结束会写分号，所以不需要额外处理

            writer.newLine();
            writer.write("-- 生成完成，共 " + count + " 条记录");
            writer.newLine();

            System.out.println("SQL文件生成完成！");
            System.out.println("输出文件: " + outputSqlFilePath);
            System.out.println("总记录数: " + count);
            System.out.println("APITaskId: " + apiTaskId);
            System.out.println("RecvDRTime: " + currentTime);
            System.out.println("RecvDRStatus: DELIVRD");
            System.out.println("RecvDRError: 000");

        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("生成SQL文件失败: " + e.getMessage());
        }
    }

    /**
     * 生成指定长度的随机十六进制字符串
     */
    private static String generateRandomHex(int length) {
        StringBuilder sb = new StringBuilder();
        String chars = "0123456789abcdef";
        for (int i = 0; i < length; i++) {
            int index = (int) (Math.random() * 16);
            sb.append(chars.charAt(index));
        }
        return sb.toString();
    }

    /**
     * 获取文件行数
     */
    private static int getLineCount(String filePath) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8))) {
            int count = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith("//")) {
                    count++;
                }
            }
            return count;
        }
    }
}