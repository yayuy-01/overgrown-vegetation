package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 灯りの呪符(2026-10-16、㉗)。小さな魔法「明かりをともす」。火も熱も出さない明かり(植物に吸われにくい代わりに、すぐ消える)。
 * <ul>
 *     <li>ユーザー指定: 使い捨て。</li>
 *     <li>仮決め: 右クリックで見ている所(16 ブロックまで、何も無ければ 6 ブロック先の空中)に 1 分の明かり(明るさ 14)/ 紙 + 地脈の雫で 2 枚。</li>
 * </ul>
 */
public class LightCharmItem extends Item {

    public static final double REACH = 16.0D;
    public static final double AIR_REACH = 6.0D;

    public LightCharmItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(level instanceof ServerLevel server)) {
            return InteractionResultHolder.success(stack);
        }
        BlockPos target = target(server, player);
        if (target == null) {
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.light_charm.blocked").withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.fail(stack);
        }
        server.setBlock(target, ModBlocks.CHARM_LIGHT.get().defaultBlockState(), 3);
        server.sendParticles(ParticleTypes.END_ROD, target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, 12, 0.2D, 0.2D, 0.2D, 0.03D);
        server.playSound(null, target, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.6F);
        stack.consume(1, player);
        return InteractionResultHolder.consume(stack);
    }

    /** 明かりを置くマス(空気のマスだけ)。置けなければ null。 */
    @Nullable
    public static BlockPos target(ServerLevel level, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(REACH)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        BlockPos pos = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos().relative(hit.getDirection())
                : BlockPos.containing(eye.add(look.scale(AIR_REACH)));
        return level.getBlockState(pos).isAir() ? pos : null;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.light_charm").withStyle(ChatFormatting.GRAY));
    }
}
