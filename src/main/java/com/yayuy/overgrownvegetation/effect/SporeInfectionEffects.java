package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.item.ProtectiveMaskItem;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import com.yayuy.overgrownvegetation.spore.SporeChunkBonus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

/**
 * 「胞子感染」({@link ModMobEffects#SPORE_INFECTION})を実際に付与する処理の一本化。
 *
 * <p>amplifier で 3 段階を区別する — {@code 0} が「胞子感染1」、{@code 1} が「胞子感染2」、{@code 2} が「胞子感染3」。
 * 持続時間・症状は {@code effects.json}({@link EffectTable#level})から段階ごとに読む。
 *
 * <h2>ルール</h2>
 * <ul>
 *     <li><b>低いグレードはかからない</b>: 既に持っている段階より低い段階の付与は何もしない
 *     (胞子感染3 を持っていれば 1・2 はかからない)。同じ段階なら持続時間を延長(再付与)する。</li>
 *     <li><b>チャンクへの胞子濃度加算</b>: プレイヤーに付与できた瞬間、そのプレイヤーのいるチャンクへ
 *     段階ごとの {@code chunkSporeBonusPercent} を積む({@link SporeChunkBonus})。継続(再付与)でも積む。</li>
 *     <li><b>防護マスク</b>({@link ProtectiveMaskItem})を被ったプレイヤーは胞子感染1 までしかかからない
 *     (2 以上の付与は 1 に丸める。既に 1 を持っていれば延長になる)。</li>
 *     <li>{@link ParasitizedMob}(「過繁茂した(MOB名)」自身)には一切付与しない — 既に感染しきった
 *     進化型なので、これ以上「胞子感染」にかかる/それで死亡する意味が無い。</li>
 *     <li>胞子感染3 は付与時にバニラの「空腹」({@code vanillaHungerDurationTicks} / {@code vanillaHungerAmplifier})も併せて付与する。</li>
 * </ul>
 */
public final class SporeInfectionEffects {

    /**
     * {@code target} に「胞子感染」(amplifier 0=胞子感染1、1=胞子感染2、2=胞子感染3)を付与する。
     *
     * @return 実際に付与(新規・昇格・延長)できたか
     */
    public static boolean infect(LivingEntity target, int amplifier) {
        return apply(target, amplifier, false);
    }

    /**
     * デバッグ用({@link com.yayuy.overgrownvegetation.item.SporeInfectionSyringeItem})。
     * 「低いグレードはかからない」ルールと防護マスクを無視し、今の段階を取り除いてから {@code amplifier} を付ける。
     * チャンクへの胞子濃度加算・バニラ空腹などの付随処理は通常経路と同じ。
     */
    public static boolean forceSet(LivingEntity target, int amplifier) {
        if (target instanceof ParasitizedMob) {
            return false;
        }
        target.removeEffect(ModMobEffects.SPORE_INFECTION);
        return apply(target, amplifier, true);
    }

    private static boolean apply(LivingEntity target, int amplifier, boolean force) {
        if (target instanceof ParasitizedMob) {
            return false;
        }
        int amp = Math.max(0, Math.min(EffectTable.MAX_LEVEL_INDEX, amplifier));
        boolean isPlayer = target instanceof Player;
        if (!force && isPlayer && amp > 0 && ProtectiveMaskItem.isWearing((Player) target)) {
            // 防護マスク: 胞子感染1 までしかかからない。
            amp = 0;
        }

        MobEffectInstance current = target.getEffect(ModMobEffects.SPORE_INFECTION);
        if (!force && current != null && current.getAmplifier() > amp) {
            // 既に持っている段階より低い段階はかからない。
            return false;
        }

        EffectTable.LevelSpec spec = DesignData.effects().level(amp);
        int duration = isPlayer ? spec.playerDurationTicks() : spec.mobDurationTicks();
        boolean applied = target.addEffect(new MobEffectInstance(ModMobEffects.SPORE_INFECTION, duration, amp));
        if (!applied) {
            return false;
        }

        if (isPlayer && target.level() instanceof ServerLevel serverLevel) {
            SporeChunkBonus.add(serverLevel, new ChunkPos(target.blockPosition()), spec.chunkSporeBonusPercent());
            if (spec.vanillaHungerDurationTicks() > 0) {
                target.addEffect(new MobEffectInstance(MobEffects.HUNGER, spec.vanillaHungerDurationTicks(),
                        Math.max(0, spec.vanillaHungerAmplifier())));
            }
        }
        return true;
    }

    /**
     * 現在の段階を取り除いてから {@code amplifier} を付与する(段階を<b>下げる</b>ための経路。
     * 胞子感染2 が過繫茂バイオームの外で満了 → 胞子感染1 へ、など)。
     * 通常の {@link #infect} は低い段階を拒否するので、これを使う。
     */
    public static boolean downgrade(LivingEntity target, int amplifier) {
        if (target instanceof ParasitizedMob) {
            return false;
        }
        target.removeEffect(ModMobEffects.SPORE_INFECTION);
        return infect(target, amplifier);
    }

    /** 現在の「胞子感染」の段階(amplifier)。未感染なら -1。 */
    public static int levelOf(LivingEntity entity) {
        MobEffectInstance current = entity.getEffect(ModMobEffects.SPORE_INFECTION);
        return current == null ? -1 : current.getAmplifier();
    }

    private SporeInfectionEffects() {
    }
}
