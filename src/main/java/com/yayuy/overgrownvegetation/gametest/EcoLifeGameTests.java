package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ecosystem.life.EcoAct;
import com.yayuy.overgrownvegetation.ecosystem.life.EcoBehavior;
import com.yayuy.overgrownvegetation.ecosystem.life.EcoLife;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModCrawlers;
import com.yayuy.overgrownvegetation.registry.ModLizards;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * G2・G3 の行動の段(2026-11-18、ecosystem/life): 腹が減ると狩って食べ、満腹になると狩らない・倒木の上で虫をあさる・眠い時間は休む・敵から逃げる。
 * 時刻に左右されないよう、名前の ecoawake / ecosleep で起きている時間を決める。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class EcoLifeGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(EcoLifeGameTests.class);
        }

        private Registration() {
        }
    }

    private EcoLifeGameTests() {
    }

    private static <T extends Mob> T place(GameTestHelper helper, EntityType<T> type, double x, double y, double z, String name) {
        T m = helper.spawn(type, new BlockPos(0, 2, 0));   // spawnWithNoFreeWill は Goal を外すので使わない
        Vec3 p = helper.absoluteVec(new Vec3(x, y, z));
        m.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
        m.setCustomName(Component.literal(name));
        return m;
    }

    static double fullness(Mob m) {
        EcoLife life = (EcoLife) m;
        return life.ecoMind().fullness(m.level().getGameTime(), EcoLife.behavior(m), m.getRandom());
    }

    static void setFullness(Mob m, double v) {
        ((EcoLife) m).ecoMind().set(m.level().getGameTime(), v);
    }

    /** 腹の減ったイアンタサウルスはそばのカタツムリを狩り、食べ終えると満腹(0.9 以上)になる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 1200)
    public static void hungryHunterKillsPreyAndBecomesFull(GameTestHelper helper) {
        Mob hunter = place(helper, ModLizards.IANTHASAURUS.get(), 1.5D, 2.0D, 3.5D, "nostroll_ecoawake");
        Mob prey = place(helper, ModCrawlers.GIANT_SNAIL.get(), 5.0D, 2.0D, 3.5D, "nostroll_noeco");
        setFullness(hunter, 0.0D);
        helper.succeedWhen(() -> {
            helper.assertTrue(!prey.isAlive(), "カタツムリがまだ生きている(体力 " + prey.getHealth() + ")");
            helper.assertTrue(((EcoLife) hunter).ecoAct() == EcoAct.NONE, "まだ食べている");
            double f = fullness(hunter);
            helper.assertTrue(f > 0.9D, "食べたのに満腹でない: " + f);
        });
    }

    /** 満腹のイアンタサウルスは、目の前のカタツムリを狩らない(200 tick)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 260)
    public static void fullHunterLeavesPreyAlone(GameTestHelper helper) {
        Mob hunter = place(helper, ModLizards.IANTHASAURUS.get(), 1.5D, 2.0D, 3.5D, "nostroll_ecoawake");
        Mob prey = place(helper, ModCrawlers.GIANT_SNAIL.get(), 4.0D, 2.0D, 3.5D, "nostroll_noeco");
        setFullness(hunter, 1.0D);
        helper.onEachTick(() -> {
            // 隣のテスト(12 ブロック先)の腹の減った狩り手が噛むことがあるので、噛んだのがこの個体かで見る
            if (prey.getLastHurtByMob() == hunter) helper.fail("満腹なのにカタツムリを噛んだ");
        });
        helper.runAtTickTime(220, () -> {
            helper.assertTrue(fullness(hunter) >= EcoLife.behavior(hunter).huntBelow, "満腹度が狩りのしきい値より下がった");
            helper.succeed();
        });
    }

    /** 倒木の上で虫をあさる: 腹が少し減ったヒロノムスは倒木へ行って eat を流し、満腹度が forageAmount ほど戻る。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 800)
    public static void foragesOnFallenLog(GameTestHelper helper) {
        for (int x = 1; x <= 5; x++) {
            helper.setBlock(new BlockPos(x, 2, 5), ModBlocks.FALLEN_LOG.get().defaultBlockState()
                    .setValue(com.yayuy.overgrownvegetation.block.FallenLogBlock.AXIS, Direction.Axis.X));
        }
        Mob m = place(helper, ModLizards.HYLONOMUS.get(), 3.0D, 2.0D, 1.5D, "nostroll_ecoawake");
        EcoBehavior b = EcoLife.behavior(m);
        setFullness(m, 0.2D);
        boolean[] ate = {false};
        helper.onEachTick(() -> {
            if (((EcoLife) m).ecoAct() == EcoAct.EAT) ate[0] = true;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(ate[0], "eat を流していない");
            double f = fullness(m);
            helper.assertTrue(f > 0.2D + b.forageAmount * 0.8D, "あさったのに満腹度が戻らない: " + f);
        });
    }

    /** 起きている時間の外は休む(rest)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void restsOutsideActiveTime(GameTestHelper helper) {
        Mob m = place(helper, ModLizards.HYLONOMUS.get(), 3.5D, 2.0D, 3.5D, "nostroll_ecosleep");
        helper.succeedWhen(() -> helper.assertTrue(((EcoLife) m).ecoAct() == EcoAct.REST, "眠い時間なのに休まない: " + ((EcoLife) m).ecoAct()));
    }

    // ================================================================ 倒木の洞(棲み処)

    static BlockPos placeDen(GameTestHelper helper, BlockPos rel, Direction facing) {
        helper.setBlock(rel, com.yayuy.overgrownvegetation.ecosystem.life.EcoBlocks.DEN.get().defaultBlockState()
                .setValue(com.yayuy.overgrownvegetation.ecosystem.life.EcoDenBlock.FACING, facing));
        return helper.absolutePos(rel);
    }

    static com.yayuy.overgrownvegetation.ecosystem.life.EcoDenBlockEntity den(GameTestHelper helper, BlockPos abs) {
        return (com.yayuy.overgrownvegetation.ecosystem.life.EcoDenBlockEntity) helper.getLevel().getBlockEntity(abs);
    }

    /** 洞に入ると Mob は消えて中に保存され(保存して読み直しても残る)、出すと入口の前に同じ種が戻り、住む洞を覚えている。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void denAdmitsSavesAndReleases(GameTestHelper helper) {
        BlockPos abs = placeDen(helper, new BlockPos(1, 2, 3), Direction.EAST);
        Mob m = place(helper, ModLizards.HYLONOMUS.get(), 3.0D, 2.0D, 3.5D, "nostroll_noeco");
        helper.assertTrue(com.yayuy.overgrownvegetation.ecosystem.life.EcoDens.enter(m, abs), "洞に入れない");
        helper.assertTrue(m.isRemoved(), "入ったのに Mob が残っている");
        var d = den(helper, abs);
        helper.assertTrue(d.count() == 1 && "hylonomus".equals(d.species()), "中身が違う: " + d.count() + " " + d.species());
        helper.assertTrue(helper.getLevel().getBlockState(abs).getValue(com.yayuy.overgrownvegetation.ecosystem.life.EcoDenBlock.OCCUPIED), "入口の絵が「いる」にならない");
        // 保存して読み直す
        net.minecraft.nbt.CompoundTag tag = d.saveWithFullMetadata(helper.getLevel().registryAccess());
        var copy = new com.yayuy.overgrownvegetation.ecosystem.life.EcoDenBlockEntity(abs, helper.getLevel().getBlockState(abs));
        copy.loadWithComponents(tag, helper.getLevel().registryAccess());
        helper.assertTrue(copy.count() == 1 && "hylonomus".equals(copy.species()), "保存して読み直すと中身が消える");
        // 出す
        java.util.List<Mob> out = new java.util.ArrayList<>();
        d.releaseAll(helper.getLevel(), com.yayuy.overgrownvegetation.ecosystem.life.EcoDenBlock.mouth(abs, helper.getLevel().getBlockState(abs)), 0.0F, out::add);
        helper.assertTrue(out.size() == 1 && out.get(0).getType() == ModLizards.HYLONOMUS.get(), "出てこない: " + out);
        helper.assertTrue(abs.equals(((EcoLife) out.get(0)).ecoMind().home), "出た個体が住む洞を覚えていない");
        helper.assertTrue(d.count() == 0, "出したのに中に残っている");
        out.get(0).discard();
        helper.succeed();
    }

    /** 眠い時間になると、近くの洞の入口まで歩いて入る(Mob が消えて洞の中に 1 体)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void sleepyCritterWalksIntoDen(GameTestHelper helper) {
        BlockPos abs = placeDen(helper, new BlockPos(1, 2, 3), Direction.EAST);
        Mob m = place(helper, ModCrawlers.PULMONOSCORPIUS.get(), 5.5D, 2.0D, 3.5D, "nostroll_ecosleep");
        helper.succeedWhen(() -> {
            helper.assertTrue(m.isRemoved(), "まだ洞の外にいる(" + m.position().subtract(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO))) + " 住む洞 " + ((EcoLife) m).ecoMind().home + " 動作 " + ((EcoLife) m).ecoAct() + ")");
            helper.assertTrue(den(helper, abs).count() == 1, "洞の中にいない");
        });
    }

    /** 洞を壊すと中の個体が出てくる(押し返しで消えたときも同じ口)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void brokenDenLetsOccupantsOut(GameTestHelper helper) {
        BlockPos abs = placeDen(helper, new BlockPos(3, 2, 3), Direction.EAST);
        den(helper, abs).stock(ModCrawlers.GIANT_SNAIL.get(), 2, helper.getLevel().getGameTime());
        helper.setBlock(new BlockPos(3, 2, 3), net.minecraft.world.level.block.Blocks.AIR);
        java.util.List<Mob> snails = helper.getLevel().getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(abs).inflate(3.0D),
                e -> e.getType() == ModCrawlers.GIANT_SNAIL.get());
        helper.assertTrue(snails.size() == 2, "壊したのに 2 体出てこない: " + snails.size());
        snails.forEach(Mob::discard);
        helper.succeed();
    }

    /** 台帳の頭数で詰める数が変わる: 満員の升では毎回詰め、頭数 0 の升では詰めない(洞 40 個で比べる)。台帳の読み口(lively)も升の欠けに合わせて下がる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 40)
    public static void ledgerHeadsDecideDenStock(GameTestHelper helper) {
        BlockPos abs = placeDen(helper, new BlockPos(3, 2, 3), Direction.EAST);
        var d = den(helper, abs);
        net.minecraft.util.RandomSource r = net.minecraft.util.RandomSource.create(42L);
        long now = helper.getLevel().getGameTime();
        int full = 0, empty = 0, half = 0;
        for (int i = 0; i < 40; i++) {
            if (com.yayuy.overgrownvegetation.ecosystem.life.EcoDens.stockWith(d, now, r, 1.0D, 1.0D) > 0) full++;
            d.releaseAll(helper.getLevel(), Vec3.atCenterOf(abs).add(0, 50, 0), 0.0F, Mob::discard);
            if (com.yayuy.overgrownvegetation.ecosystem.life.EcoDens.stockWith(d, now, r, 0.0D, 0.0D) > 0) empty++;
            d.releaseAll(helper.getLevel(), Vec3.atCenterOf(abs).add(0, 50, 0), 0.0F, Mob::discard);
            if (com.yayuy.overgrownvegetation.ecosystem.life.EcoDens.stockWith(d, now, r, 0.5D, 0.5D) > 0) half++;
            d.releaseAll(helper.getLevel(), Vec3.atCenterOf(abs).add(0, 50, 0), 0.0F, Mob::discard);
        }
        helper.assertTrue(full == 40, "満員の升で詰めない回がある: " + full);
        helper.assertTrue(empty == 0, "頭数 0 の升で詰めた: " + empty);
        helper.assertTrue(half > 8 && half < 32, "半分の升の詰め方が外れ: " + half + " / 40");
        // 台帳の読み口: この升の G2 を満員ぶん欠けさせると 0 になる(終わったら戻す)
        var t = com.yayuy.overgrownvegetation.data.DesignData.ecosystem();
        var ledger = com.yayuy.overgrownvegetation.ecosystem.EcoLedger.get(helper.getLevel());
        long key = com.yayuy.overgrownvegetation.ecosystem.EcoLedger.cellOf(abs.getX() >> 4, abs.getZ() >> 4, t.cellShift);
        long lnow = com.yayuy.overgrownvegetation.ecosystem.EcoLedger.now(helper.getLevel());
        ledger.putCellForTests(key, t.fullG2, lnow, 0.0D, lnow);
        double l2 = com.yayuy.overgrownvegetation.ecosystem.life.EcoDens.lively(helper.getLevel(), 2, abs);
        double l3 = com.yayuy.overgrownvegetation.ecosystem.life.EcoDens.lively(helper.getLevel(), 3, abs);
        ledger.putCellForTests(key, 0.0D, lnow, 0.0D, lnow);
        helper.assertTrue(l2 < 0.01D && l3 > 0.99D, "台帳の頭数が読めない: G2 " + l2 + " G3 " + l3);
        helper.succeed();
    }

    // ================================================================ 羽化(幼虫 → 梢の繭 → 成虫)

    /** 育ち切った満腹の幼虫は葉の下面に繭を作って入り(幼虫は消える)、繭から成虫が出る(繭は消える)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void nymphPupatesAndAdultEmerges(GameTestHelper helper) {
        for (int x = 1; x <= 5; x++) {
            for (int z = 1; z <= 5; z++) {
                helper.setBlock(new BlockPos(x, 5, z), net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
            }
        }
        Mob nymph = place(helper, ModCrawlers.STENODICTYA_NYMPH.get(), 3.5D, 6.0D, 3.5D, "nostroll_ecoawake");
        EcoLife life = (EcoLife) nymph;
        life.ecoMind().born = helper.getLevel().getGameTime() - com.yayuy.overgrownvegetation.ecosystem.life.EcoGoals.GROW_TICKS - 1L;
        setFullness(nymph, 1.0D);
        BlockPos[] cocoon = {null};
        helper.runAtTickTime(390, () -> {
            if (cocoon[0] == null) helper.fail("繭を作らない: 幼虫 " + (nymph.isRemoved() ? "消えた" : nymph.position().subtract(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO))))
                    + " 動作 " + life.ecoAct() + " 生まれ " + (helper.getLevel().getGameTime() - life.ecoMind().born) + " tick 前 満腹 " + fullness(nymph)
                    + " 下げる所 " + com.yayuy.overgrownvegetation.ecosystem.life.EcoGoals.cocoonSpot((net.minecraft.world.entity.PathfinderMob) nymph)
                    + " Goal " + nymph.goalSelector.getAvailableGoals().stream().filter(g -> g.isRunning()).map(g -> g.getGoal().getClass().getSimpleName()).toList());
        });
        helper.onEachTick(() -> {
            if (cocoon[0] != null) return;
            for (int x = 1; x <= 5; x++) {
                for (int z = 1; z <= 5; z++) {
                    BlockPos p = helper.absolutePos(new BlockPos(x, 4, z));
                    if (helper.getLevel().getBlockState(p).is(com.yayuy.overgrownvegetation.ecosystem.life.EcoBlocks.COCOON.get())) cocoon[0] = p;
                }
            }
            if (cocoon[0] == null) return;
            helper.assertTrue(nymph.isRemoved(), "繭を作ったのに幼虫が残っている");
            Mob adult = com.yayuy.overgrownvegetation.ecosystem.life.EcoCocoonBlock.emerge(helper.getLevel(), cocoon[0]);   // 1 日待たずに羽化させる
            helper.assertTrue(adult != null && adult.getType() == com.yayuy.overgrownvegetation.registry.ModFlyers.STENODICTYA_LOBATA.get(), "成虫が出ない: " + adult);
            helper.assertTrue(helper.getLevel().getBlockState(cocoon[0]).isAir(), "羽化したのに繭が残っている");
            adult.discard();
            helper.succeed();
        });
    }

    /** 叩かれると、叩いた相手から離れる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void fleesFromAttacker(GameTestHelper helper) {
        Mob m = place(helper, ModLizards.HYLONOMUS.get(), 3.5D, 2.0D, 3.5D, "nostroll_ecoawake");
        Mob z = place(helper, EntityType.ZOMBIE, 1.0D, 2.0D, 3.5D, "z");
        z.setNoAi(true);
        setFullness(m, 1.0D);
        double start = m.distanceTo(z);
        helper.runAtTickTime(2, () -> m.hurt(m.damageSources().mobAttack(z), 0.5F));
        helper.succeedWhen(() -> helper.assertTrue(m.distanceTo(z) > start + 1.5D, "逃げていない: " + m.distanceTo(z) + "(始め " + start + ")"));
    }
}
