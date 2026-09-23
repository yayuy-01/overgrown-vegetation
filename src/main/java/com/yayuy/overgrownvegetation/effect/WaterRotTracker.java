package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.entity.AquaticOvergrownMob;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * 「水ぐされ」({@link ModMobEffects#WATER_ROT})の付与/継続の判定。
 * 数値はすべて {@code effects.json} の {@code waterRot}({@link EffectTable.WaterRotSpec})。
 *
 * <h2>かかるのは誰か</h2>
 * <ul>
 *     <li><b>グループ1〜10 の Mob だけ</b>({@link MobGroup#of})。プレイヤー・バニラ Mob・
 *     どのグループにも属さない Mob は対象外。</li>
 *     <li><b>水中を生息域にしている Mob は対象外</b> —— 水生マーカー
 *     {@link AquaticOvergrownMob}(リゾドゥス・ゼナカンサス・オルタカンサス・メガラクネ)と、
 *     エンティティタイプタグ {@link ModTags#WATER_ROT_IMMUNE}(汚染水草など、
 *     マーカーを持たない水生種とデータパックでの追加ぶん)。</li>
 *     <li><b>攻城戦ボス</b>(ドルガ=アーケロウス・ティタニカ)も {@link ModTags#WATER_ROT_IMMUNE}
 *     で外してある(ユーザー指定 2026-09-16)。水生ではないが、防具貫通のダメージが
 *     {@code siegeArmor} のしきい値を素通りしてしまうため。</li>
 * </ul>
 *
 * <h2>いつ付いて、いつ切れるか</h2>
 * <ul>
 *     <li>水に<b>連続で</b> {@code soakTicks}(既定 600 = 30 秒)浸かると発症。水から出た時点で
 *     この滞在カウントは 0 に戻る(「合計 30 秒」ではなく「続けて 30 秒」)。</li>
 *     <li>浸かっているあいだは判定のたびに持続を {@code lingerTicks} へ張り直すので<b>切れない</b>。
 *     すでに腐っている個体が水へ入り直した場合は、30 秒待たずその場で張り直す。</li>
 *     <li>自分から上がると張り直しが止まり、残り {@code lingerTicks}(既定 600 = 30 秒)で満了する。</li>
 * </ul>
 *
 * <p>対象の Mob は水に入った時点で岸へ引き返そうとする
 * ({@link com.yayuy.overgrownvegetation.entity.EscapeWaterGoal}、2026-09-17)ので、
 * 実際に発症するのは「上がれる陸が近くに無かった個体」だけになる。
 *
 * <p>負荷対策として各エンティティにつき {@code checkIntervalTicks}(既定 10)tick に 1 回だけ判定し、
 * その 1 回で滞在カウントを間隔ぶん進める({@link SporeInfectionTracker} と同じ作り)。
 * <b>水に入っておらず腐ってもいない</b>エンティティはグループ判定より手前で弾くので、
 * 普段は {@code isInWater()} 1 回ぶんの負荷しか掛からない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class WaterRotTracker {

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity entity) || entity instanceof Player) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel) || !entity.isAlive()) {
            return;
        }

        EffectTable.WaterRotSpec spec = DesignData.effects().waterRot();
        int interval = Math.max(1, spec.checkIntervalTicks());
        if (interval > 1 && Math.floorMod(entity.tickCount + entity.getId(), interval) != 0) {
            return;
        }

        boolean inWater = entity.isInWater();
        boolean rotting = entity.hasEffect(ModMobEffects.WATER_ROT);
        if (!inWater && !rotting) {
            // 大多数はここで終わる。タグ参照もしない。
            resetSoak(entity);
            return;
        }
        if (!spec.enabled() || isWaterRotImmune(entity) || MobGroup.of(entity) == null) {
            // 対象外。すでに付いているぶん(設定を切り替えた直後など)は自然満了に任せる。
            resetSoak(entity);
            return;
        }

        if (!inWater) {
            // 腐ったまま陸に上がった。張り直さないので残り時間で満了する。
            resetSoak(entity);
            return;
        }
        if (rotting) {
            apply(entity, spec);
            resetSoak(entity);
            return;
        }
        int soaked = soak(entity) + interval;
        if (soaked >= spec.soakTicks()) {
            setSoak(entity, 0);
            apply(entity, spec);
        } else {
            setSoak(entity, soaked);
        }
    }

    /** 水ぐされを {@code lingerTicks} で張り直す(浸かっているあいだ切れないのはこれ)。 */
    private static void apply(LivingEntity entity, EffectTable.WaterRotSpec spec) {
        entity.addEffect(new MobEffectInstance(ModMobEffects.WATER_ROT,
                Math.max(1, spec.lingerTicks()), 0, false, true, true));
    }

    /**
     * 水ぐされにかからない Mob か —— 水中を生息域にしている種({@link AquaticOvergrownMob})と
     * {@link ModTags#WATER_ROT_IMMUNE}(汚染水草・攻城戦ボスなど)。
     *
     * <p>{@link com.yayuy.overgrownvegetation.entity.EscapeWaterInjector} も同じ判定を使う ——
     * 「水ぐされにかからない種は水からも逃げない」を 1 か所で決めるため。
     */
    public static boolean isWaterRotImmune(LivingEntity entity) {
        return entity instanceof AquaticOvergrownMob || entity.getType().is(ModTags.WATER_ROT_IMMUNE);
    }

    private static int soak(LivingEntity entity) {
        return entity.getData(ModAttachments.WATER_SOAK);
    }

    private static void setSoak(LivingEntity entity, int ticks) {
        entity.setData(ModAttachments.WATER_SOAK, ticks);
    }

    private static void resetSoak(LivingEntity entity) {
        if (entity.hasData(ModAttachments.WATER_SOAK) && entity.getData(ModAttachments.WATER_SOAK) != 0) {
            entity.setData(ModAttachments.WATER_SOAK, 0);
        }
    }

    private WaterRotTracker() {
    }
}
