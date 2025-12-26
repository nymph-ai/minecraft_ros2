package com.kazusa.minecraft_ros2.ros2;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.ros2.rcljava.node.BaseComposableNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import std_msgs.msg.String;

public class InputSubscriber extends BaseComposableNode {
    private static final Logger LOGGER = LoggerFactory.getLogger(InputSubscriber.class);

    public InputSubscriber() {
        super("minecraft_input_subscriber");
        this.node.<String>createSubscription(String.class, "/player/input", this::handleInput);
        LOGGER.info("InputSubscriber initialized and listening on '/player/input'");
    }

    private void handleInput(final String msg) {
        java.lang.String data = msg.getData();
        if (data == null || data.isBlank()) {
            return;
        }
        java.lang.String action = data.trim().toLowerCase();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> applyAction(minecraft, action));
    }

    private void applyAction(Minecraft minecraft, java.lang.String action) {
        LocalPlayer player = minecraft.player;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (player == null || gameMode == null) {
            return;
        }

        switch (action) {
            case "attack":
            case "left_click":
            case "click_left":
                handleAttack(minecraft, player, gameMode);
                break;
            case "use":
            case "right_click":
            case "click_right":
                handleUse(minecraft, player, gameMode);
                break;
            default:
                LOGGER.debug("Unhandled input action: {}", action);
                break;
        }
    }

    private void handleAttack(Minecraft minecraft, Player player, MultiPlayerGameMode gameMode) {
        HitResult hit = minecraft.hitResult;
        if (hit instanceof EntityHitResult entityHit) {
            gameMode.attack(player, entityHit.getEntity());
            player.swing(InteractionHand.MAIN_HAND);
            return;
        }
        if (hit instanceof BlockHitResult blockHit) {
            gameMode.startDestroyBlock(blockHit.getBlockPos(), blockHit.getDirection());
            player.swing(InteractionHand.MAIN_HAND);
            return;
        }
        player.swing(InteractionHand.MAIN_HAND);
    }

    private void handleUse(Minecraft minecraft, LocalPlayer player, MultiPlayerGameMode gameMode) {
        HitResult hit = minecraft.hitResult;
        if (hit instanceof BlockHitResult blockHit) {
            gameMode.useItemOn(player, InteractionHand.MAIN_HAND, blockHit);
            return;
        }
        gameMode.useItem(player, InteractionHand.MAIN_HAND);
    }
}
