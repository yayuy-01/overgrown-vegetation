package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.item.ExtraArmor;
import com.yayuy.overgrownvegetation.item.SunlitLanternItem;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import javax.annotation.Nullable;

/**
 * 陽溜まりのランタン({@link SunlitLanternItem})の働き(2026-10-16、①)。
 * <ul>
 *     <li>寄りつかなくする: 灯したランタンを腰に下げたプレイヤーを、忌避灯と同じ「近寄らない所」として扱う
 *     ({@code entity/RepellentAvoidGoal#lampNearby} がここを引く。その Goal はグループ 1〜4 に付いている)。効くのは {@link #affects} の Mob だけ。</li>
 *     <li>燃料: 灯している間だけ {@link #STEP} tick ごとに減らす(極相では 2 倍)。切れたら消して周りへ配る(動く光を消すため)。
 *     減るたびには配らない。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SunlitLanterns {

    /** 寄りつかない半径(ユーザー指定「半径 5 ほど」)。 */
    public static final double RADIUS = 5.0D;
    private static final int STEP = 20;

    private SunlitLanterns() {
    }

    /** ランタンが効く相手: グループ 1 の全部と、小さな敵(植物側の Mob で高さ・幅とも 1 未満。仮決め)。 */
    public static boolean affects(Entity mob) {
        MobGroup group = MobGroup.of(mob);
        if (group == null) {
            return false;
        }
        return group == MobGroup.GROUP_1 || (mob.getBbHeight() < 1.0F && mob.getBbWidth() < 1.0F);
    }

    /** 灯したランタンを腰に下げているか。 */
    public static boolean bearing(Player player) {
        ItemStack worn = ExtraArmor.worn(player, ExtraArmor.Part.WAIST);
        return worn.getItem() instanceof SunlitLanternItem && SunlitLanternItem.burning(worn);
    }

    /** {@code from} から {@code range} 以内で、灯したランタンを下げたいちばん近いプレイヤーの足元。いなければ null。 */
    @Nullable
    public static BlockPos nearestBearer(Level level, Vec3 from, double range) {
        Player best = null;
        double bestSq = range * range;
        for (Player player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            double sq = player.distanceToSqr(from);
            if (sq <= bestSq && bearing(player)) {
                best = player;
                bestSq = sq;
            }
        }
        return best == null ? null : best.blockPosition();
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide() || player.tickCount % STEP != 0) {
            return;
        }
        ItemStack worn = ExtraArmor.worn(player, ExtraArmor.Part.WAIST);
        if (!(worn.getItem() instanceof SunlitLanternItem) || !SunlitLanternItem.lit(worn)) {
            return;
        }
        burn(player, worn, STEP * (ModBiomes.isClimax(player.level().getBiome(player.blockPosition())) ? 2 : 1));
    }

    /** 燃料を {@code cost} だけ減らし、切れたら消す。 */
    public static void burn(Player player, ItemStack lantern, int cost) {
        int left = Math.max(0, SunlitLanternItem.fuel(lantern) - cost);
        SunlitLanternItem.setFuel(lantern, left);
        if (left <= 0) {
            SunlitLanternItem.setLit(lantern, false);
            ExtraArmor.broadcast(player);
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.sunlit_lantern.burnt_out")
                    .withStyle(ChatFormatting.GRAY), true);
        }
    }

    /** キーで灯す・消す(腰に下げているランタン)。サーバーで呼ぶ。 */
    public static void toggleWorn(ServerPlayer player) {
        ItemStack worn = ExtraArmor.worn(player, ExtraArmor.Part.WAIST);
        if (!(worn.getItem() instanceof SunlitLanternItem)) {
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.sunlit_lantern.not_worn")
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        if (SunlitLanternItem.toggle(worn, player)) {
            ExtraArmor.broadcast(player);
        }
    }
}
