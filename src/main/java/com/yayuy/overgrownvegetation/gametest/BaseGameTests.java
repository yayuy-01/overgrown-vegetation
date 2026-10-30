package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.base.BaseBlocks;
import com.yayuy.overgrownvegetation.base.BaseTerminalBlockEntity;
import com.yayuy.overgrownvegetation.base.build.BaseCommand;
import com.yayuy.overgrownvegetation.base.build.BaseConstruction;
import com.yayuy.overgrownvegetation.base.build.BasePurifiers;
import com.yayuy.overgrownvegetation.base.build.BaseSite;
import com.yayuy.overgrownvegetation.base.build.BaseSites;
import com.yayuy.overgrownvegetation.base.plan.BaseEmitter;
import com.yayuy.overgrownvegetation.base.plan.BasePlan;
import com.yayuy.overgrownvegetation.base.plan.BasePlanner;
import com.yayuy.overgrownvegetation.base.plan.BaseTerrain;
import com.yayuy.overgrownvegetation.base.plan.Blueprint;
import com.yayuy.overgrownvegetation.base.plan.Blueprints;
import com.yayuy.overgrownvegetation.base.plan.BuildingKind;
import com.yayuy.overgrownvegetation.base.plan.Plot;
import com.yayuy.overgrownvegetation.base.plan.States;
import com.yayuy.overgrownvegetation.base.plan.Surface;
import com.yayuy.overgrownvegetation.block.SporeDeviceBlockEntity;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 同盟軍の基地(2026-10-01、{@code base.plan} と {@code base.build})のテスト。
 *
 * <p>守りたいもの: 設計図と書き出すブロック状態の文字列がすべてゲームで読める(書き損じは黙って空気になる)/ 配置計画の決まり
 * (建物が重ならない・区画の敷地の中・道の上に建たない・レベルを上げても区画と内側の建物が変わらない・設計図の大きさが区画と同じ)/
 * レベル 1 を建てて管理端末が基地に結び付き、金網がまっすぐつながる(十字にならない)、レベル 2 に広げると中へ入った金網が消えて
 * 新しい外周に金網が建ち、外の水が残る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class BaseGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(BaseGameTests.class);
        }

        private Registration() {
        }
    }

    private static final BaseTerrain FLAT = BaseTerrain.sample((x, z) -> 64);

    private static boolean defense(Plot p) {
        return p.kind() == BuildingKind.WATCHTOWER || p.kind() == BuildingKind.GATEHOUSE || p.kind() == BuildingKind.CHECKPOINT;
    }

    /** 全レベルの全区画の設計図(4 向き)・建物の種類全部の設計図と、平地のレベル 1・4 の書き出し(外周・つながりを決めた状態)が、ゲームの BlockStateParser で読める。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void blueprintStatesParse(GameTestHelper helper) {
        Set<String> states = new HashSet<>();
        for (int lv = 1; lv <= BasePlan.MAX_LEVEL; lv++) {
            BasePlan plan = BasePlanner.plan(3, lv, FLAT);
            for (Plot p : plan.plots) {
                Blueprint bp = Blueprints.build(p, lv);
                for (String s : bp.palette()) {
                    if (!s.isEmpty()) {
                        for (int t = 0; t < 4; t++) {
                            states.add(States.rotate(s, t));
                        }
                    }
                }
            }
        }
        for (int lv : new int[]{1, 4}) {
            BasePlan plan = BasePlanner.plan(3, lv, FLAT);
            new BaseEmitter(plan, FLAT).emit(plan.minX(), plan.minZ(), plan.maxX(), plan.maxZ(), new BaseEmitter.Natural() {
                public int groundY(int x, int z) {
                    return 64;
                }

                public int surfaceY(int x, int z) {
                    return 64;
                }
            }, (x, z) -> true, p -> true, (x, y, z, s) -> {
                if (!s.equals(BaseEmitter.SOFT_AIR)) {
                    states.add(s);
                }
            });
        }
        for (Surface s : Surface.values()) {
            states.add(s.top);
        }
        // 建物の種類を全部(浄化の基地には建たない特別な施設 —— 医療テント・野外研究施設・観測施設、2026-10-02 夜 —— も)。大きさの決まっていない種類は 10 × 9
        for (BuildingKind kind : BuildingKind.values()) {
            int w = kind.width > 0 ? kind.width : 10;
            int d = kind.depth > 0 ? kind.depth : 9;
            for (int lv = 1; lv <= BasePlan.MAX_LEVEL; lv++) {
                Blueprint bp = Blueprints.build(new Plot(kind, com.yayuy.overgrownvegetation.base.plan.Zone.CORE, lv, 0, 0, w - 1, d - 1,
                        com.yayuy.overgrownvegetation.base.plan.Facing.SOUTH, 42L + kind.ordinal()), lv);
                for (String s : bp.palette()) {
                    if (!s.isEmpty()) {
                        for (int t = 0; t < 4; t++) {
                            states.add(States.rotate(s, t));
                        }
                    }
                }
            }
        }
        var lookup = helper.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK);
        Set<String> bad = new TreeSet<>();
        for (String s : states) {
            try {
                BlockState st = BlockStateParser.parseForBlock(lookup, s, false).blockState();
                for (var e : States.props(s).entrySet()) {
                    var prop = st.getBlock().getStateDefinition().getProperty(e.getKey());
                    if (prop == null || !valueName(st, prop).equals(e.getValue())) {
                        bad.add(s + "(" + e.getKey() + ")");
                    }
                }
            } catch (Exception ex) {
                bad.add(s);
            }
        }
        if (!bad.isEmpty()) {
            helper.fail("読めないブロック状態 " + bad.size() + ": " + String.join(", ", bad));
            return;
        }
        helper.succeed();
    }

    /** 配置計画の決まり(浄化は種 3 つ、ほかの種類は種 1 × レベル 1〜5、平地)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100)
    public static void planInvariants(GameTestHelper helper) {
        // 種類ごとの広げ方(2026-10-02): 浄化は種 3 つ、ほかの種類は種 1
        for (com.yayuy.overgrownvegetation.base.plan.BaseType type : com.yayuy.overgrownvegetation.base.plan.BaseType.values()) {
        for (long seed : type == com.yayuy.overgrownvegetation.base.plan.BaseType.PURIFICATION ? new long[]{1, 2, 12345} : new long[]{1}) {
            BasePlan prev = null;
            for (int lv = 1; lv <= BasePlan.MAX_LEVEL; lv++) {
                BasePlan plan = BasePlanner.plan(seed, lv, FLAT, type);
                List<Plot> ps = plan.plots;
                for (int i = 0; i < ps.size(); i++) {
                    Plot q = ps.get(i);
                    for (int j = i + 1; j < ps.size(); j++) {
                        if (q.overlaps(ps.get(j), 0)) {
                            helper.fail("重なり L" + lv + ": " + q.kind() + " と " + ps.get(j).kind());
                            return;
                        }
                    }
                    Blueprint bp = Blueprints.build(q, lv);
                    if (bp.w != q.sizeX() || bp.d != q.sizeZ()) {
                        helper.fail("設計図の大きさが区画と違う: " + q.kind() + " " + bp.w + "×" + bp.d + " / " + q.sizeX() + "×" + q.sizeZ());
                        return;
                    }
                    if (defense(q)) {
                        continue;
                    }
                    for (int x = q.x0(); x <= q.x1(); x++) {
                        for (int z = q.z0(); z <= q.z1(); z++) {
                            if (plan.inDist(x, z) < 4) { // 外周の帯と空きにはかからない(区画どうしのあいだの小物も)
                                helper.fail("外周の帯にかかる L" + lv + ": " + q.kind() + " @" + x + "," + z);
                                return;
                            }
                            Surface s = plan.surfaceAt(x, z);
                            if (s == Surface.ROAD || s == Surface.ROAD_LINE || s == Surface.PATH || s == Surface.APRON) {
                                helper.fail("道の上に建つ L" + lv + ": " + q.kind() + " @" + x + "," + z);
                                return;
                            }
                        }
                    }
                }
                if (prev != null) {
                    if (!plan.lobes.subList(0, prev.lobes.size()).equals(prev.lobes)) {
                        helper.fail("レベルを上げたら浄化圏が変わった L" + lv);
                        return;
                    }
                    Set<Plot> now = new HashSet<>(ps);
                    for (Plot q : prev.plots) {
                        if (!defense(q) && !now.contains(q)) {
                            helper.fail("レベルを上げたら内側の建物が変わった L" + lv + ": " + q);
                            return;
                        }
                    }
                }
                // 特別な施設(2026-10-02 夜、ユーザー指定): 観測・研究・野戦病院はレベル 2 から観測施設・野外研究施設・医療テントがある
                if (type.special != null && lv >= 2) {
                    com.yayuy.overgrownvegetation.base.plan.BuildingKind facility =
                            com.yayuy.overgrownvegetation.base.plan.BaseType.facility(type.special);
                    if (ps.stream().noneMatch(q -> q.kind() == facility)) {
                        helper.fail(type.label + "の基地 L" + lv + " に " + facility.label + " が無い");
                        return;
                    }
                }
                // 研究所の増築(同日その 3、ユーザー指定「出城が拡張される際、研究施設も一緒に拡張される」): 平地のレベル 3 からは増築の棟があり、棟ごとに連絡通路
                if (type == com.yayuy.overgrownvegetation.base.plan.BaseType.RESEARCH && lv >= 3) {
                    long annexes = ps.stream().filter(q -> q.kind() == com.yayuy.overgrownvegetation.base.plan.BuildingKind.ANNEX_LAB
                            || q.kind() == com.yayuy.overgrownvegetation.base.plan.BuildingKind.TEST_HALL).count();
                    long walkways = ps.stream().filter(q -> q.kind() == com.yayuy.overgrownvegetation.base.plan.BuildingKind.RESEARCH_WALKWAY).count();
                    if (annexes < lv - 2 || walkways != annexes) {
                        helper.fail("研究の基地 L" + lv + " の増築の棟 " + annexes + "・連絡通路 " + walkways);
                        return;
                    }
                }
                prev = plan;
            }
        }
        }
        helper.succeed();
    }

    /**
     * 胞子除去装置を後から置く時刻(ユーザー指定 2026-10-02「フェーズ 2 開始からゲーム内の 1 日後」)。手で作った暦で見る ——
     * 世界のフェーズは触らない(GameTest のワールドの phase.dat に残る)。
     */
    @GameTest(template = "gametest_empty7")
    public static void purifierDelayRule(GameTestHelper helper) {
        long[] clocks = new long[11];
        java.util.Arrays.fill(clocks, -1L);
        clocks[1] = 100L;
        helper.assertTrue(BasePurifiers.since(clocks) == -1L, "フェーズ 2 以上の記録が無いのに時刻がある");
        clocks[3] = 5000L; // フェーズ 1 → 3 と飛ばして上げた(2 の時刻は残らない)
        helper.assertTrue(BasePurifiers.since(clocks) == 5000L, "飛ばして上げたフェーズの時刻を拾わない");
        clocks[2] = 4000L;
        helper.assertTrue(BasePurifiers.since(clocks) == 4000L, "フェーズ 2 の時刻を拾わない");
        helper.assertTrue(!BasePurifiers.due(1, 4000L, 999_999L), "フェーズ 1 で後から置く");
        helper.assertTrue(!BasePurifiers.due(2, 4000L, 4000L + 23_999L), "1 日たつ前に後から置く");
        helper.assertTrue(BasePurifiers.due(2, 4000L, 4000L + 24_000L), "1 日たっても後から置かない");
        helper.assertTrue(BasePurifiers.due(5, 4000L, 4000L + 30_000L), "フェーズ 5 で後から置かない");
        helper.assertTrue(!BasePurifiers.due(2, -1L, 999_999L), "時刻が分からないのに後から置く");
        helper.succeed();
    }

    /**
     * 決まった遠くの座標(25600, 地面, 25600)にレベル 1 を建てて、管理端末が基地に結び付き、北の辺の金網が東西にだけつながる
     * (十字にならない)こと。レベル 2 に広げると、中へ入った金網が消え、新しい外周に金網が建ち、払う幅の中に置いた外の水が残ること。
     * 胞子除去装置を「置かない」(フェーズ 2 より前)で載せて、除去装置の場所が空いていることと、後から置くと燃料・フィルター入りで建つこと。
     * 台帳からは最後に外す(GameTest のワールドに残さない)。同じ tick で終える(後付けの見回り {@link BasePurifiers} と混ざらない)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void levelOneBaseThenUpgrade(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos arena = helper.absolutePos(BlockPos.ZERO);
        BlockPos center = new BlockPos(25600, arena.getY(), 25600); // 毎回同じ場所(テスト場の位置は実行ごとに変わり、ずらすとワールドにチャンクが溜まる)
        BaseSites data = BaseSites.get(level.getServer());
        BaseTerrain terrain = BaseCommand.sampleNoise(level, center, 0);
        BaseSite site = data.add(level.dimension(), center, 0, 77L, 1, terrain, false, com.yayuy.overgrownvegetation.base.plan.BaseType.PURIFICATION);
        boolean garrison = com.yayuy.overgrownvegetation.base.build.BaseGarrison.enabled;
        com.yayuy.overgrownvegetation.base.build.BaseGarrison.enabled = false; // 遠いチャンクの兵士は数えられず、実行のたびにワールドに溜まる
        com.yayuy.overgrownvegetation.ally.SoldierEntity veteran = null;
        try {
            BasePlan plan1 = site.plan(1);
            BasePlan plan2 = site.plan(2);
            // 外の水: レベル 1 とレベル 2 のどちらでも外で、払う幅の中(鉄条網の輪と建物は避ける)
            BlockPos pool = null;
            for (int r = 3; r <= 9 && pool == null; r++) {
                for (int x = plan2.minX(); x <= plan2.maxX() && pool == null; x++) {
                    for (int z = plan2.minZ(); z <= plan2.maxZ() && pool == null; z++) {
                        if (plan2.outDist(x, z) == r && r != BasePlan.WIRE_OFFSET && plan1.outDist(x, z) <= BasePlan.CLEAR_MARGIN
                                && plan2.plotAt(x, z) == null && plan1.plotAt(x, z) == null
                                && plan2.surfaceAt(x, z) == Surface.NATURAL && plan1.surfaceAt(x, z) == Surface.NATURAL) {
                            BlockPos w = site.toWorld(x, 0, z);
                            level.getChunk(w.getX() >> 4, w.getZ() >> 4); // 高さ地図を読む前に作る(作っていないと最低の高さを答える)
                            int g = level.getHeight(Heightmap.Types.WORLD_SURFACE, w.getX(), w.getZ()) - 1;
                            pool = new BlockPos(w.getX(), g, w.getZ());
                        }
                    }
                }
            }
            if (pool == null) {
                helper.fail("外の水を置く場所が見つからない");
                return;
            }
            level.setBlock(pool, Blocks.WATER.defaultBlockState(), 2);

            BaseConstruction.start(data, site, 0, 1);
            BaseConstruction.runToEnd(level, data, site);
            BaseEmitter em1 = site.emitter(1);
            Plot hq = plan1.plots.stream().filter(p -> p.kind() == BuildingKind.HQ).findFirst().orElseThrow();
            BlockPos terminal = site.toWorld(hq.x0() + 7, terrain.pad(hq) + 1, hq.z0() + 1);
            if (!level.getBlockState(terminal).is(BaseBlocks.BASE_TERMINAL.get())) {
                helper.fail("管理端末が無い: " + terminal.toShortString() + " = " + level.getBlockState(terminal));
                return;
            }
            if (!(level.getBlockEntity(terminal) instanceof BaseTerminalBlockEntity be) || be.siteId() != site.id) {
                helper.fail("管理端末が基地に結び付いていない");
                return;
            }
            // 北の辺のまっすぐな所の金網(支柱でも塔の隣でもないマス)は東西にだけ、斜めの辺の階段の金網は外側の列の隣 2 方向にだけつながる
            int[] straight = null;
            int[] step = null;
            for (int x = plan1.minX() + 1; x < plan1.maxX(); x++) {
                for (int z = plan1.minZ() + 1; z < 0; z++) {
                    if (!plan1.isOuterRow(x, z) || !plan1.isPerimeter(x, z) || plan1.isFencePost(x, z)
                            || plan1.plotAt(x - 1, z) != null || plan1.plotAt(x + 1, z) != null || plan1.plotAt(x, z - 1) != null || plan1.plotAt(x, z + 1) != null) {
                        continue;
                    }
                    boolean h = plan1.isOuterRow(x - 1, z) && plan1.isOuterRow(x + 1, z);
                    boolean v = plan1.isOuterRow(x, z - 1) || plan1.isOuterRow(x, z + 1);
                    if (straight == null && h && !v && z == -BasePlanner.CORE_R) {
                        straight = new int[]{x, z};
                    }
                    if (step == null && (plan1.isOuterRow(x - 1, z) != plan1.isOuterRow(x + 1, z)) && (plan1.isOuterRow(x, z - 1) != plan1.isOuterRow(x, z + 1))) {
                        step = new int[]{x, z};
                    }
                }
            }
            if (straight == null || step == null) {
                helper.fail("レベル 1 の外周に、見るマス(まっすぐな辺・斜めの辺)が無い");
                return;
            }
            int t1 = em1.target(straight[0], straight[1]);
            BlockPos fence = site.toWorld(straight[0], t1 + 2, straight[1]);
            BlockState fs = level.getBlockState(fence);
            if (!fs.is(BaseBlocks.CHAIN_LINK_FENCE.get())) {
                helper.fail("レベル 1 の北の辺に金網が無い: " + fence.toShortString() + " = " + fs);
                return;
            }
            if (!fs.getValue(IronBarsBlock.EAST) || !fs.getValue(IronBarsBlock.WEST) || fs.getValue(IronBarsBlock.NORTH) || fs.getValue(IronBarsBlock.SOUTH)) {
                helper.fail("金網のつながりが東西だけでない(十字): " + fs);
                return;
            }
            if (!level.getBlockState(site.toWorld(straight[0], t1 + BasePlan.FENCE_HEIGHT + 1, straight[1])).is(com.yayuy.overgrownvegetation.registry.ModBlocks.BARBED_WIRE.get())) {
                helper.fail("金網の上に有刺鉄線が無い");
                return;
            }
            BlockState ss = level.getBlockState(site.toWorld(step[0], em1.target(step[0], step[1]) + 2, step[1]));
            if (!ss.is(BaseBlocks.CHAIN_LINK_FENCE.get())
                    || ss.getValue(IronBarsBlock.WEST) != plan1.isOuterRow(step[0] - 1, step[1]) || ss.getValue(IronBarsBlock.EAST) != plan1.isOuterRow(step[0] + 1, step[1])
                    || ss.getValue(IronBarsBlock.NORTH) != plan1.isOuterRow(step[0], step[1] - 1) || ss.getValue(IronBarsBlock.SOUTH) != plan1.isOuterRow(step[0], step[1] + 1)) {
                helper.fail("斜めの辺の金網のつながりが外側の列と合わない @" + step[0] + "," + step[1] + ": " + ss);
                return;
            }
            if (!level.getBlockState(pool).is(Blocks.WATER)) {
                helper.fail("レベル 1 を建てたら外の水が消えた: " + pool.toShortString() + " = " + level.getBlockState(pool));
                return;
            }
            // 見張り台の屋根の機関砲台(弾 2 スタック入り)
            Plot tower = plan1.plots.stream().filter(p -> p.kind() == BuildingKind.WATCHTOWER).findFirst().orElseThrow();
            BlockPos gun = site.toWorld(tower.x0() + 2, terrain.pad(tower) + 14, tower.z0() + 2);
            if (!(level.getBlockEntity(gun) instanceof com.yayuy.overgrownvegetation.block.GunTurretBlockEntity turret) || turret.ammoCount() <= 0) {
                helper.fail("見張り台の屋根に弾の入った機関砲台が無い: " + gun.toShortString() + " = " + level.getBlockState(gun));
                return;
            }
            // 内側の柵(巡回路と中の境目、2026-10-02)。レベル 2 で中へ入る所を選んでおく
            int[] inner = null;
            for (int x = plan1.minX(); x <= plan1.maxX() && inner == null; x++) {
                for (int z = plan1.minZ(); z <= plan1.maxZ(); z++) {
                    if (em1.innerFence(x, z) == 1 && plan2.inDist(x, z) > BasePlan.INNER_FENCE + 2 && plan2.plotAt(x, z) == null) {
                        inner = new int[]{x, z};
                        break;
                    }
                }
            }
            if (inner == null) {
                helper.fail("レベル 2 で中へ入る内側の柵が無い");
                return;
            }
            BlockPos innerFence = site.toWorld(inner[0], em1.target(inner[0], inner[1]) + 2, inner[1]);
            if (!level.getBlockState(innerFence).is(BaseBlocks.CHAIN_LINK_FENCE.get())) {
                helper.fail("内側の柵が無い: " + innerFence.toShortString() + " = " + level.getBlockState(innerFence));
                return;
            }
            // 胞子除去装置(ユーザー指定 2026-10-02「フェーズが 2 になるまでは基地に胞子除去装置を置かない」): 大除去塔の除去装置の場所は空気、
            // 後から置くと燃料・フィルター入りの除去装置が建つ
            Plot bigTower = plan1.plots.stream().filter(p -> p.kind() == BuildingKind.PURIFIER_TOWER).findFirst().orElseThrow();
            BlockPos purifier = site.toWorld(bigTower.x0() + 3, terrain.pad(bigTower) + 20, bigTower.z0() + 3);
            if (!level.getBlockState(purifier).isAir()) {
                helper.fail("フェーズ 2 より前の基地に胞子除去装置がある: " + purifier.toShortString() + " = " + level.getBlockState(purifier));
                return;
            }
            BasePurifiers.installNow(level, data, site);
            if (!site.purifiers() || !(level.getBlockEntity(purifier) instanceof SporeDeviceBlockEntity device)
                    || device.getItem(SporeDeviceBlockEntity.SLOT_FILTER).isEmpty() || !device.baseSupplied()) {
                helper.fail("後から置いた胞子除去装置が無いか、フィルターが入っていないか、基地の装置(電池なし)になっていない: " + purifier.toShortString() + " = " + level.getBlockState(purifier));
                return;
            }

            // レベル 2 へ: 中へ入った外周と新しい外周を計画から選ぶ
            int[] gone = null;
            int[] fresh = null;
            for (int x = plan2.minX(); x <= plan2.maxX(); x++) {
                for (int z = plan2.minZ(); z <= plan2.maxZ(); z++) {
                    if (gone == null && plan1.isOuterRow(x, z) && plan1.isPerimeter(x, z) && !plan1.isFencePost(x, z)
                            && plan2.inDist(x, z) > 2 && plan2.plotAt(x, z) == null) {
                        gone = new int[]{x, z};
                    }
                    if (fresh == null && plan2.isOuterRow(x, z) && plan2.isPerimeter(x, z) && !plan2.isFencePost(x, z)
                            && !plan1.inside(x, z) && plan2.plotAt(x, z) == null) {
                        fresh = new int[]{x, z};
                    }
                }
            }
            if (gone == null || fresh == null) {
                helper.fail("レベル 2 で中へ入る外周か新しい外周が無い(区画が継ぎ足されていない?)");
                return;
            }
            BlockPos goneFence = site.toWorld(gone[0], em1.target(gone[0], gone[1]) + 2, gone[1]);
            // 前からいる兵士(順 2 の着手順 3「拡張したら前からいる兵士の持ち場も広げる」): テスト場の中に、レベル 1 の持ち場で置いておく
            BlockPos oldHome = com.yayuy.overgrownvegetation.base.build.BaseGarrison.guardHome(site);
            int oldRadius = com.yayuy.overgrownvegetation.base.build.BaseGarrison.guardRadius(site);
            veteran = AllyGameTests.spawnSoldier(helper, com.yayuy.overgrownvegetation.ally.SoldierKind.RIFLEMAN, new BlockPos(3, 2, 3));
            veteran.setNoAi(true);
            veteran.guard(site.id, oldHome, oldRadius);
            BaseConstruction.start(data, site, 1, 2);
            BaseConstruction.runToEnd(level, data, site);
            if (site.level() != 2) {
                helper.fail("広げたあとのレベルが 2 でない: " + site.level());
                return;
            }
            BlockPos newHome = com.yayuy.overgrownvegetation.base.build.BaseGarrison.guardHome(site);
            int newRadius = com.yayuy.overgrownvegetation.base.build.BaseGarrison.guardRadius(site);
            if (newRadius == oldRadius && newHome.equals(oldHome)) {
                helper.fail("前提: レベル 2 で持ち場の範囲が変わらない");
                return;
            }
            if (!veteran.getRestrictCenter().equals(newHome) || Math.round(veteran.getRestrictRadius()) != newRadius) {
                helper.fail("広げても前からいる兵士の持ち場が古いまま: " + veteran.getRestrictCenter().toShortString() + " 半径 "
                        + veteran.getRestrictRadius() + "(今の範囲 " + newHome.toShortString() + " 半径 " + newRadius + ")");
                return;
            }
            if (level.getBlockState(goneFence).is(BaseBlocks.CHAIN_LINK_FENCE.get())) {
                helper.fail("広げても中へ入った金網が残っている: " + goneFence.toShortString());
                return;
            }
            if (level.getBlockState(innerFence).is(BaseBlocks.CHAIN_LINK_FENCE.get())) {
                helper.fail("広げても中へ入った内側の柵が残っている: " + innerFence.toShortString());
                return;
            }
            BlockPos freshFence = site.toWorld(fresh[0], site.emitter(2).target(fresh[0], fresh[1]) + 2, fresh[1]);
            if (!level.getBlockState(freshFence).is(BaseBlocks.CHAIN_LINK_FENCE.get())) {
                helper.fail("レベル 2 の新しい外周に金網が無い: " + freshFence.toShortString() + " = " + level.getBlockState(freshFence));
                return;
            }
            if (!level.getBlockState(pool).is(Blocks.WATER)) {
                helper.fail("広げたら外の水が消えた: " + pool.toShortString() + " = " + level.getBlockState(pool));
                return;
            }
            if (!level.getBlockState(terminal).is(BaseBlocks.BASE_TERMINAL.get())) {
                helper.fail("広げたら管理端末が消えた");
                return;
            }
            helper.succeed();
        } finally {
            if (veteran != null && !veteran.isRemoved()) {
                veteran.discard();
            }
            data.remove(site);
            com.yayuy.overgrownvegetation.base.build.BaseGarrison.enabled = garrison;
        }
    }

    /**
     * 工事で機関砲台・胞子除去装置を別のブロックに置き換えても、中身が地面にばらまかれない(ユーザー指摘 2026-10-02
     * 「拡張時に機関砲や胞子除去装置が消されたとき中身がぶちまける」)。置き換えの口 {@code BasePlacer#replace} を直接呼ぶ。
     * 除去装置は隣のテスト場まで汚染を止めるので別のバッチ(置いた同じ tick に消すので動き出す前に無くなる)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40, batch = "base_spill")
    public static void replacedDevicesDoNotSpill(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos gun = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos purifier = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlock(gun, com.yayuy.overgrownvegetation.registry.ModBlocks.GUN_TURRET.get().defaultBlockState(), 3);
        level.setBlock(purifier, com.yayuy.overgrownvegetation.registry.ModBlocks.SPORE_PURIFIER.get().defaultBlockState(), 3);
        if (!(level.getBlockEntity(gun) instanceof com.yayuy.overgrownvegetation.block.GunTurretBlockEntity turret)
                || !(level.getBlockEntity(purifier) instanceof com.yayuy.overgrownvegetation.block.SporeDeviceBlockEntity device)) {
            helper.fail("機関砲台・胞子除去装置の BlockEntity が無い");
            return;
        }
        turret.setItem(0, new net.minecraft.world.item.ItemStack(com.yayuy.overgrownvegetation.registry.ModItems.AUTOCANNON_ROUND.get(), 64));
        device.setItem(com.yayuy.overgrownvegetation.block.SporeDeviceBlockEntity.SLOT_BATTERY,
                com.yayuy.overgrownvegetation.item.BatteryItem.withCharge(com.yayuy.overgrownvegetation.item.BatteryItem.CAPACITY));
        com.yayuy.overgrownvegetation.base.build.BasePlacer.replace(level, gun, Blocks.AIR.defaultBlockState());
        com.yayuy.overgrownvegetation.base.build.BasePlacer.replace(level, purifier, Blocks.STONE.defaultBlockState());
        helper.runAfterDelay(2, () -> {
            List<net.minecraft.world.entity.item.ItemEntity> items = level.getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(gun).inflate(6));
            if (!items.isEmpty()) {
                helper.fail("置き換えた設備の中身が落ちた: " + items.size() + " 個(" + items.get(0).getItem() + " など)");
                return;
            }
            if (!level.getBlockState(gun).isAir() || !level.getBlockState(purifier).is(Blocks.STONE)) {
                helper.fail("置き換えたブロックが違う: " + level.getBlockState(gun) + " / " + level.getBlockState(purifier));
                return;
            }
            helper.succeed();
        });
    }

    /** 性質の値の書き名(列挙の toString は大文字のことがあるので Property#getName で引く)。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(BlockState st, net.minecraft.world.level.block.state.properties.Property prop) {
        return prop.getName(st.getValue(prop));
    }

    private BaseGameTests() {
    }
}
