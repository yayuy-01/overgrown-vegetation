package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 地脈渡りの石(手の石。2026-10-16、⑩)。家に置いた対の石({@link com.yayuy.overgrownvegetation.block.WaystoneAnchorBlock})へ地脈を伝って戻る。
 * <ul>
 *     <li>ユーザー指定: 対の石を手の石で右クリックして対にする / 右クリック長押し 3 秒で帰る / 1 回帰るごとに地脈の雫を 1 つ使う。</li>
 *     <li>仮決め: 同じ世界(次元)の中だけ / 対の石が壊されていたら帰れず対が外れる / 着く所は対の石の上の 2 マスの空き(無ければ周り 2 マス)。</li>
 * </ul>
 */
public class LeyWaystoneItem extends Item {

    public static final int CHANNEL_TICKS = 60;

    public LeyWaystoneItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Nullable
    public static GlobalPos home(ItemStack stack) {
        return stack.get(ModDataComponents.WAYSTONE_HOME.get());
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!level.getBlockState(pos).is(ModBlocks.WAYSTONE_ANCHOR.get())) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide()) {
            context.getItemInHand().set(ModDataComponents.WAYSTONE_HOME.get(), GlobalPos.of(level.dimension(), pos.immutable()));
            level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 1.0F, 1.0F);
            if (context.getPlayer() != null) {
                context.getPlayer().displayClientMessage(Component.translatable("message.overgrown_vegetation.waystone.paired")
                        .withStyle(ChatFormatting.AQUA), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        GlobalPos home = home(stack);
        String problem = null;
        if (home == null) {
            problem = "message.overgrown_vegetation.waystone.unpaired";
        } else if (!home.dimension().equals(level.dimension())) {
            problem = "message.overgrown_vegetation.waystone.other_world";
        } else if (!player.hasInfiniteMaterials() && findDrop(player) < 0) {
            problem = "message.overgrown_vegetation.waystone.no_fuel";
        }
        if (problem != null) {
            player.displayClientMessage(Component.translatable(problem).withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return CHANNEL_TICKS;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.BOW;
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (level instanceof ServerLevel server && remaining % 5 == 0) {
            server.sendParticles(ParticleTypes.REVERSE_PORTAL, entity.getX(), entity.getY() + 1.0D, entity.getZ(), 6, 0.4D, 0.6D, 0.4D, 0.02D);
        }
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (level instanceof ServerLevel server && entity instanceof ServerPlayer player) {
            travel(server, player, stack);
        }
        return stack;
    }

    /** 対の石へ帰る。帰れたら true。 */
    public static boolean travel(ServerLevel level, ServerPlayer player, ItemStack stack) {
        GlobalPos home = home(stack);
        if (home == null || !home.dimension().equals(level.dimension())) {
            return false;
        }
        BlockPos anchor = home.pos();
        level.getChunk(anchor.getX() >> 4, anchor.getZ() >> 4); // 遠くても 1 回だけ読み込んで確かめる
        if (!level.getBlockState(anchor).is(ModBlocks.WAYSTONE_ANCHOR.get())) {
            stack.remove(ModDataComponents.WAYSTONE_HOME.get());
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.waystone.anchor_gone")
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        BlockPos stand = standingSpot(level, anchor);
        if (stand == null) {
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.waystone.blocked")
                    .withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        if (!player.hasInfiniteMaterials()) {
            int slot = findDrop(player);
            if (slot < 0) {
                player.displayClientMessage(Component.translatable("message.overgrown_vegetation.waystone.no_fuel")
                        .withStyle(ChatFormatting.GRAY), true);
                return false;
            }
            player.getInventory().getItem(slot).shrink(1);
        }
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1.0D, player.getZ(), 30, 0.4D, 0.8D, 0.4D, 0.05D);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 0.6F);
        player.teleportTo(level, stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D, player.getYRot(), player.getXRot());
        player.fallDistance = 0.0F;
        player.getCooldowns().addCooldown(stack.getItem(), 40);
        level.playSound(null, stand, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 1.2F);
        level.sendParticles(ParticleTypes.GLOW, stand.getX() + 0.5D, stand.getY() + 1.0D, stand.getZ() + 0.5D, 20, 0.4D, 0.8D, 0.4D, 0.02D);
        return true;
    }

    /** 対の石の上(無ければ周り 2 マス)で、足元と頭の 2 マスが空いている所。 */
    @Nullable
    public static BlockPos standingSpot(ServerLevel level, BlockPos anchor) {
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos p = anchor.above(dy);
            if (fits(level, p)) {
                return p;
            }
        }
        for (int r = 1; r <= 2; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    for (int dy = 0; dy <= 1; dy++) {
                        BlockPos p = anchor.offset(dx, dy, dz);
                        if (fits(level, p) && !level.getBlockState(p.below()).getCollisionShape(level, p.below()).isEmpty()) {
                            return p;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean fits(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getFluidState(feet).isEmpty();
    }

    /** 持ち物の地脈の雫の場所(無ければ -1)。 */
    public static int findDrop(Player player) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (player.getInventory().getItem(i).is(ModItems.LEY_DROP.get())) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        GlobalPos home = home(stack);
        if (home == null) {
            tooltip.add(Component.translatable("tooltip.overgrown_vegetation.waystone.unpaired").withStyle(ChatFormatting.GRAY));
        } else {
            BlockPos p = home.pos();
            tooltip.add(Component.translatable("tooltip.overgrown_vegetation.waystone.paired", p.getX(), p.getY(), p.getZ())
                    .withStyle(ChatFormatting.AQUA));
        }
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.waystone.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
