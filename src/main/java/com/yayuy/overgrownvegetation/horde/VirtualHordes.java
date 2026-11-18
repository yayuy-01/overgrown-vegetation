package com.yayuy.overgrownvegetation.horde;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PerfTable;
import com.yayuy.overgrownvegetation.entity.crawler.CrawlerPack;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.spawn.DirectorHooks;
import com.yayuy.overgrownvegetation.spawn.EnemySpawns;
import com.yayuy.overgrownvegetation.spawn.PlantMarch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>仮想の群れ</b>(2026-11-18 大群の軽量化 A。ユーザー決定: 遠くから来る群れは仮想で進め、読み込み範囲に入ったら本物に・0.2.0 に入れる。
 * 正本は共有ノート virtual-swarm-draft.md と Vault「TPS をできる限り軽くする仕組み」)。
 *
 * <p>群れ 1 つ = 位置・向き・速さ・行き先・種ごとの頭数(1 体ずつは持たない)。エンティティではないので、ぶつかり・行動・送り・描画の重さが無い。
 * parked の TD の仮想の群れ({@code td/TdData.VirtualSwarm}・{@code TdTicker})の群れの部分を土台にした。
 *
 * <h2>本物にする・仮想へ戻す(11-18 全体管理経由でユーザー了承の合わせ方)</h2>
 * <ul>
 *     <li>読み込み範囲の外(群れの真ん中のチャンクが読み込み終わっていない・エンティティが動かない所)は必ず仮想。</li>
 *     <li>内側では、どれかのプレイヤー(観戦を除く)から水平 {@code materializeBlocks}(64)で本物に。1 回に {@code spawnPerRun} 体まで・
 *     本物の総数(印 {@link #TAG} の読み込まれた個体)は {@code realCap}(300)まで。出し切れない分は同じ群れに仮想のまま残り、待つ(進まない)。</li>
 *     <li>本物の群れの一員は、全プレイヤーから水平 {@code dematerializeBlocks}(128)より遠く、行進中・無傷・標的なし・叩かれていない・名札/乗り物なし・
 *     行き先から 48 ブロックより外なら、仮想へ戻す(死なせずに {@code discard}。kill だと死の破裂で汚染を撒く)。</li>
 * </ul>
 * 頭数は作る・倒される・寿命で消える以外で変わらない(本物化・戻しで数が合う。{@link #total})。
 *
 * <h2>動き</h2>
 * {@code moveIntervalTicks} ごとに行き先へまっすぐ進み、読み込み済みの所では高さ地図で高さを合わせる(チャンクは読み込まない)。
 * 本物にしようとして 1 体も置けなければ、行き先から見て横へ 8 ブロックずらしてやり直す(TD の横歩きの簡単な形)。
 * 行き先に着いて誰も来ないまま {@code lifeAfterArriveTicks} 経てば消える【仮】。
 * 流れの地図を下らせるのは後(ユーザー決定: まっすぐ + 横へ避けるから始める)。
 *
 * <p>入口は今のところ確かめの命令 {@code /ov perf virtual} と {@link #spawn} だけ。司令塔の襲撃へのつなぎは全体管理と相談してから(既定では呼ばれない)。
 * 空の種・G1 は入れない(地上の行進できる G4・G5 = {@link DirectorHooks#marchable} だけ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class VirtualHordes extends SavedData {

    public static final String ID = OvergrownVegetation.MODID + "_virtual_hordes";
    /** 仮想の群れから本物になった個体の印(戻すとき・本物の総数を数えるとき)。 */
    public static final String TAG = "ov_virtual_horde";
    /** 本物にしたとき付けた印(戻すとき群れへ持ち帰る)を覚える個体の保存データの鍵。 */
    static final String EXTRA_KEY = "ov_virtual_horde_tag";
    /** 行き先から この距離の中では戻さない(拠点の壁を壊す範囲 = BreachGoal.BASE_RANGE と同じ)。 */
    static final double KEEP_NEAR_TARGET = 48.0D;
    /** 戻すとき、この距離の中にある同じ行き先の群れへ足す。 */
    static final double MERGE_BLOCKS = 24.0D;
    /** 着いたとみなす行き先からの距離。 */
    static final double ARRIVE_BLOCKS = 8.0D;
    /** 置けなかったとき横へずらす距離。 */
    static final double NUDGE_BLOCKS = 8.0D;
    /** 群れ 1 つの頭数の上限(壊れたデータ・命令で巨大にしない)。 */
    public static final int MAX_SWARM = 512;

    public static final SavedData.Factory<VirtualHordes> FACTORY = new SavedData.Factory<>(VirtualHordes::new, (tag, registries) -> load(tag), null);

    /** 確かめ用の数(保存しない)。 */
    /** テスト用: プレイヤーの代わりの位置 {x, z}(GameTest には本物のプレイヤーがいない。null で本物)。 */
    public static java.util.List<double[]> playersForTests;
    /** テスト用: 本物の総数の上限の上書き(null で設計値)。 */
    public static Integer realCapForTests;
    /** テストで世界の tick から動かす(ふだんの GameTest のサーバーでは動かさない: テストの Mob を勝手に戻さない)。 */
    public static boolean tickInGameTests;

    public static long materialized, dematerialized, placeFailures, expired, moveNanos, moveRuns, materializeNanos, maxMaterializeNanos, runs;

    /** 群れ 1 つ。 */
    public static final class Swarm {
        final long id;
        double x, y, z;
        /** 向き(行き先への単位ベクトル。送り・隊列用)。 */
        float dirX = 1.0F, dirZ;
        double speed;
        /** 行き先(拠点の id。無ければ -1)と座標。 */
        final int baseId;
        final double tx, tz;
        /** 本物にしたとき付ける印(襲撃の印など。空なら無し)。 */
        final String extraTag;
        /** 種(エンティティの id)ごとの頭数。 */
        final LinkedHashMap<ResourceLocation, Integer> counts = new LinkedHashMap<>();
        long arrivedAt = -1L;
        /** 本物にしようとして置けなかった回数(保存しない)。 */
        int failures;
        /** 上限で待っているか(この回は進まない。保存しない)。 */
        boolean waiting;

        Swarm(long id, double x, double y, double z, double speed, int baseId, double tx, double tz, String extraTag) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.speed = speed;
            this.baseId = baseId;
            this.tx = tx;
            this.tz = tz;
            this.extraTag = extraTag;
            faceTarget();
        }

        public int size() {
            int n = 0;
            for (int v : counts.values()) n += v;
            return n;
        }

        void add(ResourceLocation type, int n) {
            counts.merge(type, n, Integer::sum);
        }

        void faceTarget() {
            double dx = tx - x, dz = tz - z, d = Math.sqrt(dx * dx + dz * dz);
            if (d > 1.0E-3D) {
                dirX = (float) (dx / d);
                dirZ = (float) (dz / d);
            }
        }

        public long id() {
            return id;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        public float dirX() {
            return dirX;
        }

        public float dirZ() {
            return dirZ;
        }

        public double speed() {
            return waiting || arrivedAt >= 0L ? 0.0D : speed;
        }

        public Map<ResourceLocation, Integer> counts() {
            return counts;
        }
    }

    private final List<Swarm> swarms = new ArrayList<>();
    private long nextId = 1L;

    public static VirtualHordes get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public List<Swarm> swarms() {
        return swarms;
    }

    public static boolean enabled() {
        return OvergrownVegetationConfig.COMMON.virtualHordeEnabled.get();
    }

    /**
     * 仮想の群れを作る(呼ぶ側が予算を払う)。地上の行進できる種だけを数える(ほかは捨てて、その数を返さない)。
     *
     * @return 作った群れ(頭数 0 なら null)
     */
    @Nullable
    public Swarm spawn(double x, double y, double z, int baseId, double tx, double tz, String extraTag, Map<EntityType<?>, Integer> heads) {
        PerfTable t = DesignData.perf();
        Swarm s = new Swarm(nextId++, x, y, z, t.virtualSpeed, baseId, tx, tz, extraTag == null ? "" : extraTag);
        int n = 0;
        for (Map.Entry<EntityType<?>, Integer> e : heads.entrySet()) {
            if (!DirectorHooks.marchable(e.getKey()) || e.getValue() <= 0) continue;
            int k = Math.min(e.getValue(), MAX_SWARM - n);
            if (k <= 0) break;
            s.add(BuiltInRegistries.ENTITY_TYPE.getKey(e.getKey()), k);
            n += k;
        }
        if (n == 0) return null;
        swarms.add(s);
        setDirty();
        return s;
    }

    /** 仮想の頭数の合計。 */
    public int virtualHeads() {
        int n = 0;
        for (Swarm s : swarms) n += s.size();
        return n;
    }

    /** 本物になっている個体(印つき・読み込まれて生きている)の数。 */
    public static int realHeads(ServerLevel level) {
        int n = 0;
        for (Entity e : level.getAllEntities()) {
            if (e instanceof Mob m && m.isAlive() && m.getTags().contains(TAG)) n++;
        }
        return n;
    }

    /** 仮想 + 本物の頭数(往復で変わらないこと)。 */
    public int total(ServerLevel level) {
        return virtualHeads() + realHeads(level);
    }

    // ---------------------------------------------------------------- tick

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!enabled()) return;
        MinecraftServer server = event.getServer();
        if (!tickInGameTests && server instanceof net.minecraft.gametest.framework.GameTestServer) return;
        ServerLevel level = server.overworld();
        VirtualHordes h = get(server);
        PerfTable t = DesignData.perf();
        int tick = server.getTickCount();
        if (!h.swarms.isEmpty() && tick % t.virtualMoveIntervalTicks == 0) {
            long t0 = System.nanoTime();
            h.move(level, t.virtualMoveIntervalTicks, t);
            moveNanos += System.nanoTime() - t0;
            moveRuns++;
        }
        if (!h.swarms.isEmpty() && tick % t.virtualMaterializeIntervalTicks == 0) {
            long t0 = System.nanoTime();
            h.materialize(level, t);
            long dt = System.nanoTime() - t0;
            materializeNanos += dt;
            maxMaterializeNanos = Math.max(maxMaterializeNanos, dt);
            runs++;
        }
        if (tick % t.virtualDematerializeIntervalTicks == 0) {
            h.dematerialize(level, t);
        }
    }

    /** 進める(テストからも)。 */
    public void move(ServerLevel level, int ticks, PerfTable t) {
        long now = level.getGameTime();
        Iterator<Swarm> it = swarms.iterator();
        while (it.hasNext()) {
            Swarm s = it.next();
            if (s.size() <= 0) {
                it.remove();
                continue;
            }
            if (s.arrivedAt >= 0L) {
                if (now - s.arrivedAt > t.virtualLifeAfterArriveTicks) {
                    expired += s.size();
                    it.remove();
                }
                continue;
            }
            if (s.waiting) continue;
            double dx = s.tx - s.x, dz = s.tz - s.z, d = Math.sqrt(dx * dx + dz * dz);
            if (d <= ARRIVE_BLOCKS) {
                s.arrivedAt = now;
                continue;
            }
            double step = Math.min(s.speed * ticks, d - ARRIVE_BLOCKS * 0.5D);
            s.x += dx / d * step;
            s.z += dz / d * step;
            s.faceTarget();
            double g = surfaceY(level, s.x, s.z);
            if (!Double.isNaN(g)) s.y = g;
        }
        setDirty();
    }

    /** 読み込み終わった所の地表の高さ(葉を除く高さ地図。読み込み途中なら NaN。チャンクを読み込まない)。 */
    static double surfaceY(ServerLevel level, double x, double z) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        LevelChunk ch = level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
        return ch == null ? Double.NaN : ch.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx & 15, bz & 15) + 1;
    }

    /** 水平の距離の 2 乗でいちばん近いプレイヤー(観戦を除く)までの 2 乗。 */
    static double nearestPlayerSq(ServerLevel level, double x, double z) {
        double best = Double.MAX_VALUE;
        if (playersForTests != null) {
            for (double[] p : playersForTests) best = Math.min(best, (p[0] - x) * (p[0] - x) + (p[1] - z) * (p[1] - z));
            return best;
        }
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            double dx = p.getX() - x, dz = p.getZ() - z;
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best;
    }

    /** 本物にする(テストからも)。 */
    public void materialize(ServerLevel level, PerfTable t) {
        double near = t.virtualMaterializeBlocks * t.virtualMaterializeBlocks;
        int room = (realCapForTests != null ? realCapForTests : t.virtualRealCap) - realHeads(level);
        int budget = Math.min(room, t.virtualSpawnPerRun);
        RandomSource random = level.getRandom();
        Iterator<Swarm> it = swarms.iterator();
        while (it.hasNext()) {
            Swarm s = it.next();
            s.waiting = false;
            if (nearestPlayerSq(level, s.x, s.z) > near) continue;
            BlockPos center = BlockPos.containing(s.x, s.y, s.z);
            if (level.getChunkSource().getChunkNow(center.getX() >> 4, center.getZ() >> 4) == null || !level.isPositionEntityTicking(center)) {
                continue; // 読み込み範囲の外・読み込み途中: 仮想のまま(進み続ける)
            }
            if (budget <= 0) {
                s.waiting = true; // 本物の上限・この回の数の上限: その場で待つ
                continue;
            }
            int placed = 0, slot = 0;
            float start = (float) ((s.id * 0x9E3779B97F4A7C15L >>> 40) % 6283L) / 1000.0F;
            for (Map.Entry<ResourceLocation, Integer> e : s.counts.entrySet()) {
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(e.getKey());
                int left = e.getValue();
                while (left > 0 && budget > 0 && slot < MAX_SWARM) {
                    // ひまわりの並び(parked の HordeFormation の簡単な形)。体の大きさで間をあける
                    double r = 1.4D * Math.max(1.0D, type.getWidth()) * Math.sqrt(slot + 0.5D);
                    double a = start + slot * 2.3999631D;
                    slot++;
                    int bx = Mth.floor(s.x + Math.cos(a) * r), bz = Mth.floor(s.z + Math.sin(a) * r);
                    BlockPos feet = TreeCanopyGroundFinder.findSpawnSurface(level, bx, bz); // 樹冠の上でなく地面(読み込み途中なら null)
                    if (feet == null || !level.isPositionEntityTicking(feet) || !level.getFluidState(feet).isEmpty()) continue;
                    Mob m = EnemySpawns.create(level, type, feet, MobSpawnType.EVENT, random);
                    if (m == null) continue;
                    m.addTag(TAG);
                    if (!s.extraTag.isEmpty()) m.addTag(s.extraTag);
                    m.setYRot((float) Math.toDegrees(Math.atan2(-s.dirX, s.dirZ)));
                    m.getPersistentData().putString(EXTRA_KEY, s.extraTag);
                    level.addFreshEntity(m);
                    PlantMarch.begin(m, s.baseId, s.tx, s.tz);
                    left--;
                    budget--;
                    placed++;
                }
                e.setValue(left);
            }
            s.counts.values().removeIf(v -> v <= 0);
            materialized += placed;
            if (s.size() <= 0) {
                it.remove();
            } else if (placed == 0) {
                s.failures++;
                placeFailures++;
                // 1 体も置けない(水・崖・体が入らない): 行き先から見て横へずらしてやり直す
                double side = (s.failures % 2 == 0 ? 1.0D : -1.0D) * NUDGE_BLOCKS * ((s.failures + 1) / 2);
                s.x += -s.dirZ * side;
                s.z += s.dirX * side;
            }
            setDirty();
        }
    }

    /** 遠い本物を仮想へ戻す(テストからも)。戻した数を返す。 */
    public int dematerialize(ServerLevel level, PerfTable t) {
        double far = t.virtualDematerializeBlocks * t.virtualDematerializeBlocks;
        double keep = KEEP_NEAR_TARGET * KEEP_NEAR_TARGET, merge = MERGE_BLOCKS * MERGE_BLOCKS;
        List<Mob> back = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof Mob m) || !m.isAlive() || !m.getTags().contains(TAG)) continue;
            if (!PlantMarch.active(m) || m.hasCustomName() || m.isPassenger() || m.isVehicle()) continue;
            if (m.getTarget() instanceof Player || m.getHealth() < m.getMaxHealth()) continue;
            if (m.getLastHurtByMob() != null && m.tickCount - m.getLastHurtByMobTimestamp() < 200) continue;
            net.minecraft.world.phys.Vec3 tg = PlantMarch.target(m);
            if (tg == null) continue;
            double dx = tg.x - m.getX(), dz = tg.z - m.getZ();
            if (dx * dx + dz * dz < keep) continue;
            if (nearestPlayerSq(level, m.getX(), m.getZ()) <= far) continue;
            back.add(m);
        }
        for (Mob m : back) {
            net.minecraft.world.phys.Vec3 tg = PlantMarch.target(m);
            int baseId = PlantMarch.baseId(m);
            String extra = m.getPersistentData().getString(EXTRA_KEY);
            Swarm into = null;
            for (Swarm s : swarms) {
                double ax = s.x - m.getX(), az = s.z - m.getZ();
                if (s.baseId == baseId && Math.abs(s.tx - tg.x) < 1.0D && Math.abs(s.tz - tg.z) < 1.0D && ax * ax + az * az <= merge
                        && s.extraTag.equals(extra) && s.size() < MAX_SWARM) {
                    into = s;
                    break;
                }
            }
            if (into == null) {
                into = new Swarm(nextId++, m.getX(), m.getY(), m.getZ(), DesignData.perf().virtualSpeed, baseId, tg.x, tg.z, extra);
                swarms.add(into);
            }
            into.add(BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()), 1);
            if (CrawlerPack.externalOf(m) != null) CrawlerPack.leaveExternal(level, m);
            m.discard(); // 死なせない(死の破裂で汚染を撒かない)
            dematerialized++;
        }
        if (!back.isEmpty()) setDirty();
        return back.size();
    }

    /** 全部消す(確かめ用)。 */
    public int clear() {
        int n = virtualHeads();
        swarms.clear();
        setDirty();
        return n;
    }

    /** 確かめ用の 1 行。 */
    public String describe(ServerLevel level) {
        int real = realHeads(level);
        return String.format(Locale.ROOT, "仮想の群れ: %s・群れ %d・仮想 %d 体・本物 %d 体(合計 %d)・本物にした %d・戻した %d・置けなかった回 %d・寿命で消えた %d・"
                        + "進める 平均 %.3f ms・本物にする 平均 %.2f ms 最大 %.2f ms(%d 回)",
                enabled() ? "入" : "切", swarms.size(), virtualHeads(), real, virtualHeads() + real, materialized, dematerialized, placeFailures, expired,
                moveRuns == 0 ? 0.0D : moveNanos / 1e6 / moveRuns,
                runs == 0 ? 0.0D : materializeNanos / 1e6 / runs, maxMaterializeNanos / 1e6, runs);
    }

    // ---------------------------------------------------------------- 保存

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Swarm s : swarms) {
            CompoundTag c = new CompoundTag();
            c.putLong("Id", s.id);
            c.putDouble("X", s.x);
            c.putDouble("Y", s.y);
            c.putDouble("Z", s.z);
            c.putDouble("Speed", s.speed);
            c.putInt("Base", s.baseId);
            c.putDouble("TX", s.tx);
            c.putDouble("TZ", s.tz);
            c.putString("Tag", s.extraTag);
            c.putLong("Arrived", s.arrivedAt);
            ListTag counts = new ListTag();
            for (Map.Entry<ResourceLocation, Integer> e : s.counts.entrySet()) {
                CompoundTag ct = new CompoundTag();
                ct.putString("Type", e.getKey().toString());
                ct.putInt("N", e.getValue());
                counts.add(ct);
            }
            c.put("Counts", counts);
            list.add(c);
        }
        tag.put("Swarms", list);
        tag.putLong("NextId", nextId);
        return tag;
    }

    /** 保存した形から読む(テストからも)。 */
    public static VirtualHordes load(CompoundTag tag) {
        VirtualHordes h = new VirtualHordes();
        h.nextId = Math.max(1L, tag.getLong("NextId"));
        for (Tag t : tag.getList("Swarms", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            Swarm s = new Swarm(c.getLong("Id"), c.getDouble("X"), c.getDouble("Y"), c.getDouble("Z"), c.getDouble("Speed"), c.getInt("Base"),
                    c.getDouble("TX"), c.getDouble("TZ"), c.getString("Tag"));
            s.arrivedAt = c.getLong("Arrived");
            for (Tag ct : c.getList("Counts", Tag.TAG_COMPOUND)) {
                CompoundTag cc = (CompoundTag) ct;
                ResourceLocation id = ResourceLocation.tryParse(cc.getString("Type"));
                if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) continue; // 消えた種(MOD を外した)は捨てる
                int n = Math.max(0, Math.min(MAX_SWARM, cc.getInt("N")));
                if (n > 0) s.add(id, n);
            }
            if (s.size() > 0) h.swarms.add(s);
            h.nextId = Math.max(h.nextId, s.id + 1);
        }
        return h;
    }
}
