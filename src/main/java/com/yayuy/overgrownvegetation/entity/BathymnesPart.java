package com.yayuy.overgrownvegetation.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.entity.PartEntity;

import javax.annotation.Nullable;

/**
 * {@link BathymnesEntity バティムネス}の当たり判定の一部分(頭・腕の束・殻の後ろ半分。{@link BathymnesBody#PARTS})。
 * 体は長さ 11 ブロックあり、当たり判定の箱は向きで回らないので、本体の箱(2.5 × 2.5)の外にはみ出した体をこれで覆う
 * (エンダードラゴンと同じマルチパート。手本は削除した巣の蔦の部位 HiveVinePart)。
 *
 * <p>部位は体力を持たない。{@link #hurt} は本体へ丸ごと回すだけ(体力は共通)。爆発のように 1 つのダメージ源が
 * 本体と部位の両方に当たったときは、本体の {@link BathymnesEntity#hurt} が同じ tick の同じダメージ源を 1 回にまとめる。
 * 位置は本体が毎 tick、サーバーとクライアントの両方で同じ式({@link BathymnesBody#toWorld})から置き直す。
 */
public class BathymnesPart extends PartEntity<BathymnesEntity> {

    /** {@link BathymnesBody#PARTS} の何番目か。 */
    private final int index;
    private EntityDimensions size;

    public BathymnesPart(BathymnesEntity parent, int index, float size) {
        super(parent);
        this.index = index;
        this.size = EntityDimensions.scalable(size, size);
        this.refreshDimensions();
    }

    public int index() {
        return this.index;
    }

    /** 体格が変わったときに大きさを合わせる(本体が呼ぶ)。 */
    void setSize(float edge) {
        if (Math.abs(this.size.width() - edge) < 1.0E-3F) {
            return;
        }
        this.size = EntityDimensions.scalable(edge, edge);
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

    /** 生きている間だけ狙える(倒れるアニメの間は素通し。本体の箱と同じ)。 */
    @Override
    public boolean isPickable() {
        return this.getParent().isAlive();
    }

    /** どの部位を殴っても本体へ(体力は共通)。 */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return !this.isInvulnerableTo(source) && this.getParent().hurt(source, amount);
    }

    /** 部位と本体を同一視する(同じ体を二重に狙わない・自分の部位を自分で叩かない)。 */
    @Override
    public boolean is(Entity entity) {
        return this == entity || this.getParent() == entity;
    }

    /** ピックブロック(クリエイティブの中クリック)は本体のスポーンエッグ。 */
    @Nullable
    @Override
    public ItemStack getPickResult() {
        return this.getParent().getPickResult();
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return this.size;
    }
}
