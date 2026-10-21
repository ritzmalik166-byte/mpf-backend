package com.mypropertyfact.estate.common;

import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Iterator;

@Component
@Slf4j
public class FileUtils {

    private final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB

    //checking size of file
    public boolean isFileSizeValid(MultipartFile file, long maxSizeInBytes) {
        if (file == null) {
            throw new IllegalArgumentException("File cannot be null!");
        }
        return file.getSize() <= maxSizeInBytes;
    }

    //Check type of file
    public boolean isTypeImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be null or empty!");
        }
        String contentType = file.getContentType();
        return contentType != null && contentType.toLowerCase().startsWith("image/");
    }

    public boolean isPdfFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return false;
        }

        // Check file content type
        String contentType = file.getContentType();
        if (contentType == null || !contentType.equalsIgnoreCase("application/pdf")) {
            return false;
        }

        // Check file extension
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase().endsWith(".pdf")) {
            return false;
        }

        return true;
    }

    //Renaming the name of file
    public String renameFile(MultipartFile file, String suffix) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.lastIndexOf('.') == -1) {
            // If no extension, just return a UUID as the filename
            return suffix;
        }
        String newFileName = suffix;
        return newFileName;
    }

    //generating slug url
    public String generateSlug(String input) {
        if (input == null) return "";
        return input.trim()
                .replaceAll("[^a-zA-Z0-9\\s-]", "") // allow letters, numbers, spaces, and hyphens
                .replaceAll("\\s+", "-")           // replace spaces with hyphens
                .replaceAll("-{2,}", "-")          // replace multiple hyphens with a single one
                .replaceAll("^-|-$", "")           // trim hyphens from start/end
                .toLowerCase();
    }

    // Saving file to destination
    public String saveFile(MultipartFile file, String newFileNameWithoutExtension, String uploadDir, int width, int height, float webpQuality) {
        String imageName = null;
        try {
            Path uploadPath = Paths.get(uploadDir);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            // Temporary file to store the uploaded image
            File tempOriginalFile = File.createTempFile("upload_", "_" + file.getOriginalFilename());
            file.transferTo(tempOriginalFile);

            // Resize the image and save to a temp resized file (use JPEG/PNG format)
            File tempResizedFile = File.createTempFile("resized_", ".jpg"); // You can change format if needed
            resizeImage(tempOriginalFile, tempResizedFile, width, height, "jpg", webpQuality);

            // Final output file as WebP
            String finalFileName = newFileNameWithoutExtension + ".jpeg";
            File finalWebPFile = new File(uploadPath.toFile(), finalFileName);
            convertToJpeg(tempResizedFile, finalWebPFile, webpQuality);

            // Cleanup temporary files
            tempOriginalFile.delete();
            tempResizedFile.delete();
            imageName = finalFileName;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save and convert file", e);
        }
        return imageName;
    }

    // Saves as JPEG, scaling down proportionally only when the image exceeds the given bounds (never upscales)
    public String saveImageWithinBounds(MultipartFile file, String newFileNameWithoutExtension, String uploadDir,
                                        int maxWidth, int maxHeight, float quality) {
        try {
            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                throw new IOException("Invalid image file: " + file.getOriginalFilename());
            }
            double scale = Math.min(1.0, Math.min(
                    (double) maxWidth / image.getWidth(),
                    (double) maxHeight / image.getHeight()));

            Path uploadPath = Files.createDirectories(Paths.get(uploadDir));
            String fileName = newFileNameWithoutExtension + ".jpeg";
            Thumbnails.of(image)
                    .scale(scale)
                    .imageType(BufferedImage.TYPE_INT_RGB)
                    .outputFormat("jpeg")
                    .outputQuality(quality)
                    .toFile(uploadPath.resolve(fileName).toFile());
            return fileName;
        } catch (IOException e) {
            throw new RuntimeException("Failed to save image", e);
        }
    }

    //delete file from destination
    public boolean deleteFileFromDestination(String fileName, String dirPath) {
        if (fileName != null && !fileName.isEmpty()) {
            File oldFile = new File(dirPath, fileName);
            if (oldFile.exists()) {
                return oldFile.delete();
            }
        }
        return false;
    }

    public void convertToJpeg(File inputFile, File outputFile, float quality) throws IOException {
        // Read the input image
        BufferedImage image = ImageIO.read(inputFile);
        if (image == null) {
            throw new IOException("Invalid image file: " + inputFile.getName());
        }

        // Get WebP writer
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No Jpeg writer found. Make sure TwelveMonkeys plugins are loaded.");
        }
        ImageWriter writer = writers.next();

        // Configure compression
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality); // Quality from 0.0 (low) to 1.0 (high)
        }

        // Write the output file
        try (FileOutputStream fos = new FileOutputStream(outputFile);
             ImageOutputStream ios = ImageIO.createImageOutputStream(fos)) {

            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    //Resizing the image
    public void resizeImage(File inputFile, File outputFile, int targetWidth, int targetHeight, String formatName, float quality) throws IOException {
        BufferedImage originalImage = ImageIO.read(inputFile);
        if (originalImage == null) {
            throw new IOException("Invalid image file: " + inputFile.getName());
        }

        // Create high-quality scaled image
        BufferedImage resizedImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resizedImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
        g.dispose();

        // Write the image with compression
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(formatName);
        if (!writers.hasNext()) {
            throw new IllegalStateException("No writer found for format: " + formatName);
        }
        ImageWriter writer = writers.next();

        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
        }

        try (FileOutputStream fos = new FileOutputStream(outputFile);
             ImageOutputStream ios = ImageIO.createImageOutputStream(fos)) {
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(resizedImage, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    public String saveOriginalImage(MultipartFile file, String uploadDir) {
        String imageName = null;
        try {
            if (file != null && isTypeImage(file) || file != null && isPdfFile(file)) {
                Path uploadPath = Paths.get(uploadDir);
                if (!Files.exists(uploadPath)) {
                    Files.createDirectories(uploadPath);
                }
                // Generate a unique image name (or use file.getOriginalFilename())
                String originalFilename = file.getOriginalFilename();
                String extension = originalFilename.substring(originalFilename.lastIndexOf("."));
                imageName = System.currentTimeMillis() + extension;

                // Target path for the saved file
                Path filePath = uploadPath.resolve(imageName);

                // Save the file
                Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
        return imageName;
    }

    public String saveDesktopImageWithResize(MultipartFile file, String destination,
                                             int width, int height, Float quality) {
        try {
            if (file == null || file.isEmpty()) {
                return null;
            }

            if (quality == null || quality <= 0 || quality > 1) {
                quality = 0.85f;
            }

            if (!destination.endsWith(File.separator)) {
                destination += File.separator;
            }

            File dir = new File(destination);
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }

            String originalName = file.getOriginalFilename();
            if (originalName == null || originalName.trim().isEmpty()) {
                originalName = "image";
            }
            originalName = originalName.replaceAll("\\s+", "_");

            // Get extension from the original file
            String extension = "png"; // default
            int dotIndex = originalName.lastIndexOf('.');
            if (dotIndex > 0) {
                extension = originalName.substring(dotIndex + 1).toLowerCase();
                originalName = originalName.substring(0, dotIndex);
            }

            // Allowed formats
            if (!extension.equals("png") && !extension.equals("jpg") && !extension.equals("jpeg")) {
                extension = "png"; // fallback to png
            }

            String fileName = originalName + "_" +System.currentTimeMillis() + "." + extension;
            String filePath = destination + fileName;

            BufferedImage originalImage = ImageIO.read(file.getInputStream());

            // Resize and keep original format
            Thumbnails.of(originalImage)
                    .size(width, height)
                    .keepAspectRatio(true)
                    .outputFormat(extension) // same as original
                    .outputQuality(quality)
                    .toFile(new File(filePath));
            return fileName;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }




    public boolean isValidAspectRatio(InputStream imageStream, float imageWidth, float imageHeight) {
        try {
            BufferedImage image = ImageIO.read(imageStream);
            if (image == null) {
                return false;
            }

            double width = image.getWidth();
            double height = image.getHeight();
            double aspectRatio = width / height;

            double targetRatio = imageWidth / imageHeight; // ≈ 2.08
            double tolerance = 0.15; // 15% tolerance

            return Math.abs(aspectRatio - targetRatio) <= tolerance;
        }catch (Exception ex){
            ex.printStackTrace();
        }
        return false;
    }

    public String generateImageAltTag(MultipartFile file) {
        String generateAltTag = "";
        if(file != null) {
            String originalName = file.getOriginalFilename();
            if (originalName != null) {
                // Remove file extension
                String nameWithoutExt = originalName.replaceFirst("[.][^.]+$", "");

                // Replace underscores/dashes with spaces
                nameWithoutExt = nameWithoutExt.replaceAll("[-_]+", " ");

                // Capitalize first letter of each word
                generateAltTag = Arrays.stream(nameWithoutExt.split("\\s+"))
                        .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase())
                        .reduce((w1, w2) -> w1 + " " + w2)
                        .orElse("");

                // Optional: Trim any extra spaces
                generateAltTag = generateAltTag.trim();
            }
        }
        return generateAltTag;
    }

    public String generateNameFromImage(MultipartFile file) {
        if (file == null || file.getOriginalFilename() == null) {
            return "";
        }
        String originalName = file.getOriginalFilename();
        int lastIndex = originalName.lastIndexOf(".");
        String nameWithoutExtension;
        if (lastIndex > 0) {
            nameWithoutExtension = originalName.substring(0, lastIndex);
        } else {
            nameWithoutExtension = originalName; // no extension case
        }
        nameWithoutExtension = nameWithoutExtension.replaceAll("[^a-zA-Z0-9]", " ");
        nameWithoutExtension = nameWithoutExtension.trim().replaceAll("\\s+", " ");
        if (!nameWithoutExtension.isEmpty()) {
            nameWithoutExtension = nameWithoutExtension.substring(0, 1).toUpperCase()
                    + nameWithoutExtension.substring(1);
        }
        return nameWithoutExtension;
    }

    public boolean checkFileSize(MultipartFile file) {
        // Check file size (<= 5 MB here)
        return file.getSize() <= MAX_FILE_SIZE;
    }

    /** Maximum quality for JPEG when we re-encode (avoids blur). */
    private static final float JPEG_QUALITY_MAX = 0.98f;

    /** Mild sharpen kernel (3x3) for upscaled images – restores perceived sharpness. */
    private static final float[] SHARPEN_KERNEL = {
            0f, -0.5f, 0f,
            -0.5f, 3f, -0.5f,
            0f, -0.5f, 0f
    };

    /**
     * Apply a mild sharpen to reduce blur after upscaling. Returns a new BufferedImage.
     */
    private BufferedImage applySharpen(BufferedImage src) {
        try {
            Kernel kernel = new Kernel(3, 3, SHARPEN_KERNEL);
            ConvolveOp op = new ConvolveOp(kernel, ConvolveOp.EDGE_NO_OP, null);
            BufferedImage dest = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
            op.filter(src, dest);
            return dest;
        } catch (Exception e) {
            log.warn("Sharpen failed, using original: {}", e.getMessage());
            return src;
        }
    }

    /**
     * Detect image format from magic bytes. Returns extension (e.g. "png", "jpg") or null.
     */
    private String detectImageExtension(byte[] data) {
        if (data == null || data.length < 12)
            return null;
        if (data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47)
            return "png";
        if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8)
            return "jpg";
        if (data[0] == 0x47 && data[1] == 0x49 && data[2] == 0x46)
            return "gif";
        if (data[0] == 0x52 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x46 && data[8] == 0x57 && data[9] == 0x45 && data[10] == 0x42 && data[11] == 0x50)
            return "webp";
        return null;
    }

    /**
     * Save image from byte array at display-ready size (no blur from tiny images).
     * Always resizes to fit within (maxWidth, maxHeight): upscales small images (e.g. 336x161
     * from Excel) so the saved file has correct dimensions and the browser doesn't upscale.
     * Uses BICUBIC and 0.98 quality.
     */
    public String saveImageFromBytes(byte[] data, String originalFileName, String destination,
                                     int maxWidth, int maxHeight) {
        if (data == null || data.length == 0 || destination == null) {
            return null;
        }
        try {
            if (!destination.endsWith(File.separator)) {
                destination += File.separator;
            }
            File dir = new File(destination);
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            String name = originalFileName != null ? originalFileName.replaceAll("\\s+", "_") : "image";
            String extension = detectImageExtension(data);
            if (extension == null) {
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    extension = name.substring(dot + 1).toLowerCase();
                    name = name.substring(0, dot);
                } else {
                    extension = "png";
                }
            } else {
                int dot = name.lastIndexOf('.');
                if (dot > 0)
                    name = name.substring(0, dot);
            }
            if (!Arrays.asList("png", "jpg", "jpeg", "gif", "webp").contains(extension)) {
                extension = "png";
            }
            String outputExtension = extension;
            String fileName = System.currentTimeMillis() + "_" + name + "." + outputExtension;
            File outFile = new File(destination + fileName);

            BufferedImage originalImage = ImageIO.read(new java.io.ByteArrayInputStream(data));
            if (originalImage == null) {
                return null;
            }
            int origW = originalImage.getWidth();
            int origH = originalImage.getHeight();

            // Always resize to target (upscale small images so output isn't tiny when displayed)
            double scale = Math.min((double) maxWidth / origW, (double) maxHeight / origH);
            int outW = Math.max(1, (int) Math.round(origW * scale));
            int outH = Math.max(1, (int) Math.round(origH * scale));
            boolean wasUpscaled = outW > origW || outH > origH;

            BufferedImage toSave = new BufferedImage(outW, outH,
                    originalImage.getType() == BufferedImage.TYPE_INT_ARGB || originalImage.getType() == BufferedImage.TYPE_4BYTE_ABGR
                            ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D g = toSave.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            g.drawImage(originalImage, 0, 0, outW, outH, null);
            g.dispose();

            if (("jpg".equals(extension) || "jpeg".equals(extension)) && (toSave.getType() == BufferedImage.TYPE_INT_ARGB || toSave.getType() == BufferedImage.TYPE_4BYTE_ABGR)) {
                BufferedImage rgb = new BufferedImage(toSave.getWidth(), toSave.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g2 = rgb.createGraphics();
                g2.setColor(Color.WHITE);
                g2.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
                g2.drawImage(toSave, 0, 0, null);
                g2.dispose();
                toSave = rgb;
            }

            if (wasUpscaled) {
                toSave = applySharpen(toSave);
            }

            String formatName = "jpeg".equals(outputExtension) ? "jpg" : outputExtension;
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(formatName);
            if (!writers.hasNext()) {
                writers = ImageIO.getImageWritersByFormatName(outputExtension);
            }
            // Graceful fallback when WEBP writer plugin is unavailable in runtime.
            if (!writers.hasNext() && "webp".equalsIgnoreCase(outputExtension)) {
                outputExtension = "jpg";
                formatName = "jpg";
                fileName = System.currentTimeMillis() + "_" + name + "." + outputExtension;
                outFile = new File(destination + fileName);
                writers = ImageIO.getImageWritersByFormatName(formatName);
            }
            if (!writers.hasNext()) {
                Thumbnails.of(toSave).scale(1.0).outputFormat(formatName).outputQuality(JPEG_QUALITY_MAX).toFile(outFile);
                return fileName;
            }
            ImageWriter writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed() && ("jpg".equals(formatName) || "jpeg".equals(outputExtension) || "webp".equals(outputExtension))) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY_MAX);
            }
            try (FileOutputStream fos = new FileOutputStream(outFile);
                 ImageOutputStream ios = ImageIO.createImageOutputStream(fos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(toSave, null, null), param);
            } finally {
                writer.dispose();
            }
            return fileName;
        } catch (Exception e) {
            log.error("Failed to save image from bytes: {}", e.getMessage());
            return null;
        }
    }
}
