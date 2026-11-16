package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.lizard.SimpleLizard;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

/**
 * ヒロノムス {@code hylonomus}(G3・原生消費種。石炭紀のいちばん小さな爬虫類)。G3 はその環境に生息しているだけの生き物(胞子を運ばない)。
 * G1〜G10 なので、ほかのすべての Mob から敵対される(タグ group_3 → plant_side)。
 *
 * <h2>段 1(2026-11-16): 歩く・走るところまで</h2>
 * 移動は「トカゲ型」({@link SimpleLizard})。首の骨が無く、頭が胴に直に付いている(見回し・首の打ち消しは頭で)。行動(食べる・休む・隠れる)は後の段。
 * 自然には湧かない。素材は parked から戻した(正本 {@code デスクトップ\blockbench\mobs\hylonomus.bbmodel})。
 * 大きさは体格 2(11-16 ユーザー決定。前は 2.5)= 全長 2.26・高さ 0.58・幅 0.88(箱の角から測った)。数値は前の design の値【仮】。
 */
public class HylonomusEntity extends SimpleLizard {

    public static final String ID = "hylonomus";
    /** 描画倍率 1(大きさは体格 = 属性の scale 2 で掛ける)。 */
    public static final float RENDER_SCALE = 1.0F;
    static final double PX = RENDER_SCALE / 16.0D;

    /** 足の置き場(左前・右前・左後ろ・右後ろ、体格 1 のブロック)。walk の着いている間の真ん中: 前は左右 ±2.4 px・前 1.8 px、後ろは左右 ±2.2 px・後ろ 1.1 px。 */
    static final double[] LEG_HOME_LEFT = {2.4D * PX, -2.4D * PX, 2.2D * PX, -2.2D * PX};
    static final double[] LEG_HOME_FWD = {1.8D * PX, 1.8D * PX, -1.1D * PX, -1.1D * PX};
    /** 曲げの付け根: 頭 head(z −3.2。残りは頭の先の目安)、尾 tail1〜tail3(z 2.9・5.9・8.7。4 つ目は尾の先の目安)。 */
    static final double[] BEND_FRONT = {3.2D * PX, 5.0D * PX, 6.0D * PX, 7.0D * PX};
    static final double[] BEND_BACK = {2.9D * PX, 5.9D * PX, 8.7D * PX, 10.5D * PX};
    /** walk・run を 1.0 倍で流したときの地上速度(ブロック/tick、体格 1)。11-16 にモデル制作が足先の決まった 1 点で測った値(前後・左右ともそろっている)。 */
    static final double WALK_REF = 0.060D, RUN_REF = 0.099D;
    public static final double JUMP_STRENGTH = 0.62D;

    public HylonomusEntity(EntityType<? extends HylonomusEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 2);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID).add(Attributes.JUMP_STRENGTH, JUMP_STRENGTH);
    }

    @Override
    protected double[] legHomeLeft() {
        return LEG_HOME_LEFT;
    }

    @Override
    protected double[] legHomeFwd() {
        return LEG_HOME_FWD;
    }

    @Override
    protected double[] bendFront() {
        return BEND_FRONT;
    }

    @Override
    protected double[] bendBack() {
        return BEND_BACK;
    }

    @Override
    protected double cullingRadius() {
        return 0.7D * this.getScale();
    }

    @Override
    protected double walkRef() {
        return WALK_REF;
    }

    @Override
    protected double runRef() {
        return RUN_REF;
    }

    /** 足の流れる距離 0.03〜0.09 ブロック(体格 1。速さ × 1.2)【仮】。walk の前足は体格 1 で ±0.09 ブロック運ぶ。 */
    @Override
    protected double[] stepRange() {
        return new double[]{0.03D, 0.09D, 1.2D};
    }

    @Override
    protected double[] footProbe() {
        return new double[]{0.04D, 0.15D, 0.25D};
    }
}
