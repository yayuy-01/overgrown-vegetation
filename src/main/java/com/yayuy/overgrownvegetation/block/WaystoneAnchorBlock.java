package com.yayuy.overgrownvegetation.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * 対の石(2026-10-16、⑩ 地脈渡りの石の「家に置く方」。ユーザー指定「家に対の石ブロックを置き、手の石で右クリックして対にする」)。
 * 手の石({@link com.yayuy.overgrownvegetation.item.LeyWaystoneItem})が帰ってくる先。石そのものは何もしない(ときどき地脈の光の粒が昇る)。
 */
public class WaystoneAnchorBlock extends Block {

    public static final MapCodec<WaystoneAnchorBlock> CODEC = simpleCodec(WaystoneAnchorBlock::new);
    private static final VoxelShape SHAPE = Block.box(3.0D, 0.0D, 3.0D, 13.0D, 14.0D, 13.0D);

    public WaystoneAnchorBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(3) == 0) {
            level.addParticle(ParticleTypes.GLOW, pos.getX() + 0.3D + random.nextDouble() * 0.4D, pos.getY() + 0.9D,
                    pos.getZ() + 0.3D + random.nextDouble() * 0.4D, 0.0D, 0.03D, 0.0D);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.waystone_anchor").withStyle(ChatFormatting.GRAY));
    }
}
