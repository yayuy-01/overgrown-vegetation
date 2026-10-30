package com.yayuy.overgrownvegetation.td;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * <b>GameTest 専用の口</b>(TD イベント、2026-09-25)。{@code gametest/TdGameTests} だけが使う。ゲームの処理からは呼ばないこと。
 *
 * <p>なぜ要るか:
 * <ul>
 *     <li>侵攻の台帳({@link TdData.Event})の数え —— 生きている攻め手・仮想の群れ・一時停止が始まった時刻・次に群れを作る時刻 ——
 *     は td パッケージの中でしか書き換えない決まり(外から数を狂わせないため)。テストは「その状態を作ってから 1 回の帳簿を待つ」
 *     ので、状態を作る口だけをここに開ける。帳簿そのもの(勝ち・実体化・取りやめの判定)は本物の {@link TdTicker} が回す。</li>
 *     <li>攻城のフック({@link TdSiege#strike})は群れの Goal からしか呼ばれない。役ごとの削りの量(前衛 = 攻撃力の属性・
 *     支援・斥候・空・群れに入らない種)と、属性を持たない種で落ちないこと({@code hasAttribute} の確かめ)を、
 *     AI の動きに頼らず直に確かめるために開ける。</li>
 *     <li>台帳({@link TdData})は世界に 1 つで、テスト用の世界は実行をまたいで残る。テストの間だけ<b>空の台帳を借り</b>、
 *     終わったら元の中身へ戻す({@link #isolate} / {@link #restore})。</li>
 * </ul>
 */
public final class TdTestAccess {

    /** {@link TdData} の保存名(あちらの {@code ID} は private なので写し)。 */
    private static final String DATA_ID = "overgrown_vegetation_td";

    // ------------------------------------------------------------------ 台帳を借りる・返す

    /**
     * テストの間だけ空の台帳に差し替え、元の中身(保存形式)を返す。
     *
     * <p>終わっていないイベントが残っていれば(前のテストが時間切れで片付けられなかった等)、先に中止してから写しを取る
     * —— 残ったままだと、そのターゲットを探す帳簿が別のテストの最中に中止・払い戻しを起こす。
     * 差し替えた台帳は「今ちょうど終わった」ことにしておく(次の侵攻までの待ち時間の中 = テストの間に自動で予告が始まらない)。
     */
    public static CompoundTag isolate(MinecraftServer server) {
        if (TdData.get(server).live() != null) {
            OvergrownVegetation.LOGGER.warn("TD テスト: 終わっていないイベントが残っていたので中止してから台帳を借りる");
            TdTicker.forceStop(server);
        }
        TdData current = TdData.get(server);
        CompoundTag snapshot = current.save(new CompoundTag(), server.registryAccess());
        current.hideBossBar();
        CompoundTag fresh = new CompoundTag();
        fresh.putLong("lastEventEndTick", server.overworld().getGameTime());
        fresh.putString("lastOutcome", TdData.Outcome.NONE.key());
        storage(server).set(DATA_ID, TdData.FACTORY.deserializer().apply(fresh, server.registryAccess()));
        return snapshot;
    }

    /** {@link #isolate} で取った写しへ戻す(テストが始めたイベントがまだ続いていれば中止してから)。 */
    public static void restore(MinecraftServer server, CompoundTag snapshot) {
        if (TdData.get(server).live() != null) {
            TdTicker.forceStop(server);
        }
        TdData.get(server).hideBossBar();
        TdData restored = TdData.FACTORY.deserializer().apply(snapshot.copy(), server.registryAccess());
        restored.setDirty();
        storage(server).set(DATA_ID, restored);
    }

    private static DimensionDataStorage storage(MinecraftServer server) {
        return server.overworld().getDataStorage();
    }

    // ------------------------------------------------------------------ 予告

    /**
     * {@code target} で<b>予告</b>(WARNING)のイベントを始める。自動の始まり({@code TdTicker#tryTrigger})と同じ台帳の形で、
     * 過繁茂・フェーズ・バイオマス・次までの待ちの条件は見ない(コマンドの {@link TdTicker#forceStart} は予告を飛ばすので、
     * 予告中の「掘れない」「無くなったら取りやめ」を見るテスト用)。進行中のイベントがあれば投げる。
     */
    public static void startWarning(ServerLevel level, BlockPos target) {
        TdData data = TdData.get(level.getServer());
        if (data.live() != null) {
            throw new IllegalStateException("TD テスト: 進行中のイベントが残っている");
        }
        data.begin(UUID.randomUUID(), level.dimension(), target, TdData.State.WARNING, level.getGameTime());
        TargetBlockEntity.syncLock(level, target);
    }

    // ------------------------------------------------------------------ 侵攻中のイベントの数え

    /**
     * 生きている攻め手の数を決める(実体化を通さずにテストが置いた攻め手のぶん・上限の手前の状態を作る)。
     * 値段の合計は 1 体 1 にする(テストが置くのはバニラのハスクなど = {@code tdCost} 1)。
     */
    public static void setAlive(MinecraftServer server, int alive) {
        TdData data = TdData.get(server);
        TdData.Event event = active(data);
        event.alive = Math.max(0, alive);
        event.aliveCost = event.alive;
        data.setDirty();
    }

    /** 侵攻中のイベントの残りのバイオマス・まだ出てきていない分・生きている攻め手の値段({@link TdTicker#remainingBiomass})。 */
    public static double[] biomassLeft(ServerLevel level) {
        TdData.Event event = active(TdData.get(level.getServer()));
        return new double[]{TdTicker.remainingBiomass(level, event), TdTicker.comingBiomass(level, event), event.aliveCost()};
    }

    /** 侵攻中のイベントの予算の打ち止めの境目({@code TdTicker#cheapest})。 */
    public static double cheapest(ServerLevel level) {
        return TdTicker.cheapest(level, active(TdData.get(level.getServer())));
    }

    /**
     * 仮想の群れを 1 つ買わせる({@code TdTicker#spawnOneVirtual} を直に。行進させてよい頭数は見ない・ターゲットから 150〜160 の輪 =
     * テスト場から遠くて実体にならない)。編成は振るたびに変わる(空の群れなど)。買えたら true。
     */
    public static boolean buyVirtualSwarm(ServerLevel level) {
        TdData data = TdData.get(level.getServer());
        return TdTicker.spawnOneVirtual(level, data, active(data), com.yayuy.overgrownvegetation.data.DesignData.td(),
                Integer.MAX_VALUE, true, 150.0D, 160.0D);
    }

    /** ボスバーの名前(翻訳キーの引数も入った文字列)と長さ。 */
    public static Object[] bossBar(MinecraftServer server) {
        net.minecraft.server.level.ServerBossEvent bar = TdData.get(server).bossEvent();
        return new Object[]{bar.getName(), bar.getProgress()};
    }

    /** 次に仮想の群れを作ってよい時刻(予算を群れに変えさせたくないときは {@link Long#MAX_VALUE})。 */
    public static void setNextSwarmTick(MinecraftServer server, long tick) {
        TdData data = TdData.get(server);
        active(data).nextSwarmTick = tick;
        data.setDirty();
    }

    /** 一時停止が始まった時刻(-1 で止まっていない)。取りやめ({@code abandonTicks})の手前の状態を作る。 */
    public static void setPausedSince(MinecraftServer server, long tick) {
        TdData data = TdData.get(server);
        active(data).pausedSince = tick < 0L ? -1L : tick;
        data.setDirty();
    }

    /**
     * 仮想の群れを 1 つ足す(地上。{@code speed} はブロック / tick、0 なら動かない)。予算からは引かない
     * (予算・仮想の群れ・生きている数をそれぞれ決めたいテスト用)。
     */
    public static void addVirtualSwarm(MinecraftServer server, Vec3 pos, EntityType<?> type, int count, double speed) {
        TdData data = TdData.get(server);
        TdData.VirtualSwarm v = new TdData.VirtualSwarm(UUID.randomUUID(), false, pos.x, pos.y, pos.z, speed);
        v.counts.addTo(EntityType.getKey(type).toString(), count);
        active(data).virtual.add(v);
        data.setDirty();
    }

    /**
     * 地上の仮想の群れを 1 つ足す(種ごとの頭数を並びの順に。群れに入らない種は護衛として実体化する)。{@code climber} なら登る群れ。
     */
    public static void addVirtualSwarm(MinecraftServer server, Vec3 pos, java.util.LinkedHashMap<EntityType<?>, Integer> counts,
                                       boolean climber) {
        TdData data = TdData.get(server);
        TdData.VirtualSwarm v = new TdData.VirtualSwarm(UUID.randomUUID(), false, pos.x, pos.y, pos.z, 0.0D);
        counts.forEach((type, n) -> v.counts.addTo(EntityType.getKey(type).toString(), n));
        v.climber = climber;
        active(data).virtual.add(v);
        data.setDirty();
    }

    /** 仮想の群れを全部消す(予算へは戻さない)。 */
    public static void clearVirtual(MinecraftServer server) {
        TdData data = TdData.get(server);
        active(data).virtual.clear();
        data.setDirty();
    }

    private static TdData.Event active(TdData data) {
        TdData.Event event = data.active();
        if (event == null) {
            throw new IllegalStateException("TD テスト: 侵攻中のイベントが無い");
        }
        return event;
    }

    // ------------------------------------------------------------------ 攻城のフック

    /** 群れの Goal と同じ口({@code SwarmSiegeHooks.BlockStrike})で 1 回叩く。 */
    public static boolean strike(Mob member, Swarm swarm, BlockPos block, boolean breach) {
        return TdSiege.strike(member, swarm, block, breach);
    }

    /** その一員が 1 回で塊・壁を削る量。 */
    public static float siegeDamage(Mob member, Swarm swarm) {
        return TdSiege.siegeDamage(member, swarm);
    }

    // ------------------------------------------------------------------ 攻め手の顔ぶれ(2026-09-25)

    /**
     * 仮想の群れ 1 つの顔ぶれを振る({@code TdTicker#rollRoster} と同じ)。
     *
     * @return {@code [地上か空か, 登る群れか, 種の並び]}。振れなければ null
     */
    @org.jetbrains.annotations.Nullable
    public static Object[] rollRoster(int phase, TdTable table, com.yayuy.overgrownvegetation.swarm.SwarmTable swarms,
                                      net.minecraft.util.RandomSource random) {
        return rollRoster(phase, table, swarms, random, true);
    }

    /** {@link #rollRoster} の、空の群れを出してよいかを選べる形({@code TdTicker#rollRoster} の 5 引数)。 */
    @org.jetbrains.annotations.Nullable
    public static Object[] rollRoster(int phase, TdTable table, com.yayuy.overgrownvegetation.swarm.SwarmTable swarms,
                                      net.minecraft.util.RandomSource random, boolean allowAir) {
        TdTicker.Roster roster = TdTicker.rollRoster(phase, table, swarms, random, allowAir);
        return roster == null ? null : new Object[]{roster.kind(), roster.climber(), roster.types()};
    }

    /** 空の仮想の群れを 1 つ足す(動かない。予算からは引かない)。 */
    public static void addAirVirtualSwarm(MinecraftServer server, Vec3 pos, EntityType<?> type, int count) {
        TdData data = TdData.get(server);
        TdData.VirtualSwarm v = new TdData.VirtualSwarm(UUID.randomUUID(), true, pos.x, pos.y, pos.z, 0.0D);
        v.counts.addTo(EntityType.getKey(type).toString(), count);
        active(data).virtual.add(v);
        data.setDirty();
    }

    /** 同時に出ているヴェロフィル({@code TdTicker#airAttackers})。侵攻が無ければ 0。 */
    public static int airAttackers(ServerLevel level) {
        TdData.Event event = TdData.get(level.getServer()).live();
        return event == null ? 0 : TdTicker.airAttackers(level, event);
    }

    /** 次の仮想の群れが生まれる点(x, z)({@code TdTicker#ringPoint}。群れは作らない)。 */
    public static double[] ringPoint(MinecraftServer server, net.minecraft.util.RandomSource random) {
        TdData.Event event = active(TdData.get(server));
        ServerLevel level = server.getLevel(event.dimension());
        return TdTicker.ringPointForTests(level != null ? level : server.overworld(), event,
                com.yayuy.overgrownvegetation.data.DesignData.td(), random);
    }

    /** 群れが生まれる距離({@code TdTicker#spawnDistance}。見ている人の範囲の外へ押し出す)。 */
    public static double spawnDistance(double tx, double tz, double angle, double minR, double maxR,
                                       List<TdSight.Viewer> viewers, double margin, double farthest,
                                       net.minecraft.util.RandomSource random) {
        return TdTicker.spawnDistance(tx, tz, angle, minR, maxR, viewers, margin, farthest, random);
    }

    /** 一方向の侵攻の群れの向き({@code TdTicker#pickAngleInFan})。 */
    public static double pickAngleInFan(List<Double> taken, double center, double half, net.minecraft.util.RandomSource random) {
        return TdTicker.pickAngleInFan(taken, center, half, random);
    }

    /** 攻めてくる向きを振る({@code TdTicker#rollDirection}。8 方位の過繁茂チャンクの数を渡す)。 */
    public static TdDirection rollDirection(double[] sectors, double share, double bias, net.minecraft.util.RandomSource random) {
        return TdTicker.rollDirection(() -> sectors, share, bias, random);
    }

    /** 過繁茂チャンクを 8 方位に数える({@code TdTicker#overgrownSectors})。 */
    public static double[] overgrownSectors(double tx, double tz, double radius,
                                            java.util.function.Predicate<net.minecraft.world.level.ChunkPos> overgrown) {
        return TdTicker.overgrownSectors(tx, tz, radius, overgrown);
    }

    /** 8 方位の数の並び({@code TdDirection#COMPASS})での、その向きの添え字(全方位は -1)。 */
    public static int sectorIndex(TdDirection direction) {
        return java.util.Arrays.asList(TdDirection.COMPASS).indexOf(direction);
    }

    /** 進行中の侵攻の攻めてくる向き(無ければ null)。 */
    @org.jetbrains.annotations.Nullable
    public static TdDirection eventDirection(MinecraftServer server) {
        TdData.Event event = TdData.get(server).live();
        return event != null ? event.direction() : null;
    }

    /**
     * 進行中のイベントの向きを書き換える(決めた印はそのまま。予告で振った向きが侵攻に引き継がれるかを、振り直しでは出ない向きで見る)。
     * 書き換えたら true。
     */
    public static boolean setEventDirection(MinecraftServer server, TdDirection direction) {
        TdData data = TdData.get(server);
        TdData.Event event = data.live();
        if (event == null) {
            return false;
        }
        event.direction = direction;
        data.setDirty();
        return true;
    }

    /**
     * 進行中のイベントを保存形式に書いて読み直したときの、向きと「向きを決めたか」({@code [TdDirection, Boolean]}。無ければ null)。
     * 台帳そのものは差し替えない。
     */
    @org.jetbrains.annotations.Nullable
    public static Object[] reloadedDirection(MinecraftServer server) {
        TdData.Event event = TdData.get(server).live();
        if (event == null) {
            return null;
        }
        TdData.Event loaded = TdData.Event.load(event.save());
        return loaded != null ? new Object[]{loaded.direction(), loaded.directionChosen()} : null;
    }

    /** 見ている人を決める(null で本物のプレイヤーへ戻す)。GameTest のサーバーにはプレイヤーがいないので、代わりを置く。 */
    public static void setViewers(@org.jetbrains.annotations.Nullable List<TdSight.Viewer> viewers) {
        TdSight.setViewersForTests(viewers);
    }

    /** 仮想の群れの一覧(位置・速さ)。侵攻が無ければ空。 */
    public static List<double[]> virtualPositions(MinecraftServer server) {
        TdData.Event event = TdData.get(server).live();
        if (event == null) {
            return List.of();
        }
        List<double[]> out = new java.util.ArrayList<>(event.virtual.size());
        for (TdData.VirtualSwarm v : event.virtual) {
            out.add(new double[]{v.x, v.z, v.speed});
        }
        return out;
    }

    /** 空いている向き({@code TdTicker#pickAngle})。 */
    public static double pickAngle(List<Double> taken, net.minecraft.util.RandomSource random) {
        return TdTicker.pickAngle(taken, random);
    }

    /** 仮想の群れ {@code index} 番が「置けなかった」ときの横歩きを始める({@code TdTicker#startSidestep})。 */
    public static void startSidestep(MinecraftServer server, int index, net.minecraft.util.RandomSource random) {
        TdData data = TdData.get(server);
        TdData.Event event = active(data);
        TdData.VirtualSwarm v = event.virtual.get(index);
        TdTicker.startSidestepForTests(v, event, com.yayuy.overgrownvegetation.data.DesignData.td(), event.target().getX() + 0.5D,
                event.target().getZ() + 0.5D, random);
        data.setDirty();
    }

    /** 仮想の群れ {@code index} 番の横歩きの行き先の角度(NaN = 歩いていない)。 */
    public static double sidestepAngle(MinecraftServer server, int index) {
        return active(TdData.get(server)).virtual.get(index).sidestepAngle;
    }

    /** 空の仮想の群れ {@code index} 番を湧かせる位置({@code TdTicker#airPlacements})。 */
    public static List<com.yayuy.overgrownvegetation.swarm.SwarmSpawner.Placement> airPlacements(ServerLevel level, int index) {
        TdData.Event event = active(TdData.get(level.getServer()));
        return TdTicker.airPlacements(level, event.virtual.get(index), event.target().getX() + 0.5D, event.target().getZ() + 0.5D);
    }

    /** 仮想の群れの行進の間隔(tick)。 */
    public static int moveInterval() {
        return TdTicker.MOVE_INTERVAL;
    }

    /** 始めの一斉投入を済ませたか・帳簿を待たずに毎 tick 実体にする期限(tick)。侵攻が無ければ {@code [false, MIN]}。 */
    public static Object[] opening(MinecraftServer server) {
        TdData.Event event = TdData.get(server).live();
        return event == null ? new Object[]{false, Long.MIN_VALUE} : new Object[]{event.opened, event.burstUntil};
    }

    /** 壁を 1 回叩く量({@code TdSiege#wallDamage})と、その基準のアルボラの攻撃力。 */
    public static float wallDamage(Mob mob) {
        return TdSiege.wallDamage(mob);
    }

    public static double arboraBaseAttack() {
        return TdSiege.arboraBaseAttack();
    }

    /** 壁を壊すのに要る量({@code TdSiege#required}。汚染されたブロックは {@code corruptedBreakTimeMultiplier} 倍)。 */
    public static double requiredToBreak(ServerLevel level, BlockPos pos) {
        return TdSiege.required(level, pos, level.getBlockState(pos));
    }

    /** 汚染されたブロックと数えるか({@code TdSiege#corrupted})。 */
    public static boolean corrupted(ServerLevel level, BlockPos pos) {
        return TdSiege.corrupted(level, pos, level.getBlockState(pos));
    }

    /** 群れの外の攻め手が壁を 1 回叩く({@code TdSiege#strikeWall})。結果の名前(INVALID / DAMAGED / BROKEN)。 */
    public static String strikeWall(Mob mob, BlockPos pos) {
        return TdSiege.strikeWall(mob, pos).name();
    }

    /** 群れの外の攻め手が {@code goal} へ向けて崩す穴の断面({@code TdSiege#digHole})。無ければ空。 */
    public static List<BlockPos> digHole(Mob mob, BlockPos goal) {
        TdSiege.Hole hole = TdSiege.digHole(mob, goal);
        return hole == null ? List.of() : hole.cells();
    }

    /** 侵攻中のイベントが起きたフェーズ(-1 = 不明)。 */
    public static int eventPhase(MinecraftServer server) {
        TdData.Event event = TdData.get(server).live();
        return event != null ? event.phase : -1;
    }

    /** いまの侵攻の行進中の群れを、クライアントへ送る形で({@link TdHordeSync#views})。侵攻が無ければ空。 */
    public static List<com.yayuy.overgrownvegetation.network.TdHordePayload.Swarm> hordeViews(MinecraftServer server) {
        TdData.Event event = TdData.get(server).live();
        return event == null ? List.of() : TdHordeSync.views(event, com.yayuy.overgrownvegetation.data.DesignData.td());
    }

    /** 群れが行進をやめて待つ輪(ターゲットから、ブロック)。 */
    public static double holdRing() {
        return TdTicker.holdRing(com.yayuy.overgrownvegetation.data.DesignData.td());
    }

    /**
     * {@code level} のバニラの自然湧きの敵対 Mob の回を、いま {@link TdHostiles} が止めているか。{@code sync} なら先に 20 tick ごとの
     * 掛け直しを 1 回走らせる(侵攻を止めた直後に戻ることを見る)。
     */
    public static boolean monsterSpawningOff(ServerLevel level, boolean sync) {
        return TdHostiles.monsterSpawningOffForTests(level, sync);
    }

    // ------------------------------------------------------------------ 2026-09-25 の runClient 3 回目の報告

    /** 足の段より下の {@code goal} へ掘る幅 {@code width} の縦穴の、最初の段の塊(先頭 = 要の列)。掘る塊が無ければ空。 */
    public static List<BlockPos> shaftHole(ServerLevel level, Vec3 feet, BlockPos goal, BlockPos target, int width) {
        if (!TdSiege.Column.below(feet, goal)) {
            return List.of();
        }
        TdSiege.Column column = TdSiege.Column.toward(level, feet, goal, target, width);
        TdSiege.Hole hole = column != null ? column.findHole(level, target, pos -> false) : null;
        return hole != null ? hole.cells() : List.of();
    }

    /** 橋の計画(見る範囲をテスト場に収める)。置く塊・崩す塊を {@code place} で選ぶ。計画できなければ null。 */
    @org.jetbrains.annotations.Nullable
    public static List<BlockPos> bridgeSteps(ServerLevel level, BlockPos feet, BlockPos goal, int width, int back, int ahead,
                                             int persist, int clearance, boolean place,
                                             net.minecraft.world.level.levelgen.structure.BoundingBox clip) {
        TdBridges.Plan plan = TdBridges.planForTests(level, feet, goal, TdEvents.activeTarget(level), width, back, ahead,
                persist, clearance, clip);
        if (plan == null) {
            return null;
        }
        return plan.steps().stream().filter(s -> s.place() == place).map(TdBridges.Step::pos).toList();
    }

    /** 橋を架け始める(大工は群れの外のアルボラ)。計画できなければ null。 */
    @org.jetbrains.annotations.Nullable
    public static TdBridges.Project startBridge(ServerLevel level, BlockPos feet, BlockPos goal, int width, int back,
                                                int ahead, int persist, int clearance,
                                                net.minecraft.world.level.levelgen.structure.BoundingBox clip) {
        return TdBridges.startForTests(level, feet, goal, width, back, ahead, persist, clearance, clip);
    }

    /** 群れ・攻め手が頼む橋の見る範囲を差し替える(null 相当 = {@code back < 0} で戻す)。 */
    public static void setBridgeShape(int back, int ahead, int persist, int clearance,
                                      @org.jetbrains.annotations.Nullable net.minecraft.world.level.levelgen.structure.BoundingBox clip) {
        TdBridges.setShapeForTests(back < 0 ? null : new TdBridges.Shape(back, ahead, persist, clearance, clip));
    }

    /** その群れが待っている橋(無ければ null)。 */
    @org.jetbrains.annotations.Nullable
    public static TdBridges.Project bridgeOf(ServerLevel level, UUID swarm) {
        return TdBridges.projectOf(level, swarm);
    }

    /** 調べもの用: 群れの攻城の状態(失敗の文言に出す)。 */
    public static String siegeDebug(ServerLevel level, UUID swarm) {
        return TdSiege.debugState(level, swarm);
    }

    /** 攻め手のドロップを収納へ入れる口(LivingDropsEvent と同じ処理)。 */
    public static void collectDrops(TargetStorage storage, java.util.Iterator<net.minecraft.world.entity.item.ItemEntity> drops) {
        TdLootCollector.collect(storage, drops);
    }

    private TdTestAccess() {
    }
}
