package com.yayuy.overgrownvegetation.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.neoforged.neoforge.entity.PartEntity;

/**
 * {@link ErebomnesEntity エレボムネス}の<b>頭</b>の当たり判定(2026-10-01、実装係の判断で足した)。
 *
 * <p>本体の当たり判定は体格 2 で幅 3.5 × 高さ 2.1 の四角だけで、全長 8.6 のうち頭(口・柄の目・巻いた付属肢)は中心から
 * 前 2.2〜4.4 ブロック = 箱の外に出る(NOTES の「大きさ」)。そのままでは<b>いちばん目立つ頭を殴れず</b>、掴まれた獲物(頭のすぐ下に抱えられる)も
 * 振り向いて胴を殴るしかない。そこでエンダードラゴンと同じ部位({@link PartEntity})を頭に 1 つだけ足した。
 * ユーザーの「複数エンティティ(別の Mob)は使わない見込み」は別の Mob を作らないことと読み、部位は 1 体の Mob の一部なので使ってよいと判断した
 * (SPEC にも「頭に PartEntity を 1 つ足すのは可」)。
 *
 * <p>部位は体力を持たない。殴られた分は {@link ErebomnesEntity#hurtFromPart} で本体へ回す(体力は共通)。
 * 位置は本体が毎 tick、サーバーとクライアントの両方で同じ式から置く({@link ErebomnesEntity#positionHead})。
 * 保存しない・自分では tick しない(世界の一覧に入らないので、本体が動かす)。
 */
public class ErebomnesHead extends PartEntity<ErebomnesEntity> {

    private EntityDimensions size;

    public ErebomnesHead(ErebomnesEntity parent, float width, float height) {
        super(parent);
        this.size = EntityDimensions.scalable(width, height);
        this.refreshDimensions();
    }

    /** 大きさを変える(体格が変わったとき。同じなら何もしない)。 */
    void setSize(float width, float height) {
        if (Math.abs(this.size.width() - width) < 1.0E-3F && Math.abs(this.size.height() - height) < 1.0E-3F) {
            return;
        }
        this.size = EntityDimensions.scalable(width, height);
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

    /** 頭を殴った分は本体へ(体力は共通)。同じ tick の同じダメージ源は本体の側で 1 回にまとめる。 */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return !this.isInvulnerableTo(source) && this.getParent().hurtFromPart(source, amount);
    }

    /** 火をつける効果(火属性の剣・火矢)は本体へ回す(頭は tick しないので、頭に付いた火は燃えずに消えない)。 */
    @Override
    public void igniteForTicks(int ticks) {
        this.getParent().igniteForTicks(ticks);
    }

    /** 頭と本体を同一視する(同じ相手を二重に狙わない)。 */
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
