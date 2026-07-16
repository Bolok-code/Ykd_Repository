package ykd.ykd.wxbox;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 二维码图片生成工具 —— 生成 PNG 文件，输出可点击超链接。
 */
public final class QrCodeUtil {

    private static final Path OUTPUT_DIR =
            Paths.get(System.getProperty("user.dir"), "qrcode");

    private QrCodeUtil() {}

    /**
     * 生成二维码 PNG 并返回文件路径。
     *
     * @param content 二维码内容（URL）
     * @return 图片文件绝对路径，失败返回 null
     */
    public static String generate(String content) {
        try {
            Files.createDirectories(OUTPUT_DIR);

            int width = 300, height = 300;
            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height);

            String filename = "qr_" + System.currentTimeMillis() + ".png";
            Path file = OUTPUT_DIR.resolve(filename);
            MatrixToImageWriter.writeToPath(matrix, "png", file);

            return file.toAbsolutePath().toString();
        } catch (Exception e) {
            System.err.println("[错误] 二维码图片生成失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 输出可点击的 file 超链接（IDEA 终端可点击打开）。
     */
    public static void printClickableLink(String imagePath) {
        // file URI 格式，IDEA / VS Code / Windows Terminal 均支持点击
        String fileUri = "file:///" + imagePath.replace('\\', '/');
        System.out.println();
        System.out.println("  ┌──────────────────────────────────────┐");
        System.out.println("  │  点击下方链接打开二维码图片          │");
        System.out.println("  │  " + fileUri);
        System.out.println("  └──────────────────────────────────────┘");
        System.out.println();
    }
}
