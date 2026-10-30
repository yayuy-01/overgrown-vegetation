package com.yayuy.overgrownvegetation.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.neoforged.neoforge.entity.PartEntity;

/**
 * ヴォラムネスの<b>頭(襟と口)の当たり判定</b>(2026-10-01、実装係の判断)。
 *
 * <p>本体の当たり判定は地上に出た胴の柱(幅 2.5 × 高さ 8.5)だけだが、口を開く・呑む・吐くアニメでは頭が柱から前へ 3.4〜4.4 ブロック、
 * 低いところ(地上 2〜4 ブロック)まで覆いかぶさってくる(NOTES の「口の中心」の表)。目の前に来た頭を殴れないのは不自然なので、
 * 頭に部位を 1 つ足した(エンダードラゴンと同じ {@link PartEntity}。手本は HiveVinePart(削除済み))。
 *
 * <p>部位は体力を持たない。当たったら本体の {@link VoramnesEntity#hurtFromPart} へ回す(体力は本体と共通)。
 * 位置はサーバーとクライアントの両方で、同期した行動番号と行動の始まりの時刻から同じ式で出す({@link VoramnesEntity#headOffset})。
 * 爆発など 1 つのダメージ源が本体と頭の両方に当たっても 1 回分だけ効く(本体が同じ tick の同じダメージ源を捨てる)。
 */
public class VoramnesHeadPart extends PartEntity<VoramnesEntity> {

    /** 頭の箱の一辺(ブロック、体格 1 のとき。襟の幅 約 0.9 から。仮)。 */
    public static final float BASE_SIZE = 0.95F;

    private EntityDimensions size;

    public VoramnesHeadPart(VoramnesEntity parent) {
        super(parent);
        this.size = EntityDimensions.scalable(BASE_SIZE, BASE_SIZE);
        this.refreshDimensions();
    }

    /** 体格(SCALE)に合わせて箱の大きさを変える(変わったときだけ)。 */
    void setScale(float scale) {
        float s = BASE_SIZE * scale;
        if (Math.abs(this.size.width() - s) < 1.0e-3F) {
            return;
        }
        this.size = EntityDimensions.scalable(s, s);
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

    /** 頭を殴っても本体(体力は共通)へ。 */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return !this.isInvulnerableTo(source) && this.getParent().hurtFromPart(source, amount);
    }

    /** 部位と本体を同一視する。 */
    @Override
    public boolean is(Entity entity) {
        return this == entity || this.getParent() == entity;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return this.size;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }
}
