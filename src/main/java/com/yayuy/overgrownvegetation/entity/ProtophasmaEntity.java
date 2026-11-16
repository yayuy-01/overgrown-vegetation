package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.crawler.CrawlerCritter;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

/**
 * プロトファスマ {@code protophasma}(G2・原生生産種。古生代のナナフシ。翅は汚染シダの色で、茂みと枝に棲む)。
 * 2026-11-16 G2・G3 の作り直しの段 1: 「小さな地這いの虫」の型({@link CrawlerCritter})で地面を歩く・逃げるまで。
 * 梢で枝を歩く・茂みに隠れる・鳴くは後の段(梢を歩くのは張り付く型の方が合う = 全体管理へ案)。
 * 形・骨・アニメは 10-10 のまま、絵だけ 1/16 画素に塗り直した(正本 Blockbench の protophasma.bbmodel。描画倍率 1 = geo に焼き込み済み)。
 *
 * <p>速さ【仮・Claude 11-16】: ゆっくり歩く(うろつき 約 0.05)・逃げる急ぎ 約 0.157(移動の速さ 0.27)。
 * 今の walk(0.3 秒)は着いている足の平均 {@link #WALK_REF} だが、着いている間の速さが波打つので足が滑る。直した walk・run をモデル制作へ頼んだ(11-16)。
 */
public class ProtophasmaEntity extends CrawlerCritter {

    public static final String ID = "protophasma";
    /** 今の walk を FK で測った、着いている足の平均の速さ(ブロック/tick)。 */
    public static final double WALK_REF = 0.106D;
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
