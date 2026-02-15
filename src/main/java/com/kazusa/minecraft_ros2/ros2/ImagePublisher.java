package com.kazusa.minecraft_ros2.ros2;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.ros2.rcljava.Time;
import org.ros2.rcljava.node.BaseComposableNode;
import org.ros2.rcljava.publisher.Publisher;
import org.ros2.rcljava.qos.QoSProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sensor_msgs.msg.CameraInfo;
import sensor_msgs.msg.Image;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

public class ImagePublisher extends BaseComposableNode {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImagePublisher.class);
    private static final int CAPTURE_DOWNSCALE = 2;
    private static final double MIN_FOV_DEG = 1.0;
    private static final double MAX_FOV_DEG = 179.0;
    private static final double FALLBACK_FOV_DEG = 70.0;
    private static final String CAMERA_INFO_TOPIC = "/player/camera_info";
    private static final String IMAGE_TOPIC = "/player/image_raw";
    private static final int PBO_COUNT = 2;

    private final Publisher<Image> publisher;
    private final Publisher<CameraInfo> cameraInfoPublisher;
    private final Minecraft minecraft;
    private final String cameraFrameId;
    private final int[] pboIds = new int[PBO_COUNT];
    private final long[] pboFences = new long[PBO_COUNT];
    private final builtin_interfaces.msg.Time[] pboStamps = new builtin_interfaces.msg.Time[PBO_COUNT];
    private final boolean[] pboReady = new boolean[PBO_COUNT];
    private final byte[][] publishBuffers = new byte[PBO_COUNT][];

    private int downscaleFboId = 0;
    private int downscaleColorTexId = 0;
    private int downscaleWidth = 0;
    private int downscaleHeight = 0;
    private int pboBytes = 0;
    private long frameCounter = 0L;
    private Class<?> renderTargetClass = null;
    private Method renderTargetBindReadMethod = null;
    private Field renderTargetFboField = null;
    private boolean warnedRenderTargetBinding = false;

    public ImagePublisher() {
        super("minecraft_image_publisher");
        minecraft = Minecraft.getInstance();

        // SENSOR_DATA => BEST_EFFORT + low-latency delivery for video streams.
        publisher = this.node.createPublisher(Image.class, IMAGE_TOPIC, QoSProfile.SENSOR_DATA);
        cameraInfoPublisher = this.node.createPublisher(CameraInfo.class, CAMERA_INFO_TOPIC, QoSProfile.SENSOR_DATA);
        cameraFrameId = System.getenv().getOrDefault("MINECRAFT_ROS2_CAMERA_FRAME_ID", "player");

        LOGGER.info("ImagePublisher initialized (transport=ros2, image_topic={}, camera_info_topic={})", IMAGE_TOPIC, CAMERA_INFO_TOPIC);
    }

    public void captureAndPublish() {
        try {
            if (minecraft.getMainRenderTarget() == null) {
                return;
            }

            int width = minecraft.getMainRenderTarget().width;
            int height = minecraft.getMainRenderTarget().height;
            if (width <= 0 || height <= 0) {
                return;
            }

            int scaledWidth = Math.max(width / CAPTURE_DOWNSCALE, 1);
            int scaledHeight = Math.max(height / CAPTURE_DOWNSCALE, 1);
            ensureDownscaleTarget(scaledWidth, scaledHeight);
            ensurePboBuffers(scaledWidth, scaledHeight);

            final int writeIndex = (int) (frameCounter % PBO_COUNT);
            final int readIndex = (int) ((frameCounter + 1L) % PBO_COUNT);
            final builtin_interfaces.msg.Time stamp = Time.now();

            final int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            final int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            final int prevReadBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
            final int prevPackAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
            final int prevPixelPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);

            try {
                // Publish previous frame when its PBO fence is complete; otherwise skip without blocking.
                tryPublishCompletedReadSlot(readIndex, scaledWidth, scaledHeight);

                // Never block the render thread waiting on a write slot.
                if (!isPboSlotWritable(writeIndex)) {
                    frameCounter++;
                    return;
                }

                final int sourceFboId = bindMainRenderTargetForRead();
                if (sourceFboId <= 0) {
                    frameCounter++;
                    return;
                }

                // Downscale on GPU and vertically flip into the intermediate FBO.
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, sourceFboId);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, downscaleFboId);
                GL30.glBlitFramebuffer(
                        0, 0, width, height,
                        0, scaledHeight, scaledWidth, 0,
                        GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);

                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, downscaleFboId);
                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);

                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[writeIndex]);
                GL11.glReadPixels(0, 0, scaledWidth, scaledHeight, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, 0L);
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);

                long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                if (fence == 0L) {
                    LOGGER.warn("Failed to create GL sync fence for capture slot {}", writeIndex);
                    pboReady[writeIndex] = false;
                    pboStamps[writeIndex] = null;
                    pboFences[writeIndex] = 0L;
                } else {
                    pboFences[writeIndex] = fence;
                    pboStamps[writeIndex] = stamp;
                    pboReady[writeIndex] = true;
                }

                frameCounter++;
            } finally {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPixelPackBuffer);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevPackAlignment);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
                GL11.glReadBuffer(prevReadBuffer);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to capture and publish image", e);
        }
    }

    private void tryPublishCompletedReadSlot(int readIndex, int width, int height) {
        if (!pboReady[readIndex]) {
            return;
        }
        final long fence = pboFences[readIndex];
        if (fence == 0L) {
            clearPboSlotState(readIndex);
            return;
        }

        final int wait = GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
        if (wait == GL32.GL_TIMEOUT_EXPIRED) {
            return;
        }
        if (wait == GL32.GL_WAIT_FAILED) {
            LOGGER.warn("glClientWaitSync failed for read slot {}; dropping frame", readIndex);
            clearPboSlotState(readIndex);
            return;
        }
        if (wait != GL32.GL_ALREADY_SIGNALED && wait != GL32.GL_CONDITION_SATISFIED) {
            return;
        }

        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[readIndex]);
        ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY, pboBytes, null);
        if (mapped == null) {
            LOGGER.warn("glMapBuffer returned null for read slot {}; dropping frame", readIndex);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            clearPboSlotState(readIndex);
            return;
        }

        mapped.rewind();
        byte[] rgbData = publishBuffers[readIndex];
        mapped.get(rgbData, 0, pboBytes);
        final boolean unmapped = GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);

        builtin_interfaces.msg.Time publishStamp = pboStamps[readIndex];
        clearPboSlotState(readIndex);
        if (!unmapped) {
            LOGGER.warn("glUnmapBuffer failed for read slot {}; dropping frame", readIndex);
            return;
        }
        if (publishStamp != null) {
            publishFrame(rgbData, width, height, publishStamp);
        }
    }

    private boolean isPboSlotWritable(int writeIndex) {
        final long fence = pboFences[writeIndex];
        if (fence == 0L) {
            if (pboReady[writeIndex]) {
                pboReady[writeIndex] = false;
                pboStamps[writeIndex] = null;
            }
            return true;
        }

        final int wait = GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
        if (wait == GL32.GL_TIMEOUT_EXPIRED) {
            return false;
        }
        if (wait == GL32.GL_WAIT_FAILED) {
            LOGGER.warn("glClientWaitSync failed for write slot {}; dropping capture", writeIndex);
            clearPboSlotState(writeIndex);
            return false;
        }
        if (wait == GL32.GL_ALREADY_SIGNALED || wait == GL32.GL_CONDITION_SATISFIED) {
            clearPboSlotState(writeIndex);
            return true;
        }
        return false;
    }

    private void clearPboSlotState(int index) {
        if (pboFences[index] != 0L) {
            GL32.glDeleteSync(pboFences[index]);
            pboFences[index] = 0L;
        }
        pboReady[index] = false;
        pboStamps[index] = null;
    }

    private int bindMainRenderTargetForRead() {
        Object renderTarget = minecraft.getMainRenderTarget();
        if (renderTarget == null) {
            return 0;
        }

        refreshRenderTargetAccessors(renderTarget);
        if (renderTargetBindReadMethod != null) {
            try {
                renderTargetBindReadMethod.invoke(renderTarget);
                int bound = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
                if (bound > 0) {
                    return bound;
                }
            } catch (Exception ignored) {
                // Fall through to field-based binding.
            }
        }

        if (renderTargetFboField != null) {
            try {
                int fboId = renderTargetFboField.getInt(renderTarget);
                if (fboId > 0) {
                    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboId);
                    return fboId;
                }
            } catch (Exception ignored) {
                // Fall through to warning.
            }
        }

        if (!warnedRenderTargetBinding) {
            LOGGER.warn("Failed to bind explicit main render target FBO; skipping capture to avoid brittle implicit FBO reads");
            warnedRenderTargetBinding = true;
        }
        return 0;
    }

    private void refreshRenderTargetAccessors(Object renderTarget) {
        Class<?> cls = renderTarget.getClass();
        if (cls == renderTargetClass) {
            return;
        }

        renderTargetClass = cls;
        renderTargetBindReadMethod = null;
        renderTargetFboField = null;

        try {
            renderTargetBindReadMethod = cls.getMethod("bindRead");
        } catch (NoSuchMethodException ignored) {
            // Some mappings expose only the framebuffer id field.
        }

        final String[] fieldNames = {"frameBufferId", "framebufferId", "fboId"};
        for (String fieldName : fieldNames) {
            Class<?> current = cls;
            while (current != null) {
                try {
                    Field field = current.getDeclaredField(fieldName);
                    if (field.getType() == int.class) {
                        field.setAccessible(true);
                        renderTargetFboField = field;
                        return;
                    }
                } catch (NoSuchFieldException ignored) {
                    // Continue scanning.
                }
                current = current.getSuperclass();
            }
        }
    }

    private void publishFrame(byte[] rgbData, int width, int height, builtin_interfaces.msg.Time stamp) {
        if (rgbData == null || stamp == null) {
            return;
        }
        Image rosImage = new Image();
        rosImage.getHeader().setFrameId(cameraFrameId);
        rosImage.getHeader().setStamp(stamp);
        rosImage.setWidth(width);
        rosImage.setHeight(height);
        rosImage.setEncoding("rgb8");
        rosImage.setStep(width * 3);
        rosImage.setData(rgbData);
        publisher.publish(rosImage);

        CameraInfo cameraInfo = buildCameraInfo(width, height, stamp);
        cameraInfoPublisher.publish(cameraInfo);
    }

    private void ensureDownscaleTarget(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (downscaleFboId != 0 && downscaleWidth == width && downscaleHeight == height) {
            return;
        }

        releaseDownscaleTarget();
        downscaleWidth = width;
        downscaleHeight = height;

        final int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        final int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        downscaleColorTexId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, downscaleColorTexId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGB8,
                downscaleWidth,
                downscaleHeight,
                0,
                GL11.GL_RGB,
                GL11.GL_UNSIGNED_BYTE,
                (ByteBuffer) null);

        downscaleFboId = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, downscaleFboId);
        GL30.glFramebufferTexture2D(
                GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D,
                downscaleColorTexId,
                0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Downscale framebuffer incomplete: status=" + status);
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
    }

    private void releaseDownscaleTarget() {
        if (downscaleFboId != 0) {
            GL30.glDeleteFramebuffers(downscaleFboId);
            downscaleFboId = 0;
        }
        if (downscaleColorTexId != 0) {
            GL11.glDeleteTextures(downscaleColorTexId);
            downscaleColorTexId = 0;
        }
        downscaleWidth = 0;
        downscaleHeight = 0;
    }

    private void ensurePboBuffers(int width, int height) {
        final int requiredBytes = Math.max(width * height * 3, 1);
        if (requiredBytes == pboBytes && pboIds[0] != 0 && pboIds[1] != 0) {
            return;
        }
        releasePboBuffers();
        pboBytes = requiredBytes;
        for (int i = 0; i < PBO_COUNT; i++) {
            pboIds[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pboIds[i]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, pboBytes, GL15.GL_STREAM_READ);
            pboFences[i] = 0L;
            pboReady[i] = false;
            publishBuffers[i] = new byte[pboBytes];
            pboStamps[i] = null;
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        frameCounter = 0L;
    }

    private void releasePboBuffers() {
        for (int i = 0; i < PBO_COUNT; i++) {
            if (pboFences[i] != 0L) {
                GL32.glDeleteSync(pboFences[i]);
                pboFences[i] = 0L;
            }
            if (pboIds[i] != 0) {
                GL15.glDeleteBuffers(pboIds[i]);
                pboIds[i] = 0;
            }
            pboReady[i] = false;
            publishBuffers[i] = null;
            pboStamps[i] = null;
        }
        pboBytes = 0;
    }

    private CameraInfo buildCameraInfo(int width, int height, builtin_interfaces.msg.Time stamp) {
        double clampedVerticalFovDeg = Math.max(
                MIN_FOV_DEG,
                Math.min(MAX_FOV_DEG, readCurrentVerticalFovDeg()));
        double verticalFovRad = Math.toRadians(clampedVerticalFovDeg);
        double safeWidth = Math.max(width, 1);
        double safeHeight = Math.max(height, 1);
        double fy = safeHeight / (2.0 * Math.tan(verticalFovRad / 2.0));
        double fx = fy * (safeWidth / safeHeight);
        double cx = safeWidth / 2.0;
        double cy = safeHeight / 2.0;

        CameraInfo cameraInfo = new CameraInfo();
        cameraInfo.getHeader().setFrameId(cameraFrameId);
        cameraInfo.getHeader().setStamp(stamp);
        cameraInfo.setWidth(width);
        cameraInfo.setHeight(height);
        cameraInfo.setDistortionModel("plumb_bob");
        cameraInfo.setD(new double[]{0.0, 0.0, 0.0, 0.0, 0.0});
        cameraInfo.setK(new double[]{
                fx, 0.0, cx,
                0.0, fy, cy,
                0.0, 0.0, 1.0
        });
        cameraInfo.setR(new double[]{
                1.0, 0.0, 0.0,
                0.0, 1.0, 0.0,
                0.0, 0.0, 1.0
        });
        cameraInfo.setP(new double[]{
                fx, 0.0, cx, 0.0,
                0.0, fy, cy, 0.0,
                0.0, 0.0, 1.0, 0.0
        });
        return cameraInfo;
    }

    private double readCurrentVerticalFovDeg() {
        if (minecraft.options == null || minecraft.options.fov() == null || minecraft.options.fov().get() == null) {
            return FALLBACK_FOV_DEG;
        }
        return minecraft.options.fov().get().doubleValue();
    }
}
