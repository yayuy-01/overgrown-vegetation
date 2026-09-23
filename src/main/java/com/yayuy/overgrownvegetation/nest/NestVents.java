package com.yayuy.overgrownvegetation.nest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NestTable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地上へ出た噴出孔({@code block/SporeVentBlock})の台帳と、その効果の配布(2026-09-20)。
 *
 * <h2>胞子濃度の上乗せは「別の値」</h2>
 * <p>胞子濃度そのもの({@code corruption/SporeConcentration})は今までどおり 0〜100 のまま。
 * 噴出孔ぶんはここが別に持ち、<b>表示だけ</b>が 100% を超える(ユーザー指定 2026-09-19)。
 * 感染・装備汚染・シダの湧きはどれも 0〜100 の範囲を前提に組んであるので、本体をいじると全部ずれる。
 *
 * <h2>デバフ</h2>
 * <p>噴出孔から {@code ventRadius}(既定 16)の中のプレイヤーには:
 * <ul>
 *     <li><b>採掘速度の低下</b> —— バニラの採掘速度低下を掛け直す(HUD に出るので気づける)</li>
 *     <li><b>自然回復の鈍化</b> —— {@link LivingHealEvent} で回復量を {@link #HEAL_SCALE} 倍にする</li>
 * </ul>
 * 「胞子汚染速度の上昇」は<b>入れない</b> —— 装備の汚染は濃度から自動で速くなるので二重計上になる
 * (ユーザー指定 2026-09-19)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class NestVents {

    /** 噴出孔の範囲にいる間、自然回復はこの倍率になる。 */
    private static final double HEAL_SCALE = 0.4D;
    /** デバフを掛け直す間隔(tick)。切れる前に上書きできる長さにすること。 */
    private static final int APPLY_INTERVAL = 40;
    private static final int EFFECT_DURATION = 100;

    /** 次元ごとの「噴出孔の座標 → 上乗せ %」。 */
    private static final Map<ResourceKey<Level>, Map<BlockPos, Integer>> VENTS = new ConcurrentHashMap<>();

    public static void register(Level level, BlockPos pos, int percent) {
        VENTS.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>()).put(pos.immutable(), percent);
    }

    public static void unregister(Level level, BlockPos pos) {
        Map<BlockPos, Integer> map = VENTS.get(level.dimension());
        if (map != null) {
            map.remove(pos);
        }
    }

    public static Map<BlockPos, Integer> vents(Level level) {
        Map<BlockPos, Integer> map = VENTS.get(level.dimension());
        return map != null ? Collections.unmodifiableMap(map) : Map.of();
    }

    /** ワールドの読み直しで古い座標が残らないように(サーバー停止時)。 */
    public static void clearAll() {
        VENTS.clear();
    }

    /**
     * {@code pos} にかかっている噴出孔ぶんの上乗せ(%)。重なっているぶんは足し合わせる。
     * 胞子濃度そのものには足さない —— 表示と湧き率だけがこれを見る。
     */
    public static double bonusPercentAt(Level level, BlockPos pos) {
        Map<BlockPos, Integer> map = VENTS.get(level.dimension());
        if (map == null || map.isEmpty()) {
            return 0.0D;
        }
        double radius = DesignData.nest().ventRadius();
        double radiusSq = radius * radius;
        double sum = 0.0D;
        for (Map.Entry<BlockPos, Integer> entry : map.entrySet()) {
            if (entry.getKey().distSqr(pos) <= radiusSq) {
                sum += entry.getValue();
            }
        }
        return sum;
    }

    /** 噴出孔の範囲の中か(デバフと回復の鈍化の判定)。 */
    public static boolean isInside(Level level, BlockPos pos) {
        return bonusPercentAt(level, pos) > 0.0D;
    }

    /**
     * 過繫茂 Mob の湧き率に掛ける倍率。上乗せ % と同じだけ上がる
     * (+30% なら 1.3 倍。ユーザー指定 2026-09-19)。
     */
    public static double spawnMultiplierAt(Level level, BlockPos pos) {
        return 1.0D + bonusPercentAt(level, pos) / 100.0D;
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Map<BlockPos, Integer> map = VENTS.get(level.dimension());
        if (map == null || map.isEmpty()) {
            return;
        }
        if (level.getGameTime() % APPLY_INTERVAL != 0) {
            return;
        }
        NestTable t = DesignData.nest();
        double radiusSq = (double) t.ventRadius() * t.ventRadius();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || player.isCreative()) {
                continue;
            }
            BlockPos at = player.blockPosition();
            boolean inside = false;
            for (Map.Entry<BlockPos, Integer> entry : map.entrySet()) {
                if (entry.getValue() > 0 && entry.getKey().distSqr(at) <= radiusSq) {
                    inside = true;
                    break;
                }
            }
            if (inside) {
                player.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, EFFECT_DURATION, 0, true, true));
            }
        }
    }

    /** 噴出孔の範囲では自然回復が鈍る。 */
    @SubscribeEvent
    public static void onHeal(LivingHealEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof Player) || entity.level().isClientSide) {
            return;
        }
        if (!isInside(entity.level(), entity.blockPosition())) {
            return;
        }
        event.setAmount((float) (event.getAmount() * HEAL_SCALE));
    }

    private NestVents() {
    }
}
