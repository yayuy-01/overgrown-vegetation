package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.block.HerbCropBlock;
import com.yayuy.overgrownvegetation.block.OvergrownFruitPlantBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.List;

/**
 * 灰の肥料(2026-10-16、㉜)。焼けた荒地の灰で、役に立つ植物を早く育てる。
 * <ul>
 *     <li>ユーザー指定: 役に立つ植物(薬草・繊維草・力を持つ植物)だけ / 灰は焼けた土をシャベルで掘ると出る。</li>
 *     <li>仮決め: 1 回で 1 段育てる(確実に)/ バニラの作物には効かない / 灰 2 + 骨粉で 4 つ。</li>
 * </ul>
 */
public class AshFertilizerItem extends Item {

    public AshFertilizerItem(Properties properties) {
        super(properties);
    }

    /** 灰の肥料が効く植物か。 */
    public static boolean accepts(BlockState state) {
        return (state.getBlock() instanceof HerbCropBlock || state.getBlock() instanceof OvergrownFruitPlantBlock)
                && state.hasProperty(BlockStateProperties.AGE_3);
    }

    /** 1 段育てる。育ったら true。 */
    public static boolean grow(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!accepts(state)) {
            return false;
        }
        int age = state.getValue(BlockStateProperties.AGE_3);
        if (age >= 3) {
            return false;
        }
        level.setBlock(pos, state.setValue(BlockStateProperties.AGE_3, age + 1), Block.UPDATE_ALL);
        return true;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (!accepts(state)) {
            if (!level.isClientSide() && context.getPlayer() != null && state.is(net.minecraft.tags.BlockTags.CROPS)) {
                context.getPlayer().displayClientMessage(Component.translatable("message.overgrown_vegetation.ash_fertilizer.not_useful")
                        .withStyle(ChatFormatting.GRAY), true);
            }
            return InteractionResult.PASS;
        }
        if (state.getValue(BlockStateProperties.AGE_3) >= 3) {
            return InteractionResult.PASS;
        }
        if (level instanceof ServerLevel server) {
            grow(server, pos);
            server.sendParticles(ParticleTypes.ASH, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 12, 0.3D, 0.3D, 0.3D, 0.01D);
            server.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5D, pos.getY() + 0.6D, pos.getZ() + 0.5D, 5, 0.3D, 0.3D, 0.3D, 0.0D);
            server.playSound(null, pos, SoundEvents.BONE_MEAL_USE, SoundSource.BLOCKS, 1.0F, 0.8F);
            context.getItemInHand().consume(1, context.getPlayer());
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.ash_fertilizer").withStyle(ChatFormatting.GRAY));
    }
}
