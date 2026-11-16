package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.crawler.CrawlerCritter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

/**
 * アースロプレウラ {@code arthropleura}(G2・原生生産種。石炭紀の大きなヤスデ。林床の倒木と落ち葉を食べる)。
 * 2026-11-16 G2・G3 の作り直しの段 1: 「小さな地這いの虫」の型({@link CrawlerCritter})で歩く・急ぐまで。行動は後の段。
 * 形は 11-16 夜に「外」へ直した(全長 3.62・幅 1.24・高さ 0.40。脚 8 対が 2 節 = 先の節 {@code legN_l_tip}・{@code legN_r_tip} が増えた)。
 *
 * <p>walk(0.05)・run(0.12)は 11-16 にモデル制作(g2g3-bug-walk)が足を地面に止めて作った(左右の対は同時・後ろから前への波。前の walk は脚のキーが無く、手続きの CritterRig・LegSet が振っていた)。
 * 速さ【仮・Claude 11-16】: うろつき 約 0.05・急ぎ 約 0.12(移動の速さ 0.17 に掛け率)。体は曲がらない(長い体の曲げは後の段)。
 */
public class ArthropleuraEntity extends CrawlerCritter {

    public static final String ID = "arthropleura";
    /** walk を 1 倍で流す地面の速さ【仮・見立て 0.05】(今の walk は脚が地面を送らない)。 */
    public static final double WALK_REF = 0.05D;
    static final float TURN_PER_TICK = 6.0F;
    /** 移動の速さ 0.17 に掛ける: うろつき 約 0.05(2.16 × (0.17 × 0.9)²)・急ぎ 約 0.12(× 1.4)【仮】。 */
    static final double STROLL_SPEED = 0.9D, RUSH_SPEED = 1.4D;

    public ArthropleuraEntity(EntityType<? extends ArthropleuraEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 3);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID);
    }

    @Override
    protected double walkRef() {
        return WALK_REF;
    }

    /** run(1.25 秒 = 脚 5 巡)を 1 倍で流す地面の速さ(11-16 g2g3-bug-walk の確定値)。 */
    public static final double RUN_REF = 0.12D;

    @Override
    protected double runRef() {
        return RUN_REF;
    }

    /** walk 0.4〜1.8 倍(0.02〜0.09)・run 0.6〜1.25 倍(0.072〜0.15)・run へ 0.085 以上 / walk へ 0.078 以下【仮・アニメ側の見立て 11-16】。 */
    @Override
    protected double[] walkRange() {
        return new double[]{0.4D, 1.8D};
    }

    @Override
    protected double[] runRange() {
        return new double[]{0.6D, 1.25D, 0.085D, 0.078D};
    }

    @Override
    protected float turnPerTick() {
        return TURN_PER_TICK;   // 全長 3.6 の長い体
    }

    @Override
    protected double strollSpeed() {
        return STROLL_SPEED;
    }

    @Override
    public double rushSpeed() {
        return RUSH_SPEED;
    }

    @Override
    protected double bodyHalfLength() {
        return 1.6D;
    }

    /** ゆっくり長く歩いて長く止まる【仮】。 */
    @Override
    protected int[] dashTicks() {
        return new int[]{60, 140};
    }

    @Override
    protected int[] pauseTicks() {
        return new int[]{40, 120};
    }
}
