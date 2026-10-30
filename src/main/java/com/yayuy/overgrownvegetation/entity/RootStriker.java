package com.yayuy.overgrownvegetation.entity;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * <b>根の打ち手</b>(寄生された村人の進化 2 段目、<b>G5</b>。G1 の作り直し 第 1 段、2026-10-22)。
 * 2026-10-22 ユーザー決定「村人はゾンビと同じく攻撃系の進化(ゾンビと同じでもよい)、根の連枷の案は保留」→ 今は<b>蔦の処刑者と同じ作り</b>
 * (鞭で引き寄せ・棘の投擲・近接)。連枷で叩いて吹き飛ばす・盾を割る案にするかはユーザーに聞く。残すのは鼻と衣。ラフモデル(生成器 G1RootStriker)。
 */
public class RootStriker extends VineExecutioner {

    public RootStriker(EntityType<? extends RootStriker> type, Level level) {
        super(type, level);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.ZOMBIE_VILLAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.ZOMBIE_VILLAGER_DEATH;
    }
}
