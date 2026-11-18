package com.yayuy.overgrownvegetation.ecosystem.life;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 棲み処ブロック(倒木の洞)の中身(2026-11-18、行動の段)。中にいる個体を Mob の保存データのまま持つ(ハチの巣と同じ考え)。
 * <b>tick しない</b>: 出入りは {@link EcoDens} が 2 秒おきにプレイヤーのまわりの洞を見て決める。
 *
 * <p>数の決まり(ecosystem-plan.md・全体管理 11-18 了承): 数の正本は台帳の頭数。洞の中身はその升の割り当てで、初めてプレイヤーが近づいたとき
 * (と、空のまま 1 日たったとき)に 頭数 ÷ 満員 の確率で詰める。入る・出る・遠くで消えるは台帳を減らさない。
 */
public class EcoDenBlockEntity extends BlockEntity {

    public static final int CAPACITY = 3;

    private final List<CompoundTag> occupants = new ArrayList<>();
    /** 中身を最後に詰めた・空になった時刻(ゲーム時刻)。-1 = まだ詰めていない。 */
    long stockedAt = -1L;
    long emptySince = -1L;

    public EcoDenBlockEntity(BlockPos pos, BlockState state) {
        super(EcoBlocks.DEN_BE.get(), pos, state);
    }

    public int count() {
        return this.occupants.size();
    }

    public boolean full() {
        return this.occupants.size() >= CAPACITY;
    }

    /** 中にいる種の id(名前空間なし)。空なら null。 */
    @Nullable
    public String species() {
        if (this.occupants.isEmpty()) return null;
        String id = this.occupants.get(0).getString("id");
        int c = id.indexOf(':');
        return c >= 0 ? id.substring(c + 1) : id;
    }

    /** 生きている Mob をしまう(呼んだ側で discard する)。入れたら true。 */
    public boolean admit(Mob mob) {
        if (full()) return false;
        CompoundTag tag = new CompoundTag();
        if (!mob.saveAsPassenger(tag)) return false;
        tag.remove("Pos");
        tag.remove("Motion");
        tag.remove("UUID");
        tag.remove("Leash");
        tag.remove("NoGravity");   // 入る間だけ切っていた重力を戻して保存する
        this.occupants.add(tag);
        changed();
        return true;
    }

    /** 新しい個体を中に詰める(保存データは型だけ)。 */
    public void stock(EntityType<?> type, int n, long now) {
        for (int i = 0; i < n && !full(); i++) {
            CompoundTag tag = new CompoundTag();
            tag.putString("id", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
            tag.putBoolean("OvFresh", true);
            this.occupants.add(tag);
        }
        this.stockedAt = now;
        changed();
    }

    /**
     * 中の個体を全部、入口の前に出す。{@code each} は出た Mob ごとに呼ぶ(逃げる・襲うなどを決める)。出した数を返す。
     */
    public int releaseAll(ServerLevel level, Vec3 at, float yaw, Consumer<Mob> each) {
        int n = 0;
        List<CompoundTag> list = new ArrayList<>(this.occupants);
        this.occupants.clear();
        for (CompoundTag tag : list) {
            boolean fresh = tag.getBoolean("OvFresh");
            tag.remove("OvFresh");
            Entity e = EntityType.loadEntityRecursive(tag, level, x -> {
                x.moveTo(at.x + (level.random.nextDouble() - 0.5D) * 0.3D, at.y, at.z + (level.random.nextDouble() - 0.5D) * 0.3D, yaw, 0.0F);
                return x;
            });
            if (!(e instanceof Mob m)) continue;
            if (fresh) m.finalizeSpawn(level, level.getCurrentDifficultyAt(m.blockPosition()), MobSpawnType.NATURAL, null);
            m.addTag(EcoDens.DEN_TAG);
            if (m instanceof EcoLife life) life.ecoMind().home = this.worldPosition.immutable();
            if (level.addFreshEntity(m)) {
                n++;
                each.accept(m);
            }
        }
        this.emptySince = level.getGameTime();
        changed();
        return n;
    }

    private void changed() {
        this.setChanged();
        if (this.level != null && !this.level.isClientSide()) {
            BlockState s = this.getBlockState();
            boolean occ = !this.occupants.isEmpty();
            if (s.hasProperty(EcoDenBlock.OCCUPIED) && s.getValue(EcoDenBlock.OCCUPIED) != occ) {
                this.level.setBlock(this.worldPosition, s.setValue(EcoDenBlock.OCCUPIED, occ), 3);
            }
        }
    }

    /** 中身の一覧(覗くとき)。種の id ごとの数。 */
    public java.util.Map<String, Integer> census() {
        java.util.Map<String, Integer> m = new java.util.LinkedHashMap<>();
        for (CompoundTag t : this.occupants) m.merge(t.getString("id"), 1, Integer::sum);
        return m;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        list.addAll(this.occupants);
        tag.put("Occupants", list);
        tag.putLong("StockedAt", this.stockedAt);
        tag.putLong("EmptySince", this.emptySince);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.occupants.clear();
        ListTag list = tag.getList("Occupants", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) this.occupants.add(list.getCompound(i));
        this.stockedAt = tag.contains("StockedAt") ? tag.getLong("StockedAt") : -1L;
        this.emptySince = tag.contains("EmptySince") ? tag.getLong("EmptySince") : -1L;
    }
}
