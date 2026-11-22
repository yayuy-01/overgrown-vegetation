package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NetherSpreadTable;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.server.ServerStoppedEvent;
import com.yayuy.overgrownvegetation.port.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * <b>ネザーの汚染の台帳</b>(2026-11-11 骨組みで作り直し。正本は Vault「ネザーの環境の作り直し 2」の「骨組みの実装仕様」)。
 *
 * <p>チャンクが汚染されると({@link #corrupt}。<b>段階の時計の入口はこれ 1 つ</b>)、その時刻から段階 1〜5 が進む。誰が入口を呼ぶかは差し替えられる:
 * 最初の根・今の胞子の場の発芽({@link #acceptFieldSprouts})・命令。時刻で持つので、誰もいないネザーでも進む。
 *
 * <p><b>根</b>(11-11 ユーザー): ポータルの出口から<b>最初の溶岩の海まで</b>だけ伸びる。海に着いた後の広がりは胞子が主
 * (ネザー用の新しい胞子の仕組み = 寒暖差の風は相談中)。根の前線は届く時刻の早い順に進める(ダイクストラ法と同じ)。
 *
 * <ul>
 *     <li>起点: 森がネザーポータルを越えたときのネザー側の出口({@link #seedFromPortal}。{@link PortalCorruption} から)。</li>
 *     <li>最初の根: 出口から半径 {@code firstRootSearchChunks} の中でいちばん近い溶岩の海へ<b>まっすぐ</b>、海と同じ速さで(ユーザー了承)。
 *     半径の中に海が無ければ、隣へ這って(陸 {@code landTicksPerChunk}・海 {@code seaTicksPerChunk})海を探す。海に着いたら止まる。</li>
 *     <li>飛び地: 段階 {@code leapFromStage} からのチャンクが、まれに数チャンク先へ(胞子の仕組みが決まるまで {@code leapChance} 0 で止めてある)。</li>
 *     <li>上限: 起点から {@code maxRadiusChunks}(0 = なし)。</li>
 *     <li>段階 3(過繁茂)に入ったチャンクをフェーズの面積・ポイントに数える。オーバーワールドの台帳(極相)には入れない。</li>
 * </ul>
 * 地面の作り変えは {@link NetherStageWorker}(読み込まれているチャンクだけ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class NetherLedger {

    /** 1 回で前線を進める上限(留守が長いときの追いつきは数回に分かれる)。 */
    private static final int ARRIVALS_PER_PASS = 4096;
    private static final int COUNT_INTERVAL = 100;

    /** 前線の待ち行列(時刻, キー)。台帳の {@code frontier} と食い違った古い項目は取り出したときに捨てる。 */
    private static final PriorityQueue<long[]> HEAP = new PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
    private static NetherLedgerData heapOwner;

    private NetherLedger() {
    }

    /** 台帳を持つ次元か(ネザーだけ)。 */
    public static boolean isLedgerLevel(Level level) {
        return level.dimension() == Level.NETHER;
    }

    /**
     * 骨組みが動いているか(ネザーの地面・バイオームを台帳だけが変える)。2026-11-12 から、ワールドの「実験的要素」がオンのときだけ
     * (ユーザー決定: ネザーの作り直しは 0.2.0 の後なので公開版では遊ばせない)。オフなら Mob などのふだんの汚染だけが、前の経路
     * (CorruptionRegistry の表)でブロックを変える。
     */
    public static boolean active(Level level) {
        return isLedgerLevel(level) && DesignData.netherSpread().enabled
                && com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures.enabled(level);
    }

    /**
     * 過繁茂になったチャンクの報告({@link OvergrownBiomeConverter} から、オーバーワールド以外のとき)。
     * ネザーは、新しい仕組みが動いている(実験的要素がオン)なら台帳が自分で数えるので何もしない。ほかの次元(エンド)は台帳が無いのでそのまま数える。
     * 2026-11-12 ユーザー決定: 実験的要素がオフのネザーは、作り直しの前と同じくここで数える(オン・オフでフェーズの進み方を変えない)。
     * 数えたチャンクは台帳の「数えた」にも入れ、後でオンにしたとき台帳が段階 3 で数え直さないようにする(二重に数えない)。
     */
    static void record(ServerLevel level, List<ChunkPos> chunks) {
        if (chunks.isEmpty() || active(level)) return;
        int n = chunks.size();
        if (isLedgerLevel(level)) {
            NetherLedgerData data = NetherLedgerData.get(level);
            n = 0;
            for (ChunkPos c : chunks) if (data.counted.add(c.toLong())) n++;
            if (n == 0) return;
            data.setDirty();
        }
        PhaseData.get(level).addOvergrownChunks(level, n);
    }

    /**
     * ネザーの胞子の場(寒暖差の風、2026-11-11)で発芽したチャンクを「汚染された」入口へ渡す(発芽した時刻から段階 1。湖に着いた後の広がりは胞子が主)。
     * 上限(ポータルの出口から maxRadiusChunks)の外は受け取らない(ユーザー決定: 場の発芽も上限の外では起こさない)。受け取らなかったチャンクを返す(場で取り消す)。
     */
    public static long[] acceptFieldSprouts(ServerLevel nether, long[] keys) {
        if (!active(nether)) return new long[0];
        long now = nether.getGameTime();
        it.unimi.dsi.fastutil.longs.LongArrayList rejected = new it.unimi.dsi.fastutil.longs.LongArrayList();
        NetherLedgerData data = NetherLedgerData.get(nether);
        for (long key : keys) {
            if (!withinRadius(data, DesignData.netherSpread(), ChunkPos.getX(key), ChunkPos.getZ(key))) {
                rejected.add(key);
                continue;
            }
            if (com.yayuy.overgrownvegetation.spore.SporeDeviceData.guardsChunkFromLedger(nether, ChunkPos.getX(key), ChunkPos.getZ(key))) {
                continue;
            }
            corrupt(nether, key, now, Cause.SPORE);
        }
        return rejected.toLongArray();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        HEAP.clear();
        heapOwner = null;
        NetherStageWorker.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !isLedgerLevel(level) || !DesignData.netherSpread().enabled) return;
        NetherSpreadTable t = DesignData.netherSpread();
        NetherLedgerData data = NetherLedgerData.get(level);
        long now = level.getGameTime();
        if (!active(level)) {
            // 実験的要素がオフ(2026-11-12): 森を止める。止めていた間の分は、届いた時刻ごと後ろへずらす(オンに戻すと止めた所から続く)
            if (now % t.ledgerTickInterval == 0 && !(data.arrival.isEmpty() && data.frontier.isEmpty())) shift(data, t.ledgerTickInterval);
            return;
        }
        if (CorruptionSwitch.isOff()) {
            // /ov corruption false: 前線も段階も止める(止めていた間の分は届いた時刻ごと後ろへずらす)
            if (now % t.ledgerTickInterval == 0) shift(data, t.ledgerTickInterval);
            return;
        }
        if (!data.legacy.isEmpty()) migrateLegacy(level, data, t, now);
        if (now % t.ledgerTickInterval == 3) {
            spread(level, data, t, now);
            leaps(level, data, t, now);
        }
        if (now % COUNT_INTERVAL == 7) countOvergrown(level, data, t, now);
        NetherStageWorker.tick(level, data, t, now);
    }

    // ------------------------------------------------------------------------------------------
    // 起点
    // ------------------------------------------------------------------------------------------

    /**
     * ポータルの出口から森を始める。出口のチャンクに今すぐ前線が届き、最初の根がいちばん近い溶岩の海へまっすぐ伸びる。
     * もう森になっているチャンクなら何もしない。最初の根の行き先を返す(無ければ null)。
     */
    public static ChunkPos seedFromPortal(ServerLevel nether, BlockPos exit) {
        if (!active(nether)) return null;
        NetherSpreadTable t = DesignData.netherSpread();
        NetherLedgerData data = NetherLedgerData.get(nether);
        ChunkPos origin = new ChunkPos(exit);
        long key = origin.toLong();
        if (data.isReached(key)) return null;
        long now = nether.getGameTime();
        data.origins.add(key);
        corrupt(nether, key, now, Cause.PORTAL);
        ChunkPos sea = NetherTerrain.nearestSea(origin, t.firstRootSearchChunks,
                (x, z) -> isSea(nether, data, ChunkPos.asLong(x, z)));
        data.rootStart.put(key, exit.asLong()); // 根の網の太い根はポータルの出口から描き始める(2026-11-12)
        ensureHeap(data);
        if (sea != null && sea.equals(origin)) {
            data.rootDone.add(key); // 出口がもう海の上
        } else if (sea != null) {
            // 最初の根: いちばん近い溶岩の海へまっすぐ(海と同じ速さ)。海に着いたら根は止まる
            List<ChunkPos> path = line(origin, sea);
            recordPath(data, path);
            double step = t.firstRootTicksPerChunk / data.speed;
            for (int i = 1; i < path.size(); i++) {
                long k = path.get(i).toLong();
                if (data.isReached(k)) continue;
                offer(data, k, now + Math.round(step * i), key);
            }
        } else {
            // 半径の中に海が無い: 根が隣へ這って海を探す(海に着いたら止まる)
            data.creepOrigins.add(key);
            pushNeighbours(nether, data, t, key, now, key);
        }
        data.setDirty();
        OvergrownVegetation.LOGGER.info("ネザーの根: {} から始まった(最初の根の行き先 {})", origin, sea);
        return sea;
    }

    /** 最初の根の道筋を覚える(根の網の太い根がたどる。2026-11-12)。もう道に載っているチャンクは書き換えない(先に来た根が勝つ)。 */
    static void recordPath(NetherLedgerData data, List<ChunkPos> path) {
        for (int i = 0; i + 1 < path.size(); i++) {
            long a = path.get(i).toLong(), b = path.get(i + 1).toLong();
            // 別の根の道に載っているチャンクは書き換えない(先に来た根が勝つ。合流した所はつながらないことがある。仮)
            if (!data.rootNext.containsKey(a)) data.rootNext.put(a, b);
            if (!data.rootPrev.containsKey(b) && !data.rootStart.containsKey(b)) data.rootPrev.put(b, a);
        }
    }

    /** {@code a} から {@code b} へ辺でつながったチャンクの列(両端を含む)。 */
    static List<ChunkPos> line(ChunkPos a, ChunkPos b) {
        List<ChunkPos> out = new ArrayList<>();
        int x = a.x, z = a.z;
        out.add(new ChunkPos(x, z));
        int dx = b.x - a.x, dz = b.z - a.z;
        int n = Math.abs(dx) + Math.abs(dz);
        double fx = 0, fz = 0;
        for (int i = 0; i < n; i++) {
            // 残りの比に合わせて x と z を交互に進める(斜めに近いまっすぐ)
            fx = Math.abs(b.x - x) / (double) Math.max(1, Math.abs(dx));
            fz = Math.abs(b.z - z) / (double) Math.max(1, Math.abs(dz));
            if (x != b.x && (z == b.z || fx >= fz)) {
                x += Integer.signum(dx);
            } else {
                z += Integer.signum(dz);
            }
            out.add(new ChunkPos(x, z));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // 前線
    // ------------------------------------------------------------------------------------------

    private static void ensureHeap(NetherLedgerData data) {
        if (heapOwner == data) return;
        HEAP.clear();
        for (Long2LongOpenHashMap.Entry e : data.frontier.long2LongEntrySet()) {
            HEAP.add(new long[]{e.getLongValue(), e.getLongKey()});
        }
        heapOwner = data;
    }

    private static void offer(NetherLedgerData data, long key, long time, long origin) {
        if (data.rootDone.contains(origin)) return;
        if (time < data.frontier.get(key)) {
            data.frontier.put(key, time);
            data.frontierOrigin.put(key, origin);
            if (heapOwner == data) HEAP.add(new long[]{time, key});
        }
    }

    /** 根が隣へ這う(近くに海が無い起点だけ)。 */
    private static void pushNeighbours(ServerLevel level, NetherLedgerData data, NetherSpreadTable t, long key, long time, long origin) {
        int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
        WorldBorder border = level.getWorldBorder();
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int nx = cx + d[0], nz = cz + d[1];
            long nk = ChunkPos.asLong(nx, nz);
            if (data.isReached(nk) || !withinRadius(data, t, nx, nz)) continue;
            if (!border.isWithinBounds(new ChunkPos(nx, nz))) continue;
            double cost = (isSea(level, data, nk) ? t.seaTicksPerChunk : t.landTicksPerChunk) / data.speed;
            offer(data, nk, time + Math.max(1, Math.round(cost)), origin);
        }
    }

    /** 根が溶岩の海に着いた: その起点の根を止め、まだ伸びていない前線を捨てる(ここから先は胞子)。 */
    private static void rootReachedSea(NetherLedgerData data, long origin) {
        if (!data.rootDone.add(origin)) return;
        data.creepOrigins.remove(origin);
        var it = data.frontierOrigin.long2LongEntrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getLongValue() == origin) {
                data.frontier.remove(e.getLongKey());
                it.remove();
            }
        }
        heapOwner = null;
        data.setDirty();
    }

    /** 届いた時刻が {@code now} 以前の前線を、早い順に届かせる。届かせた数を返す。 */
    static int spread(ServerLevel level, NetherLedgerData data, NetherSpreadTable t, long now) {
        ensureHeap(data);
        int n = 0;
        while (!HEAP.isEmpty() && n < ARRIVALS_PER_PASS) {
            long[] top = HEAP.peek();
            if (top[0] > now) break;
            HEAP.poll();
            long key = top[1];
            if (data.frontier.get(key) != top[0]) continue; // 古い項目
            long origin = data.frontierOrigin.get(key);
            data.frontier.remove(key);
            data.frontierOrigin.remove(key);
            if (data.rootDone.contains(origin)) continue;
            corrupt(level, key, top[0], Cause.ROOT);
            n++;
            if (isSea(level, data, key)) {
                if (data.creepOrigins.contains(origin)) {
                    // 這って海を探した根: 見つけた海へのまっすぐな道を太い根の道にする(段階 1 を過ぎたチャンクには描かない。仮)
                    recordPath(data, line(new ChunkPos(origin), new ChunkPos(key)));
                }
                rootReachedSea(data, origin);
                ensureHeap(data);
            } else if (data.creepOrigins.contains(origin)) {
                pushNeighbours(level, data, t, key, top[0], origin);
            }
        }
        if (n > 0) data.setDirty();
        return n;
    }

    /** どこから汚染が来たか(記録・確かめ用)。 */
    public enum Cause {
        /** ポータルの出口(起点)。 */
        PORTAL,
        /** 最初の根(出口 → いちばん近い溶岩の海)。 */
        ROOT,
        /** 胞子(今の胞子の場の発芽。湖に着いた後の広がりの繋ぎ方は相談中)。 */
        SPORE,
        /** Mob などのふだんの汚染(飛び地の起点)。 */
        MOB,
        /** 命令・テスト。 */
        COMMAND,
        /** 版 1 の台帳から。 */
        LEGACY
    }

    /**
     * <b>チャンクが汚染された = 段階の時計が始まる、ただ 1 つの入口</b>。{@code time} がそのチャンクの段階 1 の始まり。
     * もう汚染されていれば何もせず false。根・胞子・命令のどれもここを呼ぶ(誰が呼ぶかは差し替えられる)。
     */
    public static boolean corrupt(ServerLevel level, long key, long time, Cause cause) {
        return corrupt(level, key, time, cause, null);
    }

    /**
     * {@link #corrupt} の、覆いが広がり始める所を決める版(2026-11-12)。{@code origin} が null なら、いちばん早く森になった隣のチャンクの側の
     * 辺の真ん中(根・胞子が入ってきた側)、隣に森が無ければチャンクの真ん中。
     */
    public static boolean corrupt(ServerLevel level, long key, long time, Cause cause, @javax.annotation.Nullable BlockPos origin) {
        NetherLedgerData data = NetherLedgerData.get(level);
        if (data.isReached(key)) return false;
        data.frontier.remove(key);
        data.frontierOrigin.remove(key);
        data.arrival.put(key, time);
        int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
        long o;
        if (origin != null) {
            o = BlockPos.asLong(origin.getX(), 0, origin.getZ());
        } else {
            int ox = (cx << 4) + 8, oz = (cz << 4) + 8;
            long best = Long.MAX_VALUE;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                long n = ChunkPos.asLong(cx + d[0], cz + d[1]);
                if (!data.isReached(n) || data.arrivalOf(n) >= best) continue;
                best = data.arrivalOf(n);
                ox = (cx << 4) + 8 + d[0] * 8; // 隣の側の辺の真ん中
                oz = (cz << 4) + 8 + d[1] * 8;
            }
            o = BlockPos.asLong(ox, 0, oz);
        }
        data.coverOrigin.put(key, o);
        data.setDirty();
        NetherStageWorker.noticeArrival(level, key);
        return true;
    }

    static boolean isSea(ServerLevel level, NetherLedgerData data, long key) {
        byte known = data.terrain.get(key);
        if (known >= 0) return known == 1;
        boolean sea = NetherTerrain.isSea(level, ChunkPos.getX(key), ChunkPos.getZ(key));
        data.terrain.put(key, (byte) (sea ? 1 : 0));
        return sea;
    }

    /**
     * Mob などのふだんの汚染がネザーのブロックを変えた(2026-11-11 ユーザー決定「両方」)。そのチャンクがまだ森でなく、上限の中なら、
     * 今から段階の時計を始める(飛び地の起点)。上限の外は時計を始めない(仮決め。ブロックは変わる)。
     */
    static void noticeOrdinaryCorruption(ServerLevel level, BlockPos pos) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        NetherLedgerData data = NetherLedgerData.get(level);
        long key = ChunkPos.asLong(cx, cz);
        if (data.isReached(key) || !withinRadius(data, DesignData.netherSpread(), cx, cz)) return;
        corrupt(level, key, level.getGameTime(), Cause.MOB, pos); // 覆いは当たった所から広がる(2026-11-12)
    }

    /** そのチャンクは起点(ポータルの出口)から広がりの上限の中か。 */
    public static boolean withinRadius(ServerLevel level, int cx, int cz) {
        return withinRadius(NetherLedgerData.get(level), DesignData.netherSpread(), cx, cz);
    }

    private static boolean withinRadius(NetherLedgerData data, NetherSpreadTable t, int cx, int cz) {
        if (t.maxRadiusChunks <= 0 || data.origins.isEmpty()) return true;
        long r2 = (long) t.maxRadiusChunks * t.maxRadiusChunks;
        for (int i = 0; i < data.origins.size(); i++) {
            long o = data.origins.getLong(i);
            long dx = cx - ChunkPos.getX(o), dz = cz - ChunkPos.getZ(o);
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    /** 段階 {@code leapFromStage} からのチャンクが、まれに数チャンク先へ飛ぶ(湯気に乗った胞子)。 */
    private static void leaps(ServerLevel level, NetherLedgerData data, NetherSpreadTable t, long now) {
        if (t.leapChance <= 0 || data.arrival.isEmpty()) return;
        if (data.lastLeapCheck == Long.MIN_VALUE) {
            data.lastLeapCheck = now;
            return;
        }
        RandomSource random = level.getRandom();
        long leapAge = t.stageStartTicks[t.leapFromStage - 1];
        for (int pass = 0; pass < 8 && data.lastLeapCheck + t.leapCheckTicks <= now; pass++) {
            long at = data.lastLeapCheck + t.leapCheckTicks;
            data.lastLeapCheck = at;
            List<long[]> jumps = new ArrayList<>();
            for (Long2LongOpenHashMap.Entry e : data.arrival.long2LongEntrySet()) {
                if (at - e.getLongValue() < leapAge || random.nextDouble() >= t.leapChance) continue;
                double ang = random.nextDouble() * Math.PI * 2;
                int dist = t.leapMinChunks + random.nextInt(t.leapMaxChunks - t.leapMinChunks + 1);
                int nx = ChunkPos.getX(e.getLongKey()) + (int) Math.round(Math.cos(ang) * dist);
                int nz = ChunkPos.getZ(e.getLongKey()) + (int) Math.round(Math.sin(ang) * dist);
                jumps.add(new long[]{ChunkPos.asLong(nx, nz), at});
            }
            for (long[] j : jumps) {
                int nx = ChunkPos.getX(j[0]), nz = ChunkPos.getZ(j[0]);
                if (data.isReached(j[0]) || !withinRadius(data, t, nx, nz)) continue;
                if (!level.getWorldBorder().isWithinBounds(new ChunkPos(nx, nz))) continue;
                corrupt(level, j[0], j[1], Cause.SPORE);
            }
            data.setDirty();
        }
    }

    /** 段階 3 に入ったチャンクをフェーズの面積・ポイントに数える(読み込まれていなくても)。 */
    private static void countOvergrown(ServerLevel level, NetherLedgerData data, NetherSpreadTable t, long now) {
        long age3 = t.stageStartTicks[2];
        int added = 0;
        for (Long2LongOpenHashMap.Entry e : data.arrival.long2LongEntrySet()) {
            if (now - e.getLongValue() >= age3 && data.counted.add(e.getLongKey())) added++;
        }
        if (added > 0) {
            data.setDirty();
            PhaseData.get(level).addOvergrownChunks(level, added);
        }
    }

    /** 版 1 の台帳の過繁茂チャンクは、段階 3 に入ったばかりの所として続ける(もう数えてあるので数え直さない)。 */
    private static void migrateLegacy(ServerLevel level, NetherLedgerData data, NetherSpreadTable t, long now) {
        long at = now - t.stageStartTicks[2];
        ensureHeap(data);
        List<Long> keys = new ArrayList<>(data.legacy);
        data.legacy.clear();
        for (long key : keys) {
            if (data.isReached(key)) continue;
            data.counted.add(key);
            if (data.origins.isEmpty()) data.origins.add(key);
            corrupt(level, key, at, Cause.LEGACY);
        }
        data.setDirty();
    }

    // ------------------------------------------------------------------------------------------
    // 命令・テスト
    // ------------------------------------------------------------------------------------------

    /** 時計を {@code ticks} だけ進める(届いた時刻・前線を前へずらす)。{@code /ov nether advance}。 */
    public static void advance(ServerLevel level, long ticks) {
        NetherLedgerData data = NetherLedgerData.get(level);
        shift(data, -ticks);
        NetherSpreadTable t = DesignData.netherSpread();
        long now = level.getGameTime();
        for (int i = 0; i < 64 && spread(level, data, t, now) > 0; i++) {
            // 追いつくまで(1 回 4096 チャンクまで)
        }
        countOvergrown(level, data, t, now);
    }

    private static void shift(NetherLedgerData data, long delta) {
        for (Long2LongOpenHashMap.Entry e : data.arrival.long2LongEntrySet()) e.setValue(e.getLongValue() + delta);
        for (Long2LongOpenHashMap.Entry e : data.frontier.long2LongEntrySet()) e.setValue(e.getLongValue() + delta);
        if (data.lastLeapCheck != Long.MIN_VALUE) data.lastLeapCheck += delta;
        heapOwner = null; // 待ち行列は次に作り直す
        data.setDirty();
    }

    /** 速さの倍率({@code /ov nether speed})。これから決まる前線の時刻に効く。 */
    public static void setSpeed(ServerLevel level, double speed) {
        NetherLedgerData data = NetherLedgerData.get(level);
        data.speed = Math.max(0.01, Math.min(1000.0, speed));
        data.setDirty();
    }

    /** テスト用: 過繁茂になったチャンクの報告({@link OvergrownBiomeConverter} の口と同じ)。 */
    public static void recordForTests(ServerLevel level, List<ChunkPos> chunks) {
        record(level, chunks);
    }

    /** そのチャンクの今の段階(1〜5)。前線が届いていなければ 0。 */
    public static int stageOf(ServerLevel level, long key) {
        NetherLedgerData data = NetherLedgerData.get(level);
        if (!data.isReached(key)) return 0;
        return DesignData.netherSpread().stageAt(level.getGameTime() - data.arrivalOf(key));
    }

    /** テスト用: そのチャンクに今 {@code ageTicks} 前に前線が届いたことにする(起点にもする)。 */
    public static void reachForTests(ServerLevel level, ChunkPos pos, long ageTicks) {
        NetherLedgerData data = NetherLedgerData.get(level);
        ensureHeap(data);
        if (!data.origins.contains(pos.toLong())) data.origins.add(pos.toLong());
        corrupt(level, pos.toLong(), level.getGameTime() - ageTicks, Cause.COMMAND);
        data.setDirty();
    }

    /** テスト用: 前線を {@code now} まで進める。 */
    public static int spreadForTests(ServerLevel level, long now) {
        return spread(level, NetherLedgerData.get(level), DesignData.netherSpread(), now);
    }

    /** テスト用: そのチャンクに今 {@code ageTicks} 前に前線が届いたことにする(覆いの起点を決めて。起点にもする)。 */
    public static void reachForTests(ServerLevel level, ChunkPos pos, long ageTicks, BlockPos coverOrigin) {
        NetherLedgerData data = NetherLedgerData.get(level);
        if (!data.origins.contains(pos.toLong())) data.origins.add(pos.toLong());
        corrupt(level, pos.toLong(), level.getGameTime() - ageTicks, Cause.COMMAND, coverOrigin);
    }

    /** テスト用: そのチャンクを海(true)か陸(false)として覚えさせる。 */
    public static void setTerrainForTests(ServerLevel level, long key, boolean sea) {
        NetherLedgerData.get(level).terrain.put(key, (byte) (sea ? 1 : 0));
    }

    /** テスト用: 待ち行列を捨てる(台帳を直接いじった後)。 */
    public static void resetHeapForTests() {
        HEAP.clear();
        heapOwner = null;
    }
}
