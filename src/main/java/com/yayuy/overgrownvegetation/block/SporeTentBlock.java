package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import com.yayuy.overgrownvegetation.spore.TentShelters;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * 防胞子の天幕(2026-10-16、⑮)。樹液結晶を染み込ませた布で胞子を通さない。
 * <ul>
 *     <li>ユーザー指定: 1 ブロックの天幕 / ベッドのように置いて寝る・復活地点になる / 周り半径 3 の胞子濃度を 0 にする。</li>
 *     <li>寝るのはバニラのベッドと同じ決まり(夜だけ・敵がそばにいると眠れない・眠れない世界では使えない)。</li>
 *     <li>胞子を払うのは {@link TentShelters}(天幕の座標を読み込みで登録し、{@code SporeConcentration#percentAt(ServerLevel, BlockPos)} が 0 を返す)。</li>
 *     <li>仮決め: 繊維質 5 + 樹液結晶 1 で作る。</li>
 * </ul>
 */
public class SporeTentBlock extends SleepingMatBlock implements EntityBlock {

    public static final MapCodec<SporeTentBlock> CODEC = simpleCodec(SporeTentBlock::new);
    private static final VoxelShape SHAPE = Block.box(1.0D, 0.0D, 1.0D, 15.0D, 14.0D, 15.0D);

    public SporeTentBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SporeTentBlockEntity(pos, state);
    }

    @Override
    public Optional<ServerPlayer.RespawnPosAngle> getRespawnPosition(BlockState state, EntityType<?> type, LevelReader levelReader, BlockPos pos,
                                                                      float orientation) {
        if (levelReader instanceof Level level && !BedBlock.canSetSpawn(level)) {
            return Optional.empty();
        }
        return BedBlock.findStandUpPosition(type, levelReader, pos, state.getValue(FACING), orientation)
                .map(v -> ServerPlayer.RespawnPosAngle.of(v, pos));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.spore_tent").withStyle(ChatFormatting.GRAY));
    }

    /** 天幕の登録係(中身は無い。読み込み・取り外しで {@link TentShelters} を出し入れする)。 */
    public static class SporeTentBlockEntity extends BlockEntity {
        public SporeTentBlockEntity(BlockPos pos, BlockState state) {
            super(ModBlockEntities.SPORE_TENT.get(), pos, state);
        }

        @Override
        public void setLevel(Level level) {
            super.setLevel(level);
            if (!level.isClientSide()) {
                TentShelters.register(level, worldPosition);
            }
        }

        @Override
        public void setRemoved() {
            super.setRemoved();
            if (level != null && !level.isClientSide()) {
                TentShelters.unregister(level, worldPosition);
            }
        }

        @Override
        public void onChunkUnloaded() {
            super.onChunkUnloaded();
            if (level != null && !level.isClientSide()) {
                TentShelters.unregister(level, worldPosition);
            }
        }
    }
}
