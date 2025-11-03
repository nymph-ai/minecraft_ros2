package com.kazusa.minecraft_ros2.integration.baritone;

import net.minecraft.core.BlockPos;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Utility class for interacting with Baritone via reflection.
 * This allows the minecraft_ros2 mod to submit navigation goals without a compile-time dependency on Baritone.
 */
public final class BaritoneIntegration {
    private static final Logger LOGGER = LoggerFactory.getLogger(BaritoneIntegration.class);
    private static final String BARITONE_MOD_ID = "baritone";

    private static final AtomicBoolean AVAILABILITY_CHECKED = new AtomicBoolean(false);
    private static boolean available;

    private BaritoneIntegration() {
        // Utility class
    }

    /**
     * Determine whether Baritone is available on the current client.
     *
     * @return true if the Baritone mod is loaded and its API can be located.
     */
    public static boolean isAvailable() {
        if (!AVAILABILITY_CHECKED.get()) {
            available = ModList.get().isLoaded(BARITONE_MOD_ID) && isApiPresent();
            AVAILABILITY_CHECKED.set(true);
            if (available) {
                LOGGER.info("Detected Baritone mod. ROS integration enabled.");
            } else {
                LOGGER.warn("Baritone mod not detected. ROS integration disabled.");
            }
        }
        return available;
    }

    /**
     * Submit a navigation request to Baritone targeting the provided block position.
     *
     * @param target Target block position.
     * @return true if the request could be submitted.
     */
    public static boolean navigateTo(BlockPos target) {
        if (target == null) {
            LOGGER.warn("Ignored Baritone navigation request with null target.");
            return false;
        }
        return navigateTo(target.getX(), target.getY(), target.getZ());
    }

    /**
     * Submit a navigation request to Baritone targeting the provided coordinates.
     *
     * @param x X coordinate (block space).
     * @param y Y coordinate (block space).
     * @param z Z coordinate (block space).
     * @return true if the request could be submitted.
     */
    public static boolean navigateTo(double x, double y, double z) {
        if (!isAvailable()) {
            LOGGER.debug("Baritone navigateTo called while Baritone is unavailable.");
            return false;
        }

        int targetX = (int) Math.floor(x);
        int targetY = (int) Math.floor(y);
        int targetZ = (int) Math.floor(z);
        BlockPos blockPos = BlockPos.containing(targetX, targetY, targetZ);

        try {
            Object baritone = getPrimaryBaritone();
            if (baritone == null) {
                LOGGER.warn("Primary Baritone instance is null; cannot navigate to {}", blockPos);
                return false;
            }

            Object goal = createGoalBlock(targetX, targetY, targetZ);

            Object customGoalProcess = invoke(baritone, "getCustomGoalProcess");
            if (customGoalProcess == null) {
                LOGGER.warn("Failed to obtain Baritone custom goal process; cannot navigate to {}", blockPos);
                return false;
            }

            Method setGoalAndPath = customGoalProcess.getClass()
                    .getMethod("setGoalAndPath", Class.forName("baritone.api.pathing.goals.IGoal"));
            setGoalAndPath.invoke(customGoalProcess, goal);

            LOGGER.info("Baritone navigation requested: {}", blockPos);
            return true;
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Failed to submit Baritone navigation request to {}", blockPos, e);
            return false;
        }
    }

    /**
     * Cancel any active Baritone navigation goal.
     *
     * @return true if a cancellation signal could be issued.
     */
    public static boolean cancelNavigation() {
        if (!isAvailable()) {
            LOGGER.debug("Baritone cancelNavigation called while Baritone is unavailable.");
            return false;
        }

        try {
            Object baritone = getPrimaryBaritone();
            if (baritone == null) {
                LOGGER.warn("Primary Baritone instance is null; cannot cancel navigation.");
                return false;
            }

            Object pathingBehavior = invoke(baritone, "getPathingBehavior");
            if (pathingBehavior != null) {
                invokeIfPresent(pathingBehavior, "cancelEverything");
                invokeIfPresent(pathingBehavior, "forceCancel");
            }

            Object customGoalProcess = invoke(baritone, "getCustomGoalProcess");
            if (customGoalProcess != null) {
                Class<?> goalClass = Class.forName("baritone.api.pathing.goals.IGoal");
                try {
                    Method setGoal = customGoalProcess.getClass().getMethod("setGoal", goalClass);
                    setGoal.invoke(customGoalProcess, new Object[]{null});
                } catch (NoSuchMethodException ignored) {
                    Method setGoalAndPath = customGoalProcess.getClass().getMethod("setGoalAndPath", goalClass);
                    setGoalAndPath.invoke(customGoalProcess, new Object[]{null});
                }
            }

            LOGGER.info("Baritone navigation cancelled.");
            return true;
        } catch (ReflectiveOperationException e) {
            LOGGER.error("Failed to cancel Baritone navigation", e);
            return false;
        }
    }

    private static boolean isApiPresent() {
        try {
            Class.forName("baritone.api.BaritoneAPI");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static Object getPrimaryBaritone() throws ReflectiveOperationException {
        Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
        Method getProvider = apiClass.getMethod("getProvider");
        Object provider = getProvider.invoke(null);
        Method getPrimary = provider.getClass().getMethod("getPrimaryBaritone");
        return getPrimary.invoke(provider);
    }

    private static Object createGoalBlock(int x, int y, int z) throws ReflectiveOperationException {
        Class<?> goalBlockClass = Class.forName("baritone.api.pathing.goals.GoalBlock");
        Constructor<?> ctor = goalBlockClass.getConstructor(int.class, int.class, int.class);
        return ctor.newInstance(x, y, z);
    }

    private static Object invoke(Object target, String methodName, Class<?>... parameterTypes)
            throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName, parameterTypes);
        return method.invoke(target);
    }

    private static void invokeIfPresent(Object target, String methodName, Class<?>... parameterTypes) {
        try {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            method.invoke(target);
        } catch (NoSuchMethodException ignored) {
            // Method absent on this Baritone version.
        } catch (IllegalAccessException | InvocationTargetException e) {
            LOGGER.debug("Failed to invoke {} on {}", methodName, target.getClass().getName(), e);
        }
    }
}

