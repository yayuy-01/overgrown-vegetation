package com.yayuy.overgrownvegetation.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * 倒れるアニメの後に膨らんで破裂する植物側の Mob の {@code tickDeath}(2026-11-16 コードの見直し ①14 でファルキムネス・フィロセンスの写しをまとめた)。
 * 倒れるアニメ({@code deathTicks})と破裂の時刻({@code PlantSideRules.burstTick} + 2)の遅い方まで待ってから消す。
 * 消すのは普段は {@code corpse/PlantSideBurst}。ここは外されたときの控え(11-15 ユーザー決定)。
 */
public final class BurstDeath {

    private BurstDeath() {
    }

    /** {@code tickDeath} の中身(サーバーで、待ち終えたら死の煙を出して消す)。 */
    public static void tick(LivingEntity e, int deathTicks) {
        e.deathTime++;
        if (e.deathTime >= Math.max(deathTicks, com.yayuy.overgrownvegetation.spore.PlantSideRules.burstTick(e) + 2) && !e.level().isClientSide() && !e.isRemoved()) {
            e.level().broadcastEntityEvent(e, (byte) 60);
            e.remove(Entity.RemovalReason.KILLED);
        }
    }
}
