package service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import util.Logger;

import java.io.*;

@Service
public class FileService {

    private final Logger logger;

    @Autowired
    public FileService(Logger logger) {
        this.logger = logger;
    }
    
    /**
     * 处理文件，提取文本内容
     * @param fileData 文件数据
     * @param fileName 文件名
     * @return 提取的文本内容
     */
    public String processFile(byte[] fileData, String fileName) {
        if (fileData == null || fileData.length == 0) {
            return "文件为空";
        }
        
        String lowerName = fileName.toLowerCase();
        
        try {
            if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
                return processExcel(fileData, fileName);
            } else if (lowerName.endsWith(".pdf")) {
                return processPdf(fileData);
            } else if (lowerName.endsWith(".docx") || lowerName.endsWith(".doc")) {
                return processWord(fileData, fileName);
            } else if (lowerName.endsWith(".csv") || lowerName.endsWith(".txt")) {
                return processText(fileData);
            } else {
                return "不支持的文件格式: " + fileName + "\n支持的格式: Excel、PDF、Word、CSV、TXT";
            }
        } catch (Exception e) {
            logger.log("ERROR", "处理文件失败: " + e.getMessage());
            return "处理文件失败: " + e.getMessage();
        }
    }
    
    /**
     * 处理 Excel 文件
     */
    private String processExcel(byte[] fileData, String fileName) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("【Excel文件: ").append(fileName).append("】\n\n");
        
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(fileData))) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                sb.append("【Sheet: ").append(sheet.getSheetName()).append("】\n");
                
                int rowCount = Math.min(sheet.getPhysicalNumberOfRows(), 100); // 最多100行
                for (int row = 0; row < rowCount; row++) {
                    Row currentRow = sheet.getRow(row);
                    if (currentRow == null) continue;
                    
                    for (int col = 0; col < currentRow.getPhysicalNumberOfCells(); col++) {
                        Cell cell = currentRow.getCell(col);
                        if (cell != null) {
                            sb.append(getCellValue(cell));
                            if (col < currentRow.getPhysicalNumberOfCells() - 1) {
                                sb.append("\t");
                            }
                        }
                    }
                    sb.append("\n");
                }
                
                if (sheet.getPhysicalNumberOfRows() > 100) {
                    sb.append("... (共").append(sheet.getPhysicalNumberOfRows()).append("行，只显示前100行)\n");
                }
                sb.append("\n");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 获取单元格值
     */
    private String getCellValue(Cell cell) {
        if (cell == null) return "";
        
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                double num = cell.getNumericCellValue();
                if (num == (int) num) {
                    return String.valueOf((int) num);
                }
                return String.valueOf(num);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    try {
                        return String.valueOf(cell.getNumericCellValue());
                    } catch (Exception e2) {
                        return cell.getCellFormula();
                    }
                }
            default:
                return "";
        }
    }
    
    /**
     * 处理 PDF 文件
     */
    private String processPdf(byte[] fileData) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("【PDF文件内容】\n\n");
        
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(fileData))) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            sb.append(text);
            
            if (text.length() > 5000) {
                sb.append("\n\n... (内容较长，只显示前5000字符)");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 处理 Word 文件
     */
    private String processWord(byte[] fileData, String fileName) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("【Word文件: ").append(fileName).append("】\n\n");
        
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(fileData))) {
            XWPFWordExtractor extractor = new XWPFWordExtractor(document);
            String text = extractor.getText();
            sb.append(text);
            
            if (text.length() > 5000) {
                sb.append("\n\n... (内容较长，只显示前5000字符)");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 处理文本文件
     */
    private String processText(byte[] fileData) throws IOException {
        String content = new String(fileData, "UTF-8");
        
        if (content.length() > 5000) {
            return "【文本文件内容】\n\n" + content.substring(0, 5000) + "\n\n... (内容较长，只显示前5000字符)";
        }
        
        return "【文本文件内容】\n\n" + content;
    }
    
    /**
     * 根据修改后的内容生成新文件
     * @param originalData 原始文件数据
     * @param fileName 文件名
     * @param modifiedContent 修改后的内容（文本格式）
     * @return 新文件数据
     */
    public byte[] generateModifiedFile(byte[] originalData, String fileName, String modifiedContent) {
        String lowerName = fileName.toLowerCase();
        
        try {
            if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
                return generateModifiedExcel(originalData, modifiedContent);
            } else if (lowerName.endsWith(".docx") || lowerName.endsWith(".doc")) {
                return generateModifiedWord(originalData, modifiedContent);
            } else if (lowerName.endsWith(".csv") || lowerName.endsWith(".txt")) {
                return modifiedContent.getBytes("UTF-8");
            } else {
                // PDF 等格式，生成文本文件
                return modifiedContent.getBytes("UTF-8");
            }
        } catch (Exception e) {
            logger.log("ERROR", "生成修改文件失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 生成修改后的 Excel 文件
     */
    private byte[] generateModifiedExcel(byte[] originalData, String modifiedContent) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(originalData))) {
            Sheet sheet = workbook.getSheetAt(0);
            
            // 解析修改后的内容，按行分割
            String[] lines = modifiedContent.split("\n");
            int rowIndex = 0;
            
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                
                Row row = sheet.getRow(rowIndex);
                if (row == null) row = sheet.createRow(rowIndex);
                
                // 按制表符分割单元格
                String[] cells = line.split("\t");
                for (int col = 0; col < cells.length; col++) {
                    Cell cell = row.getCell(col);
                    if (cell == null) cell = row.createCell(col);
                    
                    String value = cells[col].trim();
                    // 尝试设置为数字
                    try {
                        double num = Double.parseDouble(value);
                        cell.setCellValue(num);
                    } catch (NumberFormatException e) {
                        cell.setCellValue(value);
                    }
                }
                
                rowIndex++;
            }
            
            // 保存到字节数组
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return baos.toByteArray();
        }
    }
    
    /**
     * 生成修改后的 Word 文件
     */
    private byte[] generateModifiedWord(byte[] originalData, String modifiedContent) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(originalData))) {
            // 清除原有内容
            while (document.getParagraphs().size() > 0) {
                document.removeBodyElement(0);
            }
            
            // 添加新内容
            String[] lines = modifiedContent.split("\n");
            for (String line : lines) {
                document.createParagraph().createRun().setText(line);
            }
            
            // 保存到字节数组
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.write(baos);
            return baos.toByteArray();
        }
    }
    
    /**
     * 判断文件是否支持修改
     */
    public boolean canModify(String fileName) {
        String lowerName = fileName.toLowerCase();
        return lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls") 
            || lowerName.endsWith(".docx") || lowerName.endsWith(".doc")
            || lowerName.endsWith(".csv") || lowerName.endsWith(".txt");
    }
}
