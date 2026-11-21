package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.crawler.CrawlerCritter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

/**
 * プロトファスマ {@code protophasma}(獲物の環境生物・原生生産種。古生代のナナフシ。翅は汚染シダの色で、茂みと枝に棲む)。
 * 2026-11-16 環境生物の作り直しの段 1: 「小さな地這いの虫」の型({@link CrawlerCritter})で地面を歩く・逃げるまで。
 * 梢で枝を歩く・茂みに隠れる・鳴くは後の段(梢を歩くのは張り付く型の方が合う = 全体管理へ案)。
 * 形・骨・アニメは 10-10 のまま、絵だけ 1/16 画素に塗り直した(正本 Blockbench の protophasma.bbmodel。描画倍率 1 = geo に焼き込み済み)。
 *
 * <p>速さ【仮・Claude 11-16】: ゆっくり歩く(うろつき 約 0.05)・逃げる急ぎ 約 0.157(移動の速さ 0.27)。
 * walk・run は 11-16 にモデル制作(g2g3-bug-walk)が足を地面に止めて作り直した(前の walk は着いている間の速さが波打って滑っていた)。
 */
public class ProtophasmaEntity extends CrawlerCritter {

    public static final String ID = "protophasma";
    /** walk(10 tick で 1 巡)・run(5 tick)を 1 倍で流したとき足が滑らない地面の速さ(11-16 g2g3-bug-walk の確定値)。 */
    public static final double WALK_REF = 0.05D, RUN_REF = 0.157D;
    static final float TURN_PER_TICK = 10.0F;
    static final double STROLL_SPEED = 0.56D;

    public ProtophasmaEntity(EntityType<? extends ProtophasmaEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 1);
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

    /** walk 0.4〜2.0 倍(〜0.10)・run 0.55〜1.25 倍(0.086〜0.196)・run へ 0.095 以上 / walk へ 0.088 以下【仮・アニメ側の見立て 11-16】。 */
    @Override
    protected double[] walkRange() {
        return new double[]{0.4D, 2.0D};
    }

    @Override
    protected double[] runRange() {
        return new double[]{0.55D, 1.25D, 0.095D, 0.088D};
    }

    @Override
    protected float turnPerTick() {
        return TURN_PER_TICK;   // 細長くゆっくり向きを変える
    }

    @Override
    protected double strollSpeed() {
        return STROLL_SPEED;
    }

    @Override
    protected double bodyHalfLength() {
        return 0.7D;
    }

    /** ナナフシは長く歩いて長く止まる(小走りではない)【仮】。 */
    @Override
    protected int[] dashTicks() {
        return new int[]{40, 100};
    }

    @Override
    protected int[] pauseTicks() {
        return new int[]{40, 120};
    }
}
