package com.yayuy.overgrownvegetation.entity.parts;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.entity.PartEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 大きな体の「叩ける部位」(11-08、森の中を動く型 ② の段 1。エンダードラゴンの部位と同じ作り)。
 *
 * <p>地形とぶつかるのは本体の細い芯の箱だけにして、芯の外に見えている頭・首・鎌・腹はこの部位で殴れるようにする
 * (ユーザー決定「芯を細く + 叩ける部位」)。部位は世界に足さない(NeoForge が本体の {@code getParts()} から拾う)。
 * 地形とはぶつからず、押し合わず、保存もしない。受けた攻撃はそのまま本体の {@code hurt} へ回す
 * (本体の回避・掴みの振りほどき・威嚇の中断などがそのまま効く)。
 *
 * <p>置き場は {@link HitPartSet} が毎 tick、本体の向きに合わせて決める。種によらない部品
 * (オイカケ・ハガネカワを作るときに使い回す)。
 */
public final class HitPart extends PartEntity<Mob> {

    public final String name;
    private EntityDimensions size;
    /** 叩かれたときに本体へ知らせる口(11-14、フィロセンスの頭。無ければ null。ファルキムネスは使わない)。本体の hurt の前に呼ぶ。 */
    @Nullable
    java.util.function.BiConsumer<HitPart, DamageSource> onHurt;

    HitPart(Mob parent, String name, float width, float height) {
        super(parent);
        this.name = name;
        this.size = EntityDimensions.scalable(width, height);
        this.refreshDimensions();
        this.noPhysics = true;
    }

    /** 大きさを変える(本体の体格が変わったとき)。 */
    void resize(float width, float height) {
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

    @Nullable
    @Override
    public ItemStack getPickResult() {
        return this.getParent().getPickResult();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isInvulnerableTo(source)) {
            return false;
        }
        if (this.onHurt != null) {
            this.onHurt.accept(this, source);
        }
        return this.getParent().hurt(source, amount);
    }

    // --- 火(11-08 ユーザー決定「火の矢・火属性も芯と同じに効かせる」) ---
    // 部位は tick しないので、部位に付いた火は燃えない。火の残りは本体のものを読み書きする
    // (火の矢は当たりが弾かれたとき、当たる前に読んだ残りへ書き戻すので、読むのも本体から)。
    // Entity のコンストラクタの中(親がまだ無い)では何もしない。

    @Override
    public void igniteForTicks(int ticks) {
        Mob parent = this.getParent();
        if (parent != null) {
            parent.igniteForTicks(ticks);
        }
    }

    @Override
    public void setRemainingFireTicks(int ticks) {
        Mob parent = this.getParent();
        if (parent != null) {
            parent.setRemainingFireTicks(ticks);
        }
    }

    @Override
    public int getRemainingFireTicks() {
        Mob parent = this.getParent();
        return parent != null ? parent.getRemainingFireTicks() : 0;
    }

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
