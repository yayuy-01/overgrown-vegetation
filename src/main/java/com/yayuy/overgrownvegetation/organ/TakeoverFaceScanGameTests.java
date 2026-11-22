package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 見回りの露出面を何回かに分けて集めても、1 回で集めたのと同じ数になる(2026-11-20 見回りの重さ: 構造物の詰まったチャンクを
 * 1 回でなめると 8〜14 ms・起動直後は 100 ms を超えたので、{@link Takeover#SECTIONS_PER_VISIT} 区画ずつに分けた)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class TakeoverFaceScanGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(TakeoverFaceScanGameTests.class);
        }

        private Registration() {
        }
    }

    private TakeoverFaceScanGameTests() {
    }

    @GameTest(template = "gametest_empty7", batch = "takeover_face_scan", timeoutTicks = 40)
    public static void splitScanFindsSameFaces(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(3, 2, 3));
        List<BlockPos> placed = new ArrayList<>();
        try {
            // 建材の柱を 6 区画(96 段)にまたがって置く(区画ごとに数マス)
            for (int k = 0; k < 6; k++) {
                for (int dy = 0; dy < 3; dy++) {
                    BlockPos p = base.above(k * 16 + dy);
                    if (p.getY() >= level.getMaxBuildHeight() - 1) continue;
                    level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), 2);
                    placed.add(p);
                }
            }
            LevelChunk ch = level.getChunkAt(base);
            int[] counted = new int[1];
            Takeover.faces(level, ch, new Random(1L), 512, counted);
            helper.assertTrue(counted[0] >= placed.size(), "1 回で集めた面が少ない: " + counted[0]);
            Takeover.FaceScan sc = new Takeover.FaceScan(512);
            int calls = 0;
            while (!Takeover.scanSome(level, ch, new Random(1L), sc, Takeover.SECTIONS_PER_VISIT)) {
                calls++;
                helper.assertTrue(calls < 200, "なめ終わらない");
            }
            helper.assertTrue(calls >= 1, "分けてなめていない(1 回で終わった)");
            helper.assertTrue(sc.seen == counted[0], "分けて集めた面の数が違う: " + sc.seen + " / " + counted[0]);
            // 覚えた面を使う道: 途中なら null、なめ終われば覚えた物を返す
            Takeover.forgetFaces();
            Takeover.FaceScan first = Takeover.cachedFaces(level, ch, new Random(1L), false);
            helper.assertTrue(first == null, "1 回目でなめ終わった(区画が足りない)");
            Takeover.FaceScan got = null;
            for (int i = 0; i < 200 && got == null; i++) got = Takeover.cachedFaces(level, ch, new Random(1L), false);
            helper.assertTrue(got != null && got.seen == counted[0], "覚えた面の数が違う");
            helper.assertTrue(Takeover.cachedFaces(level, ch, new Random(1L), false) == got, "覚えた面を使っていない");
        } finally {
            Takeover.forgetFaces();
            for (BlockPos p : placed) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        }
        helper.succeed();
    }
}
