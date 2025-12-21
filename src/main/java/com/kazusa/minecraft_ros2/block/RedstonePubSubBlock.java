package com.kazusa.minecraft_ros2.block;

import com.kazusa.minecraft_ros2.ros2.BlockIntPublisher;
import com.kazusa.minecraft_ros2.ros2.BlockBoolSubscriber;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RedstonePubSubBlock extends Block implements EntityBlock {
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    // ワールド中に現在存在する座標を保持
    private static final Set<BlockPos> INSTANCES = ConcurrentHashMap.newKeySet();

    private BlockIntPublisher publisher;
    private BlockBoolSubscriber subscriber;

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RedStonePubSubBlockEntity(pos, state);
    }

    public RedstonePubSubBlock(Properties props) {
        super(props);
        publisher = null;
        subscriber = null;
        // デフォルト状態を OFF に設定
        this.registerDefaultState(this.stateDefinition.any().setValue(POWERED, false));
    }

    @Override
    public void onPlace(BlockState state, Level world, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, world, pos, oldState, isMoving);
        if (!world.isClientSide()) {
            INSTANCES.add(pos.immutable());
        }
    }

    public void onLoad(BlockPos pos) {
        // ブロックがロードされたときに座標をセットに追加
        INSTANCES.add(pos.immutable());
    }

    /** 現在ワールド上にあるすべての座標を返す */
    public static Set<BlockPos> getAllInstances() {
        return Collections.unmodifiableSet(INSTANCES);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    public InteractionResult use(
        BlockState state,
        Level world,
        BlockPos pos,
        Player player,
        InteractionHand hand,
        BlockHitResult hit
    ) {
        ItemStack held = player.getItemInHand(hand);
        if (held.getItem() == Items.STICK) {
            if (!world.isClientSide()) {
                ServerPlayer serverPlayer = (ServerPlayer) player;
                BlockEntity be = world.getBlockEntity(pos);
                if (be instanceof RedStonePubSubBlockEntity named) {
                    serverPlayer.openMenu(named);
                }
            }
            return world.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.CONSUME;
        }

        if (world.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        boolean powered = state.getValue(POWERED);
        world.setBlock(pos, state.setValue(POWERED, !powered), 3);
        world.playSound(null, pos,
            powered ? SoundEvents.STONE_BUTTON_CLICK_OFF : SoundEvents.STONE_BUTTON_CLICK_ON,
            SoundSource.BLOCKS, 0.3f, 1.0f
        );
        world.updateNeighborsAt(pos, this);
        return InteractionResult.CONSUME;
    }

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    public int getSignal(BlockState state, net.minecraft.world.level.BlockGetter world, BlockPos pos, Direction side) {
        return state.getValue(POWERED) ? 15 : 0;
    }

    @Override
    public int getDirectSignal(BlockState state, net.minecraft.world.level.BlockGetter world, BlockPos pos, Direction side) {
        return state.getValue(POWERED) ? 15 : 0;
    }

    public void initializeSubscriber(BlockPos pos, String namespace) {
        if (subscriber != null) {
            subscriber.shutdown();
            subscriber = null;
        }
        subscriber = new BlockBoolSubscriber(pos, namespace);
    }

    public BlockBoolSubscriber getSubscriber() {
        return subscriber;
    }

    public void initializePublisher(BlockPos pos, String namespace) {
        if (publisher != null) {
            publisher.shutdown();
            publisher = null;
        }
        publisher = new BlockIntPublisher(pos, namespace);
    }

    public BlockIntPublisher getPublisher() {
        return publisher;
    }

}
