package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.VisionTable;
import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.yayuy.overgrownvegetation.port.saved.OvSavedData;

/**
 * <b>探りに出す偵察</b>(2026-11-20 ユーザー決定。戦場の霧で、森は見えない所の拠点を覚えられなくなった。偵察が見つける)。
 * 見張り({@link BaseWatch}、20 秒ごと)が {@link #tick} を呼ぶ。偵察のゲート(F4)が開いてから。
 *
 * <ul>
 *   <li><b>手がかり</b>(覚えておく {@code clueDays} 日・重い順に行き先にする):
 *     <ul>
 *       <li>偵察が殺された所(どの偵察でも。プレイヤーに倒されたときだけ)。その方向へ {@code explorersOnKill} 体を足す。</li>
 *       <li>A 夜の明かり: 夜、森の視界の外にいるプレイヤーのまわりで、地表の明かり(ブロックの光 ≥ {@code lightMinLevel})を
 *       {@code lightSamples} 点だけ抜き取り、{@code lightNeeded} 点以上明るく、森の縁から {@code lightSeeBlocks} 以内なら手がかり(場所はぼかす)。
 *       明かりを抑える・屋根の下に入れると出ない。</li>
 *       <li>B 持ち帰りの汚れ: 森の視界の外にいるプレイヤーが、着ている物の汚れ(サバイバルの段 3 の GearDirt)か、最近汚染地域(過繁茂のチャンク)に
 *       いたこと({@code trailDays} 日で消える)の強い方 × {@code trailChance} の確率で手がかり。森の縁から {@code trailSenseBlocks} 以内。
 *       汚れを落とし、しばらく森に入らなければ出ない。</li>
 *     </ul>
 *   </li>
 *   <li><b>D 広がる探し方</b>: 手がかりが無いときは、森の真ん中から {@code sweepSectors} の方角のうち、まだいちばん探していない方角へ、
 *   探した距離 + {@code sweepStepBlocks} の所へ出す。探し終えたら伸ばし、{@code sweepMaxBlocks} で最初から。</li>
 *   <li><b>偵察の体</b>: 偵察は司令塔の側の位置で進む(1 日 {@code explorerBlocksPerDay})。プレイヤーが {@code materializeBlocks} に来たら
 *   G1 の段階 0 の体を出し(探りの使い。{@link G1Scout#beginExplore})、{@code dematerializeBlocks} より離れたら体を戻す。
 *   だから読み込まれていない所でも探しは進み、読み込まれていないチャンクには触らない。</li>
 *   <li><b>見つける</b>: 偵察の周り 32 は森の視界({@link ForestVision})。そこにいるプレイヤーの升は見張りがふつうに数え、
 *   偵察が初めてプレイヤーを見たときは、その升に {@code spotHours} の点を足す(偵察が見つけた)。</li>
 * </ul>
 * 数はどれも {@code director.json} の {@code vision} の【仮】。
 */
public final class ScoutSearch extends OvSavedData {

    private static final String ID = "overgrown_vegetation_scout_search";

    public static final OvSavedData.Factory<ScoutSearch> FACTORY =
            new OvSavedData.Factory<>(ScoutSearch::new, (tag, registries) -> load(tag), null);

    public enum ClueKind {
        KILLED("偵察が殺された"), LIGHT("夜の明かり"), TRAIL("持ち帰りの汚れ"), SWEEP("広がる探し方");

        public final String label;

        ClueKind(String label) {
            this.label = label;
        }

        static ClueKind byName(String n) {
            for (ClueKind k : values()) if (k.name().equals(n)) return k;
            return SWEEP;
        }
    }

    /** 手がかり 1 つ。 */
    public static final class Clue {
        public final ClueKind kind;
        public final double x, z, day;
        public final double weight;

        Clue(ClueKind kind, double x, double z, double day, double weight) {
            this.kind = kind;
            this.x = x;
            this.z = z;
            this.day = day;
            this.weight = weight;
        }
    }

    public enum Leg {
        GO, WATCH, BACK
    }

    /** 探りに出した偵察 1 体(司令塔の側の位置)。 */
    public static final class Explorer {
        public final int id;
        public final ClueKind kind;
        public final int sector;
        public double x, z;
        public final double tx, tz, sx, sz;
        public Leg leg = Leg.GO;
        public double watchUntil;
        @Nullable
        public UUID body;
        public boolean spotted;

        Explorer(int id, ClueKind kind, int sector, double sx, double sz, double tx, double tz) {
            this.id = id;
            this.kind = kind;
            this.sector = sector;
            this.sx = sx;
            this.sz = sz;
            this.x = sx;
            this.z = sz;
            this.tx = tx;
            this.tz = tz;
        }
    }

    private final List<Clue> clues = new ArrayList<>();
    private final List<Explorer> explorers = new ArrayList<>();
    /** 方角ごとの探した距離(森の真ん中から)。 */
    private double[] sweepReach = new double[0];
    private double lastPlanDay = -1.0D;
    private double lastMoveDay = -1.0D;
    private int nextId = 1;
    /** 殺されて足す偵察の残り(次の見直しを待たずに出す)。 */
    private int extraOnKill;
    /** プレイヤーが最後に汚染地域にいた日(持ち帰りの汚れの代わり)。 */
    private final Map<UUID, Double> lastInForest = new HashMap<>();
    /** これまで(保存しない): 手がかりの数・出した数・見つけた数。 */
    public static long cluesMade, explorersSent, spotted;

    private ScoutSearch() {
    }

    public static ScoutSearch get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.load(), FACTORY.constructor(), ID);
    }

    public static VisionTable table() {
        return DesignData.director().vision;
    }

    public List<Clue> clues() {
        return clues;
    }

    public List<Explorer> explorers() {
        return explorers;
    }

    /** どれかの探りの偵察が (x, z) から r 以内にいるか。 */
    public boolean anyExplorerWithin(double x, double z, double r) {
        double r2 = r * r;
        for (Explorer e : explorers) {
            double dx = e.x - x, dz = e.z - z;
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 手がかり

    public void addClue(ClueKind kind, double x, double z, double day, double weight) {
        clues.add(new Clue(kind, x, z, day, weight));
        cluesMade++;
        while (clues.size() > 32) clues.remove(lowest());
        setDirty();
    }

    private int lowest() {
        int idx = 0;
        for (int i = 1; i < clues.size(); i++) if (clues.get(i).weight < clues.get(idx).weight) idx = i;
        return idx;
    }

    /** 偵察(どれでも)がプレイヤーに殺された: その所を重い手がかりにし、そちらへ偵察を足す。 */
    public void onScoutKilled(ServerLevel level, double x, double z) {
        VisionTable t = table();
        double day = BaseMemory.today(level.getServer());
        addClue(ClueKind.KILLED, x, z, day, t.killClueWeight);
        extraOnKill += t.explorersOnKill;
        ForestDirector.get(level).note(String.format(Locale.ROOT, "偵察が殺された(%.0f, %.0f)→ その方向へ偵察を %d 体足す", x, z, t.explorersOnKill));
        setDirty();
    }

    /** 見張りから: 森の視界の外にいるプレイヤーのまわりの手がかりを測る(A 夜の明かり・B 持ち帰りの汚れ)。 */
    public void senseClues(ServerLevel level, double day, double timeOfDay) {
        VisionTable t = table();
        RandomSource r = level.getRandom();
        boolean night = timeOfDay >= 0.54D && timeOfDay <= 0.96D; // 13000〜23000 tick(BaseMemory.timeOfDay は 0〜1)
        PhaseData pd = PhaseData.get(level);
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            if (pd.isOvergrownKey(ChunkPos.asLong(p.getBlockX() >> 4, p.getBlockZ() >> 4))) lastInForest.put(p.getUUID(), day);
            if (ForestVision.sees(level, p.blockPosition())) continue; // 見えているなら手がかりは要らない
            double forestDist = ForestVision.forestDistance(level, p.getX(), p.getZ(), Math.max(t.lightSeeBlocks, t.trailSenseBlocks));
            if (night && forestDist <= t.lightSeeBlocks && lightsAround(level, p.blockPosition(), t, r) >= t.lightNeeded) {
                addFuzzed(ClueKind.LIGHT, p.getX(), p.getZ(), day, t.lightClueWeight, r);
            }
            if (forestDist <= t.trailSenseBlocks && r.nextDouble() < trailStrength(p, lastInForest.get(p.getUUID()), day) * t.trailChance) {
                addFuzzed(ClueKind.TRAIL, p.getX(), p.getZ(), day, t.trailClueWeight, r);
            }
        }
    }

    private void addFuzzed(ClueKind k, double x, double z, double day, double w, RandomSource r) {
        double f = table().clueFuzzBlocks;
        addClue(k, x + (r.nextDouble() * 2.0D - 1.0D) * f, z + (r.nextDouble() * 2.0D - 1.0D) * f, day, w);
    }

    /**
     * 地表の明かりの数(抜き取り)。プレイヤーのまわり {@code lightSampleRadius} の柱を {@code lightSamples} 本くじで引き、空の見える地表の 1 つ上の
     * ブロックの光が {@code lightMinLevel} 以上の本数。読み込まれていないチャンクは数えない。屋根の下・地下の明かりは見えない。
     */
    public static int lightsAround(ServerLevel level, BlockPos at, VisionTable t, RandomSource r) {
        int n = 0;
        for (int i = 0; i < t.lightSamples; i++) {
            int x = at.getX() + Mth.floor((r.nextDouble() * 2.0D - 1.0D) * t.lightSampleRadius);
            int z = at.getZ() + Mth.floor((r.nextDouble() * 2.0D - 1.0D) * t.lightSampleRadius);
            var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) continue;
            int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) + 1;
            if (level.getBrightness(LightLayer.BLOCK, new BlockPos(x, y, z)) >= t.lightMinLevel) n++;
        }
        return n;
    }

    /**
     * 持ち帰りの汚れの強さ(0〜1)= 着ている物の汚れの合計 ÷ {@code trailDirtFull} と、最近汚染地域にいたこと(出てから {@code trailDays} で 0)の強い方。
     * 段 3 の汚れの元を替えるときはここだけ直す。
     */
    public static double trailStrength(ServerPlayer p, @Nullable Double lastForestDay, double day) {
        VisionTable t = table();
        int dirt = 0;
        for (EquipmentSlot s : com.yayuy.overgrownvegetation.survival.decon.GearDirt.SLOTS) {
            dirt += com.yayuy.overgrownvegetation.survival.decon.GearDirt.dirt(p.getItemBySlot(s));
        }
        double fromDirt = Math.min(1.0D, dirt / t.trailDirtFull);
        double fromStay = lastForestDay == null ? 0.0D : Math.max(0.0D, 1.0D - (day - lastForestDay) / t.trailDays);
        return Math.max(fromDirt, fromStay);
    }

    // ---------------------------------------------------------------- 出す・進める

    /** 見張りから(偵察のゲートが開いていて、ピースフルでないとき)。 */
    public void tick(ServerLevel level, double day, double timeOfDay) {
        VisionTable t = table();
        if (!t.enabled) return;
        senseClues(level, day, timeOfDay);
        clues.removeIf(c -> day - c.day > t.clueDays);
        move(level, day);
        bodies(level);
        int max = (int) Math.round(t.maxExplorers * Math.max(0.34D, DirectorGates.strength(level, DirectorGates.Gate.SCOUT)));
        boolean planTime = lastPlanDay < 0.0D || day - lastPlanDay >= t.exploreIntervalDays;
        extraOnKill = Math.min(extraOnKill, t.explorersOnKill * 2); // 倒し続けても倍々に増えない
        // 殺された分は同時の上限 + explorersOnKill まで、ふだんの分は見直しの時に上限まで
        while (true) {
            boolean extra = extraOnKill > 0 && explorers.size() < max + t.explorersOnKill;
            boolean plan = planTime && explorers.size() < max;
            if (!extra && !plan) break;
            if (!launch(level, day, level.getRandom())) break;
            if (extra) extraOnKill--;
        }
        if (planTime) lastPlanDay = day;
        setDirty();
    }

    /** 偵察を 1 体出す。行き先 = いちばん重い手がかり、無ければ広がる探し方。払えなければ出さない。 */
    public boolean launch(ServerLevel level, double day, RandomSource random) {
        VisionTable t = table();
        double[] origin = forestCenter(level);
        if (origin == null) return false; // 森が無い
        double tx, tz;
        ClueKind kind;
        int sector = -1;
        Clue best = null;
        for (Clue c : clues) if (best == null || c.weight > best.weight || c.weight == best.weight && c.day > best.day) best = c;
        if (best != null) {
            clues.remove(best);
            tx = best.x;
            tz = best.z;
            kind = best.kind;
        } else {
            if (sweepReach.length != t.sweepSectors) sweepReach = new double[t.sweepSectors];
            sector = 0;
            for (int i = 1; i < sweepReach.length; i++) if (sweepReach[i] < sweepReach[sector]) sector = i;
            double ang = (sector + random.nextDouble()) * Math.PI * 2.0D / sweepReach.length; // 区切りの中で向きを振る(線と線の間も、いつか通る)
            double d = origin[2] + sweepReach[sector] + t.sweepStepBlocks;
            tx = origin[0] + Math.cos(ang) * d;
            tz = origin[1] + Math.sin(ang) * d;
            kind = ClueKind.SWEEP;
        }
        if (!ForestDirector.get(level).tryPay(level, t.exploreCost)) return false;
        // 出発は森の縁(森の真ん中から行き先の方へ森の半径)
        double ang = Math.atan2(tz - origin[1], tx - origin[0]);
        double sx = origin[0] + Math.cos(ang) * origin[2], sz = origin[1] + Math.sin(ang) * origin[2];
        Explorer e = new Explorer(nextId++, kind, sector, sx, sz, tx, tz);
        explorers.add(e);
        explorersSent++;
        ForestDirector.get(level).note(String.format(Locale.ROOT, "偵察を探りに出した(%s。(%.0f, %.0f)へ。予算 %.0f)", kind.label, tx, tz, t.exploreCost));
        return true;
    }

    /** 森の真ん中と半径 [x, z, 半径](台帳から。台帳の大きさが変わったときだけ数え直す)。森が無ければ null。 */
    @Nullable
    public double[] forestCenter(ServerLevel level) {
        PhaseData pd = PhaseData.get(level);
        int size = pd.overgrownLedgerSize();
        if (size <= 0) return null;
        if (center == null || centerSize != size) {
            double[] s = new double[3];
            pd.forEachOvergrownChunk(k -> {
                s[0] += ChunkPos.getX(k) * 16 + 8;
                s[1] += ChunkPos.getZ(k) * 16 + 8;
                s[2]++;
            });
            center = new double[]{s[0] / s[2], s[1] / s[2], Math.sqrt(size / Math.PI) * 16.0D};
            centerSize = size;
        }
        return center;
    }

    @Nullable
    private double[] center;
    private int centerSize = -1;

    /** 体の無い偵察を時間ぶん進める(体のある偵察は体の位置に合わせる)。 */
    public void move(ServerLevel level, double day) {
        VisionTable t = table();
        double dt = lastMoveDay < 0.0D ? 0.0D : Math.max(0.0D, day - lastMoveDay);
        lastMoveDay = day;
        for (int i = explorers.size() - 1; i >= 0; i--) {
            Explorer e = explorers.get(i);
            Entity body = e.body != null ? level.getEntity(e.body) : null;
            if (body instanceof AbstractParasitizedMonster m && m.isAlive()) {
                e.x = m.getX();
                e.z = m.getZ();
                if (!m.fate().scout().active()) { // 体が使いを終えた
                    finish(e);
                    explorers.remove(i);
                }
                continue;
            }
            e.body = null;
            double step = t.explorerBlocksPerDay * dt;
            switch (e.leg) {
                case GO -> {
                    if (stepToward(e, e.tx, e.tz, step)) {
                        e.leg = Leg.WATCH;
                        e.watchUntil = day + t.explorerWatchDays;
                    }
                }
                case WATCH -> {
                    if (day >= e.watchUntil) e.leg = Leg.BACK;
                }
                case BACK -> {
                    if (stepToward(e, e.sx, e.sz, step)) {
                        finish(e);
                        explorers.remove(i);
                    }
                }
            }
        }
    }

    private static boolean stepToward(Explorer e, double x, double z, double step) {
        double dx = x - e.x, dz = z - e.z;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= step || d < 1.0E-6D) {
            e.x = x;
            e.z = z;
            return true;
        }
        e.x += dx / d * step;
        e.z += dz / d * step;
        return false;
    }

    /** 使いを終えた: 広がる探し方なら、その方角を探したことにする。 */
    private void finish(Explorer e) {
        if (e.sector >= 0 && e.sector < sweepReach.length) {
            sweepReach[e.sector] += table().sweepStepBlocks;
            if (sweepReach[e.sector] >= table().sweepMaxBlocks) sweepReach[e.sector] = 0.0D;
        }
    }

    /** プレイヤーが近い偵察に体を出し、離れた偵察の体を戻す。偵察が初めてプレイヤーを見たら、その升に点を足す。 */
    void bodies(ServerLevel level) {
        VisionTable t = table();
        BaseMemory memory = BaseMemory.get(level.getServer());
        double day = BaseMemory.today(level.getServer());
        for (Explorer e : explorers) {
            ServerPlayer near = nearestPlayer(level, e.x, e.z);
            double d = near == null ? Double.POSITIVE_INFINITY : Math.hypot(near.getX() - e.x, near.getZ() - e.z);
            if (near != null && !e.spotted && d <= t.scoutBlocks) {
                e.spotted = true;
                spotted++;
                long chunk = ChunkPos.asLong(near.getBlockX() >> 4, near.getBlockZ() >> 4);
                memory.addPresence(chunk, t.spotHours, day);
                memory.setStructures(chunk, BaseWatch.countStructures(level, near.blockPosition(), DesignData.director().base.scanHalfHeight), day);
                ForestDirector.get(level).note(String.format(Locale.ROOT, "探りの偵察がプレイヤーを見つけた(%.0f, %.0f)", near.getX(), near.getZ()));
            }
            Entity body = e.body != null ? level.getEntity(e.body) : null;
            if (body != null && body.isAlive()) {
                if (d > t.dematerializeBlocks) { // 体を戻す(司令塔の側で続きを進める)。体の進みを写す
                    if (body instanceof AbstractParasitizedMonster m && m.fate() != null) {
                        G1Scout.Phase ph = m.fate().scout().phase();
                        if (ph == G1Scout.Phase.LEAVE) e.leg = Leg.BACK;
                        else if (ph == G1Scout.Phase.WATCH && e.leg == Leg.GO) {
                            e.leg = Leg.WATCH;
                            e.watchUntil = day + t.explorerWatchDays;
                        }
                    }
                    body.discard();
                    e.body = null;
                }
                continue;
            }
            if (d <= t.materializeBlocks) materialize(level, e);
        }
    }

    @Nullable
    private static ServerPlayer nearestPlayer(ServerLevel level, double x, double z) {
        ServerPlayer best = null;
        double bd = Double.POSITIVE_INFINITY;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            double d = Math.hypot(p.getX() - x, p.getZ() - z);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    /** 偵察の体(G1 の段階 0)を今の位置に出し、探りの使いを渡す。動いていない・地面が無い所では出さない(次に試す)。 */
    void materialize(ServerLevel level, Explorer e) {
        int x = Mth.floor(e.x), z = Mth.floor(e.z);
        BlockPos column = new BlockPos(x, 0, z);
        if (!level.isPositionEntityTicking(column)) return;
        var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return;
        int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        BlockPos feet = new BlockPos(x, y, z);
        // 森の方角から湧かせる偵察と同じ条件: 足元が固い・水でない・プレイヤーの目から見通せない(見える所なら、体を出さずに司令塔の側で進む)
        if (!level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)) return;
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty()) return;
        if (BaseWatch.seenByPlayer(level, feet, DesignData.director().base.scoutSpawnPlayerMinBlocks)) return;
        com.yayuy.overgrownvegetation.corpse.G1Species s = com.yayuy.overgrownvegetation.spawn.DirectorHooks.pickG1(level.getRandom());
        AbstractParasitizedMonster m = s.entityType().create(level);
        if (m == null) return;
        m.moveTo(x + 0.5D, y, z + 0.5D, level.getRandom().nextFloat() * 360.0F, 0.0F);
        if (!level.noCollision(m) || m.fate() == null) {
            m.discard();
            return;
        }
        level.addFreshEntity(m);
        double gx = e.leg == Leg.BACK ? e.sx : e.tx, gz = e.leg == Leg.BACK ? e.sz : e.tz;
        m.fate().scout().beginExplore(gx, gz, e.leg == Leg.BACK);
        ForestVision.addBaseScout(m);
        e.body = m.getUUID();
    }

    /** 探りの偵察の体が死んだ・消えた(殺されたかは BaseWatch#onDeath が見る)。 */
    public void forgetBody(UUID body) {
        explorers.removeIf(e -> body.equals(e.body));
        setDirty();
    }

    // ---------------------------------------------------------------- 見る口

    public List<String> describe(ServerLevel level) {
        List<String> out = new ArrayList<>();
        double[] c = forestCenter(level);
        out.add(String.format(Locale.ROOT, "探りの偵察 %d 体・手がかり %d(これまで 手がかり %d・出した %d・見つけた %d)・森の真ん中 %s",
                explorers.size(), clues.size(), cluesMade, explorersSent, spotted,
                c == null ? "なし" : String.format(Locale.ROOT, "(%.0f, %.0f) 半径 %.0f", c[0], c[1], c[2])));
        for (Explorer e : explorers) {
            out.add(String.format(Locale.ROOT, "  #%d %s %s (%.0f, %.0f) → (%.0f, %.0f)%s", e.id, e.kind.label, e.leg, e.x, e.z, e.tx, e.tz,
                    e.body != null ? " 体あり" : ""));
        }
        for (Clue k : clues) out.add(String.format(Locale.ROOT, "  手がかり %s (%.0f, %.0f) 重み %.1f", k.kind.label, k.x, k.z, k.weight));
        return out;
    }

    public void clearForTests() {
        clues.clear();
        explorers.clear();
        sweepReach = new double[0];
        lastPlanDay = -1.0D;
        lastMoveDay = -1.0D;
        extraOnKill = 0;
        lastInForest.clear();
        center = null;
        centerSize = -1;
        setDirty();
    }

    /** テスト用: その場に探りの偵察を 1 体置く(行き先も同じ所)。 */
    public Explorer addExplorerForTests(double x, double z) {
        Explorer e = new Explorer(nextId++, ClueKind.SWEEP, -1, x, z, x, z);
        explorers.add(e);
        return e;
    }

    /** テスト用: 森の真ん中と半径を決め打ちにする(台帳の大きさが変わるまで)。 */
    public void pinCenterForTests(ServerLevel level, double x, double z, double radius) {
        center = new double[]{x, z, radius};
        centerSize = PhaseData.get(level).overgrownLedgerSize();
    }

    public int extraOnKill() {
        return extraOnKill;
    }

    public double[] sweepReach() {
        return sweepReach;
    }

    // ---------------------------------------------------------------- 保存

    private static ScoutSearch load(CompoundTag tag) {
        ScoutSearch s = new ScoutSearch();
        s.nextId = Math.max(1, tag.getInt("NextId"));
        s.lastPlanDay = tag.contains("PlanDay") ? tag.getDouble("PlanDay") : -1.0D;
        s.lastMoveDay = tag.contains("MoveDay") ? tag.getDouble("MoveDay") : -1.0D;
        s.extraOnKill = tag.getInt("ExtraOnKill");
        long[] reach = tag.getLongArray("Sweep");
        s.sweepReach = new double[reach.length];
        for (int i = 0; i < reach.length; i++) s.sweepReach[i] = reach[i];
        ListTag cl = tag.getList("Clues", Tag.TAG_COMPOUND);
        for (int i = 0; i < cl.size(); i++) {
            CompoundTag c = cl.getCompound(i);
            s.clues.add(new Clue(ClueKind.byName(c.getString("Kind")), c.getDouble("X"), c.getDouble("Z"), c.getDouble("Day"), c.getDouble("W")));
        }
        ListTag ex = tag.getList("Explorers", Tag.TAG_COMPOUND);
        for (int i = 0; i < ex.size(); i++) {
            CompoundTag c = ex.getCompound(i);
            Explorer e = new Explorer(c.getInt("Id"), ClueKind.byName(c.getString("Kind")), c.getInt("Sector"),
                    c.getDouble("SX"), c.getDouble("SZ"), c.getDouble("TX"), c.getDouble("TZ"));
            e.x = c.getDouble("X");
            e.z = c.getDouble("Z");
            try {
                e.leg = Leg.valueOf(c.getString("Leg"));
            } catch (IllegalArgumentException ignored) {
                e.leg = Leg.GO;
            }
            e.watchUntil = c.getDouble("WatchUntil");
            e.spotted = c.getBoolean("Spotted");
            if (c.hasUUID("Body")) e.body = c.getUUID("Body");
            s.explorers.add(e);
            s.nextId = Math.max(s.nextId, e.id + 1);
        }
        ListTag lf = tag.getList("InForest", Tag.TAG_COMPOUND);
        for (int i = 0; i < lf.size(); i++) {
            CompoundTag c = lf.getCompound(i);
            if (c.hasUUID("P")) s.lastInForest.put(c.getUUID("P"), c.getDouble("Day"));
        }
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("NextId", nextId);
        tag.putDouble("PlanDay", lastPlanDay);
        tag.putDouble("MoveDay", lastMoveDay);
        tag.putInt("ExtraOnKill", extraOnKill);
        long[] reach = new long[sweepReach.length];
        for (int i = 0; i < reach.length; i++) reach[i] = Math.round(sweepReach[i]);
        tag.putLongArray("Sweep", reach);
        ListTag cl = new ListTag();
        for (Clue c : clues) {
            CompoundTag t = new CompoundTag();
            t.putString("Kind", c.kind.name());
            t.putDouble("X", c.x);
            t.putDouble("Z", c.z);
            t.putDouble("Day", c.day);
            t.putDouble("W", c.weight);
            cl.add(t);
        }
        tag.put("Clues", cl);
        ListTag ex = new ListTag();
        for (Explorer e : explorers) {
            CompoundTag t = new CompoundTag();
            t.putInt("Id", e.id);
            t.putString("Kind", e.kind.name());
            t.putInt("Sector", e.sector);
            t.putDouble("SX", e.sx);
            t.putDouble("SZ", e.sz);
            t.putDouble("TX", e.tx);
            t.putDouble("TZ", e.tz);
            t.putDouble("X", e.x);
            t.putDouble("Z", e.z);
            t.putString("Leg", e.leg.name());
            t.putDouble("WatchUntil", e.watchUntil);
            t.putBoolean("Spotted", e.spotted);
            if (e.body != null) t.putUUID("Body", e.body);
            ex.add(t);
        }
        tag.put("Explorers", ex);
        ListTag lf = new ListTag();
        for (Map.Entry<UUID, Double> en : lastInForest.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("P", en.getKey());
            t.putDouble("Day", en.getValue());
            lf.add(t);
        }
        tag.put("InForest", lf);
        return tag;
    }
}
