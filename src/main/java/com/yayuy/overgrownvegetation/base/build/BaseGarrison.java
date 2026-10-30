package com.yayuy.overgrownvegetation.base.build;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.AllyEntities;
import com.yayuy.overgrownvegetation.ally.SoldierEntity;
import com.yayuy.overgrownvegetation.ally.SoldierKind;
import com.yayuy.overgrownvegetation.base.plan.BaseEmitter;
import com.yayuy.overgrownvegetation.base.plan.BasePlan;
import com.yayuy.overgrownvegetation.base.plan.BaseType;
import com.yayuy.overgrownvegetation.base.plan.BuildingKind;
import com.yayuy.overgrownvegetation.base.plan.Facing;
import com.yayuy.overgrownvegetation.base.plan.Plot;
import com.yayuy.overgrownvegetation.base.plan.Surface;
import com.yayuy.overgrownvegetation.data.DefenseTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 基地の守備兵(2026-10-01)と、減った兵士の補充(順 2 の着手順 3、2026-10-04)。建て終えたとき・広げ終えたとき・世界生成の基地が台帳に
 * 載ったときに、レベルの定員まで兵士を足す(無料 —— 拡張の費用に入っている)。兵士は基地の中の道・広場に散らばって現れ、基地の範囲(持ち場)に
 * 留まる。広げ終えたら前からいる兵士の持ち場も新しい範囲にする(読み込まれていない兵士は読み込まれたときに)。
 *
 * <p>定員(ユーザー指定 2026-10-02): レベル 1 = 10・2 = 25・3 = 40・4 = 50・5 = 60。兵種の内訳は仮決め
 * (歩兵が主、工兵 2 割弱、砲兵はレベル 3 から・エリートはレベル 4 から)。
 *
 * <p><b>補充(ユーザー指定 10-01)</b>: 一定時間ごとに減った分だけ兵舎から湧く(貯蓄を使う、1 体ずつ間隔を空ける)。野戦病院は補充が少し安い。
 * <b>ユーザー指定(10-04)</b>: 値段は標準(歩兵 100・工兵 120・砲兵 200・エリート 400、{@code defense.json} の {@code alliedBase.replenishCosts})、
 * 離れている間の補充は戻るまでに済ませておく(日が変わるたびの精算で {@link #replenishAway})。
 * Claude 仮決め: 間隔 30 秒・野戦病院は 2 割引・出す兵種は定員に対していちばん足りない割合のもの・出口は兵舎のテントの入口の前
 * (読み込まれていて動ける兵舎を順番に)・貯蓄が足りなければたまるまで待つ・工事中は補充しない。
 *
 * <p>減った数は<b>名簿</b>({@link BaseSite#roster})で数える: 出した兵士を載せ、倒れたら消す。読み込まれていないチャンクの兵士は
 * エンティティとして見えない(L5 は「プレイヤーがいる」範囲 —— 中心から最大 172 ブロック —— にいても反対側が読み込まれていないことがある)ので、
 * 数えると二重に出す。名簿は基地が全部読み込まれているときに実際の兵士で直す({@link #census})。この仕組みより前の基地は、そこか、
 * 読み込まれて見えた兵士が定員に届いたときに名簿ができる(L3 以上は描画距離 8 だと全部が一度に読み込まれない。2026-10-04 にクライアントで見た)。
 * 兵士を消すのは倒れたとき({@link LivingDeathEvent})と陥落だけで、ほかの消し方({@code discard})は名簿に届かない —— 数え直しで直る。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class BaseGarrison {

    /**
     * 建て終えたとき・留守中に兵士を足すか(GameTest が遠くに建てる基地では切る —— 遠いチャンクの兵士は数えられず、実行のたびにワールドに溜まる)。
     * 切った基地は守備兵を置いていない({@link BaseSite#garrisoned} が偽)ので、補充もしない。
     */
    public static boolean enabled = true;
    /** GameTest 用: 兵士を作って名簿に載せ、貯蓄も払うが、ワールドには出さない(遠い基地の兵士はワールドに溜まる)。 */
    public static boolean dryRunForTests;

    /** 補充の見回りの間隔(tick)。 */
    private static final int STEP = 20;
    /** 名簿の無い前の基地を数えにいく間隔(tick)。 */
    private static final int UNKNOWN_CENSUS_INTERVAL = 100;
    /** 名簿を実際の兵士で直す間隔(tick。基地が全部読み込まれているときだけ)。 */
    private static final int CENSUS_INTERVAL = 6000;
    /** 兵舎の入口で立てる高さを探す上下の幅(計画の高さから)。 */
    private static final int STAND_SEARCH = 4;

    private static final int[][] QUOTA = { // {歩兵, 砲兵, 工兵, エリート}
            {0, 0, 0, 0},
            {8, 0, 2, 0},
            {20, 0, 5, 0},
            {29, 5, 6, 0},
            {33, 5, 7, 5},
            {38, 6, 8, 8},
    };

    /** レベル {@code level} の兵種ごとの定員。 */
    public static Map<SoldierKind, Integer> quota(int level) {
        int[] q = QUOTA[Math.max(1, Math.min(BasePlan.MAX_LEVEL, level))];
        Map<SoldierKind, Integer> out = new EnumMap<>(SoldierKind.class);
        out.put(SoldierKind.RIFLEMAN, q[0]);
        out.put(SoldierKind.ARTILLERY, q[1]);
        out.put(SoldierKind.ENGINEER, q[2]);
        out.put(SoldierKind.ELITE, q[3]);
        return out;
    }

    /** レベル {@code level} の定員の合計。 */
    public static int quotaTotal(int level) {
        int n = 0;
        for (int v : quota(level).values()) {
            n += v;
        }
        return n;
    }

    // ---------- 値段 ----------

    /** 兵種 {@code kind} の兵士 1 体の補充の値段(貯蓄)。野戦病院の基地は割引(ユーザー指定 10-01「少し安い」、割合は仮決め)。 */
    public static int price(SoldierKind kind, BaseType type) {
        DefenseTable t = DesignData.defense();
        int cost = t.baseReplenishCost(kind.id);
        if (type == BaseType.HOSPITAL) {
            cost = (int) Math.round(cost * (1.0D - t.baseReplenishHospitalDiscount()));
        }
        return cost;
    }

    // ---------- 名簿 ----------

    /** 名簿で数えた兵種ごとの足りない数(定員 − 名簿。多すぎる兵種は 0)。 */
    public static Map<SoldierKind, Integer> deficit(BaseSite site) {
        Map<SoldierKind, Integer> need = quota(site.level());
        for (SoldierKind k : site.roster.values()) {
            need.merge(k, -1, Integer::sum);
        }
        need.replaceAll((k, v) -> Math.max(0, v));
        return need;
    }

    /** 足りない兵士の数の合計。 */
    public static int missing(BaseSite site) {
        int n = 0;
        for (int v : deficit(site).values()) {
            n += v;
        }
        return n;
    }

    /** 次に補充する兵種: 定員に対していちばん足りない割合のもの(同じなら兵種の順)。足りていれば null。 */
    @Nullable
    public static SoldierKind nextKind(BaseSite site) {
        Map<SoldierKind, Integer> quota = quota(site.level());
        Map<SoldierKind, Integer> need = deficit(site);
        SoldierKind best = null;
        double bestShare = 0.0D;
        for (SoldierKind k : SoldierKind.values()) {
            int n = need.getOrDefault(k, 0);
            if (n <= 0) {
                continue;
            }
            double share = (double) n / quota.get(k);
            if (best == null || share > bestShare) {
                best = k;
                bestShare = share;
            }
        }
        return best;
    }

    /** 名簿を {@code soldiers} にする(数え直しと GameTest)。名簿は実際の兵士と合っていて、守備兵を置いた基地になる。 */
    public static void setRoster(BaseSites data, BaseSite site, Collection<SoldierEntity> soldiers) {
        site.roster.clear();
        for (SoldierEntity s : soldiers) {
            site.roster.put(s.getUUID(), s.kind());
        }
        site.rosterKnown = true;
        site.garrisoned = true;
        data.changed();
    }

    /** 基地が全部読み込まれていれば、実際に生きている兵士で名簿を作り直す。作り直したら真。 */
    public static boolean census(ServerLevel level, BaseSites data, BaseSite site) {
        if (!allLoaded(level, site)) {
            return false;
        }
        List<SoldierEntity> found = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof SoldierEntity s && s.siteId() == site.id && s.isAlive()) {
                found.add(s);
            }
        }
        setRoster(data, site, found);
        return true;
    }

    /**
     * 基地の兵士が来うるチャンク(持ち場の円を囲む四角 + 1 チャンク。壁の中の箱も入る)が、どれも読み込まれていてエンティティまで読み込み終えているか
     * (動いていなくてよい —— 描画距離の端の、動かないが見えているチャンクの兵士も数えられる)。読み込みを外す途中のチャンクは、
     * 本体の tick の終わり(この判定を呼ぶ {@link ServerTickEvent.Post} の前)にエンティティの読み込みも外れる。
     */
    public static boolean allLoaded(ServerLevel level, BaseSite site) {
        BlockPos home = guardHome(site);
        int r = guardRadius(site) + 16;
        for (int cx = (home.getX() - r) >> 4; cx <= (home.getX() + r) >> 4; cx++) {
            for (int cz = (home.getZ() - r) >> 4; cz <= (home.getZ() + r) >> 4; cz++) {
                if (!level.areEntitiesLoaded(ChunkPos.asLong(cx, cz)) || level.getChunkSource().getChunkNow(cx, cz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 守備兵が全滅したか(陥落の判定、ユーザー指定 10-01「貯蓄の枯渇 + 壁の中の守備兵の全滅」)。名簿のある基地は名簿が空。
     * 名簿の無い基地は、基地が全部読み込まれていて壁の中(計画の外接の箱)に生きている守備兵がいない(読み込まれていない兵士を「いない」と数えない)。
     */
    public static boolean wiped(ServerLevel level, BaseSite site) {
        if (site.garrisoned && site.rosterKnown) {
            return site.roster.isEmpty();
        }
        return allLoaded(level, site) && countInside(level, site) == 0;
    }

    // ---------- 持ち場 ----------

    /** 守る範囲の中心(区画の和の外接の真ん中、地面の高さ)。 */
    public static BlockPos guardHome(BaseSite site) {
        int[] b = site.plan(site.level()).bounds();
        int cx = (b[0] + b[2]) / 2;
        int cz = (b[1] + b[3]) / 2;
        return site.toWorld(cx, site.emitter(site.level()).target(cx, cz), cz);
    }

    /** 守る範囲の半径(外接の長い辺の半分 + 2)。 */
    public static int guardRadius(BaseSite site) {
        int[] b = site.plan(site.level()).bounds();
        return Math.max(b[2] - b[0], b[3] - b[1]) / 2 + 2;
    }

    /**
     * 前からいる兵士の持ち場を今のレベルの範囲にする(広げ終えたとき。10-02 からの残り「前からいる兵士の持ち場も広げる」)。
     * 読み込まれていない兵士は、読み込まれたときに {@link #onJoin} で直す。直した数。
     */
    public static int rehome(ServerLevel level, BaseSite site) {
        BlockPos home = guardHome(site);
        int radius = guardRadius(site);
        int n = 0;
        for (Entity e : level.getAllEntities()) {
            if (e instanceof SoldierEntity s && s.siteId() == site.id && needsRehome(s, home, radius)) {
                s.guard(site.id, home, radius);
                n++;
            }
        }
        return n;
    }

    private static boolean needsRehome(SoldierEntity s, BlockPos home, int radius) {
        return !s.hasRestriction() || !s.getRestrictCenter().equals(home) || Math.round(s.getRestrictRadius()) != radius;
    }

    // ---------- 足す ----------

    /**
     * 基地 {@code site} の兵士を、今のレベルの定員まで足す(無料)。足した数を返す。名簿で数える。名簿の無い前の基地は、全部読み込まれていれば
     * 数え直してから、そうでなければ前と同じく壁の中の読み込まれた兵士を数える(見えた兵士は名簿に載せる)。
     */
    public static int reinforce(ServerLevel level, BaseSite site) {
        if (site.fallen()) { // 陥落した基地には足さない(仮の陥落、順 2 の着手順 1)
            return 0;
        }
        BaseSites data = BaseSites.get(level.getServer());
        Map<SoldierKind, Integer> need;
        if (!site.garrisoned || site.rosterKnown || census(level, data, site)) {
            need = deficit(site);
        } else {
            need = quota(site.level());
            for (SoldierEntity s : level.getEntitiesOfClass(SoldierEntity.class, box(level, site), s -> s.siteId() == site.id && s.isAlive())) {
                need.merge(s.kind(), -1, Integer::sum);
                site.roster.putIfAbsent(s.getUUID(), s.kind());
            }
        }
        site.garrisoned = true;
        data.changed();
        BaseEmitter em = site.emitter(site.level());
        BlockPos home = guardHome(site);
        int radius = guardRadius(site);
        List<int[]> spots = spots(site.plan(site.level()), new Random(site.seed ^ (site.id * 0x9E3779B97F4A7C15L) ^ site.roster.size()));
        int added = 0;
        int n = 0;
        for (Map.Entry<SoldierKind, Integer> e : need.entrySet()) {
            for (int i = 0; i < e.getValue() && !spots.isEmpty(); i++) {
                int[] p = spots.get(n++ % spots.size());
                BlockPos at = site.toWorld(p[0], em.target(p[0], p[1]) + 1, p[1]);
                if (enlist(level, data, site, e.getKey(), at, level.getRandom().nextFloat() * 360.0F, home, radius) != null) {
                    added++;
                }
            }
        }
        return added;
    }

    /** 兵種 {@code kind} の兵士を {@code at} に出し、持ち場を決めて名簿に載せる。出した兵士(出せなければ null)。 */
    @Nullable
    static SoldierEntity enlist(ServerLevel level, BaseSites data, BaseSite site, SoldierKind kind, BlockPos at, float yaw,
                                BlockPos home, int radius) {
        EntityType<? extends SoldierEntity> type = AllyEntities.type(kind);
        SoldierEntity s = type.create(level, null, at, MobSpawnType.EVENT, false, false);
        if (s == null) {
            return null;
        }
        s.setYRot(yaw);
        s.setYHeadRot(yaw);
        s.setYBodyRot(yaw);
        s.guard(site.id, home, radius);
        if (!dryRunForTests && !level.addFreshEntity(s)) {
            return null;
        }
        site.roster.put(s.getUUID(), kind);
        data.changed();
        return s;
    }

    // ---------- 補充 ----------

    /**
     * プレイヤーのいる基地(着手順 1 の「読み込まれている」と同じ)で、減っている間は間隔ごとに 1 体ずつ兵舎から出す。
     * 名簿の無い基地は数えにいき、名簿のある基地もときどき実際の兵士で直す(どちらも基地が全部読み込まれているときだけ)。
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int tick = server.getTickCount();
        if (tick % STEP != 0) {
            return;
        }
        BaseSites data = BaseSites.get(server);
        if (data.all().isEmpty()) {
            return;
        }
        int interval = DesignData.defense().baseReplenishIntervalTicks();
        for (BaseSite site : List.copyOf(data.all())) {
            if (site.fallen() || site.building() || !site.garrisoned) {
                continue;
            }
            ServerLevel level = server.getLevel(site.dimension);
            if (level == null || !BaseEconomy.present(server, site)) {
                continue;
            }
            if (tick % (site.rosterKnown ? CENSUS_INTERVAL : UNKNOWN_CENSUS_INTERVAL) == 0) {
                census(level, data, site);
            }
            if (!enabled || !site.rosterKnown) {
                continue;
            }
            SoldierKind kind = nextKind(site);
            if (kind == null) {
                site.replenishTimer = 0;
                continue;
            }
            site.replenishTimer += STEP;
            if (site.replenishTimer < interval) {
                continue;
            }
            if (site.stock < price(kind, site.type)) {
                site.replenishTimer = 0; // 貯蓄がたまるまで待つ(次の間隔でもう一度)
                continue;
            }
            Exit exit = barracksExit(level, site);
            if (exit != null && replenishAt(level, data, site, exit.pos(), exit.yaw()) != null) {
                site.replenishTimer = 0;
            } // 出せなければ(読み込まれて動いている兵舎が無い)次の見回りでもう一度
        }
    }

    /**
     * 補充の兵士を 1 体 {@code at} に出し、貯蓄から払う(いちばん足りない兵種)。出した兵士。足りている・貯蓄が足りない・陥落した・出せないときは null。
     * 間隔は見ない(見回り・コマンド・GameTest が呼ぶ)。
     */
    @Nullable
    public static SoldierEntity replenishAt(ServerLevel level, BaseSites data, BaseSite site, BlockPos at, float yaw) {
        SoldierKind kind = nextKind(site);
        if (kind == null || site.fallen()) {
            return null;
        }
        int cost = price(kind, site.type);
        if (site.stock < cost) {
            return null;
        }
        SoldierEntity s = enlist(level, data, site, kind, at, yaw, guardHome(site), guardRadius(site));
        if (s != null) {
            site.stock -= cost;
            data.changed();
        }
        return s;
    }

    /** コマンド用: いまの補充を 1 体、兵舎の入口に出す(間隔を待たない)。読み込まれて動いている兵舎が無ければ null。 */
    @Nullable
    public static SoldierEntity replenishNow(ServerLevel level, BaseSites data, BaseSite site) {
        Exit exit = barracksExit(level, site);
        return exit == null ? null : replenishAt(level, data, site, exit.pos(), exit.yaw());
    }

    /**
     * 留守の 1 日ぶんの補充(ユーザー指定 10-04「戻るまでに済ませておく」。{@link BaseEconomy#settleDays} がその日の回復と減りの後に呼ぶ):
     * 減っていた分を、1 日に間隔で数えた数まで、貯蓄が足りる分だけ出す(兵舎ではなく、持ち場の道・広場へ —— 戻ったときにはもう散らばっている)。
     * 読み込まれていないチャンクへ出しても保存される(世界生成の基地の守備兵と同じ)。出した数。
     */
    public static int replenishAway(MinecraftServer server, BaseSites data, BaseSite site) {
        if (!enabled || site.fallen() || site.building() || !site.garrisoned || !site.rosterKnown) {
            return 0;
        }
        ServerLevel level = server.getLevel(site.dimension);
        if (level == null) {
            return 0;
        }
        int perDay = (int) (BaseEconomy.DAY / Math.max(1, DesignData.defense().baseReplenishIntervalTicks()));
        List<int[]> spots = null;
        BaseEmitter em = null;
        BlockPos home = null;
        int radius = 0;
        int n = 0;
        while (n < perDay) {
            SoldierKind kind = nextKind(site);
            if (kind == null || site.stock < price(kind, site.type)) {
                break;
            }
            if (spots == null) {
                spots = spots(site.plan(site.level()), new Random(site.seed ^ (site.lastDay * 0xC2B2AE3D27D4EB4FL) ^ site.roster.size()));
                if (spots.isEmpty()) {
                    break;
                }
                em = site.emitter(site.level());
                home = guardHome(site);
                radius = guardRadius(site);
            }
            int[] p = spots.get(n % spots.size());
            BlockPos at = site.toWorld(p[0], em.target(p[0], p[1]) + 1, p[1]);
            if (enlist(level, data, site, kind, at, level.getRandom().nextFloat() * 360.0F, home, radius) == null) {
                break;
            }
            site.stock -= price(kind, site.type);
            n++;
        }
        return n;
    }

    /** 補充の兵士の出口(立つ場所と向き)。 */
    record Exit(BlockPos pos, float yaw) {
    }

    /**
     * 補充の兵士が出てくる場所: 兵舎のテントの入口の前(読み込まれていて動いている兵舎を順番に)。兵舎の無い計画は道・広場から。
     * どこも読み込まれていなければ null。
     */
    @Nullable
    static Exit barracksExit(ServerLevel level, BaseSite site) {
        BasePlan plan = site.plan(site.level());
        BaseEmitter em = site.emitter(site.level());
        List<Plot> barracks = new ArrayList<>();
        for (Plot p : plan.plots) {
            if (p.kind() == BuildingKind.BARRACKS_TENT || p.kind() == BuildingKind.BARRACKS_TENT_LARGE) {
                barracks.add(p);
            }
        }
        for (int i = 0; i < barracks.size(); i++) {
            int k = Math.floorMod(site.nextBarracks + i, barracks.size());
            Plot p = barracks.get(k);
            int[] d = doorstep(p);
            BlockPos stand = standable(level, site.toWorld(d[0], em.target(d[0], d[1]) + 1, d[1]));
            if (stand != null) {
                site.nextBarracks = k + 1;
                return new Exit(stand, yaw(p.facing().rotateCw(site.rotation)));
            }
        }
        if (barracks.isEmpty()) {
            for (int[] s : spots(plan, new Random(site.seed))) {
                BlockPos stand = standable(level, site.toWorld(s[0], em.target(s[0], s[1]) + 1, s[1]));
                if (stand != null) {
                    return new Exit(stand, level.getRandom().nextFloat() * 360.0F);
                }
            }
        }
        return null;
    }

    /** 建物の入口の前のマス(区画の正面の真ん中の、区画のすぐ外。兵舎のテントの入口は正面の妻・前室の真ん中)。局所座標 {x, z}。 */
    static int[] doorstep(Plot p) {
        int cx = (p.x0() + p.x1()) / 2;
        int cz = (p.z0() + p.z1()) / 2;
        return switch (p.facing()) {
            case SOUTH -> new int[]{cx, p.z1() + 1};
            case NORTH -> new int[]{cx, p.z0() - 1};
            case EAST -> new int[]{p.x1() + 1, cz};
            case WEST -> new int[]{p.x0() - 1, cz};
        };
    }

    /** 向き {@code f} を向く体の向き(度。南 0・西 90・北 180・東 270)。 */
    static float yaw(Facing f) {
        return switch (f) {
            case SOUTH -> 0.0F;
            case WEST -> 90.0F;
            case NORTH -> 180.0F;
            case EAST -> 270.0F;
        };
    }

    /**
     * {@code column} の上下 {@value #STAND_SEARCH} マスで、兵士が立てる場所(足元に当たり判定があり、足と頭の 2 マスが空いていて水が無い)。
     * 動いていないチャンクなら null(ブロックを読むと読み込みが起きる・出しても歩き出さない)。
     */
    @Nullable
    static BlockPos standable(ServerLevel level, BlockPos column) {
        if (!level.isPositionEntityTicking(column)) {
            return null;
        }
        for (int dy = STAND_SEARCH; dy >= -STAND_SEARCH; dy--) {
            BlockPos p = column.above(dy);
            BlockPos below = p.below();
            if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()
                    && level.getBlockState(p).getCollisionShape(level, p).isEmpty() && level.getFluidState(p).isEmpty()
                    && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) {
                return p;
            }
        }
        return null;
    }

    // ---------- 出入りと倒れたとき ----------

    /**
     * 兵士が世界に入ったとき(新しく出た・チャンクと一緒に読み込まれた): 名簿に載っていなければ載せ(名簿の食い違いを直す)、持ち場が今のレベルの
     * 範囲でなければ直す(広げたときに読み込まれていなかった兵士)。陥落した基地の兵士は {@code BaseEconomy#onJoin} が消す。
     */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof SoldierEntity s) || s.siteId() <= 0) {
            return;
        }
        BaseSites data = BaseSites.get(level.getServer());
        BaseSite site = data.byId(s.siteId());
        if (site == null || site.fallen() || !site.dimension.equals(level.dimension())) {
            return;
        }
        if (site.garrisoned && s.isAlive() && !site.roster.containsKey(s.getUUID())) {
            site.roster.put(s.getUUID(), s.kind());
            if (!site.rosterKnown && site.roster.size() >= quotaTotal(site.level())) {
                // 名簿の無い前の基地: 読み込まれて見えた兵士が定員に届けば、欠けは無い(大きな基地は全部が一度に読み込まれないことがある)
                site.rosterKnown = true;
            }
            data.changed();
        }
        BlockPos home = guardHome(site);
        int radius = guardRadius(site);
        if (needsRehome(s, home, radius)) {
            s.guard(site.id, home, radius);
        }
    }

    /** 兵士が倒れたら名簿から消す(倒れるのを取り消されたら届かない —— いちばん後に聞く)。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof SoldierEntity s) || s.siteId() <= 0 || !(s.level() instanceof ServerLevel level)) {
            return;
        }
        BaseSites data = BaseSites.get(level.getServer());
        BaseSite site = data.byId(s.siteId());
        if (site != null && site.roster.remove(s.getUUID()) != null) {
            data.changed();
        }
    }

    // ---------- 表示 ----------

    /** 管理端末とコマンドの 1 行(守備兵の数と補充の様子)。 */
    public static Component describe(BaseSite site) {
        int total = quotaTotal(site.level());
        if (!site.garrisoned) {
            return Component.translatable("message.overgrown_vegetation.base.garrison_none", total);
        }
        if (!site.rosterKnown) {
            return Component.translatable("message.overgrown_vegetation.base.garrison_unknown", total);
        }
        SoldierKind kind = nextKind(site);
        if (kind == null) {
            return Component.translatable("message.overgrown_vegetation.base.garrison_full", site.rosterSize(), total);
        }
        int cost = price(kind, site.type);
        Component name = Component.translatable(AllyEntities.type(kind).getDescriptionId());
        if (site.stock < cost) {
            return Component.translatable("message.overgrown_vegetation.base.garrison_waiting", site.rosterSize(), total, name, cost);
        }
        int interval = DesignData.defense().baseReplenishIntervalTicks();
        return Component.translatable("message.overgrown_vegetation.base.garrison_replenishing", site.rosterSize(), total, name, cost,
                Math.max(0, interval - site.replenishTimer) / 20, interval / 20);
    }

    // ---------- 壁の中 ----------

    /** 基地の壁の中(計画の浄化圏の和の外接)の箱。高さは世界の上下いっぱい。 */
    static AABB box(ServerLevel level, BaseSite site) {
        int[] b = site.plan(site.level()).bounds();
        BlockPos c0 = site.toWorld(b[0], 0, b[1]);
        BlockPos c1 = site.toWorld(b[2], 0, b[3]);
        return new AABB(Math.min(c0.getX(), c1.getX()), level.getMinBuildHeight(), Math.min(c0.getZ(), c1.getZ()),
                Math.max(c0.getX(), c1.getX()) + 1, level.getMaxBuildHeight(), Math.max(c0.getZ(), c1.getZ()) + 1);
    }

    /** 壁の中にいる、その基地の生きている守備兵の数(読み込まれている兵士だけ)。 */
    public static int countInside(ServerLevel level, BaseSite site) {
        return level.getEntitiesOfClass(SoldierEntity.class, box(level, site), s -> s.siteId() == site.id && s.isAlive()).size();
    }

    /** 兵士が現れる場所(道・広場・前庭・巡回路と路地。建物の足元と外周の帯は除く)を 3 マスおきに集めて混ぜる。 */
    static List<int[]> spots(BasePlan plan, Random rng) {
        List<int[]> out = new ArrayList<>();
        for (int x = plan.minX(); x <= plan.maxX(); x += 3) {
            for (int z = plan.minZ(); z <= plan.maxZ(); z += 3) {
                if (plan.inDist(x, z) < 4 || plan.plotAt(x, z) != null) {
                    continue;
                }
                Surface s = plan.surfaceAt(x, z);
                if (s == Surface.ROAD || s == Surface.PLAZA || s == Surface.APRON || s == Surface.PATH) {
                    out.add(new int[]{x, z});
                }
            }
        }
        Collections.shuffle(out, rng);
        return out;
    }

    private BaseGarrison() {
    }
}
