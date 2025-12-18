package com.kazusa.minecraft_ros2.ros2;

import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ros2.rcljava.node.BaseComposableNode;
import org.ros2.rcljava.publisher.Publisher;
import org.ros2.rcljava.qos.QoSProfile;
import sensor_msgs.msg.Image;
import org.ros2.rcljava.Time;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

public class ImagePublisher extends BaseComposableNode {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImagePublisher.class);

    private final Publisher<Image> publisher;
    private final Minecraft minecraft;
    private Image rosImage;
    private ImageWriter jpegWriter;
    private static final float JPEG_QUALITY = 0.7f; // 0.0 to 1.0, higher = better quality but larger size

    public ImagePublisher() {
        super("minecraft_image_publisher");
        minecraft = Minecraft.getInstance();

        // Use SENSOR_DATA QoS profile which has BEST_EFFORT reliability
        // This prevents message buffering and ensures real-time delivery
        publisher = this.node.createPublisher(Image.class, "/player/image_raw", QoSProfile.SENSOR_DATA);

        // Disable JPEG writer to use uncompressed RGB8 for better Foxglove compatibility
        // JPEG encoding in ROS Image messages is not well supported by some visualization tools
        jpegWriter = null;

        LOGGER.info("ImagePublisher initialized with BEST_EFFORT QoS (depth=1) and RGB8 encoding");
    }

    public void captureAndPublish() {
        try {
            int width = minecraft.getMainRenderTarget().width;
            int height = minecraft.getMainRenderTarget().height;

            // Skip if render target is not ready
            if (width <= 0 || height <= 0) {
                return;
            }

            // Scale down to reduce bandwidth while maintaining image quality
            // scale=2 gives ~427x240 resolution for better visual quality
            int scale = 2;
            int scaledWidth = width / scale;
            int scaledHeight = height / scale;

            // Get RGBA pixels from the entire screen
            ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

            // Convert from RGBA to RGB while scaling down
            byte[] imageData;
            String encoding;
            int step;

            if (jpegWriter != null) {
                // Create BufferedImage for JPEG compression
                BufferedImage bufferedImage = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB);

                for (int y = 0; y < scaledHeight; y++) {
                    for (int x = 0; x < scaledWidth; x++) {
                        int srcX = x * scale;
                        int srcY = y * scale;
                        int srcIndex = ((height - 1 - srcY) * width + srcX) * 4;

                        int r = buffer.get(srcIndex) & 0xFF;
                        int g = buffer.get(srcIndex + 1) & 0xFF;
                        int b = buffer.get(srcIndex + 2) & 0xFF;
                        int rgb = (r << 16) | (g << 8) | b;
                        bufferedImage.setRGB(x, y, rgb);
                    }
                }

                // Compress to JPEG
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
                jpegWriter.setOutput(ios);

                ImageWriteParam param = jpegWriter.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);

                jpegWriter.write(null, new javax.imageio.IIOImage(bufferedImage, null, null), param);
                ios.close();

                imageData = baos.toByteArray();
                encoding = "jpeg";
                step = 0; // JPEG doesn't use step

            } else {
                // Fallback to uncompressed RGB
                byte[] rgbData = new byte[scaledWidth * scaledHeight * 3];
                for (int y = 0; y < scaledHeight; y++) {
                    for (int x = 0; x < scaledWidth; x++) {
                        int srcX = x * scale;
                        int srcY = y * scale;
                        int srcIndex = ((height - 1 - srcY) * width + srcX) * 4;
                        int dstIndex = (y * scaledWidth + x) * 3;

                        rgbData[dstIndex] = buffer.get(srcIndex);         // R
                        rgbData[dstIndex + 1] = buffer.get(srcIndex + 1); // G
                        rgbData[dstIndex + 2] = buffer.get(srcIndex + 2); // B
                    }
                }
                imageData = rgbData;
                encoding = "rgb8";
                step = scaledWidth * 3;
            }

            // Create ROS2 Image message
            if (rosImage == null) {
                rosImage = new Image();
            }
            rosImage.getHeader().setFrameId("player");
            rosImage.getHeader().setStamp(Time.now());
            rosImage.setWidth(scaledWidth);
            rosImage.setHeight(scaledHeight);
            rosImage.setEncoding(encoding);
            rosImage.setStep(step);
            rosImage.setData(imageData);

            // Send asynchronously (reduce main thread load)
            CompletableFuture.runAsync(() -> publisher.publish(rosImage));

        } catch (Exception e) {
            LOGGER.error("Failed to capture and publish image", e);
        }
    }
}
