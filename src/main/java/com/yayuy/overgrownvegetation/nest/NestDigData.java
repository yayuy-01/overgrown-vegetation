package com.yayuy.overgrownvegetation.nest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <b>掘りかけの巣</b>をワールドに残す({@link SavedData}、2026-09-20 のユーザー報告
 * 「再ログインのたびにグループがなくなるバグがあると巣が一生完成しないのでは」)。
 *
 * <p>アルボラ側の記憶(建設グループ・建設地・役割)は<b>もともとエンティティの NBT に保存されている</b>
 * ので、再ログインでも消えない。消えていたのは<b>掘削キューのほう</b> ——
 * {@code NestGrowthQueue.JOBS} はただの静的リストで、サーバーを落とすと丸ごと消える。
 * すると掘るものが無くなり、次の判定で<b>同じ場所に別の設計で掘り直し</b>が始まって、
 * 掘りかけの巣の上に違う形が重なっていた。1 マスずつ掘る作りにして何分もかかるようになったぶん、
 * ここに当たる確率がぐっと上がった。
 *
 * <h2>何を保存するか</h2>
 * <p><b>形そのものは保存しない</b>。{@link NestLayout} は「入口・種・設計値」から同じ形を出す
 * 純粋な計算なので、<b>種だけ憶えておけば読み直しのときに組み立て直せる</b>
 * (1 万マスの座標を毎回書き出すより、はるかに軽い)。
 * 保存するのは入口・種・どこまで掘ったか・掘り手の名簿だけ。
 *
 * <p>地形の高さも設計に渡しているが、それを使うのは噴出孔の柱だけで、
 * <b>掘る場所(hollow)には影響しない</b>。塚が盛られて地面が高くなっていても、
 * どこまで掘ったかの数え方はずれない。
 */
public final class NestDigData extends SavedData {

    private static final String ID = "overgrown_vegetation_nest_dig";

    public static final SavedData.Factory<NestDigData> FACTORY =
            new SavedData.Factory<>(NestDigData::new, (tag, registries) -> load(tag), null);

    /** 掘りかけの巣 1 つ。{@code null} なら掘っていない(建設は世界に 1 つだけ)。 */
    public record Snapshot(BlockPos origin, long seed, int moundCursor, int cursor,
                           int decorCursor, int ventCursor, List<UUID> crew) {
    }

    @Nullable
    private Snapshot snapshot;

    private NestDigData() {
    }

    public static NestDigData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static NestDigData load(CompoundTag tag) {
        NestDigData data = new NestDigData();
        if (!tag.contains("dig")) {
            return data;
        }
        CompoundTag dig = tag.getCompound("dig");
        BlockPos origin = NbtUtils.readBlockPos(dig, "origin").orElse(null);
        if (origin == null) {
            return data;
        }
        List<UUID> crew = new ArrayList<>();
        ListTag list = dig.getList("crew", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < list.size(); i++) {
            crew.add(NbtUtils.loadUUID(list.get(i)));
        }
        data.snapshot = new Snapshot(origin, dig.getLong("seed"), dig.getInt("mound"),
                dig.getInt("cursor"), dig.getInt("decor"), dig.getInt("vent"), crew);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        if (snapshot == null) {
            return tag;
        }
        CompoundTag dig = new CompoundTag();
        dig.put("origin", NbtUtils.writeBlockPos(snapshot.origin()));
        dig.putLong("seed", snapshot.seed());
        dig.putInt("mound", snapshot.moundCursor());
        dig.putInt("cursor", snapshot.cursor());
        dig.putInt("decor", snapshot.decorCursor());
        dig.putInt("vent", snapshot.ventCursor());
        ListTag list = new ListTag();
        for (UUID id : snapshot.crew()) {
            list.add(NbtUtils.createUUID(id));
        }
        dig.put("crew", list);
        tag.put("dig", dig);
        return tag;
    }

    @Nullable
    public Snapshot snapshot() {
        return snapshot;
    }

    public void store(Snapshot next) {
        this.snapshot = next;
        setDirty();
    }

    public void clear() {
        if (this.snapshot != null) {
            this.snapshot = null;
            setDirty();
        }
    }
}
