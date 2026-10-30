package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.corruption.LeyVeinField;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 地脈探し(2026-10-22 に作り直し。前は「地脈の羅針盤」でハイヴノードと汚染されていない土地を指した。ユーザー指定
 * 「地脈探しは地脈の場所を知れるだけ」)。針がいちばん近い地脈の線({@link LeyVeinField#nearestLinePoint})を指す。
 * 線の帯(幅 5)の中に立つと、針は線に沿った向きを指す(線を辿れるように)。場はシードと座標だけの関数なので、チャンクの読み込みは要らない。
 * 針の先は持っている間サーバーが {@link #REFRESH_TICKS} ごとに {@code ley_compass_target} へ書き、クライアントの模型が回す。
 * 右クリックで線までのおよその距離を出す。燃料なし。
 */
public class LeyCompassItem extends Item {

    public static final int REFRESH_TICKS = 40;
    /** 線を探す範囲(ブロック)。セルの大きさの半分ほどで必ず線に当たる(既定 192 → 100 ブロック以内)。 */
    public static final int SEARCH_RADIUS = 256;
    /** 線の上で針の先を置く距離(線に沿って先へ)。 */
    private static final double ALONG = 16.0D;

    public LeyCompassItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel server) {
            BlockPos from = player.blockPosition();
            int[] p = LeyVeinField.nearestLinePoint(server.getSeed(), from.getX(), from.getZ(), SEARCH_RADIUS);
            Component msg;
            if (p == null) {
                msg = Component.translatable("message.overgrown_vegetation.ley_compass.none");
            } else {
                int d = (int) Math.round(Math.sqrt(Mth.square((double) p[0] - from.getX()) + Mth.square((double) p[1] - from.getZ())));
                msg = LeyVeinField.isVeinAt(server.getSeed(), from.getX(), from.getZ()) ? Component.translatable("message.overgrown_vegetation.ley_compass.on_line")
                        : Component.translatable("message.overgrown_vegetation.ley_compass.distance", d);
            }
            player.displayClientMessage(msg.copy().withStyle(ChatFormatting.AQUA), true);
            refresh(server, stack, from);
        }
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.SPYGLASS_USE, SoundSource.PLAYERS, 0.6F, 1.4F);
        player.getCooldowns().addCooldown(this, 10);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level instanceof ServerLevel server && (entity.tickCount + slot) % REFRESH_TICKS == 0) {
            // 前の版の「汚染されていない土地」の切り替えの印が残っていれば消す(部品の登録は古いセーブの読み込みのため残してある)。
            if (stack.has(ModDataComponents.LEY_COMPASS_CLEAN.get())) {
                stack.remove(ModDataComponents.LEY_COMPASS_CLEAN.get());
            }
            refresh(server, stack, entity.blockPosition());
        }
    }

    public static void refresh(ServerLevel level, ItemStack stack, BlockPos from) {
        BlockPos target = target(level, from);
        GlobalPos next = target != null ? GlobalPos.of(level.dimension(), target) : null;
        GlobalPos now = stack.get(ModDataComponents.LEY_COMPASS_TARGET.get());
        if (next == null) {
            if (now != null) {
                stack.remove(ModDataComponents.LEY_COMPASS_TARGET.get());
            }
        } else if (!next.equals(now)) {
            stack.set(ModDataComponents.LEY_COMPASS_TARGET.get(), next);
        }
    }

    /** 針の先。いちばん近い線の上の点、線の上に立っていれば線に沿って {@link #ALONG} 先。無ければ null(針は回る)。 */
    @Nullable
    public static BlockPos target(ServerLevel level, BlockPos from) {
        long seed = level.getSeed();
        int[] p = LeyVeinField.nearestLinePoint(seed, from.getX(), from.getZ(), SEARCH_RADIUS);
        if (p == null) {
            return null;
        }
        if (LeyVeinField.isVeinAt(seed, from.getX(), from.getZ())) { // 線の帯(幅 5)の中に立っている
            double[] flow = new double[4];
            if (LeyVeinField.flowAt(seed, from.getX(), from.getZ(), flow)) {
                return BlockPos.containing(from.getX() + 0.5D + flow[1] * ALONG, from.getY(), from.getZ() + 0.5D + flow[2] * ALONG);
            }
        }
        return new BlockPos(p[0], from.getY(), p[1]);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.ley_compass").withStyle(ChatFormatting.GRAY));
    }
}
