package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.crawler.CrawlerCritter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

/**
 * プルモノスコルピウス {@code pulmonoscorpius}(G3・原生消費種。石炭紀の大きなサソリ。倒木の下に棲む)。
 * 2026-11-16 G2・G3 の作り直しの段 1: 「小さな地這いの虫」の型({@link CrawlerCritter})で歩く・急ぐまで。行動(狩る・休む・棲み処)は後の段。
 * 形・骨・アニメは 10-22 のまま、絵だけ 1/16 画素に塗り直した(正本 Blockbench の pulmonoscorpius.bbmodel。描画倍率 1 = geo に焼き込み済み)。
 *
 * <p>速さ【仮・Claude 11-16】: 急ぎ 地面 約 0.17(移動の速さ 0.28)・うろつき ×0.6 で 約 0.06。
 * <b>今の walk には脚のキーが無い</b>(脚は退避した手続きの CritterRig が動かしていた)ので、脚が止まったまま滑る。
 * 地面を送る walk・run をモデル制作へ頼んだ(11-16)。それまでの基準 {@link #WALK_REF} は胴の揺れが自然に見える仮の値。
 */
public class PulmonoscorpiusEntity extends CrawlerCritter {

    public static final String ID = "pulmonoscorpius";
    /** 仮(脚が地面を送らないので、足の滑りとは関係ない。胴と尾の揺れの速さだけ)。 */
    public static final double WALK_REF = 0.08D;
    static final float TURN_PER_TICK = 12.0F;
    static final double STROLL_SPEED = 0.6D;

    public PulmonoscorpiusEntity(EntityType<? extends PulmonoscorpiusEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 5);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    @Override
    protected double walkRef() {
        return WALK_REF;
    }

    @Override
    protected float turnPerTick() {
        return TURN_PER_TICK;   // 長い体(2.5 ブロック)なのでグレガリアの 30 より遅く回る
    }

    @Override
    protected double strollSpeed() {
        return STROLL_SPEED;
    }

    @Override
    protected double bodyHalfLength() {
        return 0.9D;
    }

    /** サソリはぱっと走ってじっと止まる: 小走りは短め、止まるのは長め【仮】。 */
    @Override
    protected int[] dashTicks() {
        return new int[]{12, 30};
    }

    @Override
    protected int[] pauseTicks() {
        return new int[]{20, 60};
    }
}
