package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.AllyEntities;
import com.yayuy.overgrownvegetation.ally.SoldierEntity;
import com.yayuy.overgrownvegetation.ally.SoldierKind;
import com.yayuy.overgrownvegetation.base.build.BaseEconomy;
import com.yayuy.overgrownvegetation.base.build.BaseGarrison;
import com.yayuy.overgrownvegetation.base.build.BaseRefuge;
import com.yayuy.overgrownvegetation.base.build.BaseSite;
import com.yayuy.overgrownvegetation.base.build.BaseSites;
import com.yayuy.overgrownvegetation.base.plan.BasePlan;
import com.yayuy.overgrownvegetation.base.plan.BaseTerrain;
import com.yayuy.overgrownvegetation.base.plan.BaseType;
import com.yayuy.overgrownvegetation.data.DefenseTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 同盟軍の基地の避難場所と守備兵の補充(順 2 の着手順 3、2026-10-04)。値段と兵種の選び方・名簿(出した兵士を載せ、倒れたら消す)・
 * 留守中の補充(ユーザー指定 10-04「戻るまでに済ませておく」)・守備兵を足す処理と名簿の数え直し・持ち場の直し・壁の中で敵が湧かないこと
 * (ユーザー指定 10-01「TD のときも普段も避難場所」)。期待値は設計値から引く。
 *
 * <p>遠い基地は毎回同じ場所(ほかの基地の試験と重ねない)。兵士を出す試験はテスト場の中に出して AI を切り、同じ tick のうちに消す
 * (兵士は 48 マス先の敵を撃つ)。台帳に載せた基地も同じ tick のうちに外す(壁の中は湧きを止めるので、隣のテスト場の湧きに触らない)。
 * 強制で読み込む試験だけは数 tick 待つ(終わったら外す)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class BaseGarrisonGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(BaseGarrisonGameTests.class);
        }

        private Registration() {
        }
    }

    private static final BaseTerrain FLAT = BaseTerrain.sample((x, z) -> 64);
    /** 名簿・補充・避難場所の試験の遠い基地(台帳の上だけで、チャンクは作らない)。 */
    private static final BlockPos LEDGER_AT = new BlockPos(-22008, 64, 25608);
    /** 守備兵を足す試験の遠い基地(まわりのチャンクを強制的に読み込む。終わったら外す)。 */
    private static final BlockPos GARRISON_AT = new BlockPos(-22520, 64, 26120);

    /**
     * 強制で読み込んだ基地のチャンクが全部読み込まれるまで 5 tick ごとに待ってから {@code body} を 1 回だけ流す({@code ticks} を過ぎたらそのまま流す)。
     * 決め打ちの 40 tick だと、同じ組で走るほかの試験の生成に押されて間に合わないことがあった(2026-10-07、信頼・取引の試験が 8 本増えて組が変わったとき)。
     */
    private static void whenLoaded(GameTestHelper helper, ServerLevel level, BaseSite site, int ticks, Runnable body) {
        if (ticks <= 0 || BaseGarrison.allLoaded(level, site)) {
            body.run();
            return;
        }
        helper.runAfterDelay(5, () -> whenLoaded(helper, level, site, ticks - 5, body));
    }

    private static BaseSite addSite(ServerLevel level, BlockPos center, int lv, BaseType type) {
        BaseSites data = BaseSites.get(level.getServer());
        BaseSite site = data.add(Level.OVERWORLD, center, 0, 4242L, lv, FLAT, true, type);
        BaseEconomy.start(data, site, BaseEconomy.today(level.getServer()));
        return site;
    }

    /** 世界に出さない兵士(名簿の数合わせ)。 */
    private static SoldierEntity dummy(ServerLevel level, SoldierKind kind) {
        SoldierEntity s = AllyEntities.type(kind).create(level);
        if (s == null) {
            throw new IllegalStateException("兵士を作れない: " + kind);
        }
        return s;
    }

    /** 定員から {@code short} だけ欠けた名簿(兵種ごとの欠けの数)。 */
    private static List<SoldierEntity> rosterShort(ServerLevel level, int lv, Map<SoldierKind, Integer> shortBy) {
        List<SoldierEntity> out = new ArrayList<>();
        for (Map.Entry<SoldierKind, Integer> e : BaseGarrison.quota(lv).entrySet()) {
            int n = e.getValue() - shortBy.getOrDefault(e.getKey(), 0);
            for (int i = 0; i < n; i++) {
                out.add(dummy(level, e.getKey()));
            }
        }
        return out;
    }

    /** 読み込まれている、その基地の兵士。 */
    private static List<SoldierEntity> soldiers(ServerLevel level, BaseSite site) {
        List<SoldierEntity> out = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof SoldierEntity s && s.siteId() == site.id && !s.isRemoved()) {
                out.add(s);
            }
        }
        return out;
    }

    /** 値段は設計値(野戦病院は割引)、名簿の欠けと、次に補充する兵種(定員に対していちばん足りない割合)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void pricesAndPicksFollowDesign(GameTestHelper helper) {
        DefenseTable t = DesignData.defense();
        for (SoldierKind k : SoldierKind.values()) {
            int base = t.baseReplenishCost(k.id);
            helper.assertTrue(base > 0, k.id + " の補充の値段が 0");
            helper.assertTrue(BaseGarrison.price(k, BaseType.DEFENSE) == base, k.id + " の値段が設計値と違う");
            int hospital = (int) Math.round(base * (1.0D - t.baseReplenishHospitalDiscount()));
            helper.assertTrue(BaseGarrison.price(k, BaseType.HOSPITAL) == hospital && hospital < base, k.id + " の野戦病院の値段が割り引かれない");
        }
        helper.assertTrue(t.baseReplenishIntervalTicks() >= 20, "補充の間隔が短すぎる");

        ServerLevel level = helper.getLevel();
        BaseSites data = BaseSites.get(level.getServer());
        BaseSite site = addSite(level, LEDGER_AT, 4, BaseType.DEFENSE);
        try {
            helper.assertTrue(!site.garrisoned() && site.rosterKnown() && site.rosterSize() == 0, "新しい基地の名簿が空でない");
            helper.assertTrue(BaseGarrison.missing(site) == BaseGarrison.quotaTotal(4), "新しい基地の欠けが定員と違う");
            BaseGarrison.setRoster(data, site, rosterShort(level, 4, Map.of(SoldierKind.ELITE, 1)));
            helper.assertTrue(BaseGarrison.missing(site) == 1 && BaseGarrison.nextKind(site) == SoldierKind.ELITE, "エリートが 1 欠けなのにエリートを選ばない");
            Map<SoldierKind, Integer> q = BaseGarrison.quota(4);
            BaseGarrison.setRoster(data, site, rosterShort(level, 4, Map.of(SoldierKind.RIFLEMAN, 3, SoldierKind.ENGINEER, 1)));
            SoldierKind want = 3.0D / q.get(SoldierKind.RIFLEMAN) >= 1.0D / q.get(SoldierKind.ENGINEER) ? SoldierKind.RIFLEMAN : SoldierKind.ENGINEER;
            helper.assertTrue(BaseGarrison.nextKind(site) == want, "いちばん足りない割合の兵種を選ばない: " + BaseGarrison.nextKind(site) + " / " + want);
            helper.assertTrue(BaseGarrison.deficit(site).get(SoldierKind.RIFLEMAN) == 3 && BaseGarrison.deficit(site).get(SoldierKind.ENGINEER) == 1,
                    "欠けの数え方が違う");
            BaseGarrison.setRoster(data, site, rosterShort(level, 4, Map.of()));
            helper.assertTrue(BaseGarrison.nextKind(site) == null && BaseGarrison.missing(site) == 0, "定員どおりなのに補充する兵種がある");
        } finally {
            data.remove(site);
        }
        helper.succeed();
    }

    /**
     * 補充は貯蓄から払い、名簿に載る(持ち場は基地の範囲)。野戦病院は安い。貯蓄が足りなければ出さない。倒れたら名簿から消える。
     * 名簿は保存を通る。陥落したら名簿は空になり、補充しない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void replenishPaysAndRosterFollowsDeaths(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BaseSites data = BaseSites.get(server);
        BaseSite site = addSite(level, LEDGER_AT, 1, BaseType.DEFENSE);
        BaseSite hospital = addSite(level, LEDGER_AT.offset(512, 0, 0), 1, BaseType.HOSPITAL);
        List<SoldierEntity> spawned = new ArrayList<>();
        try {
            BaseGarrison.setRoster(data, site, List.of());
            BaseGarrison.setRoster(data, hospital, List.of());
            int stock = site.stock();
            SoldierKind kind = BaseGarrison.nextKind(site);
            BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
            SoldierEntity s = BaseGarrison.replenishAt(level, data, site, at, 0.0F);
            helper.assertTrue(s != null, "補充の兵士が出ない");
            s.setNoAi(true);
            spawned.add(s);
            helper.assertTrue(s.kind() == kind && s.siteId() == site.id && site.inRoster(s.getUUID()) && site.rosterSize() == 1,
                    "補充の兵士が名簿に載らない・兵種が違う");
            helper.assertTrue(site.stock() == stock - BaseGarrison.price(kind, site.type), "補充の値段が貯蓄から引かれない: " + stock + " → " + site.stock());
            helper.assertTrue(s.getRestrictCenter().equals(BaseGarrison.guardHome(site)) && Math.round(s.getRestrictRadius()) == BaseGarrison.guardRadius(site),
                    "補充の兵士の持ち場が基地の範囲でない");

            int hs = hospital.stock();
            SoldierEntity h = BaseGarrison.replenishAt(level, data, hospital, helper.absolutePos(new BlockPos(4, 2, 4)), 0.0F);
            helper.assertTrue(h != null, "野戦病院の補充の兵士が出ない");
            h.setNoAi(true);
            spawned.add(h);
            int hp = BaseGarrison.price(h.kind(), BaseType.HOSPITAL);
            helper.assertTrue(hospital.stock() == hs - hp && hp < DesignData.defense().baseReplenishCost(h.kind().id), "野戦病院の補充が安くない");

            BaseEconomy.setStock(data, site, BaseGarrison.price(BaseGarrison.nextKind(site), site.type) - 1);
            int before = site.rosterSize();
            helper.assertTrue(BaseGarrison.replenishAt(level, data, site, at, 0.0F) == null && site.rosterSize() == before, "貯蓄が足りないのに補充した");

            s.kill();
            helper.assertTrue(!site.inRoster(s.getUUID()) && site.rosterSize() == 0, "倒れた兵士が名簿に残っている");
            helper.assertTrue(BaseGarrison.missing(site) == BaseGarrison.quotaTotal(1), "倒れた分が欠けに戻らない");

            BaseSite copy = BaseSite.reloaded(hospital);
            helper.assertTrue(copy.rosterSize() == hospital.rosterSize() && copy.inRoster(h.getUUID()) && copy.rosterKnown() && copy.garrisoned(),
                    "名簿が保存を通らない");

            BaseEconomy.setStock(data, hospital, 5000);
            BaseEconomy.fall(server, data, hospital, BaseEconomy.Reason.COMMAND);
            helper.assertTrue(hospital.rosterSize() == 0 && h.isRemoved(), "陥落しても名簿が残る・兵士が消えない");
            helper.assertTrue(BaseGarrison.replenishAt(level, data, hospital, at, 0.0F) == null, "陥落した基地に補充した");
        } finally {
            for (SoldierEntity e : spawned) {
                if (!e.isRemoved()) {
                    e.discard();
                }
            }
            data.remove(site);
            data.remove(hospital);
        }
        helper.succeed();
    }

    /**
     * 留守中の補充(ユーザー指定 10-04「戻るまでに済ませておく」): 留守の日の精算で、減っていた分を 1 日に間隔で数えた数まで出す。
     * 守備兵を置いていない基地・プレイヤーがいた日は出さない。遠い基地の兵士はワールドに溜まるので、世界には出さない(名簿と貯蓄だけ見る)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void awayCatchUpFollowsDailyLimit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BaseSites data = BaseSites.get(server);
        BaseSite site = addSite(level, LEDGER_AT, 5, BaseType.DEFENSE);
        boolean dry = BaseGarrison.dryRunForTests;
        BaseGarrison.dryRunForTests = true;
        try {
            int quota = BaseGarrison.quotaTotal(5);
            int perDay = (int) (BaseEconomy.DAY / DesignData.defense().baseReplenishIntervalTicks());
            BaseEconomy.settleDays(server, data, site, site.lastDay() + 1, false, 0.0D);
            helper.assertTrue(site.rosterSize() == 0, "守備兵を置いていない基地に補充した");
            BaseGarrison.setRoster(data, site, List.of());
            BaseEconomy.settleDays(server, data, site, site.lastDay() + 1, true, 0.0D);
            helper.assertTrue(site.rosterSize() == 0, "プレイヤーがいた日に留守の補充をした(いる間は兵舎から 1 体ずつ)");

            int stock = site.stock();
            BaseEconomy.settleDays(server, data, site, site.lastDay() + 1, false, 0.0D);
            int first = site.rosterSize();
            helper.assertTrue(first == Math.min(quota, perDay), "留守の 1 日の補充が 1 日の上限と違う: " + first + " / " + perDay);
            int paid = 0;
            for (SoldierKind k : SoldierKind.values()) {
                paid += site.rosterCount(k) * BaseGarrison.price(k, site.type);
            }
            int cap = DesignData.defense().baseStockCap(5);
            int want = BaseEconomy.apply(stock, cap, site.lastRecovery(), 0) - paid;
            helper.assertTrue(site.stock() == want, "留守の補充の値段が貯蓄から引かれない: " + site.stock() + " / " + want);
            BaseEconomy.settleDays(server, data, site, site.lastDay() + 1, false, 0.0D);
            helper.assertTrue(BaseGarrison.missing(site) == 0 && site.rosterSize() == quota, "2 日目で定員まで戻らない: " + site.rosterSize() + " / " + quota);
            int rest = site.stock();
            BaseEconomy.settleDays(server, data, site, site.lastDay() + 1, false, 0.0D);
            helper.assertTrue(site.rosterSize() == quota && site.stock() >= rest, "定員どおりなのに留守の補充で貯蓄を使った");
        } finally {
            BaseGarrison.dryRunForTests = dry;
            data.remove(site);
        }
        helper.succeed();
    }

    /**
     * 守備兵を足す処理({@link BaseGarrison#reinforce}。10-02 からの残り「テストが無い」): 読み込まれた基地に定員どおり(兵種ごと)・
     * 名簿に載る・持ち場は基地の範囲・壁の中に出る・もう一度呼んでも足さない。名簿の食い違い(discard は名簿に届かない)は数え直しで直る。
     * 遠い基地のまわりを強制で読み込み、兵士は数えたらその tick のうちに消す。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void reinforceFillsQuotaAndCensusFixesRoster(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BaseSites data = BaseSites.get(level.getServer());
        BaseSite site = addSite(level, GARRISON_AT, 1, BaseType.DEFENSE);
        BlockPos home = BaseGarrison.guardHome(site);
        int r = BaseGarrison.guardRadius(site) + 16;
        int cx0 = (home.getX() - r) >> 4;
        int cx1 = (home.getX() + r) >> 4;
        int cz0 = (home.getZ() - r) >> 4;
        int cz1 = (home.getZ() + r) >> 4;
        Runnable cleanup = () -> {
            for (SoldierEntity s : soldiers(level, site)) {
                s.discard();
            }
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    level.setChunkForced(cx, cz, false);
                }
            }
            data.remove(site);
        };
        try {
            helper.assertTrue(!BaseGarrison.allLoaded(level, site), "前提: 試験の遠い基地がもう読み込まれている");
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    level.setChunkForced(cx, cz, true);
                }
            }
        } catch (RuntimeException e) {
            cleanup.run();
            throw e;
        }
        whenLoaded(helper, level, site, 160, () -> {
            try {
                helper.assertTrue(BaseGarrison.allLoaded(level, site), "前提: 強制で読み込んだ基地が全部読み込まれない");
                int quota = BaseGarrison.quotaTotal(1);
                int added = BaseGarrison.reinforce(level, site);
                List<SoldierEntity> found = soldiers(level, site);
                helper.assertTrue(added == quota && found.size() == quota && site.rosterSize() == quota && site.garrisoned(),
                        "定員まで足されない: 足した " + added + "・いる " + found.size() + "・名簿 " + site.rosterSize() + " / " + quota);
                for (Map.Entry<SoldierKind, Integer> e : BaseGarrison.quota(1).entrySet()) {
                    long n = found.stream().filter(s -> s.kind() == e.getKey()).count();
                    helper.assertTrue(n == e.getValue(), e.getKey().id + " の数が定員と違う: " + n + " / " + e.getValue());
                }
                for (SoldierEntity s : found) {
                    helper.assertTrue(site.inRoster(s.getUUID()), "足した兵士が名簿に無い");
                    helper.assertTrue(s.getRestrictCenter().equals(home) && Math.round(s.getRestrictRadius()) == BaseGarrison.guardRadius(site),
                            "足した兵士の持ち場が基地の範囲でない");
                    helper.assertTrue(site.contains(s.getBlockX(), s.getBlockZ(), 1), "兵士が壁の外に出た: " + s.blockPosition().toShortString());
                }
                helper.assertTrue(BaseGarrison.reinforce(level, site) == 0, "定員なのにまた足した");
                found.get(0).discard();
                helper.assertTrue(site.rosterSize() == quota, "discard で名簿が変わった(倒れたときだけ消える決まり)");
                helper.assertTrue(BaseGarrison.census(level, data, site) && site.rosterSize() == quota - 1, "数え直しで名簿が直らない: " + site.rosterSize());
                helper.succeed();
            } finally {
                cleanup.run();
            }
        });
    }

    /**
     * 持ち場の直し(10-02 からの残り「拡張したら前からいる兵士の持ち場も広げる」): 古い持ち場のまま読み込まれた兵士は今の範囲に直り、
     * 名簿に無ければ載る。読み込まれている兵士は {@link BaseGarrison#rehome}(広げ終えたとき)でまとめて直る。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void joinRehomesAndEnlists(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BaseSites data = BaseSites.get(level.getServer());
        BaseSite site = addSite(level, LEDGER_AT, 2, BaseType.DEFENSE);
        SoldierEntity s = dummy(level, SoldierKind.ENGINEER);
        try {
            BaseGarrison.setRoster(data, site, List.of());
            BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
            s.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D);
            s.setNoAi(true);
            s.guard(site.id, at, 5); // 古い持ち場(広げる前の範囲の写し)
            level.addFreshEntity(s);
            helper.assertTrue(s.getRestrictCenter().equals(BaseGarrison.guardHome(site)) && Math.round(s.getRestrictRadius()) == BaseGarrison.guardRadius(site),
                    "読み込まれた兵士の持ち場が今の範囲に直らない");
            helper.assertTrue(site.inRoster(s.getUUID()), "名簿に無い兵士が読み込まれても名簿に載らない");
            s.guard(site.id, at, 5);
            helper.assertTrue(BaseGarrison.rehome(level, site) >= 1 && s.getRestrictCenter().equals(BaseGarrison.guardHome(site)),
                    "広げ終えたときの持ち場の直しが効かない");
        } finally {
            if (!s.isRemoved()) {
                s.discard();
            }
            data.remove(site);
        }
        helper.succeed();
    }

    /**
     * 避難場所(ユーザー指定 10-01): 陥落していない基地の壁の中(外周の帯を含む)だけで、過繁茂の Mob とバニラの敵対 Mob の世界の湧きを止める。
     * 動物・兵士・スポナー・スポーンエッグ・レイドの苗床(置き場所で避ける)・壁の外は止めない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void refugeBlocksWorldSpawnsInsideWalls(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BaseSites data = BaseSites.get(server);
        BaseSite site = addSite(level, LEDGER_AT, 1, BaseType.DEFENSE);
        try {
            BasePlan plan = site.plan(1);
            int[] deep = null;
            int[] band = null;
            int[] outside = null;
            for (int x = plan.minX(); x <= plan.maxX(); x++) {
                for (int z = plan.minZ(); z <= plan.maxZ(); z++) {
                    if (deep == null && plan.inDist(x, z) > 10) {
                        deep = new int[]{x, z};
                    }
                    if (band == null && plan.inDist(x, z) == 1) {
                        band = new int[]{x, z};
                    }
                    if (outside == null && plan.outDist(x, z) == 3) {
                        outside = new int[]{x, z};
                    }
                }
            }
            if (deep == null || band == null || outside == null) {
                helper.fail("基地の中・外周の帯・外のマスが見つからない");
                return;
            }
            BlockPos in = site.toWorld(deep[0], 64, deep[1]);
            BlockPos wall = site.toWorld(band[0], 64, band[1]);
            BlockPos out = site.toWorld(outside[0], 64, outside[1]);
            helper.assertTrue(BaseRefuge.covers(level, in.getX() + 0.5D, in.getZ() + 0.5D) && BaseRefuge.siteAt(level, in.getX(), in.getZ()) == site,
                    "壁の中が避難場所でない");
            helper.assertTrue(BaseRefuge.covers(level, wall.getX() + 0.5D, wall.getZ() + 0.5D), "外周の帯が避難場所でない");
            helper.assertTrue(!BaseRefuge.covers(level, out.getX() + 0.5D, out.getZ() + 0.5D), "壁の外が避難場所になっている");

            // バニラの自然湧き(作る前の下調べ)
            helper.assertTrue(placement(level, EntityType.ZOMBIE, MobSpawnType.NATURAL, in) == MobSpawnEvent.SpawnPlacementCheck.Result.FAIL,
                    "壁の中でバニラの敵が自然に湧く");
            helper.assertTrue(placement(level, ModEntities.PARASITIZED_COW.get(), MobSpawnType.NATURAL, in) == MobSpawnEvent.SpawnPlacementCheck.Result.FAIL,
                    "壁の中で過繁茂の Mob が自然に湧く");
            helper.assertTrue(placement(level, EntityType.COW, MobSpawnType.NATURAL, in) == MobSpawnEvent.SpawnPlacementCheck.Result.DEFAULT,
                    "壁の中で動物まで止めている");
            helper.assertTrue(placement(level, EntityType.ZOMBIE, MobSpawnType.SPAWNER, in) == MobSpawnEvent.SpawnPlacementCheck.Result.DEFAULT,
                    "スポナーまで止めている");
            helper.assertTrue(placement(level, EntityType.ZOMBIE, MobSpawnType.NATURAL, out) == MobSpawnEvent.SpawnPlacementCheck.Result.DEFAULT,
                    "壁の外の湧きを止めている");

            // それ以外の世界の湧き(この MOD の湧かせ方は create(..., MobSpawnType, ...) を通る。取り消されると null で、finalizeSpawn も走らない)
            helper.assertTrue(EntityType.ZOMBIE.create(level, null, in, MobSpawnType.EVENT, false, false) == null, "壁の中でイベントの敵が湧く");
            helper.assertTrue(ModEntities.PARASITIZED_COW.get().create(level, null, in, MobSpawnType.MOB_SUMMONED, false, false) == null,
                    "壁の中で呼ばれた過繁茂の Mob が湧く");
            // 止めないもの(finalizeSpawn の余計な動き —— ゾンビの騎乗用のニワトリなど —— を遠いチャンクに残さないよう、イベントだけ流す)
            helper.assertTrue(!cancelled(level, EntityType.ZOMBIE, MobSpawnType.SPAWN_EGG, in), "スポーンエッグまで止めている");
            helper.assertTrue(!cancelled(level, EntityType.ZOMBIE, MobSpawnType.SPAWNER, in), "スポナーまで止めている(作ったあと)");
            helper.assertTrue(!cancelled(level, ModEntities.OVERGROWN_NEXUS.get(), MobSpawnType.EVENT, in), "レイドの苗床の湧きを取り消した(置き場所で避ける決まり)");
            helper.assertTrue(!cancelled(level, AllyEntities.type(SoldierKind.RIFLEMAN), MobSpawnType.EVENT, in), "兵士まで止めている");
            helper.assertTrue(!cancelled(level, EntityType.ZOMBIE, MobSpawnType.EVENT, out), "壁の外のイベントの敵を止めている");
            helper.assertTrue(cancelled(level, EntityType.SKELETON, MobSpawnType.PATROL, in), "壁の中でパトロールの敵が湧く");
            helper.assertTrue(BaseRefuge.blocksSpawn(level, ModEntities.PARASITIZED_COW.get(), in)
                    && !BaseRefuge.blocksSpawn(level, ModEntities.BLIGHT_FERN.get(), in), "死骸の孵化の下調べが違う(シダは止めない)");

            BaseEconomy.fall(server, data, site, BaseEconomy.Reason.COMMAND);
            helper.assertTrue(!BaseRefuge.covers(level, in.getX() + 0.5D, in.getZ() + 0.5D), "陥落した基地が避難場所のまま");
        } finally {
            data.remove(site);
        }
        helper.succeed();
    }

    /** 世界の湧きの最後のイベントを流して、湧きが取り消されたかを返す(作るだけで世界には出さず、finalizeSpawn も呼ばない)。 */
    private static boolean cancelled(ServerLevel level, EntityType<? extends Mob> type, MobSpawnType spawnType, BlockPos pos) {
        Mob mob = type.create(level);
        if (mob == null) {
            throw new IllegalStateException("作れない: " + type);
        }
        mob.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        NeoForge.EVENT_BUS.post(new FinalizeSpawnEvent(mob, level, mob.getX(), mob.getY(), mob.getZ(), level.getCurrentDifficultyAt(pos),
                spawnType, null, null));
        return mob.isSpawnCancelled();
    }

    /** 自然湧きの下調べのイベントを流して、答えを返す。 */
    private static MobSpawnEvent.SpawnPlacementCheck.Result placement(ServerLevel level, EntityType<?> type, MobSpawnType spawnType, BlockPos pos) {
        MobSpawnEvent.SpawnPlacementCheck event = new MobSpawnEvent.SpawnPlacementCheck(type, level, spawnType, pos, level.getRandom(), true);
        NeoForge.EVENT_BUS.post(event);
        return event.getResult();
    }

    private BaseGarrisonGameTests() {
    }
}
