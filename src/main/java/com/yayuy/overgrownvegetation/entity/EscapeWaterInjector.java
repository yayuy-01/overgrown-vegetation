package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.effect.WaterRotTracker;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.world.entity.PathfinderMob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * {@link EscapeWaterGoal} を<b>グループ1〜10 の陸生 {@link PathfinderMob}</b> に仕込む
 * (ユーザー要望 2026-09-17)。{@link GuidedHuntInjector} と同じ、1 種ずつ {@code registerGoals} を
 * 書き換えて回らずに済ませるための一括の差し込み。
 *
 * <p>対象は<b>水ぐされにかかる種とぴったり同じ</b>({@link WaterRotTracker#isWaterRotImmune})。
 * リゾドゥスなどの水生種と攻城戦ボスは水から逃げない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID)
public final class EscapeWaterInjector {

    /**
     * <b>いちばん先に見る</b>。水ぐされは毎秒 1 ずつ確実に削ってくるので、追跡も進軍もうずくまりも
     * 中断して上がる。優先度 0 の {@link net.minecraft.world.entity.ai.goal.FloatGoal} とは
     * 使うフラグが違う(あちらは JUMP、こちらは MOVE と LOOK)ので、浮きながら岸へ泳ぐ形になる。
     */
    private static final int PRIORITY = 0;

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof PathfinderMob mob
                && MobGroup.of(mob) != null
                && !WaterRotTracker.isWaterRotImmune(mob)) {
            mob.goalSelector.addGoal(PRIORITY, new EscapeWaterGoal(mob));
        }
    }

    private EscapeWaterInjector() {
    }
}
