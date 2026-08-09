package com.texcellsms.sms.util;

import java.io.*;
import java.util.*;

public class NumberComparator {

    public static void main(String[] args) {
        // 文件路径
        String file1Path = "D:\\文件比对\\号码文件1.txt";
        String file2Path = "D:\\文件比对\\号码文件2.txt";
        String outputPath = "D:\\文件比对\\号码文件1有_文件2没有.txt";

        try {
            long startTime = System.currentTimeMillis();

            // 执行比对
            CompareResult result = compareFiles(file1Path, file2Path);

            // 输出统计信息
            System.out.println("========== 比对结果 ==========");
            System.out.println("文件1总号码数：" + result.totalFile1);
            System.out.println("文件2总号码数：" + result.totalFile2);
            System.out.println("文件1独有的号码数：" + result.missingNumbers.size());
            System.out.println("文件1和文件2共有的号码数：" + (result.totalFile1 - result.missingNumbers.size()));
            System.out.println("================================");

            // 保存结果
            if (result.missingNumbers.isEmpty()) {
                System.out.println("✓ 所有号码在文件2中都存在！");
            } else {
                writeResult(outputPath, result.missingNumbers);
                System.out.println("✓ 结果已保存到：" + outputPath);

                // 显示前20个示例
                System.out.println("\n前20个独有号码示例：");
                int showCount = Math.min(20, result.missingNumbers.size());
                for (int i = 0; i < showCount; i++) {
                    System.out.println("  " + (i+1) + ". " + result.missingNumbers.get(i));
                }
                if (result.missingNumbers.size() > 20) {
                    System.out.println("  ... 还有 " + (result.missingNumbers.size() - 20) + " 个");
                }
            }

            long endTime = System.currentTimeMillis();
            System.out.println("\n耗时：" + (endTime - startTime) + "ms");

        } catch (FileNotFoundException e) {
            System.err.println("❌ 错误：找不到文件！请确认文件路径是否正确。");
            System.err.println("   请检查 D:\\文件比对\\ 目录下是否存在这两个文件");
            e.printStackTrace();
        } catch (IOException e) {
            System.err.println("❌ 错误：读取文件失败！");
            e.printStackTrace();
        }
    }

    /**
     * 比对两个文件
     */
    public static CompareResult compareFiles(String file1Path, String file2Path)
            throws IOException {

        System.out.println("正在读取文件2...");
        Set<String> file2Numbers = readNumbersToSet(file2Path);

        System.out.println("正在读取文件1...");
        List<String> file1Numbers = readNumbersToList(file1Path);

        System.out.println("正在比对号码...");
        List<String> missingNumbers = new ArrayList<>();
        for (String number : file1Numbers) {
            if (!file2Numbers.contains(number)) {
                missingNumbers.add(number);
            }
        }

        return new CompareResult(file1Numbers.size(), file2Numbers.size(), missingNumbers);
    }

    /**
     * 读取文件到Set集合（用于快速查找）
     */
    private static Set<String> readNumbersToSet(String filePath) throws IOException {
        Set<String> numbers = new HashSet<>();
        int lineCount = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    numbers.add(trimmed);
                    lineCount++;
                }
            }
        }
        System.out.println("  读取完成：" + lineCount + " 行");
        return numbers;
    }

    /**
     * 读取文件到List集合（保留顺序）
     */
    private static List<String> readNumbersToList(String filePath) throws IOException {
        List<String> numbers = new ArrayList<>();
        int lineCount = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    numbers.add(trimmed);
                    lineCount++;
                }
            }
        }
        System.out.println("  读取完成：" + lineCount + " 行");
        return numbers;
    }

    /**
     * 将结果写入文件
     */
    private static void writeResult(String outputPath, List<String> numbers)
            throws IOException {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(outputPath), "UTF-8"))) {
            for (String number : numbers) {
                writer.write(number);
                writer.newLine();
            }
        }
    }

    /**
     * 结果封装类
     */
    static class CompareResult {
        int totalFile1;
        int totalFile2;
        List<String> missingNumbers;

        CompareResult(int totalFile1, int totalFile2, List<String> missingNumbers) {
            this.totalFile1 = totalFile1;
            this.totalFile2 = totalFile2;
            this.missingNumbers = missingNumbers;
        }
    }
}