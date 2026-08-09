package model;

import java.time.LocalDateTime;

/**
 * 发票报销记录 — 存储发票识别结果 + 核验状态 + 报销信息。
 */
public record InvoiceRecord(
    // === 发票基础信息 (来自百度AI OCR) ===
    String invoiceCode,       // 发票代码
    String invoiceNumber,     // 发票号码
    String invoiceType,       // 发票类型: 增值税专票/普票/全电发票等
    String invoiceDate,       // 开票日期
    String checkCode,         // 校验码
    String sellerName,        // 销方名称
    String sellerTaxId,       // 销方税号
    String buyerName,         // 购方名称
    String buyerTaxId,        // 购方税号
    String amountWithoutTax,  // 不含税金额
    String taxAmount,         // 税额
    String totalAmount,       // 价税合计

    // === 核验结果 ===
    String verifyStatus,      // 真票 / 假票 / 作废票 / 查无此票 / 核验失败
    String verifyTime,        // 核验时间

    // === 报销信息 ===
    String userId,            // 报销人微信ID
    String reason,            // 报销理由
    String reimburseDate,     // 报销日期
    double reimburseAmount    // 报销金额 (等于价税合计)
) {
    /** 构建核验失败的占位记录。 */
    public static InvoiceRecord verifyFailed(String reason, String userId) {
        return new InvoiceRecord("", "", "", "", "", "", "", "", "", "", "", "",
            "核验失败: " + reason, java.time.LocalDateTime.now().toString(),
            userId, "", java.time.LocalDateTime.now().toString(), 0);
    }

    /** 从百度AI OCR返回的字段构建记录（核验前）。 */
    public static InvoiceRecord fromOcrResult(java.util.Map<String, String> fields, String userId, String reason) {
        return new InvoiceRecord(
            fields.getOrDefault("InvoiceCode", ""),
            fields.getOrDefault("InvoiceNum", ""),
            fields.getOrDefault("InvoiceType", ""),
            fields.getOrDefault("InvoiceDate", ""),
            fields.getOrDefault("CheckCode", ""),
            fields.getOrDefault("SellerName", ""),
            fields.getOrDefault("SellerRegisterNum", ""),
            fields.getOrDefault("PurchaserName", ""),
            fields.getOrDefault("PurchaserRegisterNum", ""),
            fields.getOrDefault("AmountInFiguers", ""),
            fields.getOrDefault("TaxAmount", ""),
            fields.getOrDefault("TotalAmount", ""),
            "", "",
            userId, reason, java.time.LocalDateTime.now().toString(),
            parseAmount(fields.getOrDefault("TotalAmount", "0"))
        );
    }

    private static double parseAmount(String s) {
        try { return Double.parseDouble(s); }
        catch (NumberFormatException e) { return 0; }
    }
}
