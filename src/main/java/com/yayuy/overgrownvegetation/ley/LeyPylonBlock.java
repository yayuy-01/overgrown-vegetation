package com.yayuy.overgrownvegetation.ley;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModLey;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;

/**
 * <b>地脈の柱</b>(2026-11-13 サバイバルの背骨 段 2b、決定 13「柱から柱へ線を張る」)。木の柱に真鍮の碍子。線の巻きで 2 か所をクリックして線を張る。
 * <ul>
 *     <li>素手で右クリック: 線の数・つながる網(柱・出す側・受け取る側・ためる側の数)を出す。</li>
 *     <li>電池を持って右クリック: 電池を掛ける(網の余りで充電し、足りない時は網へ出す)。素手でしゃがんで右クリック: 外す。</li>
 *     <li>壊すと、持っている線の数だけ線の巻きと、掛けた電池を落とす。相手の柱の線も外す。</li>
 * </ul>
 * 見た目【仮】: 1 ブロックの木の柱(上に腕木と碍子)。後でユーザーが描き直す。
 */
public class LeyPylonBlock extends BaseEntityBlock {

    public static final MapCodec<LeyPylonBlock> CODEC = simpleCodec(LeyPylonBlock::new);
    private static final VoxelShape SHAPE = box(6.0D, 0.0D, 6.0D, 10.0D, 16.0D, 10.0D);

    public LeyPylonBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LeyBatteryHolder.BATTERY, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LeyBatteryHolder.BATTERY);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LeyPylonBlockEntity(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (stack.is(ModLey.WIRE.get())) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION; // 線の巻きの側で扱う
        }
        if (level.getBlockEntity(pos) instanceof LeyPylonBlockEntity be && LeyBatteryHolder.insert(level, pos, state, player, stack, be)) {
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof LeyPylonBlockEntity be)) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown() && LeyBatteryHolder.takeOut(level, pos, state, player, be)) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (level instanceof ServerLevel server) {
            player.displayClientMessage(info(server, pos, be), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    static Component info(ServerLevel level, BlockPos pos, LeyPylonBlockEntity be) {
        LeyGrid.Network net = LeyGrid.collect(level, pos, new HashSet<>());
        Component line = Component.translatable("message.overgrown_vegetation.ley_pylon.info",
                be.links().size(), DesignData.sporeField().ley().wireMaxPerPylon(),
                net.pylons.size(), net.producers.size(), net.consumers.size(), net.buffers.size());
        if (!be.battery().isEmpty()) {
            line = line.copy().append(Component.translatable("message.overgrown_vegetation.ley.battery_part",
                    LeyText.percent(LeyBatteryHolder.fraction(be.battery()))));
        }
        return line;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof LeyPylonBlockEntity be) {
            LeyBatteryHolder.drop(level, pos, be);
            int wires = be.links().size();
            for (BlockPos other : be.links()) {
                // 相手のチャンクが読まれていなくても読んで線を外す(11-18 見直し ②6。外さないと、相手が後で読まれたときに「切れた線」として
                // もう 1 本落としていた)。線は 24 ブロックまでで、壊すのはプレイヤーの 1 回の操作なので、まれな同期の読み込みは許す
                if (level.getBlockEntity(other) instanceof LeyPylonBlockEntity o) {
                    o.removeLink(pos);
                }
            }
            if (wires > 0) {
                Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new ItemStack(ModLey.WIRE.get(), wires));
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
