package ykd.ykd.llm.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.springframework.stereotype.Service;
import ykd.ykd.exception.ErrorCode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
public class DocumentParsingService {

    private static final int MAX_TEXT_LENGTH = 10000;

    public ParseResult parse(Path file, String fileName) throws IOException {
        String lowerName = fileName.toLowerCase();
        byte[] bytes = Files.readAllBytes(file);

        if (lowerName.endsWith(".pdf")) {
            return parsePdf(bytes);
        } else if (lowerName.endsWith(".docx") || lowerName.endsWith(".doc")) {
            return parseWord(bytes);
        } else if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
            return parseExcel(bytes);
        } else if (lowerName.endsWith(".txt") || lowerName.endsWith(".csv") || lowerName.endsWith(".md")) {
            return parsePlainText(bytes);
        } else {
            throw new UnsupportedOperationException(ErrorCode.DOCUMENT_UNSUPPORTED.getDefaultMessage() + ": " + getExtension(fileName));
        }
    }

    private ParseResult parsePdf(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int pageCount = document.getNumberOfPages();
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            text = truncate(text);
            log.info("[DocParser] PDF解析完成: pages={}, chars={}", pageCount, text.length());
            return new ParseResult("PDF", pageCount, text);
        }
    }

    private ParseResult parseWord(byte[] bytes) throws IOException {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            XWPFWordExtractor extractor = new XWPFWordExtractor(document);
            String text = extractor.getText();
            extractor.close();
            text = truncate(text);
            int paragraphCount = document.getParagraphs().size();
            log.info("[DocParser] Word解析完成: paragraphs={}, chars={}", paragraphCount, text.length());
            return new ParseResult("Word", paragraphCount, text);
        }
    }

    private ParseResult parseExcel(byte[] bytes) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();
            int sheetCount = workbook.getNumberOfSheets();
            int totalRows = 0;

            for (int s = 0; s < sheetCount; s++) {
                XSSFSheet sheet = workbook.getSheetAt(s);
                if (sheetCount > 1) {
                    sb.append("\n=== Sheet: ").append(sheet.getSheetName()).append(" ===\n");
                }
                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    XSSFRow row = sheet.getRow(r);
                    if (row == null) continue;
                    totalRows++;
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        XSSFCell cell = row.getCell(c);
                        if (c > 0) sb.append("\t");
                        sb.append(getCellValue(cell));
                    }
                    sb.append("\n");
                    if (sb.length() > MAX_TEXT_LENGTH * 2) break;
                }
            }

            String text = truncate(sb.toString());
            log.info("[DocParser] Excel解析完成: sheets={}, rows={}, chars={}", sheetCount, totalRows, text.length());
            return new ParseResult("Excel", totalRows, text);
        }
    }

    private ParseResult parsePlainText(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        text = truncate(text);
        int lineCount = text.split("\n", -1).length;
        log.info("[DocParser] 纯文本解析完成: lines={}, chars={}", lineCount, text.length());
        return new ParseResult("文本", lineCount, text);
    }

    private String getCellValue(XSSFCell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                double val = cell.getNumericCellValue();
                yield (val == (long) val) ? String.valueOf((long) val) : String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

    private String truncate(String text) {
        if (text == null) return "";
        text = text.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n").replaceAll("[ \\t]+", " ").trim();
        if (text.length() > MAX_TEXT_LENGTH) {
            return text.substring(0, MAX_TEXT_LENGTH) + "\n\n...（文档过长，已截取前 " + MAX_TEXT_LENGTH + " 字）";
        }
        return text;
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot + 1) : "未知";
    }

    public record ParseResult(String fileType, int elementCount, String text) {
    }
}
