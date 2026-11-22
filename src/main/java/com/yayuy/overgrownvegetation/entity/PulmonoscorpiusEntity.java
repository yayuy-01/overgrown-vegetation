package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.crawler.CrawlerCritter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

/**
 * プルモノスコルピウス {@code pulmonoscorpius}(狩る側の環境生物・原生消費種。石炭紀の大きなサソリ。倒木の下に棲む)。
 * 2026-11-16 環境生物の作り直しの段 1: 「小さな地這いの虫」の型({@link CrawlerCritter})で歩く・急ぐまで。行動(狩る・休む・棲み処)は後の段。
 * 形・骨・アニメは 10-22 のまま、絵だけ 1/16 画素に塗り直した(正本 Blockbench の pulmonoscorpius.bbmodel。描画倍率 1 = geo に焼き込み済み)。
 *
 * <p>速さ【仮・Claude 11-16】: 急ぎ 地面 約 0.17(移動の速さ 0.28)・うろつき ×0.6 で 約 0.06。
 * walk・run は 11-16 にモデル制作(g2g3-bug-walk)が足を地面に止めて作り直した(前の walk は脚のキーが無く、手続きの CritterRig が動かしていた)。
 */
public class PulmonoscorpiusEntity extends CrawlerCritter {

    public static final String ID = "pulmonoscorpius";
    /** walk(脚 1 巡 8 tick・1 ループ 2 巡)・run(4.5 tick)を 1 倍で流したとき足が滑らない地面の速さ(11-16 g2g3-bug-walk の確定値)。 */
    public static final double WALK_REF = 0.06D, RUN_REF = 0.17D;
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
    protected double runRef() {
        return RUN_REF;
    }

    /** walk 0.4〜1.9 倍(〜0.114)・run 0.55〜1.25 倍(0.094〜0.2125)・run へ 0.11 以上 / walk へ 0.10 以下【仮・アニメ側の見立て 11-16】。 */
    @Override
    protected double[] walkRange() {
        return new double[]{0.4D, 1.9D};
    }

    @Override
    protected double[] runRange() {
        return new double[]{0.55D, 1.25D, 0.11D, 0.10D};
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
