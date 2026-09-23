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
import java.util.Collections;
import java.util.List;

/**
 * ワールドにあるアルボラの巣の一覧({@link SavedData}、2026-09-19)。
 *
 * <p>巣は掘り終わったあともチャンクがアンロードされることがあるので、
 * <b>座標と側室の位置はここに持つ</b>。核の永続強化や噴出孔の効果は、巣が未ロードでも
 * 効き続ける必要があるため(ユーザー指定 7: 効果は世界全体)。
 *
 * <p>実際の掘削は {@link NestGrowthQueue}、形の計算は {@link NestLayout}、
 * 営巣のきっかけは {@link NestFormation}。
 */
public final class NestData extends SavedData {

    private static final String ID = "overgrown_vegetation_nest";

    public static final SavedData.Factory<NestData> FACTORY =
            new SavedData.Factory<>(NestData::new, (tag, registries) -> load(tag), null);

    /**
     * 掘り終わった巣 1 つ。
     *
     * @param origin      入口(地表)
     * @param corePos     女王室の奥の核。無ければ null
     * @param chambers    側室の位置と種類
     * @param route        巣じゅうの道しるべ(添字 0 が坑口)。Mob の出入りに使う
     * @param routeParents 道しるべ i の親(坑口へ 1 歩戻る先)。根は -1
     * @param createdTime  掘り始めたゲーム内時刻
     */
    public record Nest(BlockPos origin, @Nullable BlockPos corePos, List<ChamberRecord> chambers,
                       List<BlockPos> route, int[] routeParents, long createdTime) {
    }

    /** 側室 1 つの記録。 */
    public record ChamberRecord(NestChamber type, BlockPos pos) {
    }

    private final List<Nest> nests = new ArrayList<>();

    private NestData() {
    }

    public static NestData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static NestData load(CompoundTag tag) {
        NestData data = new NestData();
        ListTag list = tag.getList("nests", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            BlockPos origin = NbtUtils.readBlockPos(entry, "origin").orElse(BlockPos.ZERO);
            BlockPos core = entry.contains("core")
                    ? NbtUtils.readBlockPos(entry, "core").orElse(null) : null;
            long created = entry.getLong("created");
            List<ChamberRecord> chambers = new ArrayList<>();
            ListTag cl = entry.getList("chambers", Tag.TAG_COMPOUND);
            for (int c = 0; c < cl.size(); c++) {
                CompoundTag ct = cl.getCompound(c);
                NestChamber type = NestChamber.byId(ct.getString("type"));
                BlockPos pos = NbtUtils.readBlockPos(ct, "pos").orElse(null);
                if (type != null && pos != null) {
                    chambers.add(new ChamberRecord(type, pos));
                }
            }
            List<BlockPos> route = new ArrayList<>();
            for (long key : entry.getLongArray("route")) {
                route.add(BlockPos.of(key));
            }
            int[] parents = entry.getIntArray("routeParents");
            if (parents.length != route.size()) {
                // 古い記録(2026-09-20 より前)は幹に沿った 1 本の線だった。鎖として読み直す。
                parents = new int[route.size()];
                for (int r = 0; r < parents.length; r++) {
                    parents[r] = r - 1;
                }
            }
            data.nests.add(new Nest(origin, core, chambers, route, parents, created));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Nest nest : nests) {
            CompoundTag entry = new CompoundTag();
            entry.put("origin", NbtUtils.writeBlockPos(nest.origin()));
            if (nest.corePos() != null) {
                entry.put("core", NbtUtils.writeBlockPos(nest.corePos()));
            }
            entry.putLong("created", nest.createdTime());
            ListTag cl = new ListTag();
            for (ChamberRecord c : nest.chambers()) {
                CompoundTag ct = new CompoundTag();
                ct.putString("type", c.type().id());
                ct.put("pos", NbtUtils.writeBlockPos(c.pos()));
                cl.add(ct);
            }
            entry.put("chambers", cl);
            long[] route = new long[nest.route().size()];
            for (int r = 0; r < route.length; r++) {
                route[r] = nest.route().get(r).asLong();
            }
            entry.putLongArray("route", route);
            entry.putIntArray("routeParents", nest.routeParents());
            list.add(entry);
        }
        tag.put("nests", list);
        return tag;
    }

    public List<Nest> nests() {
        return Collections.unmodifiableList(nests);
    }

    public int count() {
        return nests.size();
    }

    /** 掘り終わった巣を登録する({@link NestGrowthQueue} から)。 */
    public void onNestDug(ServerLevel level, NestLayout.Plan plan) {
        List<ChamberRecord> chambers = new ArrayList<>();
        for (NestLayout.Chamber c : plan.chambers) {
            chambers.add(new ChamberRecord(c.type(), c.pos()));
        }
        nests.add(new Nest(plan.origin, plan.corePos, chambers, List.copyOf(plan.route),
                plan.routeParents.toIntArray(), level.getGameTime()));
        setDirty();
    }

    /** {@code pos} から {@code radius} 以内に巣の入口があるか(営巣の間隔を空けるのに使う)。 */
    public boolean hasNestWithin(BlockPos pos, double radius) {
        double rr = radius * radius;
        for (Nest nest : nests) {
            if (nest.origin().distSqr(pos) <= rr) {
                return true;
            }
        }
        return false;
    }

    /**
     * 世界にある<b>孵化室</b>の数。1 つでもあれば亜種が解禁される(ユーザー指定 1)。
     * {@code variant/MobVariants} が湧きのたびに引くので、数え直しは軽く保つこと。
     */
    public int hatcheryCount() {
        int n = 0;
        for (Nest nest : nests) {
            for (ChamberRecord c : nest.chambers()) {
                if (c.type() == NestChamber.HATCHERY) {
                    n++;
                }
            }
        }
        return n;
    }

    /** いちばん近い巣。無ければ null。 */
    @Nullable
    public Nest nearest(BlockPos pos) {
        Nest best = null;
        double bestSq = Double.MAX_VALUE;
        for (Nest nest : nests) {
            double sq = nest.origin().distSqr(pos);
            if (sq < bestSq) {
                bestSq = sq;
                best = nest;
            }
        }
        return best;
    }

    /**
     * {@code pos} を含む巣(入口から {@code radius} 以内で、入口より下)。無ければ null。
     * 「巣の中にいるか」の判定に使う —— 巣は掘り方で広がるので、正確な形ではなく大きめの球で見る。
     */
    @Nullable
    public Nest enclosing(BlockPos pos, double radius) {
        Nest best = null;
        double bestSq = radius * radius;
        for (Nest nest : nests) {
            if (pos.getY() > nest.origin().getY() + 4) {
                continue;
            }
            double dx = pos.getX() - nest.origin().getX();
            double dz = pos.getZ() - nest.origin().getZ();
            double sq = dx * dx + dz * dz;
            if (sq <= bestSq) {
                bestSq = sq;
                best = nest;
            }
        }
        return best;
    }

    /** {@code origin} を入口とする巣。無ければ null。 */
    @Nullable
    public Nest byOrigin(BlockPos origin) {
        for (Nest nest : nests) {
            if (nest.origin().equals(origin)) {
                return nest;
            }
        }
        return null;
    }

    /** 巣を 1 つ記録から消す(核を倒したあとの後始末などに使う予定)。 */
    public boolean remove(BlockPos origin) {
        boolean removed = nests.removeIf(n -> n.origin().equals(origin));
        if (removed) {
            setDirty();
        }
        return removed;
    }
}
