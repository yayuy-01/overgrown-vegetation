package com.yayuy.overgrownvegetation.base.build;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.SoldierEntity;
import com.yayuy.overgrownvegetation.ally.SoldierKind;
import com.yayuy.overgrownvegetation.base.plan.BaseEmitter;
import com.yayuy.overgrownvegetation.base.plan.BasePlan;
import com.yayuy.overgrownvegetation.base.plan.BuildingKind;
import com.yayuy.overgrownvegetation.base.plan.Facing;
import com.yayuy.overgrownvegetation.base.plan.Pal;
import com.yayuy.overgrownvegetation.base.plan.Plot;
import com.yayuy.overgrownvegetation.base.plan.States;
import com.yayuy.overgrownvegetation.block.GunTurretBlock;
import com.yayuy.overgrownvegetation.block.GunTurretBlockEntity;
import com.yayuy.overgrownvegetation.block.SporeDeviceBlockEntity;
import com.yayuy.overgrownvegetation.data.DefenseTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.spore.SporeFilters;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 基地の整備(順 2 の着手順 4、2026-10-04)。工兵が倉庫の品で機関砲台・胞子除去装置を整備し、壊れた所と汚染された所を計画どおりに直す。
 *
 * <p><b>ユーザー指定</b>: 整備するのは工兵・荷捌き場の倉庫に貯蓄で機関砲弾・補修材・新しいフィルターが補充され工兵はそれを使う・弾の補充・
 * 覆われていたら植物をはがす・フィルターが詰まったら交換・使用済みのフィルターは除染機で使い回す・フィルターは使用済み + 未使用で除去装置 × 2 まで(10-02)/
 * 壁は工兵が貯蓄で直す・中の汚染と巨大樹の根は工兵が解体(10-01)/ 除染機は今のもの・補修材で直すのは基地のブロック全部(プレイヤーが置いたものは触らない)・
 * はしごの除去塔は工兵が登る・基地の外のプレイヤーの設備は整備しない(10-04)。
 *
 * <p>流れ(プレイヤーのいる、陥落していない・工事中でない・今の版の基地だけ):
 * <ol>
 *     <li>計画の設備と倉庫の場所を裏のスレッドで数える({@link Layout}。レベルと除去装置の有無が変わったら数え直す)。整備するのは計画にある設備だけ</li>
 *     <li>倉庫へ {@code upkeepRestockIntervalTicks} ごとに目安まで買い足す({@link BaseWarehouse#restock})。除染機の出し入れも</li>
 *     <li>設備を {@value #SCAN_INTERVAL} tick ごとに見回り、減った・止まった設備の仕事を作る</li>
 *     <li>読み込まれたチャンクを順に計画と見比べ({@link BaseRepair})、直すマスを近くでまとめて仕事にする</li>
 *     <li>手の空いた工兵に、止まった設備 → 減った設備 → 修理と解体の順で、近いものから渡す({@link EngineerWorkGoal} が片付ける)</li>
 * </ol>
 * 留守中は何もしない(設備は読み込まれていない間は動かず減らない。壊れていた所は戻ってから直す)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class BaseUpkeep {

    /** 整備を回すか(GameTest で切る)。 */
    public static boolean enabled = true;
    /** 調べもの用: 工兵の動きをログに出す(GameTest で一時的に)。 */
    public static boolean trace;

    /** 割り振りの間隔(tick)。 */
    private static final int STEP = 20;
    /** 設備の見回りの間隔(tick)。 */
    static final int SCAN_INTERVAL = 200;
    /** 計画との見比べを 1 チャンク進める間隔(tick)。 */
    private static final int SWEEP_INTERVAL = 4;
    /** 誰も受け持たない仕事を捨てるまで(tick。次の見回りで作り直す)。 */
    private static final int JOB_TTL = 12000;
    /** 品が無い・誰も行けなかった仕事を次に渡すまで(tick)。 */
    private static final int RETRY_DELAY = 1200;
    /** 行けなかった設備・マスを見回りから外しておく時間(tick)。 */
    private static final int COOLDOWN = 2400;
    /** 修理と解体の仕事の数の上限(多すぎると見比べを止めて片付くのを待つ)。 */
    private static final int MAX_FIX_JOBS = 64;
    /** 修理と解体の 1 つの仕事のマスの数と、まとめる近さ。 */
    private static final int FIX_GROUP = 24;
    private static final int FIX_RADIUS = 6;

    private static final String TURRET_ID = States.id(Pal.gunTurret(Facing.SOUTH));
    private static final String PURIFIER_ID = States.id(Pal.sporePurifier(Facing.SOUTH));
    private static final String DECONTAMINATOR_ID = States.id(Pal.decontaminator(Facing.SOUTH));
    private static final String CRATE_ID = States.id(Pal.crate(Facing.SOUTH));
    private static final Set<String> SHELF_IDS = Set.of(States.id(Pal.BARREL), States.id(Pal.chest(Facing.SOUTH)));

    /**
     * 計画の設備(機関砲台・胞子除去装置)と倉庫の場所(世界の座標)。{@code gates} は正門ごとの {壁の中の側, 外の側} の立つ所
     * (工兵が壁の外の仕事へ出入りするときに通る。柵の門の列の真ん中の前後 3 マス)。
     */
    public record Layout(int level, boolean purifiers, List<BlockPos> turrets, List<BlockPos> purifierDevices,
                         List<BaseWarehouse.Store> stores, List<BlockPos[]> gates) {

        public Layout(int level, boolean purifiers, List<BlockPos> turrets, List<BlockPos> purifierDevices, List<BaseWarehouse.Store> stores) {
            this(level, purifiers, turrets, purifierDevices, stores, List.of());
        }
    }

    private static final String GATE_ID = "minecraft:dark_oak_fence_gate";

    /** 基地ごとの整備の控え(保存しない)。 */
    static final class State {
        int level = -1;
        boolean purifiers;
        @Nullable
        CompletableFuture<Layout> pendingLayout;
        @Nullable
        Layout layout;
        final List<UpkeepJob> jobs = new ArrayList<>();
        final LongOpenHashSet busyDevices = new LongOpenHashSet();
        final LongOpenHashSet busyCells = new LongOpenHashSet();
        final Long2LongOpenHashMap cooldown = new Long2LongOpenHashMap();
        final Map<UUID, Integer> carriedFilters = new HashMap<>();
        long[] sweepChunks = new long[0];
        int sweepCursor;
        @Nullable
        CompletableFuture<BaseRepair.ChunkPlan> pendingSweep;
        long nextRestock;
        /** 下ごしらえに失敗した(ログは 1 回だけ。レベルが変わるまで整備しない)。 */
        boolean broken;
        int engineers;
        int rounds = -1;
        int redstone;
        int filters;
        int repair;
        int sweeps;
        int fixed;
        int serviced;
        /** コマンドの見比べで見つけたマス(最初のいくつか。表示用)。 */
        final List<String> lastSweep = new ArrayList<>();
    }

    /** コマンド用: 直近のコマンドの見比べで見つけたマス(最初のいくつか)。 */
    public static List<String> lastSweep(BaseSite site) {
        return site.upkeep == null ? List.of() : List.copyOf(site.upkeep.lastSweep);
    }

    // ---------- 計画の設備と倉庫 ----------

    /** 計画の設備と倉庫を数える(裏のスレッドで呼んでよい)。 */
    static Layout computeLayout(BaseSite site, int lv, boolean purifiers) {
        BaseEmitter em = site.emitter(lv);
        BasePlan plan = em.plan();
        BaseEmitter.Natural ideal = new BaseEmitter.Natural() {
            @Override
            public int groundY(int x, int z) {
                return em.target(x, z);
            }

            @Override
            public int surfaceY(int x, int z) {
                return em.target(x, z);
            }
        };
        boolean hasWarehouse = false;
        for (Plot p : plan.plots) {
            if (isWarehouse(p.kind())) {
                hasWarehouse = true;
                break;
            }
        }
        boolean warehouses = hasWarehouse;
        List<BlockPos> turrets = new ArrayList<>();
        List<BlockPos> devices = new ArrayList<>();
        Map<Plot, List<Object[]>> boxes = new LinkedHashMap<>();
        Map<Plot, List<int[]>> gateCells = new LinkedHashMap<>();
        for (int bx = plan.minX(); bx <= plan.maxX(); bx += 16) {
            for (int bz = plan.minZ(); bz <= plan.maxZ(); bz += 16) {
                em.emit(bx, bz, Math.min(bx + 15, plan.maxX()), Math.min(bz + 15, plan.maxZ()), ideal, (x, z) -> true, p -> true,
                        (x, y, z, s) -> {
                            String id = States.id(s);
                            if (id.equals(TURRET_ID)) {
                                turrets.add(site.toWorld(x, y, z));
                            } else if (id.equals(PURIFIER_ID)) {
                                if (purifiers) {
                                    devices.add(site.toWorld(x, y, z));
                                }
                            } else if (id.equals(GATE_ID)) {
                                Plot p = plan.plotAt(x, z);
                                Facing f = Facing.byName(States.prop(s, "facing"));
                                if (p != null && p.kind() == BuildingKind.GATEHOUSE && f != null) {
                                    gateCells.computeIfAbsent(p, k -> new ArrayList<>()).add(new int[]{x, y, z, f.dx, f.dz});
                                }
                            } else if (id.equals(CRATE_ID) || id.equals(DECONTAMINATOR_ID) || SHELF_IDS.contains(id)) {
                                Plot p = plan.plotAt(x, z);
                                if (p != null && (warehouses ? isWarehouse(p.kind()) : p.kind() == BuildingKind.AMMO_BUNKER)) {
                                    boxes.computeIfAbsent(p, k -> new ArrayList<>()).add(new Object[]{site.toWorld(x, y, z),
                                            States.rotate(s, site.rotation)});
                                }
                            }
                        });
            }
        }
        List<BaseWarehouse.Store> stores = new ArrayList<>();
        for (Map.Entry<Plot, List<Object[]>> e : boxes.entrySet()) {
            List<BlockPos> crates = new ArrayList<>();
            List<BlockPos> shelves = new ArrayList<>();
            List<BlockPos> decon = new ArrayList<>();
            BlockPos stand = null;
            for (Object[] c : e.getValue()) {
                BlockPos pos = (BlockPos) c[0];
                String s = (String) c[1];
                String id = States.id(s);
                if (id.equals(CRATE_ID)) {
                    crates.add(pos);
                } else if (id.equals(DECONTAMINATOR_ID)) {
                    decon.add(pos);
                    Direction d = Direction.byName(States.prop(s, "facing"));
                    if (stand == null && d != null) {
                        stand = pos.relative(d);
                    }
                } else {
                    shelves.add(pos);
                }
            }
            if (stand == null) {
                int[] d = BaseGarrison.doorstep(e.getKey());
                stand = site.toWorld(d[0], em.target(d[0], d[1]) + 1, d[1]);
            }
            stores.add(new BaseWarehouse.Store(List.copyOf(crates), List.copyOf(shelves), List.copyOf(decon), stand));
        }
        // 正門: 柵の門の列の真ん中の、門の向き(通り抜ける向き)の前後 3 マス。壁の中の側を先に
        List<BlockPos[]> gates = new ArrayList<>();
        for (List<int[]> cells : gateCells.values()) {
            cells.sort(Comparator.comparingInt((int[] c) -> c[0]).thenComparingInt(c -> c[2]));
            int[] g = cells.get(cells.size() / 2);
            int ax = g[0] + 3 * g[3];
            int az = g[2] + 3 * g[4];
            int bx = g[0] - 3 * g[3];
            int bz = g[2] - 3 * g[4];
            boolean aIn = plan.inside(ax, az) && !plan.isPerimeter(ax, az);
            boolean bIn = plan.inside(bx, bz) && !plan.isPerimeter(bx, bz);
            if (aIn == bIn) {
                continue; // どちらが中か分からない
            }
            BlockPos a = site.toWorld(ax, em.target(ax, az) + 1, az);
            BlockPos b = site.toWorld(bx, plan.inside(bx, bz) || plan.plotAt(bx, bz) != null ? em.target(bx, bz) + 1 : g[1], bz);
            if (!aIn) {
                a = site.toWorld(ax, plan.plotAt(ax, az) != null ? em.target(ax, az) + 1 : g[1], az);
                b = site.toWorld(bx, em.target(bx, bz) + 1, bz);
            }
            gates.add(aIn ? new BlockPos[]{a, b} : new BlockPos[]{b, a});
        }
        return new Layout(lv, purifiers, List.copyOf(turrets), List.copyOf(devices), List.copyOf(stores), List.copyOf(gates));
    }

    private static boolean isWarehouse(BuildingKind k) {
        return k == BuildingKind.WAREHOUSE_TENT || k == BuildingKind.WAREHOUSE;
    }

    /** 見比べるチャンク(計画の範囲 = 区画の和と払う幅を含む外接)。真ん中から近い順。 */
    static long[] sweepChunks(BaseSite site) {
        BasePlan plan = site.plan(site.level());
        BlockPos a = site.toWorld(plan.minX(), 0, plan.minZ());
        BlockPos b = site.toWorld(plan.maxX(), 0, plan.maxZ());
        int cx0 = Math.min(a.getX(), b.getX()) >> 4;
        int cz0 = Math.min(a.getZ(), b.getZ()) >> 4;
        int cx1 = Math.max(a.getX(), b.getX()) >> 4;
        int cz1 = Math.max(a.getZ(), b.getZ()) >> 4;
        List<long[]> list = new ArrayList<>();
        int ccx = site.center.getX() >> 4;
        int ccz = site.center.getZ() >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                list.add(new long[]{ChunkPos.asLong(cx, cz), (long) (cx - ccx) * (cx - ccx) + (long) (cz - ccz) * (cz - ccz)});
            }
        }
        list.sort(Comparator.comparingLong(v -> v[1]));
        long[] out = new long[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i)[0];
        }
        return out;
    }

    // ---------- 回す ----------

    /** 整備を回してよい基地か(陥落・工事中・前の版・守備兵なし・プレイヤーがいない、は回さない)。 */
    static boolean active(MinecraftServer server, BaseSite site) {
        return enabled && !site.fallen() && !site.building() && !site.outdated() && site.garrisoned()
                && server.getLevel(site.dimension) != null && BaseEconomy.present(server, site);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!enabled) {
            return;
        }
        MinecraftServer server = event.getServer();
        BaseSites data = BaseSites.get(server);
        if (data.all().isEmpty()) {
            return;
        }
        long tick = server.getTickCount();
        for (BaseSite site : List.copyOf(data.all())) {
            if (!active(server, site)) {
                if (site.upkeep != null && !site.upkeep.jobs.isEmpty()) {
                    clearJobs(site.upkeep);
                }
                continue;
            }
            ServerLevel level = server.getLevel(site.dimension);
            State st = state(site);
            if (!layoutReady(site, st)) {
                continue;
            }
            if (st.rounds < 0 && storesLoaded(level, st)) {
                count(level, st); // 管理端末の行を最初の買い足しより前から出す
            }
            if (tick % SWEEP_INTERVAL == 0) {
                sweepStep(level, site, st, tick);
            }
            if ((tick + site.id) % SCAN_INTERVAL == 0) {
                scan(level, site, st, tick);
            }
            if (tick >= st.nextRestock) {
                st.nextRestock = tick + DesignData.defense().baseUpkeepRestockIntervalTicks();
                restock(level, data, site, st);
            }
            if ((tick + site.id) % STEP == 0) {
                assign(level, site, st, tick);
            }
        }
    }

    /** 基地の控え(無ければ作る)。レベルか除去装置の有無が変わっていたら作り直す(計画が変わる)。 */
    static State state(BaseSite site) {
        State st = site.upkeep;
        if (st == null || st.level != site.level() || st.purifiers != site.purifiers()) {
            st = new State();
            st.level = site.level();
            st.purifiers = site.purifiers();
            site.upkeep = st;
        }
        return st;
    }

    /** 計画の設備と倉庫が数え終わっているか(まだなら裏のスレッドで数え始める)。 */
    private static boolean layoutReady(BaseSite site, State st) {
        if (st.layout != null) {
            return true;
        }
        if (st.pendingLayout == null) {
            int lv = site.level();
            boolean pur = site.purifiers();
            st.pendingLayout = CompletableFuture.supplyAsync(() -> computeLayout(site, lv, pur), Util.backgroundExecutor());
            return false;
        }
        if (!st.pendingLayout.isDone() || st.broken) {
            return false;
        }
        try {
            st.layout = st.pendingLayout.join();
            st.sweepChunks = sweepChunks(site);
        } catch (RuntimeException e) {
            OvergrownVegetation.LOGGER.warn("基地 #{} の整備の下ごしらえに失敗した(このレベルの間は整備しない)", site.id, e);
            st.broken = true;
            return false;
        }
        return true;
    }

    /** GameTest・コマンド用: 計画の設備と倉庫をいま数える(待つ)。 */
    public static Layout layoutNow(BaseSite site) {
        State st = state(site);
        if (st.layout == null) {
            st.layout = computeLayout(site, site.level(), site.purifiers());
            st.sweepChunks = sweepChunks(site);
            st.pendingLayout = null;
        }
        return st.layout;
    }

    private static void clearJobs(State st) {
        st.jobs.clear();
        st.busyDevices.clear();
        st.busyCells.clear();
    }

    // ---------- 設備の見回り ----------

    /** 機関砲台に足す弾の数(弾が {@code upkeepTurretRefillBelow} 以下なら {@code upkeepTurretFill} まで、でなければ 0)。 */
    public static int roundsWanted(GunTurretBlockEntity t) {
        DefenseTable d = DesignData.defense();
        int ammo = t.ammoCount();
        return ammo <= d.baseUpkeepTurretRefillBelow() ? Math.max(0, d.baseUpkeepTurretFill() - ammo) : 0;
    }

    /**
     * 除去装置に足すレッドストーンの数。2026-10-24 から除去装置は電池で動き、基地の除去装置は電池なしで動くので<b>いつも 0</b>
     * (補給の仕事・倉庫の在庫の仕組みは、後で消すか電池に替えるまで残しておく)。
     */
    public static int redstoneWanted(SporeDeviceBlockEntity dev) {
        return 0;
    }

    /** 除去装置のフィルターを替えるか(無い・目詰まり・{@code upkeepFilterSwapWear} 以上すり減った)。 */
    public static boolean filterWanted(SporeDeviceBlockEntity dev) {
        ItemStack f = dev.getItem(SporeDeviceBlockEntity.SLOT_FILTER);
        if (f.isEmpty() || !SporeFilters.isUsable(f)) {
            return true;
        }
        return f.isDamageableItem() && f.getDamageValue() >= DesignData.defense().baseUpkeepFilterSwapWear() * f.getMaxDamage();
    }

    /** 除去装置の取れた胞子を取り出すか。 */
    public static boolean sporesWanted(SporeDeviceBlockEntity dev) {
        return dev.getItem(SporeDeviceBlockEntity.SLOT_SPORE).getCount() >= DesignData.defense().baseUpkeepSporesEmptyAt();
    }

    /** 機関砲台の整備が要るか、止まっているか: {0 = 要らない, 1 = 減った, 2 = 止まった}。 */
    static int turretNeed(BlockState s, GunTurretBlockEntity t) {
        boolean overgrown = s.hasProperty(GunTurretBlock.OVERGROWN) && s.getValue(GunTurretBlock.OVERGROWN);
        if (overgrown || t.ammoCount() == 0) {
            return 2;
        }
        return roundsWanted(t) > 0 ? 1 : 0;
    }

    /** 除去装置の整備が要るか、止まっているか: {0, 1, 2}。 */
    static int purifierNeed(SporeDeviceBlockEntity d) {
        ItemStack f = d.getItem(SporeDeviceBlockEntity.SLOT_FILTER);
        if (d.isJammed() || f.isEmpty() || !SporeFilters.isUsable(f)) {
            return 2;
        }
        return sporesWanted(d) || redstoneWanted(d) > 0 || filterWanted(d) ? 1 : 0;
    }

    /** 設備を見回り、整備の要る設備の仕事を作る(読み込まれている設備だけ)。作った数。 */
    static int scan(ServerLevel level, BaseSite site, State st, long tick) {
        if (st.layout == null) {
            return 0;
        }
        int made = 0;
        for (BlockPos p : st.layout.turrets()) {
            if (skip(level, st, p, tick)) {
                continue;
            }
            if (level.getBlockEntity(p) instanceof GunTurretBlockEntity t) {
                if (!t.baseSupplied()) {
                    t.setBaseSupplied(true); // 基地の砲台(建て直した物も)は電池なしで動く(2026-10-16)
                }
                int need = turretNeed(level.getBlockState(p), t);
                if (need > 0) {
                    addDeviceJob(st, UpkeepJob.device(UpkeepJob.Kind.TURRET, site.id, p, need == 2 ? 0 : 1, tick));
                    made++;
                }
            }
        }
        for (BlockPos p : st.layout.purifierDevices()) {
            if (skip(level, st, p, tick)) {
                continue;
            }
            if (level.getBlockEntity(p) instanceof SporeDeviceBlockEntity d) {
                d.setBaseSupplied(true); // 基地の除去装置(建て直した物も)は電池なしで動く(2026-10-24)
                int need = purifierNeed(d);
                if (need > 0) {
                    addDeviceJob(st, UpkeepJob.device(UpkeepJob.Kind.PURIFIER, site.id, p, need == 2 ? 0 : 1, tick));
                    made++;
                }
            }
        }
        return made;
    }

    private static boolean skip(ServerLevel level, State st, BlockPos p, long tick) {
        long key = p.asLong();
        if (st.busyDevices.contains(key) || st.cooldown.getOrDefault(key, 0L) > tick) {
            return true;
        }
        return level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null;
    }

    private static void addDeviceJob(State st, UpkeepJob job) {
        st.jobs.add(job);
        st.busyDevices.add(job.pos().asLong());
    }

    // ---------- 見比べ ----------

    private static void sweepStep(ServerLevel level, BaseSite site, State st, long tick) {
        if (st.pendingSweep != null) {
            if (!st.pendingSweep.isDone()) {
                return;
            }
            if (!com.yayuy.overgrownvegetation.perf.TickBudget.mayStart(com.yayuy.overgrownvegetation.perf.PerfJob.BASE_UPKEEP_COMPARE)) {
                return; // サーバーが重ければ見比べ(チャンク 1 つぶんのマスを引く)を次の回へ(2026-10-10。結果は同じで遅れるだけ)
            }
            BaseRepair.ChunkPlan cp;
            try {
                cp = st.pendingSweep.join();
            } catch (RuntimeException e) {
                OvergrownVegetation.LOGGER.warn("基地 #{} の見比べに失敗した", site.id, e);
                st.pendingSweep = null;
                return;
            }
            st.pendingSweep = null;
            if (cp.level == site.level() && level.getChunkSource().getChunkNow(ChunkPos.getX(cp.chunk), ChunkPos.getZ(cp.chunk)) != null) {
                long start = com.yayuy.overgrownvegetation.perf.TickBudget.begin();
                try {
                    addFixJobs(site, st, BaseRepair.compare(level, site, cp), tick);
                } finally {
                    com.yayuy.overgrownvegetation.perf.TickBudget.end(com.yayuy.overgrownvegetation.perf.PerfJob.BASE_UPKEEP_COMPARE, start, 1);
                }
            }
            return;
        }
        if (st.engineers == 0 || st.sweepChunks.length == 0 || fixJobs(st) >= MAX_FIX_JOBS) {
            return;
        }
        for (int tries = 0; tries < Math.min(32, st.sweepChunks.length); tries++) {
            long c = st.sweepChunks[st.sweepCursor];
            st.sweepCursor++;
            if (st.sweepCursor >= st.sweepChunks.length) {
                st.sweepCursor = 0;
                st.sweeps++;
            }
            if (level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c)) != null) {
                int lv = site.level();
                boolean pur = site.purifiers();
                st.pendingSweep = CompletableFuture.supplyAsync(() -> BaseRepair.plan(site, lv, pur, c), Util.backgroundExecutor());
                return;
            }
        }
    }

    /** GameTest・コマンド用: 読み込まれているチャンクを全部いま見比べる(待つ)。直すマスの数。 */
    public static int sweepNow(ServerLevel level, BaseSite site) {
        State st = state(site);
        layoutNow(site);
        st.lastSweep.clear();
        long tick = level.getServer().getTickCount();
        int n = 0;
        for (long c : st.sweepChunks) {
            if (level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c)) == null) {
                continue;
            }
            List<UpkeepJob.Fix> fixes = BaseRepair.compare(level, site, BaseRepair.plan(site, site.level(), site.purifiers(), c));
            n += fixes.size();
            for (UpkeepJob.Fix f : fixes) {
                if (st.lastSweep.size() < 8) {
                    st.lastSweep.add(f.kind() + "@" + f.pos().toShortString() + " "
                            + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(f.expected().getBlock()).getPath() + "→"
                            + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(f.state().getBlock()).getPath());
                }
            }
            addFixJobs(site, st, fixes, tick);
        }
        return n;
    }

    private static int fixJobs(State st) {
        int n = 0;
        for (UpkeepJob j : st.jobs) {
            if (j.kind() == UpkeepJob.Kind.FIX) {
                n++;
            }
        }
        return n;
    }

    /** 直すマスを、近いものどうし {@value #FIX_GROUP} マスまでの仕事にまとめる(もう仕事に入っているマス・あきらめたマスは飛ばす)。 */
    static void addFixJobs(BaseSite site, State st, List<UpkeepJob.Fix> fixes, long tick) {
        List<UpkeepJob.Fix> open = new ArrayList<>();
        for (UpkeepJob.Fix f : fixes) {
            long key = f.pos().asLong();
            if (!st.busyCells.contains(key) && st.cooldown.getOrDefault(key, 0L) <= tick) {
                open.add(f);
            }
        }
        open.sort(Comparator.comparingInt(UpkeepJob.Fix::priority).thenComparingInt(f -> f.pos().getY()));
        while (!open.isEmpty() && fixJobs(st) < MAX_FIX_JOBS) {
            UpkeepJob.Fix seed = open.remove(0);
            List<UpkeepJob.Fix> group = new ArrayList<>();
            group.add(seed);
            for (int i = 0; i < open.size() && group.size() < FIX_GROUP; ) {
                UpkeepJob.Fix f = open.get(i);
                if (Math.abs(f.pos().getX() - seed.pos().getX()) <= FIX_RADIUS && Math.abs(f.pos().getZ() - seed.pos().getZ()) <= FIX_RADIUS
                        && Math.abs(f.pos().getY() - seed.pos().getY()) <= FIX_RADIUS) {
                    group.add(open.remove(i));
                } else {
                    i++;
                }
            }
            group.sort(Comparator.comparingInt((UpkeepJob.Fix f) -> f.pos().getY()));
            UpkeepJob job = new UpkeepJob(UpkeepJob.Kind.FIX, site.id, seed.pos(), seed.priority() + 0, group, tick);
            st.jobs.add(job);
            for (UpkeepJob.Fix f : group) {
                st.busyCells.add(f.pos().asLong());
            }
        }
    }

    // ---------- 倉庫 ----------

    /** 倉庫が全部読み込まれているか(倉庫が無ければ偽)。 */
    private static boolean storesLoaded(ServerLevel level, State st) {
        if (st.layout == null || st.layout.stores().isEmpty()) {
            return false;
        }
        for (BaseWarehouse.Store s : st.layout.stores()) {
            if (!BaseWarehouse.loaded(level, s)) {
                return false;
            }
        }
        return true;
    }

    private static void restock(ServerLevel level, BaseSites data, BaseSite site, State st) {
        if (!storesLoaded(level, st)) {
            return; // 倉庫が全部読み込まれているときだけ(数えられない分を買い足さない)
        }
        for (BaseWarehouse.Store s : st.layout.stores()) {
            BaseWarehouse.housekeep(level, s);
        }
        int carried = 0;
        for (int v : st.carriedFilters.values()) {
            carried += v;
        }
        BaseWarehouse.restock(level, data, site, st.layout.stores(), st.layout.turrets().size(), st.layout.purifierDevices().size(), carried);
        count(level, st);
    }

    private static void count(ServerLevel level, State st) {
        List<BaseWarehouse.Store> stores = st.layout.stores();
        st.rounds = BaseWarehouse.count(level, stores, BaseWarehouse.Supply.ROUNDS);
        st.redstone = BaseWarehouse.count(level, stores, BaseWarehouse.Supply.REDSTONE);
        st.filters = BaseWarehouse.count(level, stores, BaseWarehouse.Supply.FILTER);
        st.repair = BaseWarehouse.count(level, stores, BaseWarehouse.Supply.REPAIR);
    }

    /** GameTest・コマンド用: いま倉庫へ買い足す(倉庫が全部読み込まれていれば)。使った貯蓄。 */
    public static int restockNow(ServerLevel level, BaseSite site) {
        State st = state(site);
        layoutNow(site);
        int before = site.stock();
        restock(level, BaseSites.get(level.getServer()), site, st);
        return before - site.stock();
    }

    // ---------- 割り振り ----------

    private static void assign(ServerLevel level, BaseSite site, State st, long tick) {
        // 手の空いた工兵は壁の中と払う幅(正門・外の有刺鉄線の輪・根の仕事で外にいる)から探す
        List<SoldierEntity> engineers = new ArrayList<>(level.getEntitiesOfClass(SoldierEntity.class,
                BaseGarrison.box(level, site).inflate(BasePlan.CLEAR_MARGIN + 8, 0, BasePlan.CLEAR_MARGIN + 8),
                s -> s.siteId() == site.id && s.kind() == SoldierKind.ENGINEER && s.isAlive()));
        for (Map.Entry<UUID, SoldierKind> e : site.roster.entrySet()) { // 名簿の工兵は基地の外にいても(追いかけて出た・GameTest)
            if (e.getValue() == SoldierKind.ENGINEER && level.getEntity(e.getKey()) instanceof SoldierEntity s && s.isAlive()
                    && !engineers.contains(s)) {
                engineers.add(s);
            }
        }
        st.engineers = engineers.size();
        for (java.util.Iterator<UpkeepJob> it = st.jobs.iterator(); it.hasNext(); ) {
            UpkeepJob j = it.next();
            if (j.worker != null && !alive(level, j.worker)) {
                j.worker = null; // 受け持った工兵がいなくなった(倒れた・読み込みが外れた)
            }
            if (j.worker == null && tick - j.created > JOB_TTL) {
                release(st, j);
                it.remove();
            }
        }
        st.carriedFilters.keySet().removeIf(id -> !alive(level, id));
        st.cooldown.long2LongEntrySet().removeIf(e -> e.getLongValue() <= tick);
        int range = DesignData.defense().baseUpkeepEngageRange();
        for (SoldierEntity e : engineers) {
            if (busy(st, e.getUUID()) || !level.isPositionEntityTicking(e.blockPosition())) {
                continue;
            }
            if (e.getTarget() != null && e.getTarget().isAlive() && e.distanceToSqr(e.getTarget()) <= (double) range * range) {
                continue; // 戦っている
            }
            UpkeepJob best = null;
            double bestScore = Double.MAX_VALUE;
            for (UpkeepJob j : st.jobs) {
                if (j.worker != null || j.notBefore > tick || !level.isPositionEntityTicking(j.pos())) {
                    continue; // 動いていない所(シミュレーション距離の外)の仕事は、歩いていっても止まるだけ
                }
                double score = j.priority() * 1.0E7 + e.distanceToSqr(j.pos().getX() + 0.5, j.pos().getY(), j.pos().getZ() + 0.5);
                if (score < bestScore) {
                    bestScore = score;
                    best = j;
                }
            }
            if (best != null) {
                best.worker = e.getUUID();
            }
        }
    }

    /**
     * 兵士 {@code id} が読み込まれていて生きていて、動いているチャンクにいるか(どこにいても —— 壁の外で仕事をしている工兵もいる)。
     * シミュレーション距離の外へ歩き出た工兵は止まったまま動かないので、仕事を返してもらう(10-04 のクライアントで、L5 の端から倉庫へ向かって止まった)。
     */
    private static boolean alive(ServerLevel level, UUID id) {
        return level.getEntity(id) instanceof SoldierEntity s && s.isAlive() && level.isPositionEntityTicking(s.blockPosition());
    }

    private static boolean busy(State st, UUID id) {
        for (UpkeepJob j : st.jobs) {
            if (id.equals(j.worker)) {
                return true;
            }
        }
        return false;
    }

    private static void release(State st, UpkeepJob j) {
        if (j.kind() == UpkeepJob.Kind.FIX) {
            for (UpkeepJob.Fix f : j.fixes()) {
                st.busyCells.remove(f.pos().asLong());
            }
        } else {
            st.busyDevices.remove(j.pos().asLong());
        }
    }

    // ---------- 工兵の Goal から ----------

    @Nullable
    private static BaseSite siteOf(SoldierEntity e) {
        if (e.siteId() <= 0 || !(e.level() instanceof ServerLevel level)) {
            return null;
        }
        BaseSite site = BaseSites.get(level.getServer()).byId(e.siteId());
        return site != null && site.upkeep != null ? site : null;
    }

    /** 工兵 {@code e} が受け持っている仕事(無ければ null)。 */
    @Nullable
    public static UpkeepJob jobFor(SoldierEntity e) {
        BaseSite site = siteOf(e);
        if (site == null) {
            return null;
        }
        for (UpkeepJob j : site.upkeep.jobs) {
            if (e.getUUID().equals(j.worker)) {
                return j;
            }
        }
        return null;
    }

    /** 仕事 {@code job} がまだあって、工兵 {@code e} が受け持っているか。 */
    public static boolean owns(SoldierEntity e, UpkeepJob job) {
        BaseSite site = siteOf(e);
        return site != null && e.getUUID().equals(job.worker) && site.upkeep.jobs.contains(job);
    }

    /** 工兵 {@code e} の基地の倉庫(まだ数えていなければ null)。 */
    @Nullable
    public static List<BaseWarehouse.Store> storesOf(SoldierEntity e) {
        BaseSite site = siteOf(e);
        return site == null || site.upkeep.layout == null || site.upkeep.layout.stores().isEmpty() ? null : site.upkeep.layout.stores();
    }

    /**
     * 工兵 {@code e} にいちばん近い、読み込まれている倉庫。{@code job} があれば、その仕事の品(弾・レッドストーン・フィルター・補修材)が 1 つでもある倉庫から。
     */
    @Nullable
    public static BaseWarehouse.Store nearestStore(SoldierEntity e, @Nullable UpkeepJob job) {
        List<BaseWarehouse.Store> stores = storesOf(e);
        if (stores == null || !(e.level() instanceof ServerLevel level)) {
            return null;
        }
        BaseWarehouse.Store best = null;
        double bestD = Double.MAX_VALUE;
        for (BaseWarehouse.Store s : stores) {
            if (!BaseWarehouse.loaded(level, s) || !level.isPositionEntityTicking(s.stand())) {
                continue; // 動いていない倉庫へは歩いていっても止まるだけ
            }
            if (job != null && !hasFor(level, s, job)) {
                continue;
            }
            double d = e.distanceToSqr(s.stand().getX() + 0.5, s.stand().getY(), s.stand().getZ() + 0.5);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    private static boolean hasFor(ServerLevel level, BaseWarehouse.Store s, UpkeepJob job) {
        return switch (job.kind()) {
            case TURRET -> BaseWarehouse.has(level, s, BaseWarehouse.Supply.ROUNDS);
            case PURIFIER -> BaseWarehouse.has(level, s, BaseWarehouse.Supply.REDSTONE) || BaseWarehouse.has(level, s, BaseWarehouse.Supply.FILTER);
            case FIX -> BaseWarehouse.has(level, s, BaseWarehouse.Supply.REPAIR);
        };
    }

    /**
     * 工兵 {@code e} が {@code to} へ行くのに正門を通るなら {壁の中の側, 外の側} の立つ所(工兵と行き先の片方だけが壁の中のとき。正門が無ければ null)。
     * 経路探しは 48 マスほどしか見ないので、壁の外の仕事へは正門を先に目指さないと、外周の内側で行き止まる(10-04 のクライアントで見た)。
     */
    @Nullable
    public static BlockPos[] gateFor(SoldierEntity e, BlockPos to) {
        BaseSite site = siteOf(e);
        if (site == null || site.upkeep.layout == null || site.upkeep.layout.gates().isEmpty()) {
            return null;
        }
        boolean engIn = inside(site, e.getBlockX(), e.getBlockZ());
        boolean toIn = inside(site, to.getX(), to.getZ());
        if (engIn == toIn) {
            return null;
        }
        BlockPos[] best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos[] g : site.upkeep.layout.gates()) {
            double d = g[0].distSqr(e.blockPosition()) + g[1].distSqr(to);
            if (d < bestD) {
                bestD = d;
                best = g;
            }
        }
        return best == null ? null : engIn ? best : new BlockPos[]{best[1], best[0]};
    }

    /**
     * 壁の側か(区画の和の中 = 外周の帯を含む、と外に出た建物の足元)。偽 = 外の払う幅・その先(外の有刺鉄線の輪・根)。
     * 外周の金網・壁と外に出た塔は中から直せるので、正門を通るのは本当に外のマスへ行くときだけ。
     */
    private static boolean inside(BaseSite site, int wx, int wz) {
        int[] l = site.toLocal(wx, wz);
        BasePlan plan = site.plan(site.level());
        return plan.inside(l[0], l[1]) || plan.plotAt(l[0], l[1]) != null;
    }

    /** 品が無いなどで、仕事をしばらく置いておく(受け持ちを外す)。 */
    public static void retry(SoldierEntity e, UpkeepJob job) {
        job.worker = null;
        if (e.level() instanceof ServerLevel l) {
            job.notBefore = l.getServer().getTickCount() + RETRY_DELAY;
        }
    }

    // ---------- GameTest・コマンド ----------

    /** いま設備を見回る(作った仕事の数)。 */
    public static int scanNow(ServerLevel level, BaseSite site) {
        State st = state(site);
        layoutNow(site);
        return scan(level, site, st, level.getServer().getTickCount());
    }

    /** いま手の空いた工兵に仕事を渡す。 */
    public static void assignNow(ServerLevel level, BaseSite site) {
        State st = state(site);
        layoutNow(site);
        assign(level, site, st, level.getServer().getTickCount());
    }

    /** いまある仕事(読むだけ)。 */
    public static List<UpkeepJob> jobs(BaseSite site) {
        return site.upkeep == null ? List.of() : List.copyOf(site.upkeep.jobs);
    }

    /** 控えを捨てる(GameTest の後片付け)。 */
    public static void forget(BaseSite site) {
        site.upkeep = null;
    }

    /**
     * GameTest 用: 計画の設備と倉庫の代わりに {@code layout} を使う(テスト場の中に置いた設備と箱で工兵を歩かせる)。見比べはしない。
     */
    public static void setLayoutForTests(BaseSite site, Layout layout) {
        State st = state(site);
        st.layout = layout;
        st.pendingLayout = null;
        st.sweepChunks = new long[0];
    }

    /** GameTest 用: 直すマスを 1 つ直す({@link BaseRepair#apply})。 */
    public static boolean applyForTests(ServerLevel level, BaseSite site, UpkeepJob.Fix fix) {
        return BaseRepair.apply(level, site, fix);
    }

    /** 仕事を終えた(または、行けなかった・設備が無くなった = {@code ok} が偽 → しばらく見回りから外す)。 */
    public static void done(SoldierEntity e, UpkeepJob job, boolean ok) {
        BaseSite site = siteOf(e);
        if (site == null) {
            return;
        }
        State st = site.upkeep;
        if (st.jobs.remove(job)) {
            release(st, job);
            if (ok && job.kind() != UpkeepJob.Kind.FIX) {
                st.serviced++;
            }
            if (!ok && e.level() instanceof ServerLevel l) {
                long until = l.getServer().getTickCount() + COOLDOWN;
                if (job.kind() == UpkeepJob.Kind.FIX) {
                    for (UpkeepJob.Fix f : job.fixes()) {
                        st.cooldown.put(f.pos().asLong(), until);
                    }
                } else {
                    st.cooldown.put(job.pos().asLong(), until);
                }
            }
        }
    }

    /** 工兵 {@code e} が運んでいる品(フィルターの数を、倉庫の数え方に入れる)。 */
    public static void carrying(SoldierEntity e, List<ItemStack> carried) {
        BaseSite site = siteOf(e);
        if (site == null) {
            return;
        }
        int n = 0;
        for (ItemStack s : carried) {
            if (SporeFilters.is(s)) {
                n += s.getCount();
            }
        }
        if (n > 0) {
            site.upkeep.carriedFilters.put(e.getUUID(), n);
        } else {
            site.upkeep.carriedFilters.remove(e.getUUID());
        }
    }

    /** 直すマス {@code fix} に届かなかった・立てる所が無かった: しばらく見比べから外す(行けないマスを何度も仕事にしない)。 */
    public static void skipFix(SoldierEntity e, UpkeepJob.Fix fix) {
        BaseSite site = siteOf(e);
        if (site != null && e.level() instanceof ServerLevel l) {
            site.upkeep.cooldown.put(fix.pos().asLong(), l.getServer().getTickCount() + COOLDOWN);
        }
    }

    /** 直したマスを数える(表示用)。 */
    public static void fixed(SoldierEntity e) {
        BaseSite site = siteOf(e);
        if (site != null) {
            site.upkeep.fixed++;
        }
    }

    /** 工兵が倒れたら、運んでいたフィルターは失われる(数え方から外す)。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof SoldierEntity s && s.kind() == SoldierKind.ENGINEER) {
            BaseSite site = siteOf(s);
            if (site != null) {
                site.upkeep.carriedFilters.remove(s.getUUID());
            }
        }
    }

    // ---------- 表示 ----------

    /** 管理端末とコマンドの 1 行(倉庫の品と目安・仕事の数)。 */
    public static Component describe(BaseSite site) {
        if (site.building()) {
            return Component.translatable("message.overgrown_vegetation.base.upkeep_building");
        }
        if (site.outdated()) {
            return Component.translatable("message.overgrown_vegetation.base.upkeep_outdated");
        }
        State st = site.upkeep;
        if (st == null || st.layout == null || st.layout.stores().isEmpty() || st.rounds < 0) {
            return Component.translatable("message.overgrown_vegetation.base.upkeep_no_warehouse");
        }
        Layout l = st.layout;
        int lv = site.level();
        int t = l.turrets().size();
        int p = l.purifierDevices().size();
        int busy = 0;
        for (UpkeepJob j : st.jobs) {
            if (j.worker != null) {
                busy++;
            }
        }
        return Component.translatable("message.overgrown_vegetation.base.upkeep",
                st.rounds, BaseWarehouse.target(BaseWarehouse.Supply.ROUNDS, lv, t, p),
                st.redstone, BaseWarehouse.target(BaseWarehouse.Supply.REDSTONE, lv, t, p),
                st.filters, BaseWarehouse.target(BaseWarehouse.Supply.FILTER, lv, t, p),
                st.repair, BaseWarehouse.target(BaseWarehouse.Supply.REPAIR, lv, t, p),
                st.jobs.size(), busy);
    }

    /** コマンド用の詳しい行。 */
    public static List<String> details(BaseSite site) {
        List<String> out = new ArrayList<>();
        State st = site.upkeep;
        if (st == null) {
            out.add("整備の控えがまだ無い(プレイヤーのいる基地で作る)");
            return out;
        }
        if (st.layout == null) {
            out.add("計画の設備と倉庫を数えている途中");
            return out;
        }
        int[] kinds = new int[3];
        int[] fixKinds = new int[UpkeepJob.FixKind.values().length];
        for (UpkeepJob j : st.jobs) {
            kinds[j.kind().ordinal()]++;
            for (UpkeepJob.Fix f : j.fixes()) {
                fixKinds[f.kind().ordinal()]++;
            }
        }
        out.add("計画の機関砲台 " + st.layout.turrets().size() + "・除去装置 " + st.layout.purifierDevices().size()
                + "・倉庫 " + st.layout.stores().size() + " 棟(除染機 " + st.layout.stores().stream().mapToInt(s -> s.decontaminators().size()).sum() + ")"
                + "・工兵 " + st.engineers + " 人(読み込まれている)");
        out.add("仕事: 機関砲台 " + kinds[0] + "・除去装置 " + kinds[1] + "・修理と解体 " + kinds[2]
                + "(置く " + fixKinds[UpkeepJob.FixKind.RESTORE.ordinal()] + "・取り除く " + fixKinds[UpkeepJob.FixKind.REMOVE.ordinal()]
                + "・元に戻す " + fixKinds[UpkeepJob.FixKind.REVERT.ordinal()] + "・覆いを剥がす " + fixKinds[UpkeepJob.FixKind.UNCOVER.ordinal()] + " マス)");
        out.add("見比べ: " + st.sweepCursor + " / " + st.sweepChunks.length + " チャンク(" + st.sweeps + " 周)・直したマス " + st.fixed
                + "・整備した設備 " + st.serviced + "・運んでいるフィルター " + st.carriedFilters.values().stream().mapToInt(Integer::intValue).sum());
        if (!st.cooldown.isEmpty()) {
            StringBuilder sb = new StringBuilder("行けなかったので外している所 " + st.cooldown.size() + ":");
            int shown = 0;
            for (long k : st.cooldown.keySet()) {
                if (shown++ >= 6) {
                    break;
                }
                sb.append(' ').append(BlockPos.of(k).toShortString());
            }
            out.add(sb.toString());
        }
        return out;
    }

    private BaseUpkeep() {
    }
}
