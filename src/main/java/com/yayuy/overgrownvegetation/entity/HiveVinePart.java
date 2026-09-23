package com.yayuy.overgrownvegetation.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.neoforged.neoforge.entity.PartEntity;

/**
 * {@link HiveVineEntity 巣の蔦}の当たり判定の一部分。蔦は広間の殻まで十数ブロック伸びた曲線なので、
 * エンティティ 1 個ぶんの箱では「弧の真ん中」しか殴れない。そこでエンダードラゴンと同じ
 * <b>マルチパート</b>({@link PartEntity})にして、曲線に沿って小さな箱を並べ、
 * <b>蔦のどこを殴っても</b>本体({@link HiveVineEntity})にダメージが入るようにしている。
 *
 * <p>部位自体は体力を持たない。{@link #hurt} を親へ丸ごと転送するだけなので、
 * 「1 本の蔦 = 体力 30」は変わらない。
 */
public class HiveVinePart extends PartEntity<HiveVineEntity> {

    private EntityDimensions size;

    public HiveVinePart(HiveVineEntity parent, float width, float height) {
        super(parent);
        this.size = EntityDimensions.scalable(width, height);
        this.refreshDimensions();
    }

    /**
     * 部位の大きさを変える。蔦の長さは広間の半径(木の大きさ)で 10〜22 ブロックと変わるので、
     * 部位の間隔に合わせて太らせ、隙間なく覆えるようにする({@link HiveVineEntity} が呼ぶ)。
     */
    public void setSize(float width) {
        if (Math.abs(this.size.width() - width) < 1.0e-3F) {
            return;
        }
        this.size = EntityDimensions.scalable(width, width);
        this.refreshDimensions();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    /** どの部位を殴っても、蔦そのもの(体力 30)へダメージが入る。 */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return !this.isInvulnerableTo(source) && this.getParent().hurt(source, amount);
    }

    /** 部位と本体を同一視する(同じ蔦を二重に狙わないように)。 */
    @Override
    public boolean is(Entity entity) {
        return this == entity || this.getParent() == entity;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return this.size;
    }
}
