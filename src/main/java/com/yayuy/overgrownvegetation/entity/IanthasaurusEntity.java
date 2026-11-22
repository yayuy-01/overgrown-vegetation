package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.lizard.SimpleLizard;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import com.yayuy.overgrownvegetation.port.entity.PortAttributes;

/**
 * イアンタサウルス {@code ianthasaurus}(狩る側の環境生物・原生消費種。背に帆を持つ石炭紀の爬虫類。帆は今の大きさのまま = 10-10 の決定)。狩る側の環境生物はその環境に
 * 生息しているだけの生き物(胞子を運ばない)。G1〜G8・環境生物なので、ほかのすべての Mob から敵対される(タグ critter_hunter → plant_side)。
 *
 * <h2>段 1(2026-11-16): 歩く・走るところまで</h2>
 * 移動は「トカゲ型」({@link SimpleLizard})。胴は 1 本(腰の骨が無く、尾と後ろ脚が胴に付いている)なので、背骨のくねりは胴の振りと尾の波だけ。
 * 行動(走る・襲う・食べる・休む・日向ぼっこ)は後の段。自然には湧かない(湧きは生態系の台帳につなぐ予定)。素材は parked から戻した
 * (正本 {@code デスクトップ\blockbench\mobs\ianthasaurus.bbmodel} と往復の違い 0)。大きさは体格 2(MobStats の scale)。数値は前(10-10)の design の値【仮】。
 */
public class IanthasaurusEntity extends SimpleLizard {

    public static final String ID = "ianthasaurus";
    /** 描画倍率 1(大きさは体格 = 属性の scale 2 で掛ける。10-10 の決定「2 倍」。寸法へ焼き込むかはモデル制作がユーザーに聞いている)。 */
    public static final float RENDER_SCALE = 1.0F;
    static final double PX = RENDER_SCALE / 16.0D;

    /** 足の置き場(左前・右前・左後ろ・右後ろ、体格 1 のブロック。体格 2 が掛かる)。walk の着いている間の真ん中: 前は左右 ±4.3 px・前 4.0 px、後ろは左右 ±4.4 px・後ろ 4.6 px。 */
    static final double[] LEG_HOME_LEFT = {4.3D * PX, -4.3D * PX, 4.4D * PX, -4.4D * PX};
    static final double[] LEG_HOME_FWD = {4.0D * PX, 4.0D * PX, -4.6D * PX, -4.6D * PX};
    /** 曲げの付け根: 首 neck・頭 head(z −5.6・−7.8。残り 2 つは頭の先の目安)、尾 tail1〜tail3(z 6.3・11.8・17.3。4 つ目は尾の先の目安)。 */
    static final double[] BEND_FRONT = {5.6D * PX, 7.8D * PX, 9.5D * PX, 11.0D * PX};
    static final double[] BEND_BACK = {6.3D * PX, 11.8D * PX, 17.3D * PX, 21.0D * PX};
    /**
     * walk・run を 1.0 倍で流したときの地上速度(ブロック/tick、体格 1。体格 2 で倍)。11-16 に環境生物の作業セッションが足先の決まった点と
     * 接地した隅の滑りの谷で測った値(walk 0.09〜0.10・run 0.15〜0.16)。最初の 0.055・0.10 は「いちばん低い隅」が接地の途中で入れ替わる
     * ぶんを差し引いて小さく出ていた(再生が約 1.8 倍速すぎて足が滑っていた)。
     */
    static final double WALK_REF = 0.10D, RUN_REF = 0.155D;
    public static final double JUMP_STRENGTH = 0.62D;

    public IanthasaurusEntity(EntityType<? extends IanthasaurusEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 4);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return MobStats.apply(Monster.createMonsterAttributes(), ID).add(PortAttributes.JUMP_STRENGTH.get(), JUMP_STRENGTH);
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
        return 1.2D * this.getScale();
    }

    @Override
    protected double walkRef() {
        return WALK_REF;
    }

    @Override
    protected double runRef() {
        return RUN_REF;
    }

    /** 足の流れる距離 0.06〜0.19 ブロック(体格 1。速さ × 1.6)【仮】。walk の前足は体格 2 で ±0.18 ブロック運ぶ。 */
    @Override
    protected double[] stepRange() {
        return new double[]{0.06D, 0.19D, 1.6D};
    }

    @Override
    protected double[] footProbe() {
        return new double[]{0.06D, 0.25D, 0.4D};
    }
}
