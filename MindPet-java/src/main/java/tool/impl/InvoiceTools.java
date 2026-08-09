package tool.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.InvoiceRecord;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import service.BaiduAiClient;
import service.ReimbursementExcelService;
import tool.ToolUserContext;
import util.Logger;

import java.time.Duration;
import java.time.LocalDateTime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发票报销工具 — OCR识别 + 真伪核验 + 写入报销Excel。
 * OCR 结果缓存到 Redis（30分钟），用户补充信息时无需重发图片。
 */
@Component
public class InvoiceTools {

    private static final String OCR_CACHE_KEY = "ocr:invoice:latest:";
    private static final Duration OCR_CACHE_TTL = Duration.ofMinutes(30);

    private final BaiduAiClient baiduAi;
    private final ReimbursementExcelService excelService;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Logger logger;

    @Autowired
    public InvoiceTools(BaiduAiClient baiduAi, ReimbursementExcelService excelService,
                        StringRedisTemplate redis, Logger logger) {
        this.baiduAi = baiduAi;
        this.excelService = excelService;
        this.redis = redis;
        this.mapper = new ObjectMapper();
        this.logger = logger;
    }

    // ==================== OCR 缓存 ====================

    private void cacheOcrResult(String userId, Map<String, String> fields) {
        try {
            redis.opsForValue().set(OCR_CACHE_KEY + userId, mapper.writeValueAsString(fields), OCR_CACHE_TTL);
            logger.log("INFO", "OCR结果已缓存(30min) → " + fields.getOrDefault("InvoiceNum", "?"));
        } catch (Exception e) {
            logger.log("WARN", "OCR缓存写入失败: " + e.getMessage());
        }
    }

    private Map<String, String> getCachedOcr(String userId) {
        try {
            String json = redis.opsForValue().get(OCR_CACHE_KEY + userId);
            if (json == null || json.isBlank()) return null;
            return mapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 核心：发票报销 ====================

    @Tool(description = """
        核验发票真伪并记录报销。支持两种场景：
        1. 用户发送发票图片 → OCR识别 → 验真 → 写入Excel，OCR结果自动缓存
        2. 用户补充信息（如"类型是增值税普通发票"）→ 从缓存读取上次的OCR结果，合并用户提供的新信息后重新核验

        reason: 报销理由/事由
        invoiceType: 用户补充的发票类型（如"增值税普通发票"），仅补充信息时填写，首次核销留空
        """)
    public String reimburseInvoice(
            @ToolParam(description = "报销理由，如'差旅费''办公用品''公司统一采购'等，用户没说明则留空")
            String reason,
            @ToolParam(description = "用户补充的发票类型（如'增值税普通发票''增值税电子普通发票'），仅当用户明确说了类型时才填写，否则留空")
            String invoiceType) {

        String userId = ToolUserContext.get();
        if (userId == null || userId.isBlank()) {
            return "⚠️ 无法获取用户身份，请重新发送。";
        }

        if (!baiduAi.isConfigured()) {
            return "⚠️ 发票核验服务未配置（缺少百度AI API Key），请联系管理员。";
        }

        byte[] imageData = ToolUserContext.getImageData();
        Map<String, String> fields;
        boolean fromCache = false;

        // ========== Step 1: 获取发票数据（OCR 或缓存） ==========
        if (imageData != null && imageData.length > 0) {
            // 有图片 → OCR 识别
            logger.log("INFO", "开始处理发票报销 - 用户: " + userId + ", 图片: " + (imageData.length / 1024) + "KB");

            fields = baiduAi.ocrInvoice(imageData);
            if (fields == null || fields.isEmpty()) {
                return "❌ 发票识别失败，请确认图片清晰、完整。\n\n提示：请发送原始发票图片，不要裁剪或压缩。";
            }

            String ocrInvoiceNum = fields.getOrDefault("InvoiceNum", "");
            if (ocrInvoiceNum.isBlank()) {
                return "❌ 未能从图片中识别到发票号码。\n识别到的信息: " + fields.toString();
            }

            // 优先用完整发票类型名（InvoiceTypeOrg > InvoiceType）
            String fullType = fields.getOrDefault("InvoiceTypeOrg", "");
            if (!fullType.isBlank()) {
                fields.put("InvoiceType", fullType);
            }

            // 缓存 OCR 结果，方便后续补充信息
            cacheOcrResult(userId, fields);
            logger.log("INFO", "OCR识别: " + fields.getOrDefault("InvoiceCode", "") + " / "
                + ocrInvoiceNum + " / " + fields.getOrDefault("SellerName", "")
                + " / ¥" + fields.getOrDefault("TotalAmount", ""));

        } else {
            // 无图片 → 从缓存读取
            fields = getCachedOcr(userId);
            if (fields == null || fields.isEmpty()) {
                return "⚠️ 未找到发票图片，也没有缓存的发票信息。\n请先发送发票图片再说「核销」。";
            }
            fromCache = true;
            logger.log("INFO", "使用缓存OCR数据 - 用户: " + userId
                + ", 号码: " + fields.getOrDefault("InvoiceNum", "?"));
        }

        // ========== Step 1.5: 用户补充信息覆盖 ==========
        if (invoiceType != null && !invoiceType.isBlank()) {
            String oldType = fields.getOrDefault("InvoiceType", "");
            fields.put("InvoiceType", invoiceType.trim());
            logger.log("INFO", "用户补充发票类型: '" + oldType + "' → '" + invoiceType.trim() + "'");
            // 更新缓存
            if (fromCache) cacheOcrResult(userId, fields);
        }

        String invoiceCode = fields.getOrDefault("InvoiceCode", "");
        String invoiceNum = fields.getOrDefault("InvoiceNum", "");
        String invoiceDate = fields.getOrDefault("InvoiceDate", "");
        String sellerName = fields.getOrDefault("SellerName", "");
        String totalAmount = fields.getOrDefault("TotalAmount", "");

        // ========== Step 2: 查重 ==========
        if (excelService.isDuplicate(invoiceCode, invoiceNum)) {
            return "⚠️ 该发票已报销过，请勿重复提交。\n"
                + "发票号码: " + invoiceNum + "\n"
                + "发票代码: " + invoiceCode + "\n"
                + "金额: ¥" + totalAmount;
        }

        // ========== Step 3: 真伪核验 ==========
        String verifyStatus = baiduAi.verifyInvoice(fields);
        String now = LocalDateTime.now().toString();

        boolean isGenuine = verifyStatus.contains("真") && !verifyStatus.contains("假");
        String finalReason = reason != null && !reason.isBlank() ? reason : "（未填写）";

        if (!isGenuine) {
            InvoiceRecord failRecord = InvoiceRecord.fromOcrResult(fields, userId, finalReason);
            InvoiceRecord withVerify = new InvoiceRecord(
                failRecord.invoiceCode(), failRecord.invoiceNumber(), failRecord.invoiceType(),
                failRecord.invoiceDate(), failRecord.checkCode(), failRecord.sellerName(), failRecord.sellerTaxId(),
                failRecord.buyerName(), failRecord.buyerTaxId(),
                failRecord.amountWithoutTax(), failRecord.taxAmount(), failRecord.totalAmount(),
                verifyStatus, now,
                failRecord.userId(), failRecord.reason(), failRecord.reimburseDate(), failRecord.reimburseAmount()
            );
            excelService.appendRecord(withVerify);

            // 生成更友好的错误提示
            StringBuilder err = new StringBuilder();
            err.append("❌ 发票核验未通过: ").append(verifyStatus).append("\n");
            err.append("发票号码: ").append(invoiceNum).append("\n");
            err.append("金额: ¥").append(totalAmount).append("\n");
            err.append("销方: ").append(sellerName).append("\n\n");
            err.append("该发票已记录但标注为异常。");
            if (verifyStatus.contains("invalid") || verifyStatus.contains("缺少") || verifyStatus.contains("不足")) {
                err.append("\n💡 提示：你可以补充发票信息后重试，比如回复「类型是增值税普通发票」。");
                err.append("\n当前类型: ").append(fields.getOrDefault("InvoiceType", "未知"));
            }
            return err.toString();
        }

        // ========== Step 4: 写入 Excel ==========
        String reimburseDate = LocalDateTime.now().toString();
        InvoiceRecord record = InvoiceRecord.fromOcrResult(fields, userId, finalReason);
        InvoiceRecord verified = new InvoiceRecord(
            record.invoiceCode(), record.invoiceNumber(), record.invoiceType(),
            record.invoiceDate(), record.checkCode(), record.sellerName(), record.sellerTaxId(),
            record.buyerName(), record.buyerTaxId(),
            record.amountWithoutTax(), record.taxAmount(), record.totalAmount(),
            verifyStatus, now,
            record.userId(), record.reason(), reimburseDate, record.reimburseAmount()
        );

        int rowNum = excelService.appendRecord(verified);
        if (rowNum < 0) {
            return "❌ 写入报销Excel失败，请稍后重试。";
        }

        // ========== Step 5: 成功反馈 ==========
        StringBuilder sb = new StringBuilder();
        sb.append("✅ 发票核验通过，报销已记录\n\n");
        sb.append("📋 发票信息:\n");
        sb.append("  号码: ").append(invoiceNum).append("\n");
        if (!invoiceCode.isBlank()) sb.append("  代码: ").append(invoiceCode).append("\n");
        if (!invoiceDate.isBlank()) sb.append("  开票日期: ").append(invoiceDate).append("\n");
        if (!sellerName.isBlank()) sb.append("  销方: ").append(sellerName).append("\n");
        sb.append("  金额: ¥").append(totalAmount).append("\n");
        if (!finalReason.isBlank() && !"（未填写）".equals(finalReason))
            sb.append("  事由: ").append(finalReason).append("\n");
        sb.append("  核验状态: ").append(verifyStatus).append("\n");
        sb.append("  核验时间: ").append(now).append("\n");
        sb.append("\n报销Excel已更新（第").append(rowNum + 1).append("行）");

        // 清除缓存（核验成功后不再需要）
        try { redis.delete(OCR_CACHE_KEY + userId); } catch (Exception ignored) {}

        return sb.toString();
    }

    // ==================== 重新验真已有记录 ====================

    @Tool(description = """
        对Excel中已有的发票记录重新进行真伪核验，并更新状态。适用于：
        - 首次核验失败（如缺少参数），用户补充信息后需要重新验真
        - 之前标记为异常状态的发票需要再次验证

        数据来源：报销记录Excel文件（路径在 application.yml 的 reimbursement.excel.path 配置）
        会自动从Excel中读取该发票的已有数据，调用百度验真API重新核验，并将结果追加写入Excel。

        invoiceNumber: 发票号码，如'97386849'
        invoiceType: 用户补充/修正的发票类型（如'增值税普通发票'），没补充则留空使用原有类型
        reason: 用户补充的报销理由，没补充则留空使用原有理由
        """)
    public String reverifyInvoice(
            @ToolParam(description = "发票号码，如'97386849'、'23502000000030934618'")
            String invoiceNumber,
            @ToolParam(description = "用户补充的发票类型（如'增值税普通发票''增值税电子普通发票'），没补充则留空")
            String invoiceType,
            @ToolParam(description = "用户补充的报销理由，没补充则留空")
            String reason) {

        String userId = ToolUserContext.get();
        if (userId == null || userId.isBlank()) {
            return "⚠️ 无法获取用户身份。";
        }

        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            return "⚠️ 请提供发票号码。";
        }

        // Step 1: 从 Excel 读取已有记录
        Map<String, String> fields = excelService.findByInvoiceNum(invoiceNumber.trim());
        if (fields == null) {
            return "❌ 未找到发票号码 " + invoiceNumber.trim() + " 的记录。\n请确认号码是否正确，或先发送发票图片进行首次核销。";
        }

        String existingType = fields.getOrDefault("InvoiceType", "");
        String existingReason = fields.getOrDefault("Reason", "");
        String existingStatus = fields.getOrDefault("VerifyStatus", "");

        logger.log("INFO", "重新验真 - 号码: " + invoiceNumber + ", 原类型: " + existingType
            + ", 原状态: " + existingStatus);

        // Step 2: 用户补充信息覆盖
        boolean updated = false;
        if (invoiceType != null && !invoiceType.isBlank()) {
            logger.log("INFO", "  类型覆盖: '" + existingType + "' → '" + invoiceType.trim() + "'");
            fields.put("InvoiceType", invoiceType.trim());
            updated = true;
        }
        String finalReason;
        if (reason != null && !reason.isBlank()) {
            finalReason = reason.trim();
            updated = true;
        } else if (!existingReason.isBlank() && !"（未填写）".equals(existingReason)) {
            finalReason = existingReason;
        } else {
            finalReason = "（未填写）";
        }

        if (!updated) {
            return "⚠️ 未提供新的信息。当前发票类型: " + existingType
                + "，报销理由: " + finalReason
                + "，核验状态: " + existingStatus
                + "\n\n如需重新验真，请补充发票类型或理由，如「重新验真97386849，类型是增值税普通发票」。";
        }

        // Step 3: 重新调用验真 API
        String verifyStatus = baiduAi.verifyInvoice(fields);
        String now = LocalDateTime.now().toString();

        boolean isGenuine = verifyStatus.contains("真") && !verifyStatus.contains("假");

        // Step 4: 写入 Excel（追加新行，保留旧行作为历史）
        InvoiceRecord record = InvoiceRecord.fromOcrResult(fields, userId, finalReason);
        InvoiceRecord newRecord = new InvoiceRecord(
            record.invoiceCode(), record.invoiceNumber(), record.invoiceType(),
            record.invoiceDate(), record.checkCode(), record.sellerName(), record.sellerTaxId(),
            record.buyerName(), record.buyerTaxId(),
            record.amountWithoutTax(), record.taxAmount(), record.totalAmount(),
            verifyStatus, now,
            record.userId(), finalReason, LocalDateTime.now().toString(), record.reimburseAmount()
        );

        int rowNum = excelService.appendRecord(newRecord);
        if (rowNum < 0) {
            return "❌ 写入Excel失败，请稍后重试。";
        }

        // Step 5: 返回结果
        if (isGenuine) {
            return "✅ 重新验真通过！\n"
                + "发票号码: " + invoiceNumber.trim() + "\n"
                + "发票类型: " + fields.getOrDefault("InvoiceType", "") + "\n"
                + "金额: ¥" + fields.getOrDefault("TotalAmount", "") + "\n"
                + "核验状态: " + verifyStatus + "\n"
                + "事由: " + finalReason + "\n"
                + "已更新到Excel第" + (rowNum + 1) + "行";
        } else {
            return "❌ 重新验真仍未通过: " + verifyStatus + "\n"
                + "发票号码: " + invoiceNumber.trim() + "\n"
                + "发票类型: " + fields.getOrDefault("InvoiceType", "") + "\n"
                + "金额: ¥" + fields.getOrDefault("TotalAmount", "") + "\n"
                + "已记录到Excel第" + (rowNum + 1) + "行\n"
                + "\n💡 可以继续补充信息后再次重新验真。";
        }
    }

    // ==================== 查询报销记录 ====================

    @Tool(description = """
        查询我的报销记录。数据来源：报销记录Excel文件（路径在 application.yml 中配置）。
        返回已报销的发票列表（日期、发票号码、销方、金额、核验状态）。
        """)
    public String queryReimbursements() {
        String userId = ToolUserContext.get();
        if (userId == null || userId.isBlank()) {
            return "⚠️ 无法获取用户身份。";
        }

        List<String> records = excelService.queryByUser(userId);
        if (records.isEmpty()) {
            return "📭 你还没有报销记录。\n发送发票图片并说「报销」即可开始。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📋 你的报销记录（共 ").append(records.size()).append(" 条）:\n\n");
        for (int i = 0; i < records.size(); i++) {
            sb.append(i + 1).append(". ").append(records.get(i)).append("\n");
        }
        return sb.toString().trim();
    }
}
