package service;

import model.InvoiceRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import util.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报销 Excel 服务 — 追加写入、查重、查询。
 * 使用 Apache POI + synchronized 保证并发安全。
 * <p>
 * Excel 自定义格式（12列）：
 * <pre>
 * A:报销日期 | B:报销人 | C:发票代码 | D:发票号码 | E:发票类型 | F:开票日期
 * G:销方名称 | H:价税合计 | I:报销金额 | J:报销理由 | K:核验状态 | L:核验时间
 * </pre>
 */
@Service
public class ReimbursementExcelService {

    private final String excelPath;
    private final Logger logger;
    private final Object writeLock = new Object();

    /** 表头 — 14列 */
    private static final String[] HEADERS = {
        "报销日期", "报销人", "发票代码", "发票号码", "发票类型", "开票日期",
        "销方名称", "价税合计", "报销金额", "报销理由", "核验状态", "核验时间",
        "校验码", "不含税金额"
    };

    /** 列索引 */
    private static final int COL_INVOICE_CODE = 2;
    private static final int COL_INVOICE_NUM = 3;
    private static final int COL_INVOICE_TYPE = 4;
    private static final int COL_INVOICE_DATE = 5;
    private static final int COL_SELLER_NAME = 6;
    private static final int COL_TOTAL_AMOUNT = 7;
    private static final int COL_REASON = 9;
    private static final int COL_VERIFY_STATUS = 10;
    private static final int COL_CHECK_CODE = 12;
    private static final int COL_AMOUNT_WITHOUT_TAX = 13;

    public ReimbursementExcelService(
            @Value("${reimbursement.excel.path:./reimbursement_records.xlsx}") String excelPath,
            Logger logger) {
        this.excelPath = excelPath;
        this.logger = logger;
        try {
            this.logger.log("INFO", "报销Excel服务已初始化，路径: " + excelPath
                + " → 绝对路径: " + new File(excelPath).getAbsolutePath());
        } catch (Exception e) {
            this.logger.log("ERROR", "报销Excel路径解析失败: " + e.getMessage());
        }
    }

    // ==================== 初始化 ====================

    /**
     * 确保父目录存在（文件本身由 appendRecord 在锁内创建）。
     */
    private void ensureParentDir() throws IOException {
        File file = new File(excelPath);
        Path parent = file.toPath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /** 在 workbook 中创建表头行 + 样式（仅新文件时调用）。 */
    private void createHeaderRow(Workbook workbook) {
        Sheet sheet = workbook.getSheetAt(0);
        Row headerRow = sheet.createRow(0);
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setFontHeightInPoints((short) 11);
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setBorderBottom(BorderStyle.THIN);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);

        for (int i = 0; i < HEADERS.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(HEADERS[i]);
            cell.setCellStyle(headerStyle);
        }

        int[] widths = {14, 20, 18, 18, 14, 14, 28, 14, 14, 30, 14, 22};
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
        sheet.createFreezePane(0, 1);
    }

    // ==================== 查重 ====================

    /**
     * 检查发票是否已存在于 Excel 中。
     * @return true 表示已存在（重复）
     */
    public boolean isDuplicate(String invoiceCode, String invoiceNumber) {
        if (invoiceCode.isBlank() || invoiceNumber.isBlank()) return false;

        File file = new File(excelPath);
        if (!file.exists()) return false;

        try (Workbook workbook = new XSSFWorkbook(new FileInputStream(file))) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int rowIdx = 1; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                Row row = sheet.getRow(rowIdx);
                if (row == null) continue;
                String existingCode = getCellString(row, COL_INVOICE_CODE);
                String existingNum = getCellString(row, COL_INVOICE_NUM);
                if (invoiceCode.equals(existingCode) && invoiceNumber.equals(existingNum)) {
                    logger.log("INFO", "发票重复: " + invoiceCode + " / " + invoiceNumber);
                    return true;
                }
            }
        } catch (Exception e) {
            logger.log("ERROR", "查重失败: " + e.getMessage() + " (文件: " + file.getAbsolutePath() + ")");
        }
        return false;
    }

    // ==================== 追加写入 ====================

    /**
     * 追加一条报销记录到 Excel 末尾。
     * @return 追加后的行号，-1 表示失败
     */
    public int appendRecord(InvoiceRecord record) {
        synchronized (writeLock) {
            int[] delays = {100, 500, 1500};
            for (int attempt = 0; attempt < delays.length; attempt++) {
                File file = new File(excelPath);
                boolean isNew = !file.exists();

                try {
                    if (isNew) {
                        ensureParentDir();
                    }

                    // 读取已有内容到内存（不锁文件，避免Windows上多句柄冲突）
                    Workbook workbook;
                    if (isNew) {
                        workbook = new XSSFWorkbook();
                        workbook.createSheet("报销记录");
                        createHeaderRow(workbook);
                        logger.log("INFO", "已创建报销Excel模板: " + file.getAbsolutePath());
                    } else {
                        byte[] existing;
                        try {
                            existing = Files.readAllBytes(file.toPath());
                        } catch (IOException e) {
                            // 文件被锁定无法读取，等一会重试
                            throw e;
                        }
                        if (existing.length == 0) {
                            workbook = new XSSFWorkbook();
                            workbook.createSheet("报销记录");
                            createHeaderRow(workbook);
                        } else {
                            workbook = new XSSFWorkbook(new ByteArrayInputStream(existing));
                        }
                    }

                    Sheet sheet = workbook.getSheetAt(0);
                    int newRowIdx = sheet.getLastRowNum() + 1;

                    Row row = sheet.createRow(newRowIdx);
                    writeRow(row, record);

                    // 写回文件
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        workbook.write(fos);
                    }
                    workbook.close();

                    logger.log("INFO", "报销记录已写入 Excel 第" + (newRowIdx + 1) + "行: "
                        + record.invoiceNumber() + " " + record.totalAmount() + "元");
                    return newRowIdx;

                } catch (Exception e) {
                    if (attempt < delays.length - 1) {
                        logger.log("WARN", "写入报销Excel失败(第" + (attempt + 1) + "次)，"
                            + delays[attempt] + "ms后重试: " + e.getMessage());
                        try { Thread.sleep(delays[attempt]); } catch (InterruptedException ignored) {}
                    } else {
                        logger.log("ERROR", "写入报销Excel失败(已重试" + delays.length + "次): " + e.getMessage()
                            + " — 文件: " + file.getAbsolutePath() + "，请检查是否用Excel打开了该文件");
                        return -1;
                    }
                }
            }
            return -1;
        }
    }

    // ==================== 查询 ====================

    /**
     * 按发票号码查找报销记录，返回 OCR 原始字段 Map（用于重新验真）。
     * @return 字段 Map，找不到返回 null
     */
    public Map<String, String> findByInvoiceNum(String invoiceNumber) {
        if (invoiceNumber == null || invoiceNumber.isBlank()) return null;

        File file = new File(excelPath);
        if (!file.exists()) return null;

        try (Workbook workbook = new XSSFWorkbook(new FileInputStream(file))) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int rowIdx = 1; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                Row row = sheet.getRow(rowIdx);
                if (row == null) continue;
                String existingNum = getCellString(row, COL_INVOICE_NUM);
                if (invoiceNumber.equals(existingNum)) {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("InvoiceCode", getCellString(row, COL_INVOICE_CODE));
                    fields.put("InvoiceNum", getCellString(row, COL_INVOICE_NUM));
                    fields.put("InvoiceType", getCellString(row, COL_INVOICE_TYPE));
                    fields.put("InvoiceDate", getCellString(row, COL_INVOICE_DATE));
                    fields.put("SellerName", getCellString(row, COL_SELLER_NAME));
                    fields.put("TotalAmount", getCellString(row, COL_TOTAL_AMOUNT));
                    fields.put("CheckCode", getCellString(row, COL_CHECK_CODE));
                    fields.put("AmountInFiguers", getCellString(row, COL_AMOUNT_WITHOUT_TAX));
                    fields.put("VerifyStatus", getCellString(row, COL_VERIFY_STATUS));
                    fields.put("Reason", getCellString(row, COL_REASON));
                    logger.log("INFO", "从Excel找到发票: " + invoiceNumber + " (第" + (rowIdx + 1) + "行)");
                    return fields;
                }
            }
        } catch (Exception e) {
            logger.log("ERROR", "查找发票失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 查询某个用户的所有报销记录。
     */
    public List<String> queryByUser(String userId) {
        List<String> results = new ArrayList<>();
        if (userId == null || userId.isBlank()) return results;

        File file = new File(excelPath);
        if (!file.exists()) return results;

        try {
            try (Workbook workbook = new XSSFWorkbook(new FileInputStream(file))) {
                Sheet sheet = workbook.getSheetAt(0);
                for (int rowIdx = 1; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                    Row row = sheet.getRow(rowIdx);
                    if (row == null) continue;
                    String rowUserId = getCellString(row, 1); // B列 = 报销人
                    if (userId.equals(rowUserId)) {
                        String invoiceNum = getCellString(row, COL_INVOICE_NUM);
                        String sellerName = getCellString(row, 6);  // G列
                        String totalAmount = getCellString(row, 7); // H列
                        String reason = getCellString(row, 9);       // J列
                        String verifyStatus = getCellString(row, 10); // K列
                        String date = getCellString(row, 0);         // A列
                        results.add(date + " | " + invoiceNum + " | " + sellerName
                            + " | ¥" + totalAmount + " | " + verifyStatus
                            + " | " + (reason.length() > 20 ? reason.substring(0, 20) + "..." : reason));
                    }
                }
            }
        } catch (Exception e) {
            logger.log("ERROR", "查询报销记录失败: " + e.getMessage());
        }
        return results;
    }

    // ==================== 内部工具 ====================

    private void writeRow(Row row, InvoiceRecord r) {
        row.createCell(0).setCellValue(r.reimburseDate() != null ? r.reimburseDate() : "");
        row.createCell(1).setCellValue(r.userId() != null ? r.userId() : "");
        row.createCell(COL_INVOICE_CODE).setCellValue(r.invoiceCode() != null ? r.invoiceCode() : "");
        row.createCell(COL_INVOICE_NUM).setCellValue(r.invoiceNumber() != null ? r.invoiceNumber() : "");
        row.createCell(COL_INVOICE_TYPE).setCellValue(r.invoiceType() != null ? r.invoiceType() : "");
        row.createCell(COL_INVOICE_DATE).setCellValue(r.invoiceDate() != null ? r.invoiceDate() : "");
        row.createCell(COL_SELLER_NAME).setCellValue(r.sellerName() != null ? r.sellerName() : "");
        row.createCell(COL_TOTAL_AMOUNT).setCellValue(r.totalAmount() != null ? r.totalAmount() : "");
        row.createCell(8).setCellValue(r.reimburseAmount());
        row.createCell(COL_REASON).setCellValue(r.reason() != null ? r.reason() : "");
        row.createCell(COL_VERIFY_STATUS).setCellValue(r.verifyStatus() != null ? r.verifyStatus() : "");
        row.createCell(11).setCellValue(r.verifyTime() != null ? r.verifyTime() : "");
        row.createCell(COL_CHECK_CODE).setCellValue(r.checkCode() != null ? r.checkCode() : "");
        row.createCell(COL_AMOUNT_WITHOUT_TAX).setCellValue(r.amountWithoutTax() != null ? r.amountWithoutTax() : "");
    }

    private String getCellString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                yield v == (long) v ? String.valueOf((long) v) : String.valueOf(v);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }
}
