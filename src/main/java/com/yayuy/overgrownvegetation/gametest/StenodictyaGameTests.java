package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.StenodictyaLobataEntity;
import com.yayuy.overgrownvegetation.entity.flyer.Flock;
import com.yayuy.overgrownvegetation.entity.flyer.SkyFlyer;
import com.yayuy.overgrownvegetation.entity.flyer.SkyProbe;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModFlyers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * ステノディクティア(G2 成虫)の段 1(2026-11-16): 飛ぶ型の土台で飛び、葉の層の下面に逆さにぶら下がって止まる。
 * 下面の止まり木は土台の口({@code SkyFlyer#perchUnder})。上に止まる種(セミナリア・ヴェロフィル)の止まり方は、それぞれのテストで見ている。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class StenodictyaGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(StenodictyaGameTests.class);
        }

        private Registration() {
        }
    }

    private StenodictyaGameTests() {
    }

    private static StenodictyaLobataEntity spawn(GameTestHelper helper, double x, double y, double z) {
        StenodictyaLobataEntity v = helper.spawn(ModFlyers.STENODICTYA_LOBATA.get(), new BlockPos(3, 2, 3));
        Vec3 p = helper.absoluteVec(new Vec3(x, y, z));
        v.moveTo(p.x, p.y, p.z, 0.0F, 0.0F);
        v.debugSolo = true;
        return v;
    }

    /**
     * テスト場に小さな林冠を作る(テスト場は高さ 8 にバリアの天井がある): 高さ 6 に葉を x 1〜3・z 2〜4 に並べ(下は空き)、東の x 4〜6 の列は空まで空ける(林冠の縁の外 = 下から寄る口)。
     * @return 東の端の真ん中の葉(ぶら下がる所)
     */
    private static BlockPos canopy(GameTestHelper helper) {
        for (int x = 1; x <= 3; x++) {
            for (int z = 2; z <= 4; z++) {
                helper.setBlock(new BlockPos(x, 6, z), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
            }
        }
        for (int x = 4; x <= 6; x++) {
            for (int z = 2; z <= 4; z++) {
                for (int y = 1; y <= 12; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
            }
        }
        return helper.absolutePos(new BlockPos(3, 6, 3));
    }

    /** G2 に入り、植物側で、当たりは胴の芯 0.6 × 0.4、叩ける部位は無い。群れは 3 まで・下面に止まる種。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void stenodictyaRegisteredAsG2(GameTestHelper helper) {
        StenodictyaLobataEntity v = spawn(helper, 3.5D, 6.0D, 3.5D);
        if (MobGroup.of(v.getType()) != MobGroup.GROUP_2) {
            helper.fail("G2 に入っていない: " + MobGroup.of(v.getType()));
            return;
        }
        if (!(v instanceof ParasitizedMob)) {
            helper.fail("植物側でない");
            return;
        }
        if (Math.abs(v.getBbWidth() - 0.6F) > 1e-3 || Math.abs(v.getBbHeight() - 0.4F) > 1e-3) {
            helper.fail("芯の大きさが 0.6 × 0.4 でない: " + v.getBbWidth() + " × " + v.getBbHeight());
            return;
        }
        if (v.hitParts().count() != 0) {
            helper.fail("叩ける部位がある: " + v.hitParts().count());
            return;
        }
        helper.succeed();
    }

    /** 下面の止まり木の探し方: 葉の層の下面(下が空き・横に空から下りられる口)を選び、地面や葉の上面は選ばない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void stenodictyaFindsLeafUndersides(GameTestHelper helper) {
        BlockPos leaf = canopy(helper);
        if (!SkyProbe.hangable(helper.getLevel(), leaf)) {
            helper.fail("縁の葉の下面に止まれない: " + leaf + " 口 " + SkyProbe.hangOutside(helper.getLevel(), leaf));
            return;
        }
        BlockPos out = SkyProbe.hangOutside(helper.getLevel(), leaf);
        if (out == null || out.getY() != leaf.getY() - 1 || out.getX() <= leaf.getX()) {
            helper.fail("下から寄る口が林冠の縁の外(東)でない: " + out);
            return;
        }
        List<BlockPos> spots = SkyProbe.findHangPerches(helper.getLevel(), leaf, 3, 3, 2.0D);
        for (BlockPos s : spots) {
            if (s.getY() != leaf.getY()) {
                helper.fail("葉の層の下面でない所を選んだ: " + s + "(葉 y " + leaf.getY() + ")");
                return;
            }
        }
        if (spots.isEmpty()) {
            helper.fail("下面の止まり木が見つからない");
            return;
        }
        helper.succeed();
    }

    /**
     * 葉の上面に乗った所から止まりに行っても、上面で「着いた」とならず、口の列へ下りてから下面にぶら下がる(11-17 stenodictya-anim が見つけた、
     * 上面に乗ったまま降りる段に入って 200 tick 動かなかった件)。150 tick の中に下面に止まること。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 170)
    public static void stenodictyaDoesNotLandOnLeafTop(GameTestHelper helper) {
        BlockPos leaf = canopy(helper);
        StenodictyaLobataEntity v = spawn(helper, 3.5D, 7.0D, 3.5D);   // 縁の葉の上面に乗せる
        v.roostOverride = Boolean.TRUE;
        v.debugRoostAt(leaf);
        helper.onEachTick(() -> {
            if (v.mode() == SkyFlyer.MODE_LANDING && v.getY() > leaf.getY()) {
                helper.fail(String.format("葉の上面で降りる段に入った: y %.2f(葉 %d)", v.getY(), leaf.getY()));
                return;
            }
            if (v.mode() == SkyFlyer.MODE_PERCHED && leaf.equals(v.perchPos())) helper.succeed();
        });
        helper.runAfterDelay(150L, () -> {
            if (v.mode() == SkyFlyer.MODE_PERCHED) return;
            helper.fail(String.format("150 tick で下面に止まらない: 動き %d 位置 %.2f %.2f %.2f(葉 %s)", v.mode(), v.getX(), v.getY(), v.getZ(), leaf));
        });
    }

    /**
     * 夜(差し込み): 林冠の縁の外の口から寄って、葉の下面に逆さにぶら下がって止まる(足元 = 葉の下面 − 当たりの高さ − 0.02)。
     * 叩くと口へ抜けてから昇る(真上の葉に刺さらない)。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 420)
    public static void stenodictyaHangsUnderLeafAndDropsOut(GameTestHelper helper) {
        BlockPos leaf = canopy(helper);
        StenodictyaLobataEntity v = spawn(helper, 5.5D, 3.0D, 3.5D);   // テスト場の中(天井は高さ 8 のバリア)、口の列の下
        v.roostOverride = Boolean.TRUE;
        v.debugRoostAt(leaf);
        double hangY = leaf.getY() - v.getBbHeight() - 0.02D;
        boolean[] hit = {false};
        boolean[] wentOut = {false};
        double[] y0 = {Double.NaN};
        int[] hitAt = {-1};
        int[] t = {0};
        helper.onEachTick(() -> {
            t[0]++;
            if (!hit[0]) {
                if (v.mode() != SkyFlyer.MODE_PERCHED) return;
                if (!leaf.equals(v.perchPos())) {
                    helper.fail("縁の葉でない所に止まった: " + v.perchPos() + "(葉 " + leaf + ")");
                    return;
                }
                if (Math.abs(v.getY() - hangY) > 0.01D || Math.abs(v.getX() - (leaf.getX() + 0.5D)) > 0.05D || Math.abs(v.getZ() - (leaf.getZ() + 0.5D)) > 0.05D) {
                    helper.fail(String.format("ぶら下がる位置が違う: %.2f %.2f %.2f(的 %.2f %.2f %.2f)", v.getX(), v.getY(), v.getZ(),
                            leaf.getX() + 0.5D, hangY, leaf.getZ() + 0.5D));
                    return;
                }
                if (v.flock() == null || v.flock().state() != Flock.State.ROOST) {
                    helper.fail("群れが休んでいない");
                    return;
                }
                hit[0] = true;
                y0[0] = v.getY();
                hitAt[0] = t[0];
                v.hurt(helper.getLevel().damageSources().generic(), 1.0F);
                return;
            }
            // 口(東)へ抜けてから昇る: まず葉の層より下のまま葉の列の東へ出て、その後で葉の上面より高く昇る
            // (逃げる向きは決まらないので、決まった tick の位置ではなく通った道で見る)
            if (v.getY() < leaf.getY() && v.getX() >= leaf.getX() + 1.0D) wentOut[0] = true;
            if (v.getY() > leaf.getY() + 1.5D) {
                if (!wentOut[0]) {
                    helper.fail(String.format("口へ抜ける前に昇った(葉の中を通った): %.2f %.2f %.2f(葉 %s)", v.getX(), v.getY(), v.getZ(), leaf));
                    return;
                }
                helper.succeed();
                return;
            }
            if (t[0] - hitAt[0] == 80) {
                helper.fail(String.format("80 tick で昇らない: %.2f %.2f %.2f(葉 %s)・動き %d・口へ出た %s", v.getX(), v.getY(), v.getZ(), leaf, v.mode(), wentOut[0]));
            }
        });
        helper.runAfterDelay(400L, () -> {
            if (hit[0]) return;
            helper.fail(String.format("400 tick で止まらない: 動き %d 群れ %s 止まり木 %s 位置 %.2f %.2f %.2f(葉 %s・ぶら下がる高さ %.2f)",
                    v.mode(), v.flock() == null ? "null" : v.flock().state(), v.perchPos(), v.getX(), v.getY(), v.getZ(), leaf, hangY));
        });
    }

    // ------------------------------------------------------------------ 幼虫(11-17 段 1: 梢を歩く)

    /** 幼虫: G2 に入り、植物側で、当たりは胴の芯 0.45 × 0.3(0.7 倍に作り直した形)。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void stenodictyaNymphRegisteredAsG2(GameTestHelper helper) {
        com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity v = helper.spawn(com.yayuy.overgrownvegetation.registry.ModCrawlers.STENODICTYA_NYMPH.get(), new BlockPos(3, 2, 3));
        if (MobGroup.of(v.getType()) != MobGroup.GROUP_2) {
            helper.fail("G2 に入っていない: " + MobGroup.of(v.getType()));
            return;
        }
        if (!(v instanceof ParasitizedMob)) {
            helper.fail("植物側でない");
            return;
        }
        if (Math.abs(v.getBbWidth() - 0.45F) > 1e-3 || Math.abs(v.getBbHeight() - 0.3F) > 1e-3) {
            helper.fail("芯の大きさが 0.45 × 0.3 でない: " + v.getBbWidth() + " × " + v.getBbHeight());
            return;
        }
        helper.succeed();
    }

    /**
     * 幼虫のうろつきの行き先は梢(てっぺんが葉の所・今の高さ ±2)の上だけで、地面へは下りない。
     * テスト場の天井のバリア(高さ 8)を抜き、高さ 3 に 3×3 の葉の台を置いて、台の上から 60 回選ばせる。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void stenodictyaNymphStrollsOnlyOnCanopy(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) helper.setBlock(new BlockPos(x, 8, z), Blocks.AIR.defaultBlockState());
        }
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                helper.setBlock(new BlockPos(x, 3, z), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
            }
        }
        BlockPos feet = helper.absolutePos(new BlockPos(3, 4, 3));
        BlockPos lo = helper.absolutePos(new BlockPos(2, 4, 2)), hi = helper.absolutePos(new BlockPos(4, 4, 4));
        net.minecraft.util.RandomSource rnd = net.minecraft.util.RandomSource.create(17L);
        int found = 0;
        for (int i = 0; i < 60; i++) {
            Vec3 p = com.yayuy.overgrownvegetation.entity.StenodictyaNymphEntity.canopySpot(helper.getLevel(), feet, rnd);
            if (p == null) continue;
            found++;
            BlockPos b = BlockPos.containing(p);
            if (b.getY() != feet.getY() || b.getX() < lo.getX() || b.getX() > hi.getX() || b.getZ() < lo.getZ() || b.getZ() > hi.getZ()) {
                helper.fail("葉の台の上でない所を選んだ: " + b + "(台 " + lo + "〜" + hi + ")");
                return;
            }
        }
        if (found == 0) {
            helper.fail("葉の台の上を 1 度も選ばない");
            return;
        }
        helper.succeed();
    }
}
