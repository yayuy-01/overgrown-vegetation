package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.AllyEntities;
import com.yayuy.overgrownvegetation.ally.SoldierEntity;
import com.yayuy.overgrownvegetation.ally.SoldierKind;
import com.yayuy.overgrownvegetation.base.BaseBlocks;
import com.yayuy.overgrownvegetation.base.build.BaseCommand;
import com.yayuy.overgrownvegetation.base.build.BaseConstruction;
import com.yayuy.overgrownvegetation.base.build.BaseEconomy;
import com.yayuy.overgrownvegetation.base.build.BaseGarrison;
import com.yayuy.overgrownvegetation.base.build.BaseSite;
import com.yayuy.overgrownvegetation.base.build.BaseSites;
import com.yayuy.overgrownvegetation.base.build.BaseUpkeep;
import com.yayuy.overgrownvegetation.base.build.BaseWarehouse;
import com.yayuy.overgrownvegetation.base.build.EngineerWorkGoal;
import com.yayuy.overgrownvegetation.base.build.UpkeepJob;
import com.yayuy.overgrownvegetation.base.plan.BaseEmitter;
import com.yayuy.overgrownvegetation.base.plan.BasePlan;
import com.yayuy.overgrownvegetation.base.plan.BaseTerrain;
import com.yayuy.overgrownvegetation.base.plan.BaseType;
import com.yayuy.overgrownvegetation.base.plan.Surface;
import com.yayuy.overgrownvegetation.block.GunTurretBlock;
import com.yayuy.overgrownvegetation.block.GunTurretBlockEntity;
import com.yayuy.overgrownvegetation.block.SporeDeviceBlockEntity;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.data.DefenseTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.spore.SporeFilters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * 基地の整備(順 2 の着手順 4、2026-10-04)。期待値は設計値から引く。
 *
 * <p>1 本目は決まった遠くの座標にレベル 1 を建て(前の回のブロックが残っている前提。同じ場所なら上書き)、計画の設備と倉庫・倉庫への買い足し・
 * 設備の見回り・計画との見比べと直し方を、見回りの口を直接呼んで確かめる(見回りに任せない —— 遠い基地のチャンクはすぐ読み込みが外れる)。
 * 2 本目はテスト場の中に箱・機関砲台・はしごの除去塔を置き、工兵が本当に歩いて(はしごも登って)整備するのを見る。
 * どちらも自分だけのバッチ(機関砲台・除去装置は隣のテスト場に届く)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class BaseUpkeepGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(BaseUpkeepGameTests.class);
        }

        private Registration() {
        }
    }

    /** 整備の試験で建てる遠い基地(ほかの基地の試験と重ねない。毎回同じ場所)。 */
    private static final int UPKEEP_X = -28672;
    private static final int UPKEEP_Z = 25600;
    /** 工兵を歩かせる試験の台帳だけの基地(チャンクは作らない)。 */
    private static final BlockPos WALK_SITE = new BlockPos(-29184, 64, 26112);

    /** 局所の列 {x, z} のうち、条件に合うものを中心に近い順に。 */
    private static List<int[]> columns(BasePlan plan, BiPredicate<Integer, Integer> ok) {
        List<int[]> out = new ArrayList<>();
        for (int x = plan.minX(); x <= plan.maxX(); x++) {
            for (int z = plan.minZ(); z <= plan.maxZ(); z++) {
                if (ok.test(x, z)) {
                    out.add(new int[]{x, z});
                }
            }
        }
        out.sort((a, b) -> Integer.compare(Math.abs(a[0]) + Math.abs(a[1]), Math.abs(b[0]) + Math.abs(b[1])));
        return out;
    }

    private static void clear(ServerLevel level, BlockPos p) {
        if (level.getBlockEntity(p) instanceof Container c) {
            c.clearContent();
        }
    }

    private static Map<BlockPos, UpkeepJob.Fix> fixes(BaseSite site) {
        Map<BlockPos, UpkeepJob.Fix> out = new HashMap<>();
        for (UpkeepJob j : BaseUpkeep.jobs(site)) {
            for (UpkeepJob.Fix f : j.fixes()) {
                out.put(f.pos(), f);
            }
        }
        return out;
    }

    /**
     * レベル 1 を建てて: 計画の設備と倉庫(除染機と工兵の立つ所)・はしごしか無い除去塔を見つける / 倉庫へ目安まで買い足して値段どおり貯蓄が減る・
     * 2 回目は買わない・貯蓄 0 なら買わない / 覆われた機関砲台・目詰まりして胞子でいっぱいの除去装置が「止まった」仕事になる / 計画と見比べて、
     * 壊れた金網は補修材 1 個・汚染された砂利は補修材なし・中の根と払う幅の根は取り除く・覆いは剥がす・外の有刺鉄線の輪は置き直す、
     * プレイヤーが置いたブロックは触らない、直すと元に戻り、見比べた後に変わっていたら直さない。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 100, batch = "base_upkeep")
    public static void warehouseRepairAndServiceOnPlacedBase(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BaseSites data = BaseSites.get(level.getServer());
        boolean garrison = BaseGarrison.enabled;
        boolean upkeep = BaseUpkeep.enabled;
        BaseGarrison.enabled = false; // 遠いチャンクの兵士は数えられず、実行のたびにワールドに溜まる
        BaseUpkeep.enabled = false; // 見回りに任せず、口を直接呼ぶ
        BaseSite site = null;
        List<Runnable> undo = new ArrayList<>();
        try {
            BlockPos center = new BlockPos(UPKEEP_X, helper.absolutePos(BlockPos.ZERO).getY(), UPKEEP_Z);
            BaseTerrain terrain = BaseCommand.sampleNoise(level, center, 0);
            site = data.add(level.dimension(), center, 0, 4343L, 1, terrain, true, BaseType.PURIFICATION);
            BaseEconomy.start(data, site, BaseEconomy.today(level.getServer()));
            BaseConstruction.start(data, site, 0, 1);
            BaseConstruction.runToEnd(level, data, site);
            BaseGarrison.setRoster(data, site, List.of()); // 守備兵を置いた基地(名簿は空でよい)
            DefenseTable t = DesignData.defense();

            // ---- 計画の設備と倉庫 ----
            BaseUpkeep.Layout lay = BaseUpkeep.layoutNow(site);
            helper.assertTrue(!lay.turrets().isEmpty() && !lay.purifierDevices().isEmpty(),
                    "計画の設備が見つからない: 機関砲台 " + lay.turrets().size() + "・除去装置 " + lay.purifierDevices().size());
            helper.assertTrue(!lay.stores().isEmpty(), "倉庫が見つからない");
            for (BaseWarehouse.Store s : lay.stores()) {
                helper.assertTrue(!s.decontaminators().isEmpty(), "倉庫に除染機の場所が無い");
                helper.assertTrue(level.getBlockState(s.decontaminators().get(0)).is(ModBlocks.DECONTAMINATOR.get()),
                        "新しく建てた倉庫に除染機が無い: " + level.getBlockState(s.decontaminators().get(0)));
                helper.assertTrue(EngineerWorkGoal.standable(level, s.stand()), "倉庫の工兵の立つ所に立てない: " + s.stand().toShortString());
                helper.assertTrue(!s.shelves().isEmpty() && !s.crates().isEmpty(), "倉庫に棚か弾薬箱が無い");
            }
            boolean ladder = false;
            for (BlockPos p : lay.purifierDevices()) {
                int[] l = EngineerWorkGoal.findLadder(level, p);
                if (l != null) {
                    ladder = true;
                    helper.assertTrue(l[3] == p.getY() - 1 && l[3] - l[1] >= 3, "はしごの上の端が除去装置のすぐ下でない");
                }
            }
            helper.assertTrue(ladder, "はしごしか無い除去塔(浄化圏の柱)が見つからない");

            // ---- 建てたばかりの基地には直すマスが無い(計画の書き出しと置いたブロックが食い違わない)----
            int baseline = BaseUpkeep.sweepNow(level, site);
            if (baseline != 0) {
                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (UpkeepJob.Fix f : fixes(site).values()) {
                    if (shown++ < 6) {
                        sb.append(' ').append(f.kind()).append('@').append(f.pos().toShortString()).append(' ')
                                .append(BuiltInRegistries.BLOCK.getKey(f.expected().getBlock())).append("→")
                                .append(BuiltInRegistries.BLOCK.getKey(f.state().getBlock()));
                    }
                }
                helper.fail("建てたばかりの基地に直すマスが " + baseline + " ある:" + sb);
                return;
            }
            BaseUpkeep.forget(site);
            lay = BaseUpkeep.layoutNow(site);

            // ---- 倉庫への買い足し ----
            for (BaseWarehouse.Store s : lay.stores()) {
                for (BlockPos p : s.all()) {
                    clear(level, p); // 前の回の品を空にする
                }
            }
            int nt = lay.turrets().size();
            int np = lay.purifierDevices().size();
            int expectSpent = 0;
            Map<BaseWarehouse.Supply, Integer> expectCount = new HashMap<>();
            for (BaseWarehouse.Supply s : BaseWarehouse.Supply.values()) {
                int target = BaseWarehouse.target(s, 1, nt, np);
                int lots = (target + s.lot() - 1) / s.lot();
                expectCount.put(s, lots * s.lot());
                expectSpent += lots * t.baseUpkeepCost(s.costKey);
            }
            helper.assertTrue(BaseWarehouse.target(BaseWarehouse.Supply.FILTER, 1, nt, np) == np * t.baseUpkeepStock("filtersPerPurifier"),
                    "フィルターの目安が除去装置 × 設計値でない");
            BaseEconomy.setStock(data, site, 0);
            helper.assertTrue(BaseUpkeep.restockNow(level, site) == 0, "貯蓄 0 なのに買い足した");
            helper.assertTrue(BaseWarehouse.count(level, lay.stores(), BaseWarehouse.Supply.REPAIR) == 0, "貯蓄 0 なのに補修材が入った");
            BaseEconomy.setStock(data, site, 100000);
            int spent = BaseUpkeep.restockNow(level, site);
            helper.assertTrue(spent == expectSpent, "買い足しの値段が設計値と違う: " + spent + " / " + expectSpent);
            for (BaseWarehouse.Supply s : BaseWarehouse.Supply.values()) {
                int have = BaseWarehouse.count(level, lay.stores(), s);
                helper.assertTrue(have == expectCount.get(s), s + " が目安まで入らない: " + have + " / " + expectCount.get(s));
            }
            helper.assertTrue(site.stock() == 100000 - expectSpent, "貯蓄から引かれていない");
            helper.assertTrue(BaseUpkeep.restockNow(level, site) == 0, "目安に届いているのにまた買い足した");

            // ---- 設備の見回り ----
            BlockPos turret = lay.turrets().get(0);
            BlockPos purifier = lay.purifierDevices().get(0);
            GunTurretBlockEntity tb = (GunTurretBlockEntity) level.getBlockEntity(turret);
            SporeDeviceBlockEntity db = (SporeDeviceBlockEntity) level.getBlockEntity(purifier);
            helper.assertTrue(tb != null && db != null, "計画の設備の場所に機関砲台・除去装置が無い");
            tb.clearContent();
            tb.setItem(0, new ItemStack(ModItems.AUTOCANNON_ROUND.get(), 10));
            BlockState ts = level.getBlockState(turret);
            ((GunTurretBlock) ts.getBlock()).setOvergrown(level, turret, ts, true);
            db.setItem(SporeDeviceBlockEntity.SLOT_SPORE, new ItemStack(ModItems.SPORE.get(), 64));
            db.setBaseSupplied(true); // 基地の除去装置は電池なしで動く(2026-10-24 からレッドストーンの補給は無い)
            ItemStack spent0 = new ItemStack(ModItems.SPORE_FILTER.get());
            spent0.setDamageValue(spent0.getMaxDamage() - 1);
            db.setItem(SporeDeviceBlockEntity.SLOT_FILTER, spent0);
            helper.assertTrue(BaseUpkeep.roundsWanted(tb) == t.baseUpkeepTurretFill() - 10, "足す弾の数が設計値と違う");
            helper.assertTrue(BaseUpkeep.redstoneWanted(db) == 0, "電池化の後もレッドストーンを足そうとする");
            helper.assertTrue(BaseUpkeep.filterWanted(db) && BaseUpkeep.sporesWanted(db), "目詰まり・胞子いっぱいを整備しない");
            BaseUpkeep.scanNow(level, site);
            boolean turretJob = false;
            boolean purifierJob = false;
            for (UpkeepJob j : BaseUpkeep.jobs(site)) {
                if (j.kind() == UpkeepJob.Kind.TURRET && j.pos().equals(turret)) {
                    turretJob = j.priority() == 0;
                }
                if (j.kind() == UpkeepJob.Kind.PURIFIER && j.pos().equals(purifier)) {
                    purifierJob = j.priority() == 0;
                }
            }
            helper.assertTrue(turretJob, "覆われた機関砲台が「止まった」仕事にならない");
            helper.assertTrue(purifierJob, "目詰まりした除去装置が「止まった」仕事にならない");
            BaseUpkeep.scanNow(level, site);
            helper.assertTrue(BaseUpkeep.jobs(site).size() == 2, "同じ設備の仕事が二重にできた: " + BaseUpkeep.jobs(site).size());

            // ---- 計画との見比べ ----
            BasePlan plan = site.plan(1);
            BaseEmitter em = site.emitter(1);
            BaseSite s1 = site;
            Set<Long> used = new HashSet<>();
            BlockPos fence = null;
            for (int[] c : columns(plan, (x, z) -> plan.isOuterRow(x, z) && plan.isPerimeter(x, z) && !plan.isFencePost(x, z)
                    && plan.plotAt(x, z) == null)) {
                BlockPos p = s1.toWorld(c[0], em.target(c[0], c[1]) + 2, c[1]);
                if (level.getBlockState(p).is(BaseBlocks.CHAIN_LINK_FENCE.get())) {
                    fence = p;
                    break;
                }
            }
            helper.assertTrue(fence != null, "前提: 外周の金網が見つからない");
            List<BlockPos> gravels = new ArrayList<>();
            for (int[] c : columns(plan, (x, z) -> plan.inside(x, z) && plan.plotAt(x, z) == null && plan.surfaceAt(x, z) == Surface.NATURAL
                    && !plan.isPerimeter(x, z) && em.innerFence(x, z) == 0)) {
                BlockPos p = s1.toWorld(c[0], em.target(c[0], c[1]), c[1]);
                if (level.getBlockState(p).is(Blocks.GRAVEL) && level.getBlockState(p.above()).isAir()
                        && level.getBlockState(p.above(2)).isAir() && used.add(BlockPos.asLong(p.getX() >> 2, 0, p.getZ() >> 2))) {
                    gravels.add(p);
                    if (gravels.size() == 4) {
                        break;
                    }
                }
            }
            helper.assertTrue(gravels.size() == 4, "前提: 中の砂利の列が 4 つ見つからない");
            BlockPos dirty = gravels.get(0);
            BlockPos root = gravels.get(1).above();
            BlockPos playerBlock = gravels.get(2).above();
            BlockPos playerTop = gravels.get(3);
            BlockPos covered = null;
            for (int[] c : columns(plan, (x, z) -> plan.inside(x, z) && plan.plotAt(x, z) == null && plan.surfaceAt(x, z) != Surface.NATURAL
                    && !plan.isPerimeter(x, z))) {
                BlockPos p = s1.toWorld(c[0], em.target(c[0], c[1]), c[1]);
                if (OvergrownLayerData.canCover(level.getBlockState(p))) {
                    covered = p;
                    break;
                }
            }
            helper.assertTrue(covered != null, "前提: 覆える道の地面が見つからない");
            BlockPos marginRoot = null;
            BlockPos wire = null;
            for (int[] c : columns(plan, (x, z) -> !plan.inside(x, z) && plan.plotAt(x, z) == null && plan.isClear(x, z)
                    && plan.surfaceAt(x, z) == Surface.NATURAL && plan.outDist(x, z) >= 2)) {
                BlockPos w = s1.toWorld(c[0], 0, c[1]);
                int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, w.getX(), w.getZ()) - 1;
                BlockPos p = new BlockPos(w.getX(), top, w.getZ());
                if (plan.isWire(c[0], c[1])) {
                    if (wire == null && level.getBlockState(p).is(ModBlocks.BARBED_WIRE.get())) {
                        wire = p;
                    }
                } else if (marginRoot == null && level.getBlockState(p).is(Blocks.GRASS_BLOCK) && plan.outDist(c[0], c[1]) != BasePlan.WIRE_OFFSET - 1
                        && plan.outDist(c[0], c[1]) != BasePlan.WIRE_OFFSET + 1) {
                    marginRoot = p.above();
                }
                if (wire != null && marginRoot != null) {
                    break;
                }
            }
            helper.assertTrue(wire != null && marginRoot != null, "前提: 外の有刺鉄線の輪か払う幅の地面が見つからない");

            Block overgrownGravel = BuiltInRegistries.BLOCK.get(OvergrownVegetation.id("overgrown_gravel"));
            Block giantLog = BuiltInRegistries.BLOCK.get(OvergrownVegetation.id("giant_overgrown_log"));
            BlockState fenceState = level.getBlockState(fence);
            level.setBlock(fence, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(dirty, overgrownGravel.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(root, giantLog.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(playerBlock, Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(playerTop, Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_ALL);
            OvergrownLayerData.cover(level, covered);
            level.setBlock(marginRoot, giantLog.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(wire, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            BlockPos pb = playerBlock;
            BlockPos pt = playerTop;
            undo.add(() -> {
                level.setBlock(pb, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(pt, Blocks.GRAVEL.defaultBlockState(), Block.UPDATE_ALL);
            });

            int found = BaseUpkeep.sweepNow(level, site);
            Map<BlockPos, UpkeepJob.Fix> fx = fixes(site);
            UpkeepJob.Fix f;
            f = fx.get(fence);
            helper.assertTrue(f != null && f.kind() == UpkeepJob.FixKind.RESTORE && f.cost() == 1 && f.priority() == 2
                    && f.state().is(BaseBlocks.CHAIN_LINK_FENCE.get()), "壊れた金網が補修材 1 個の外周の穴にならない: " + f);
            f = fx.get(dirty);
            helper.assertTrue(f != null && f.kind() == UpkeepJob.FixKind.RESTORE && f.cost() == 0 && f.state().is(Blocks.GRAVEL),
                    "汚染された砂利が補修材なしで砂利に戻らない: " + f);
            f = fx.get(root);
            helper.assertTrue(f != null && f.kind() == UpkeepJob.FixKind.REMOVE, "中の根が取り除かれない: " + f);
            f = fx.get(covered);
            helper.assertTrue(f != null && f.kind() == UpkeepJob.FixKind.UNCOVER, "覆いが剥がされない: " + f);
            f = fx.get(marginRoot);
            helper.assertTrue(f != null && f.kind() == UpkeepJob.FixKind.REMOVE, "払う幅の根が取り除かれない: " + f);
            f = fx.get(wire);
            helper.assertTrue(f != null && f.kind() == UpkeepJob.FixKind.RESTORE && f.cost() == 1 && f.state().is(ModBlocks.BARBED_WIRE.get()),
                    "外の有刺鉄線の輪が置き直されない: " + f);
            helper.assertTrue(!fx.containsKey(playerBlock) && !fx.containsKey(playerTop), "プレイヤーが置いたブロックを直そうとした");
            helper.assertTrue(found >= 6, "見比べで見つけたマスが少ない: " + found);

            for (BlockPos p : new BlockPos[]{fence, dirty, root, covered, marginRoot, wire}) {
                helper.assertTrue(BaseUpkeep.applyForTests(level, site, fx.get(p)), "直せない: " + p.toShortString());
            }
            helper.assertTrue(level.getBlockState(fence).is(BaseBlocks.CHAIN_LINK_FENCE.get()), "金網が戻らない");
            helper.assertTrue(level.getBlockState(dirty).is(Blocks.GRAVEL), "砂利が戻らない");
            helper.assertTrue(level.getBlockState(root).isAir() && level.getBlockState(marginRoot).isAir(), "根が残っている");
            helper.assertTrue(!OvergrownLayerData.isCovered(level, covered), "覆いが残っている");
            helper.assertTrue(level.getBlockState(wire).is(ModBlocks.BARBED_WIRE.get()), "有刺鉄線が戻らない");
            helper.assertTrue(level.getBlockState(playerBlock).is(Blocks.STONE_BRICKS) && level.getBlockState(playerTop).is(Blocks.STONE_BRICKS),
                    "プレイヤーが置いたブロックが消えた");

            // 見比べた後に変わっていたら直さない(プレイヤーが先に直した・置いた)
            level.setBlock(fence, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            BaseUpkeep.forget(site);
            BaseUpkeep.sweepNow(level, site);
            UpkeepJob.Fix again = fixes(site).get(fence);
            helper.assertTrue(again != null, "壊し直した金網を見つけない");
            level.setBlock(fence, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(!BaseUpkeep.applyForTests(level, site, again) && level.getBlockState(fence).is(Blocks.STONE),
                    "見比べた後に置かれたブロックを上書きした");
            level.setBlock(fence, fenceState, Block.UPDATE_ALL);
            helper.succeed();
        } finally {
            for (Runnable r : undo) {
                r.run();
            }
            if (site != null) {
                BaseUpkeep.forget(site);
                data.remove(site);
            }
            BaseGarrison.enabled = garrison;
            BaseUpkeep.enabled = upkeep;
        }
    }

    /**
     * 工兵が本当に歩いて整備する: テスト場の中の箱(倉庫の代わり)から弾・レッドストーン・フィルターを取り、覆われて弾の減った機関砲台を拭いて弾を足し、
     * はしごしか無い除去塔を登って胞子を取り出し・レッドストーンを足し・目詰まりしたフィルターを替え、降りて使用済みを除染機へ持ち帰る。
     * 計画の代わりにテスト場の設備と箱を渡す({@link BaseUpkeep#setLayoutForTests})。機関砲台は動かないようにレッドストーンで止める。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 1200, batch = "base_upkeep_walk")
    public static void engineerWalksClimbsAndServices(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BaseSites data = BaseSites.get(level.getServer());
        DefenseTable t = DesignData.defense();
        BlockPos chest = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos decon = helper.absolutePos(new BlockPos(2, 2, 1));
        BlockPos stand = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos turret = helper.absolutePos(new BlockPos(5, 2, 5));
        BlockPos purifier = helper.absolutePos(new BlockPos(3, 6, 3));
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(decon, ModBlocks.DECONTAMINATOR.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(turret, ModBlocks.GUN_TURRET.get().defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(helper.absolutePos(new BlockPos(6, 2, 5)), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL); // 撃たない
        for (int y = 2; y <= 5; y++) {
            level.setBlock(helper.absolutePos(new BlockPos(3, y, 3)), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(helper.absolutePos(new BlockPos(3, y, 4)), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH),
                    Block.UPDATE_ALL);
        }
        level.setBlock(purifier, ModBlocks.SPORE_PURIFIER.get().defaultBlockState(), Block.UPDATE_ALL);

        Container box = (Container) level.getBlockEntity(chest);
        box.setItem(0, new ItemStack(ModItems.AUTOCANNON_ROUND.get(), 64));
        box.setItem(1, new ItemStack(ModItems.AUTOCANNON_ROUND.get(), 64));
        box.setItem(2, new ItemStack(ModItems.AUTOCANNON_ROUND.get(), 64));
        box.setItem(3, new ItemStack(Items.REDSTONE, 32));
        box.setItem(4, new ItemStack(ModItems.SPORE_FILTER.get()));
        GunTurretBlockEntity tb = (GunTurretBlockEntity) level.getBlockEntity(turret);
        tb.setItem(0, new ItemStack(ModItems.AUTOCANNON_ROUND.get(), 10));
        BlockState ts = level.getBlockState(turret);
        ((GunTurretBlock) ts.getBlock()).setOvergrown(level, turret, ts, true);
        SporeDeviceBlockEntity db = (SporeDeviceBlockEntity) level.getBlockEntity(purifier);
        db.setItem(SporeDeviceBlockEntity.SLOT_SPORE, new ItemStack(ModItems.SPORE.get(), 64));
        db.setBaseSupplied(true); // 基地の除去装置は電池なしで動く(2026-10-24 からレッドストーンの補給は無い)
        ItemStack worn = new ItemStack(ModItems.SPORE_FILTER.get());
        worn.setDamageValue(worn.getMaxDamage() - 1);
        db.setItem(SporeDeviceBlockEntity.SLOT_FILTER, worn);

        BaseSite site = data.add(Level.OVERWORLD, WALK_SITE, 0, 4444L, 1, BaseTerrain.sample((x, z) -> 64), true, BaseType.DEFENSE);
        BaseEconomy.start(data, site, BaseEconomy.today(level.getServer()));
        BaseEconomy.setStock(data, site, 0); // 買い足さない(箱の品だけで)
        BaseUpkeep.setLayoutForTests(site, new BaseUpkeep.Layout(1, true, List.of(turret), List.of(purifier),
                List.of(new BaseWarehouse.Store(List.of(), List.of(chest), List.of(decon), stand))));
        SoldierEntity eng = AllyEntities.type(SoldierKind.ENGINEER).create(level, null, helper.absolutePos(new BlockPos(1, 2, 4)),
                MobSpawnType.EVENT, false, false);
        helper.assertTrue(eng != null, "工兵を作れない");
        eng.guard(site.id, helper.absolutePos(new BlockPos(3, 2, 3)), 12);
        level.addFreshEntity(eng);
        // 世界に入るとき BaseGarrison#onJoin が持ち場を基地(遠くの WALK_SITE)の真ん中へ付け替えるので、テスト場へ掛け直す。
        // 付け替えたままだと仕事の合間に MoveTowardsRestrictionGoal が WALK_SITE の方角へ引っ張り、その方角はテスト場の置き場所
        // しだい —— 南東の回だけはしごのマスへ寄って、倉庫へ歩き出したときにはしごの上の端まで運ばれていた(2026-10-18 の揺れ)
        eng.guard(site.id, helper.absolutePos(new BlockPos(3, 2, 3)), 12);
        BaseGarrison.setRoster(data, site, List.of(eng));
        BaseSite walkSite = site;
        BaseEconomy.presenceForTests = s -> s == walkSite;
        Runnable cleanup = () -> {
            if (!eng.isRemoved()) {
                eng.discard();
            }
            BaseUpkeep.trace = false;
            BaseEconomy.presenceForTests = null;
            BaseUpkeep.forget(walkSite);
            data.remove(walkSite);
            level.setBlock(purifier, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL); // 除去装置を残さない(圏内の汚染が止まる)
        };
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        java.util.function.Function<BlockPos, String> rel = p -> p.subtract(origin).toShortString();
        java.util.function.Supplier<String> state = () -> { // 時間切れのときに、どの条件で止まっていたか
            UpkeepJob mine = BaseUpkeep.jobFor(eng);
            StringBuilder jobs = new StringBuilder();
            for (UpkeepJob j : BaseUpkeep.jobs(walkSite)) {
                jobs.append(jobs.isEmpty() ? "" : " ").append(j.kind()).append('@').append(rel.apply(j.pos())).append(j == mine ? "(受け持ち)" : "");
            }
            ItemStack filter = db.getItem(SporeDeviceBlockEntity.SLOT_FILTER);
            net.minecraft.world.entity.LivingEntity target = eng.getTarget();
            return String.format("tick %d / 工兵 %s %.2f,%.2f,%.2f 地面 %s 無重力 %s 経路 %s 左手 %s 標的 %s / 仕事 [%s] / 砲台 弾 %d 覆い %s"
                            + " / 除去装置 胞子 %d 基地 %d フィルター %d 稼働 %s 濃度 %.1f%% / 持ち帰ったフィルター %d / 箱の弾 %d",
                    helper.getTick(), eng.isRemoved() ? "消えた" : "", eng.getX() - origin.getX(), eng.getY() - origin.getY(), eng.getZ() - origin.getZ(),
                    eng.onGround(), eng.isNoGravity(), eng.getNavigation().isDone() ? "なし" : "歩いている",
                    eng.getOffhandItem().isEmpty() ? "-" : BuiltInRegistries.ITEM.getKey(eng.getOffhandItem().getItem()).getPath(),
                    target == null ? "-" : target.getType().toShortString() + " " + String.format("%.1f", eng.distanceTo(target)), jobs,
                    tb.ammoCount(), level.getBlockState(turret).getValue(GunTurretBlock.OVERGROWN),
                    db.getItem(SporeDeviceBlockEntity.SLOT_SPORE).getCount(), (db.baseSupplied() ? 1 : 0),
                    filter.isEmpty() ? -1 : filter.getDamageValue(), db.isActive(),
                    com.yayuy.overgrownvegetation.spore.SporeConcentration.at(level, new net.minecraft.world.level.ChunkPos(purifier)).percentBeforeDevices(),
                    spentFilters(level, chest, decon), rounds(box));
        };
        helper.runAfterDelay(1190, () -> { // 時間切れ: 片付けてから、止まっていた所を書いて落とす(前は何も言わずに時間切れになっていた)
            String why;
            try {
                why = state.get();
            } finally {
                cleanup.run();
            }
            helper.fail("時間内に整備し終わらない: " + why);
        });
        BaseUpkeep.scanNow(level, site);
        BaseUpkeep.assignNow(level, site);
        helper.onEachTick(() -> {
            if (eng.isRemoved()) {
                return;
            }
            BlockState now = level.getBlockState(turret);
            boolean turretDone = tb.ammoCount() >= t.baseUpkeepTurretFill() && !now.getValue(GunTurretBlock.OVERGROWN);
            ItemStack filter = db.getItem(SporeDeviceBlockEntity.SLOT_FILTER);
            // 動き出した除去装置は600 tick ごとにフィルターを減らす
            boolean purifierDone = db.getItem(SporeDeviceBlockEntity.SLOT_SPORE).isEmpty()
                    && db.baseSupplied()
                    && SporeFilters.isUsable(filter) && filter.getDamageValue() <= 4;
            int spentHome = spentFilters(level, chest, decon);
            if (BaseUpkeep.trace && eng.tickCount % 100 == 0) {
                OvergrownVegetation.LOGGER.info("[upkeep-test] turret={} ammo={} purifier={} spores={} fuel={} filter={} spentHome={} job={} noGrav={} ground={} jobs={}",
                        turretDone, tb.ammoCount(), purifierDone, db.getItem(SporeDeviceBlockEntity.SLOT_SPORE).getCount(),
                        (db.baseSupplied() ? 1 : 0), filter.getDamageValue(), spentHome, BaseUpkeep.jobFor(eng),
                        eng.isNoGravity(), eng.onGround(), BaseUpkeep.jobs(walkSite).size());
            }
            if (turretDone && purifierDone && spentHome == 1 && BaseUpkeep.jobFor(eng) == null && !eng.isNoGravity() && eng.onGround()) {
                int used = 192 - rounds(box);
                boolean ok = used == t.baseUpkeepTurretFill() - 10;
                if (BaseUpkeep.trace) {
                    OvergrownVegetation.LOGGER.info("[upkeep-test] 終わった: {}", state.get());
                }
                cleanup.run();
                if (ok) {
                    helper.succeed();
                } else {
                    helper.fail("箱から取った弾の数が足した数と違う: " + used + " / " + (t.baseUpkeepTurretFill() - 10));
                }
            }
        });
    }

    /** 箱と除染機に入っている、洗う余地のあるフィルターの数。 */
    private static int spentFilters(ServerLevel level, BlockPos... at) {
        int n = 0;
        for (BlockPos p : at) {
            Container c = (Container) level.getBlockEntity(p);
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (SporeFilters.needsWashing(c.getItem(i))) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 箱の機関砲弾の数。 */
    private static int rounds(Container box) {
        int n = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            if (box.getItem(i).is(ModItems.AUTOCANNON_ROUND.get())) {
                n += box.getItem(i).getCount();
            }
        }
        return n;
    }

    private BaseUpkeepGameTests() {
    }
}
