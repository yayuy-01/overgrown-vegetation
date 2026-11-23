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
 * ペトロラコサウルス {@code petrolacosaurus}(狩る側の環境生物・原生消費種。石炭紀の小さな爬虫類。鞭のような長い尾)。狩る側の環境生物はその環境に生息しているだけの生き物
 * (胞子を運ばない)。G1〜G8・環境生物なので、ほかのすべての Mob から敵対される(タグ critter_hunter → plant_side)。
 *
 * <h2>段 1(2026-11-16): 歩く・走るところまで</h2>
 * 移動はピリムネスで作った「トカゲ型」({@link SimpleLizard} → {@code LizardRunner})。行動(食べる・休む・日向ぼっこ・群れ・棲み処)は後の段。
 * 自然には湧かない(環境生物の湧きは生態系の台帳 {@code ecosystem/life/EcoDens} につなぐ予定)。素材は 10-30 に parked へ退避したものを戻した
 * (正本 {@code デスクトップ\blockbench\mobs\petrolacosaurus.bbmodel} と往復の違い 0)。大きさは体格 2(MobStats の scale。10-10 の決定。寸法へ焼き込むかはモデル制作がユーザーに聞いている)。
 * 数値は前(10-10)の design の値を引き継いだ【仮】。
 */
public class PetrolacosaurusEntity extends SimpleLizard {

    public static final String ID = "petrolacosaurus";
    /** 描画倍率 1(大きさは体格 = 属性の scale 2 で掛ける。10-10 の決定「2 倍」。寸法へ焼き込むかはモデル制作がユーザーに聞いている)。 */
    public static final float RENDER_SCALE = 1.0F;
    static final double PX = RENDER_SCALE / 16.0D;

    /** 足の置き場(左前・右前・左後ろ・右後ろ、体格 1 のブロック。体格 2 が掛かる)。直した walk(11-16)の着いている間の真ん中: 前は左右 ±3.8 px・前 3.0 px、後ろは左右 ±4.3 px・後ろ 2.4 px。 */
    static final double[] LEG_HOME_LEFT = {3.8D * PX, -3.8D * PX, 4.3D * PX, -4.3D * PX};
    static final double[] LEG_HOME_FWD = {3.0D * PX, 3.0D * PX, -2.4D * PX, -2.4D * PX};
    /** 曲げの付け根: 首 neck・頭 head(z −4.6・−9。残り 2 つは頭の先の目安)、尾 tail1〜tail4(z 4.7・9.7・15.2・20.7)。 */
    static final double[] BEND_FRONT = {4.6D * PX, 9.0D * PX, 11.0D * PX, 13.0D * PX};
    static final double[] BEND_BACK = {4.7D * PX, 9.7D * PX, 15.2D * PX, 20.7D * PX};
    /** walk・run を 1.0 倍で流したときの地上速度(ブロック/tick、体格 1。体格 2 で倍)。11-16 にモデル制作が walk・run の振り幅を縮めて前後とも基準どおりにした(FK で前 0.046・後ろ 0.043、run 前 0.15)。 */
    static final double WALK_REF = 0.05D, RUN_REF = 0.15D;
    /** 跳ぶ力(2 ブロックの段を跳び上がる)。 */
    public static final double JUMP_STRENGTH = 0.62D;

    public PetrolacosaurusEntity(EntityType<? extends PetrolacosaurusEntity> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.xpReward(ID, 3);
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
        return 1.3D * this.getScale();
    }

    @Override
    protected double walkRef() {
        return WALK_REF;
    }

    @Override
    protected double runRef() {
        return RUN_REF;
    }

    /** 足の流れる距離 0.05〜0.14 ブロック(体格 1。速さ × 1.5)【仮】。直した walk の前足は体格 1 で ±0.10 ブロック運ぶ(歩幅は前の約 0.8 倍)。 */
    @Override
    protected double[] stepRange() {
        return new double[]{0.05D, 0.14D, 1.5D};
    }

    @Override
    protected double[] footProbe() {
        return new double[]{0.06D, 0.25D, 0.45D};
    }
}
