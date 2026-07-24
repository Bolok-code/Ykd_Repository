package ykd.ykd.document;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.springframework.ai.chat.client.ChatClient;

import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import ykd.ykd.exception.ErrorCode;
import java.util.Base64;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;


@Slf4j
@Service
public class DocumentParsingService {

    private static final int MAX_TEXT_LENGTH = 10000;
    private static final int OCR_TEXT_THRESHOLD = 50;
    private static final int OCR_DPI = 200;
    private final ChatClient ocrClient;
    public DocumentParsingService(OpenAiChatModel openAiChatModel) {
        this.ocrClient = ChatClient.builder(openAiChatModel).build();
    }


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

            if (text == null || text.trim().length() < OCR_TEXT_THRESHOLD) {
                log.info("[DocParser] PDF文本内容不足({}字)，判定为扫描版，启用AI OCR",
                        text != null ? text.trim().length() : 0);
                text = ocrPdfWithAi(document);
            }

            text = truncate(text);
            log.info("[DocParser] PDF解析完成: pages={}, chars={}", pageCount, text.length());
            return new ParseResult("PDF", pageCount, text);
        }
    }
    private String ocrPdfWithAi(PDDocument document) throws IOException {
        PDFRenderer renderer = new PDFRenderer(document);
        int pageCount = document.getNumberOfPages();
        StringBuilder ocrText = new StringBuilder();

        for (int i = 0; i < pageCount; i++) {
            log.info("[DocParser] OCR识别第 {}/{} 页", i + 1, pageCount);
            BufferedImage image = renderer.renderImageWithDPI(i, OCR_DPI);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            String dataUri = "data:image/png;base64," + base64;

            try {
                String pageText = ocrClient.prompt()
                        .user(userSpec -> userSpec
                                .text("请提取这张图片中的所有文字内容，保持原始排版格式，只输出识别到的文字，不要添加任何解释或说明。")
                                .media(new Media(MimeTypeUtils.IMAGE_PNG, URI.create(dataUri))))
                        .call()
                        .content();

                if (pageText != null && !pageText.isBlank()) {
                    ocrText.append(pageText.trim()).append("\n\n");
                }
            } catch (Exception e) {
                log.error("[DocParser] 第 {} 页OCR识别失败: {}", i + 1, e.getMessage());
            }

            if (ocrText.length() > MAX_TEXT_LENGTH * 2) {
                log.warn("[DocParser] OCR文本已达上限，停止后续页面识别");
                break;
            }
        }

        log.info("[DocParser] AI OCR完成: 识别页数={}, 总字数={}", pageCount, ocrText.length());
        return ocrText.toString();
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


}
