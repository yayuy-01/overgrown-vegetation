package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 森の器官の記録(2026-11-17。オーバーワールドに 1 つ)。司令塔が予算を払って芽吹かせた器官の、種類・形・種・場所・芽吹いた時刻・
 * 置いた所までの数・生きているか。形そのもの(立体の設計図)は保存しない —— 種から作り直せる({@link OrganBlueprint})。
 *
 * <p>育ち方は時計で決まる({@link PhaseData#clock} − 芽吹いた時刻)。読み込まれていない所でも育ち、役割も働く。
 * ブロックは読み込まれたときに追いつきで置く({@link OrganWorld})。
 */
public final class ForestOrgans extends SavedData {

    private static final String ID = "overgrown_vegetation_forest_organs";

    public static final SavedData.Factory<ForestOrgans> FACTORY =
            new SavedData.Factory<>(ForestOrgans::new, (tag, registries) -> load(tag), null);

    public enum State {
        /** 芽吹いて育っている(役割はまだ)。 */
        GROWING,
        /** 育ち切った(役割が働く)。 */
        ALIVE,
        /** 芯を壊されて枯れた(形は残る。役割は止まる)。 */
        WITHERED
    }

    /** 器官 1 つ。 */
    public static final class Organ {
        public final int id;
        public final OrganKind kind;
        public final int variant;
        public final int seed;
        /** 向き(0〜3 = 90 度ずつ)。 */
        public final int rot;
        /** 小さい版(林床の家〜小屋の大きさ。2026-11-17)。 */
        public final boolean small;
        public final int x, z;
        /** 地面の高さ(芽を置くまで {@link Integer#MIN_VALUE})。 */
        public int y = Integer.MIN_VALUE;
        public long plantedAt;
        public State state = State.GROWING;
        /** 置いた所まで(設計図の順の添字)。 */
        public int placed;
        /** 設計図の数(作るまで 0)。 */
        public int total;
        /** 枯れの状態に替えた所まで。 */
        public int withered;
        public long witheredAt = -1L;
        /** 芽(仮の芯)を片付けたか。 */
        public boolean budCleared;
        /** 建つ所の木を払い終えたか(11-19)。 */
        public boolean cleared;
        /** 枯れた後に生え直させた苗木の数(試した数。11-19)。 */
        public int regrown; // -1 = 植え終えた
        /** 枯れた後に生やした下草の数(試した数。11-19 段階の生え直し)。 */
        public int regrowUnder;
        /** 触手を出したか。 */
        public boolean tentacles;
        /** 役割を最後に数えた時刻。 */
        public long roleAt = -1L;
        /** フェーズのポイントの端数。 */
        public double pointCarry;
        public long nurseryAt, guardAt, crystalAt;
        /** 置いたときの設計図の形の指紋(0 = まだ・11-18 より前の保存)。違えば置き直す(見直し ②1)。 */
        public int shapeHash;
        /** 形の合わせ直しで数を変えた(保存の印を付ける。保存しない)。 */
        boolean refit;
        /** この器官が出した守りの Mob(UUID。上限を数える。11-18 見直し ②4・ユーザー決定 3)。 */
        public final java.util.Set<java.util.UUID> guards = new java.util.LinkedHashSet<>();

        Organ(int id, OrganKind kind, boolean small, int variant, int seed, int rot, int x, int z, long plantedAt) {
            this.small = small;
            this.id = id;
            this.kind = kind;
            this.variant = variant;
            this.seed = seed;
            this.rot = rot;
            this.x = x;
            this.z = z;
            this.plantedAt = plantedAt;
        }

        public boolean active() {
            return state == State.ALIVE;
        }

        public boolean resolved() {
            return y != Integer.MIN_VALUE;
        }

        public BlockPos anchor() {
            return new BlockPos(x, resolved() ? y : 64, z);
        }

        public ChunkPos chunk() {
            return new ChunkPos(x >> 4, z >> 4);
        }

        /** 育ち具合 0〜1(時計から)。 */
        public double growth(long now) {
            double ticks = DesignData.organs().growDays(small) * 24000.0D;
            return Math.max(0.0D, Math.min(1.0D, (now - plantedAt) / ticks));
        }

        public String describe(long now) {
            return String.format(java.util.Locale.ROOT, "#%d %s%s %s (%d, %s, %d) %s 育ち %.0f%% 置いた %d/%d", id, kind.label, small ? "(小)" : "",
                    OrganShapes.variantName(variant), x, resolved() ? Integer.toString(y) : "?", z, stateLabel(), growth(now) * 100.0D,
                    placed, total);
        }

        private String stateLabel() {
            return switch (state) {
                case GROWING -> "育っている";
                case ALIVE -> "生きている";
                case WITHERED -> "枯れた";
            };
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("Id", id);
            t.putString("Kind", kind.id);
            t.putBoolean("Small", small);
            t.putInt("Variant", variant);
            t.putInt("Seed", seed);
            t.putInt("Rot", rot);
            t.putInt("X", x);
            t.putInt("Z", z);
            t.putInt("Y", y);
            t.putLong("Planted", plantedAt);
            t.putString("State", state.name());
            t.putInt("Placed", placed);
            t.putInt("Total", total);
            t.putInt("Withered", withered);
            t.putLong("WitheredAt", witheredAt);
            t.putBoolean("BudCleared", budCleared);
            t.putBoolean("Cleared", cleared);
            t.putInt("Regrown", regrown);
            t.putInt("RegrowUnder", regrowUnder);
            t.putBoolean("Tentacles", tentacles);
            t.putLong("RoleAt", roleAt);
            t.putDouble("PointCarry", pointCarry);
            t.putLong("NurseryAt", nurseryAt);
            t.putLong("GuardAt", guardAt);
            t.putLong("CrystalAt", crystalAt);
            t.putInt("ShapeHash", shapeHash);
            if (!guards.isEmpty()) {
                ListTag g = new ListTag();
                for (java.util.UUID u : guards) g.add(net.minecraft.nbt.NbtUtils.createUUID(u));
                t.put("Guards", g);
            }
            return t;
        }

        @Nullable
        static Organ load(CompoundTag t) {
            OrganKind kind = OrganKind.byId(t.getString("Kind"));
            if (kind == null) return null;
            Organ o = new Organ(t.getInt("Id"), kind, t.getBoolean("Small"), t.getInt("Variant"), t.getInt("Seed"), t.getInt("Rot"), t.getInt("X"), t.getInt("Z"),
                    t.getLong("Planted"));
            o.y = t.getInt("Y");
            try {
                o.state = State.valueOf(t.getString("State"));
            } catch (IllegalArgumentException e) {
                o.state = State.GROWING;
            }
            o.placed = t.getInt("Placed");
            o.total = t.getInt("Total");
            o.withered = t.getInt("Withered");
            o.witheredAt = t.getLong("WitheredAt");
            o.budCleared = t.getBoolean("BudCleared");
            // 11-19 より前の保存は、置き始めていれば払い済みとみなす(建った後から払わない)
            o.cleared = t.contains("Cleared") ? t.getBoolean("Cleared") : o.placed > 0 || o.budCleared;
            o.regrown = t.getInt("Regrown");
            o.regrowUnder = t.getInt("RegrowUnder");
            o.tentacles = t.getBoolean("Tentacles");
            o.roleAt = t.getLong("RoleAt");
            o.pointCarry = t.getDouble("PointCarry");
            o.nurseryAt = t.getLong("NurseryAt");
            o.guardAt = t.getLong("GuardAt");
            o.crystalAt = t.getLong("CrystalAt");
            o.shapeHash = t.getInt("ShapeHash");
            for (Tag g : t.getList("Guards", Tag.TAG_INT_ARRAY)) {
                try {
                    o.guards.add(net.minecraft.nbt.NbtUtils.loadUUID(g));
                } catch (IllegalArgumentException ignored) {
                }
            }
            return o;
        }
    }

    private final List<Organ> organs = new ArrayList<>();
    private int nextId = 1;
    /** 司令塔が最後に器官を考えた日。 */
    private double planDay = -1.0D;

    private ForestOrgans() {
    }

    public static ForestOrgans get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public static ForestOrgans get(ServerLevel level) {
        return get(level.getServer());
    }

    public List<Organ> organs() {
        return Collections.unmodifiableList(organs);
    }

    @Nullable
    public Organ byId(int id) {
        for (Organ o : organs) if (o.id == id) return o;
        return null;
    }

    /** 枯れていない器官(育っている + 生きている)の数(大きさ別)。 */
    public int livingCount(boolean small) {
        int n = 0;
        for (Organ o : organs) if (o.state != State.WITHERED && o.small == small) n++;
        return n;
    }

    /** 枯れていない器官の数(大きさを問わない)。 */
    public int livingCount() {
        return livingCount(false) + livingCount(true);
    }

    public int count(OrganKind kind, boolean activeOnly) {
        int n = 0;
        for (Organ o : organs) {
            if (o.kind != kind || o.state == State.WITHERED) continue;
            if (activeOnly && !o.active()) continue;
            n++;
        }
        return n;
    }

    public double planDay() {
        return planDay;
    }

    void setPlanDay(double day) {
        planDay = day;
        setDirty();
    }

    /** 器官を芽吹かせる(値段は呼ぶ側が払う)。 */
    public Organ plant(ServerLevel level, OrganKind kind, boolean small, int variant, int seed, int rot, int x, int z) {
        Organ o = new Organ(nextId++, kind, small, variant, seed, rot & 3, x, z, PhaseData.clockNow(level));
        organs.add(o);
        OrganWorld.invalidateCaches();
        setDirty();
        return o;
    }

    /** 記録から消す(確かめ用のコマンドとテストの片付け。置いたブロックは消さない)。 */
    public boolean remove(int id) {
        boolean r = organs.removeIf(o -> o.id == id);
        if (r) {
            OrganWorld.forget(id);
            OrganWorld.invalidateCaches();
            setDirty();
        }
        return r;
    }

    void changed() {
        setDirty();
    }

    // ---------------------------------------------------------------- 芯

    /**
     * 芯のブロックが壊れた(どの経路でも。爆発も)。その場所を含む器官を枯らす。育つ前の芽の芯でも同じ(芽吹きが止まる)。
     */
    public static void onCoreRemoved(ServerLevel level, BlockPos pos) {
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
        ForestOrgans data = get(level);
        Organ hit = data.containing(pos);
        if (hit == null || hit.state == State.WITHERED) return;
        data.wither(level, hit, "芯が壊れた");
    }

    /** その場所を含む器官(芯のまわりの箱で見る)。 */
    @Nullable
    Organ containing(BlockPos pos) {
        Organ best = null;
        double bestD = Double.MAX_VALUE;
        for (Organ o : organs) {
            if (!o.resolved()) continue;
            double dx = pos.getX() - o.x, dz = pos.getZ() - o.z, dy = pos.getY() - o.y;
            if (Math.abs(dx) > OrganBlueprint.MAX_HALF || Math.abs(dz) > OrganBlueprint.MAX_HALF || dy < -OrganBlueprint.MAX_DOWN
                    || dy > OrganBlueprint.MAX_UP) continue;
            double d = dx * dx + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = o;
            }
        }
        return best;
    }

    /**
     * 枯れたときに引くポイント(11-19 ユーザー了承の固定値)= フェーズを進める器官(大きい版)1 本の 1 日分 × {@code wither.pointsDays}
     * × 小さい版の強さ × (フェーズを進める器官なら {@code phaseOrganPointsFactor})。閾値の無いフェーズでは 0。
     */
    public static double witherPoints(int phase, Organ o) {
        if (DesignData.phases().pointsToAdvance(phase) <= 0L) return 0.0D;
        OrganTable t = DesignData.organs();
        return OrganRoles.chunkPointsPerDay(t.phaseChunks) * t.witherPointsDays * t.strength(o.small)
                * (o.kind == OrganKind.PHASE ? t.phaseOrganPointsFactor : 1.0D);
    }

    /** 芯を壊したときに引いた量(テスト・コマンドの表示用)。 */
    public record Penalty(long points, double budget) {
    }

    /**
     * 枯らす: 役割を止め、フェーズのポイントと森の予算(バイオマス)を一気に引く(2026-11-17 ユーザー「フェーズのポイントとバイオマスが
     * 一気に下がるとかにしたいな」)。ポイント = フェーズを進める器官(大きい版)の 1 日分 × {@code wither.pointsDays}(11-19 固定値)、予算 = 今の上限 × {@code budgetCapShare}。
     * フェーズを進める器官はポイントを、蓄えの嚢は予算を多めに引く。小さい版は {@code small.strength} 倍。
     * 嚢・卵・光る筋・芯を枯れの状態に替えるのは {@link OrganWorld} が少しずつ(読み込まれたとき)。育つ前の芽を潰したときも同じ量を引く。
     */
    public Penalty wither(ServerLevel level, Organ o, String why) {
        if (o.state == State.WITHERED) return new Penalty(0L, 0.0D);
        o.state = State.WITHERED;
        o.witheredAt = PhaseData.clockNow(level);
        o.withered = 0;
        OrganTable t = DesignData.organs();
        double k = t.strength(o.small);
        PhaseData pd = PhaseData.get(level);
        long points = pd.removePoints((long) Math.floor(witherPoints(pd.getPhase(), o)));
        com.yayuy.overgrownvegetation.director.ForestDirector dir = com.yayuy.overgrownvegetation.director.ForestDirector.get(level);
        double have = dir.available(level);
        double cut = Math.min(have, com.yayuy.overgrownvegetation.director.ForestDirector.cap(level) * t.witherBudgetCapShare * k
                * (o.kind == OrganKind.SAC ? t.sacBudgetFactor : 1.0D));
        dir.tryPay(level, cut);
        OrganWorld.invalidateCaches();
        setDirty();
        dir.note(String.format(java.util.Locale.ROOT, "器官 #%d %s%s が枯れた(%s)。ポイント −%d・予算 −%.0f", o.id, o.kind.label,
                o.small ? "(小)" : "", why, points, cut));
        return new Penalty(points, cut);
    }

    // ---------------------------------------------------------------- 保存

    private static ForestOrgans load(CompoundTag tag) {
        ForestOrgans d = new ForestOrgans();
        d.nextId = Math.max(1, tag.getInt("NextId"));
        d.planDay = tag.contains("PlanDay") ? tag.getDouble("PlanDay") : -1.0D;
        ListTag list = tag.getList("Organs", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Organ o = Organ.load(list.getCompound(i));
            if (o != null) {
                d.organs.add(o);
                d.nextId = Math.max(d.nextId, o.id + 1);
            }
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("NextId", nextId);
        tag.putDouble("PlanDay", planDay);
        ListTag list = new ListTag();
        for (Organ o : organs) list.add(o.save());
        tag.put("Organs", list);
        return tag;
    }
}
