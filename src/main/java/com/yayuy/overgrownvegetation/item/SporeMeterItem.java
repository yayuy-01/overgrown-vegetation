package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 「胞子濃度計」— 持っている間、1 秒ごとにアクションバーへ 3 本の針: 浮遊(足元の胞子濃度)・降り積もり(発芽のしきい値 = 100%)・
 * 自分の暴露量(感染の量 1500 %秒 = 100%)。右クリックでチャット欄に風・土地の様子・暴露量の数。
 *
 * <p>2026-11-04 感染システムの第 1 段で parked から戻した。退避前の形(浮遊と降り積もりの 2 本の針・右クリックで風と土地)に
 * 暴露の針と暴露量の行を足し、場が無いときの古い読み(距離・割合・汚染ブロックの成分・ペディキュラ・装置)を消した。
 * レシピは材料(地脈の結晶の欠片・濾し布・真鍮)が退避中なので戻していなかった。2026-11-14 段 2c で汚染の前から作れる作り方にした
 * (板ガラス・銅 2・コンパス・レッドストーン【仮】。{@code recipe/spore_meter.json})。
 */
public class SporeMeterItem extends Item {

    public SporeMeterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel serverLevel) {
            fieldReading(serverLevel, player);
            serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.7F, 1.4F);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /** 持っている間(利き手かもう一方の手)、1 秒ごとにアクションバーへ今いる所の値と自分の暴露量。 */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (!(level instanceof ServerLevel server) || !(entity instanceof Player player) || player.tickCount % 20 != 0) {
            return;
        }
        if (selected || player.getOffhandItem() == stack) {
            player.displayClientMessage(heldLine(server, player), true);
        }
    }

    /** 持っている間の 1 行(3 本の針)。 */
    public static Component heldLine(ServerLevel level, Player player) {
        double[] v = readings(level, player.blockPosition());
        double air = v[0], dep = v[1];
        double exp = SporeExposureTracker.fraction(player) * 100.0D;
        return Component.translatable("item.overgrown_vegetation.spore_meter.needles",
                needle(air), shown(air), needle(dep), shown(dep), needle(exp), String.format("%.0f", exp))
                .withStyle(colorFor(air));
    }

    /**
     * 今いる所の値 {浮遊 %, 降り積もり(発芽のしきい値 = 100)%}。場を読まない次元・場が止まっているときは 0。
     * 雷雨(胞子の嵐)の間は浮遊が振り切れる(999)。
     */
    public static double[] readings(ServerLevel level, BlockPos pos) {
        if (!SporeConcentration.useField(level)) {
            return new double[]{0.0D, 0.0D};
        }
        double air = SporeConcentration.percentAt(level, pos);
        if (level.dimension() == Level.OVERWORLD && level.isThundering()) {
            air = Math.max(air, 999.0D);
        }
        SporeFieldServer.Cell cell = SporeFieldServer.cell(level, pos.getX() >> 4, pos.getZ() >> 4);
        double threshold = DesignData.sporeField().field().germThreshold;
        double dep = cell == null ? 0.0D : cell.dep() / threshold * 100.0D;
        return new double[]{air, dep};
    }

    private static String shown(double percent) {
        return percent >= 100.0D ? "100+" : String.format("%.0f", percent);
    }

    private static ChatFormatting colorFor(double percent) {
        return percent >= 100.0D ? ChatFormatting.LIGHT_PURPLE
                : percent >= 50.0D ? ChatFormatting.RED
                : percent >= 15.0D ? ChatFormatting.YELLOW
                : ChatFormatting.GREEN;
    }

    /** 右クリック: アクションバーに針、チャット欄に風の向き・強さ、チャンクの段階と萌芽体の数、自分の暴露量。 */
    private static void fieldReading(ServerLevel level, Player player) {
        BlockPos pos = player.blockPosition();
        player.displayClientMessage(heldLine(level, player), true);
        PrevailingWind.Sample w = level.dimension() == Level.OVERWORLD && SporeConcentration.useField(level)
                ? SporeFieldServer.windNow(level) : null; // ネザー・エンドに風は無い
        if (w != null) {
            String[] names = {"east", "southeast", "south", "southwest", "west", "northwest", "north", "northeast"};
            Component dir = Component.translatable("item.overgrown_vegetation.spore_meter.dir." + names[(int) Math.round(w.angleDeg() / 45.0) & 7]);
            player.displayClientMessage(Component.translatable("item.overgrown_vegetation.spore_meter.wind",
                    dir, String.format("%.1f", w.strength)).withStyle(ChatFormatting.AQUA), false);
        }
        SporeFieldServer.Cell cell = SporeConcentration.useField(level) ? SporeFieldServer.cell(level, pos.getX() >> 4, pos.getZ() >> 4) : null;
        if (cell != null && cell.stage() > 0) {
            int stage = Math.min(2, cell.stage());
            player.displayClientMessage(Component.translatable("item.overgrown_vegetation.spore_meter.stage",
                    Component.translatable("item.overgrown_vegetation.spore_meter.stage." + stage), cell.count())
                    .withStyle(ChatFormatting.DARK_GREEN), false);
        }
        double dose = SporeExposureTracker.dose(player);
        double full = DesignData.sporeField().infectDose();
        player.displayClientMessage(Component.translatable("item.overgrown_vegetation.spore_meter.exposure",
                String.format("%.0f", dose), String.format("%.0f", full), String.format("%.0f", dose / full * 100.0D))
                .withStyle(ChatFormatting.GRAY), false);
    }

    /** 10 目盛りの針(棒)。 */
    private static String needle(double percent) {
        int n = (int) Math.max(0, Math.min(10, Math.round(percent / 10.0D)));
        return "▮".repeat(n) + "▯".repeat(10 - n);
    }
}
