package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.OrganTable;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;

/**
 * <b>器官の台帳</b>(2026-09-23)。次元ごと。
 *
 * <h2>覚えるのは節だけ</h2>
 * <p>器官は必ず<b>節</b>({@code organ_node})から生える。台帳が抱えるのは
 * <b>節の位置・種類・生えた時刻</b>の 3 つだけで、<b>組み上がった姿は保存していない</b> ——
 * 姿は節の位置と種から毎回計算できる({@link OrganShape})。
 * 塔 1 本は数百マスあるので、これを保存すると台帳が一気に太る。
 *
 * <h2>台帳と実体を分ける</h2>
 * <p>器官の効果は<b>この台帳だけ</b>から決まる({@link OrganInfluence})。
 * ブロックを読みに行かない —— 器官は世界の反対側にあることも、
 * 誰も居ないチャンクにあることもあるので、<b>読み込まれていなくても効かなければならない</b>。
 *
 * <p>育ち具合は「生えた時刻からの経過」で求まるので、毎 tick 更新するものが無い。
 *
 * <h2>枯れかけ</h2>
 * <p>節を壊されると、その器官は台帳の<b>生きている側から枯れかけへ移る</b>。
 * 効果はその場で消え、姿は {@link OrganWither} が少しずつ削っていく。
 * 途中でサーバーが落ちても、次に読み込まれたときに同じ形を計算し直して続きをやる
 * (消す作業は何度やっても同じ結果になる)。
 */
public final class OrganData extends SavedData {

    private static final String ID = "overgrown_vegetation_organs";

    public static final SavedData.Factory<OrganData> FACTORY =
            new SavedData.Factory<>(OrganData::new, (tag, registries) -> load(tag), null);

    /** 生えた器官 1 つぶん。 */
    public record Organ(OrganKind kind, long born) {
    }

    /** 生きている節。{@link BlockPos#asLong} → 種類と生えた時刻。 */
    private final Long2ObjectOpenHashMap<Organ> alive = new Long2ObjectOpenHashMap<>();

    /** 節を壊されて枯れていく最中の器官。位置 → 種類。 */
    private final Long2ObjectOpenHashMap<OrganKind> withering = new Long2ObjectOpenHashMap<>();

    private OrganData() {
    }

    public static OrganData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static OrganData load(CompoundTag tag) {
        OrganData data = new OrganData();
        ListTag nodes = tag.getList("nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < nodes.size(); i++) {
            CompoundTag node = nodes.getCompound(i);
            data.alive.put(node.getLong("pos"),
                    new Organ(OrganKind.byName(node.getString("kind")), node.getLong("born")));
        }
        ListTag dying = tag.getList("withering", Tag.TAG_COMPOUND);
        for (int i = 0; i < dying.size(); i++) {
            CompoundTag node = dying.getCompound(i);
            data.withering.put(node.getLong("pos"), OrganKind.byName(node.getString("kind")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag nodes = new ListTag();
        for (Long2ObjectMap.Entry<Organ> entry : alive.long2ObjectEntrySet()) {
            CompoundTag node = new CompoundTag();
            node.putLong("pos", entry.getLongKey());
            node.putString("kind", entry.getValue().kind().getSerializedName());
            node.putLong("born", entry.getValue().born());
            nodes.add(node);
        }
        tag.put("nodes", nodes);

        ListTag dying = new ListTag();
        for (Long2ObjectMap.Entry<OrganKind> entry : withering.long2ObjectEntrySet()) {
            CompoundTag node = new CompoundTag();
            node.putLong("pos", entry.getLongKey());
            node.putString("kind", entry.getValue().getSerializedName());
            dying.add(node);
        }
        tag.put("withering", dying);
        return tag;
    }

    // --- 出し入れ ---

    /** 器官が生えた。既にそこに在れば何もしない(生えた時刻を上書きして若返らせない)。 */
    public boolean add(BlockPos node, OrganKind kind, long gameTime) {
        if (alive.containsKey(node.asLong())) {
            return false;
        }
        alive.put(node.asLong(), new Organ(kind, gameTime));
        setDirty();
        OrganInfluence.forget();
        return true;
    }

    /**
     * 節が壊された。効果は<b>その場で</b>消え、姿は枯れかけへ回す。
     *
     * @return 枯れ始めたら true(そこに器官が無かったら false)
     */
    public boolean kill(BlockPos node) {
        Organ organ = alive.remove(node.asLong());
        if (organ == null) {
            return false;
        }
        withering.put(node.asLong(), organ.kind());
        setDirty();
        OrganInfluence.forget();
        return true;
    }

    /** 枯らし終えた({@link OrganWither} から)。 */
    public void witherDone(long node) {
        if (withering.remove(node) != null) {
            setDirty();
        }
    }

    public boolean has(BlockPos node) {
        return alive.containsKey(node.asLong());
    }

    public int count() {
        return alive.size();
    }

    /** 生きている節(位置 → 器官)。読むだけ。 */
    public Long2ObjectMap<Organ> nodes() {
        return alive;
    }

    /** 枯れかけの節(位置 → 種類)。読むだけ。 */
    public Long2ObjectMap<OrganKind> withering() {
        return withering;
    }

    @Nullable
    public OrganKind kindAt(BlockPos node) {
        Organ organ = alive.get(node.asLong());
        return organ == null ? null : organ.kind();
    }

    /** その節が生えた時刻。無ければ {@link Long#MIN_VALUE}。 */
    public long bornAt(BlockPos node) {
        Organ organ = alive.get(node.asLong());
        return organ == null ? Long.MIN_VALUE : organ.born();
    }

    /** その器官の育ち具合(0〜1)。効果はこれに比例する。 */
    public static double maturity(long bornGameTime, long now, OrganTable.SporeGland spec) {
        if (spec.maturityTicks() <= 0L) {
            return 1.0D;
        }
        long age = now - bornGameTime;
        if (age <= 0L) {
            return 0.0D;
        }
        return Math.min(1.0D, (double) age / spec.maturityTicks());
    }

    /** {@code origin} からいちばん近い生きている節。1 つも無ければ {@code null}。 */
    @Nullable
    public BlockPos nearest(BlockPos origin) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (long packed : alive.keySet()) {
            BlockPos pos = BlockPos.of(packed);
            double distance = pos.distSqr(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    /** 台帳を空にする(デバッグコマンド用)。枯れかけも捨てる。 */
    public void clear() {
        if (alive.isEmpty() && withering.isEmpty()) {
            return;
        }
        alive.clear();
        withering.clear();
        setDirty();
        OrganInfluence.forget();
    }

    /** 設計値の胞子腺の節。呼ぶ側を短くするためだけの近道。 */
    public static OrganTable.SporeGland spec() {
        return DesignData.organs().sporeGland();
    }
}
