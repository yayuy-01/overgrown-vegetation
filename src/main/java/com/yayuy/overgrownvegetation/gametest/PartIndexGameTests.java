package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.AcumnesEntity;
import com.yayuy.overgrownvegetation.perf.PartIndex;
import com.yayuy.overgrownvegetation.registry.ModFlyers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 部位の升目の索引({@link PartIndex}、2026-11-18 大群のための軽量化 B の ①)。絞った一覧が、探す箱に重なる部位を 1 つも取りこぼさないこと。
 * GameTest のサーバーでは索引は答えを変えず(全部を渡す)、すべての {@code getEntities} で取りこぼしを数えている。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PartIndexGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(PartIndexGameTests.class);
        }

        private Registration() {
        }
    }

    private PartIndexGameTests() {
    }

    /**
     * アクムネス 5 体(部位 25)を飛ばし、毎 tick 少しずつ動かし、途中で 1 体を 5 ブロック跳ばす(AI は止めてテスト場の中で)。毎 tick、各部位の箱(と少し広げた箱)で絞った一覧を作り、
     * 重なる部位が全部入っているか確かめる。最後に、この間の全部の {@code getEntities} の検算で取りこぼしが 0 か。
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 80)
    public static void partIndexNeverMissesAnOverlappingPart(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PartIndex.resetCounts();
        List<AcumnesEntity> mobs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            AcumnesEntity a = helper.spawn(ModFlyers.ACUMNES.get(), new BlockPos(1 + i, 2, 3));
            Vec3 p = helper.absoluteVec(new Vec3(1.5D + i, 2.0D + (i % 2), 3.5D));
            a.moveTo(p.x, p.y, p.z, i * 70.0F, 0.0F);
            a.setNoAi(true); // 隣のテスト場へ飛んで行かない・襲わない(部位はAI なしでも毎 tick 置き直される)
            mobs.add(a);
        }
        PartIndex index = ((PartIndex.Holder) level).overgrown_vegetation$partIndex();
        int[] misses = {0};
        int[] checked = {0};
        String[] first = {""};
        for (int t = 1; t <= 60; t++) {
            final int tick = t;
            helper.runAtTickTime(t, () -> {
                for (int i = 0; i < mobs.size(); i++) {
                    AcumnesEntity a = mobs.get(i);
                    // 1 tick に 0.9 ブロック往復させ、tick 30 に 1 体だけ 5 ブロック(索引の余裕 3 を超える)跳ばす。テスト場の中で
                    double dx = (tick / 4) % 2 == 0 ? 0.9D : -0.9D;
                    if (tick == 30 && i == 0) dx = 5.0D;
                    if (tick == 31 && i == 0) dx = -5.0D;
                    a.moveTo(a.getX() + (i % 2 == 0 ? dx : 0.0D), a.getY(), a.getZ() + (i % 2 == 1 ? dx : 0.0D), a.getYRot() + 9.0F, 0.0F);
                }
                Collection<PartEntity<?>> all = level.getPartEntities();
                for (AcumnesEntity a : mobs) {
                    for (PartEntity<?> part : a.hitParts().parts()) {
                        for (double grow : new double[]{0.0D, 0.6D, 2.5D}) {
                            AABB box = part.getBoundingBox().inflate(grow);
                            Collection<PartEntity<?>> got = index.filteredForTest(level, all, box);
                            for (PartEntity<?> p : all) {
                                if (p.getBoundingBox().intersects(box)) {
                                    checked[0]++;
                                    if (!got.contains(p)) {
                                        if (misses[0] == 0) first[0] = "t" + tick + " part " + p.getBoundingBox() + " box " + box + " got " + got.size() + " " + index.describe(level);
                                        misses[0]++;
                                    }
                                }
                            }
                        }
                    }
                }
            });
        }
        helper.runAtTickTime(61, () -> {
            if (level.getPartEntities().size() < 16) {
                helper.fail("部位が 16 未満で索引を使っていない: " + level.getPartEntities().size());
            } else if (checked[0] < 1000 || misses[0] > 0) {
                helper.fail("絞った一覧の取りこぼし " + misses[0] + " / 確かめた " + checked[0] + " 最初: " + first[0]);
            } else if (PartIndex.mismatches() > 0) {
                helper.fail("getEntities の検算で取りこぼし " + PartIndex.mismatches() + " / " + PartIndex.checks());
            } else {
                helper.succeed();
            }
        });
    }
}
