package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 乗っ取りの器官の形を、写した地形から別スレッドで作っても、生きた世界から主スレッドで作ったのと同じになる
 * (2026-11-20 全体管理の決定「案 2」。{@link TakeoverSnapshot}・{@link OrganFusion#decideAsync})。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class TakeoverSnapshotGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        // 1.20.1: 受け手のメソッドの名前はクラスごとに変える(Forge の EventBus は「クラスの単純名_メソッド名_イベントの単純名」で包みを作って使い回すので、同じパッケージの同じ名前の入れ子のクラスがぶつかり、前の受け手が 2 回呼ばれる)
        @SubscribeEvent
        public static void onTakeoverSnapshotGameTests(RegisterGameTestsEvent event) {
            event.register(TakeoverSnapshotGameTests.class);
        }

        private Registration() {
        }
    }

    private TakeoverSnapshotGameTests() {
    }

    /** 5 種類 × 大小で、写しから作った形 = 生きた世界から作った形。写しの外は読まない。 */
    @GameTest(template = "gametest_empty7", batch = "takeover_snapshot", timeoutTicks = 400)
    public static void snapshotPlanMatchesLivePlan(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StructureFusionGameTests.Site s = StructureFusionGameTests.prepare(level, 6_407_600, 6_400_000);
        ForestOrgans data = ForestOrgans.get(level);
        BoundingBox box = new BoundingBox(s.bx - 30, s.fy + 1, s.bz - 30, s.bx + 40, s.fy + 60, s.bz + 40);
        try {
            StructureFusionGameTests.register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(s.house));
            Takeover.Face wall = new Takeover.Face(s.bx, s.fy + 3, s.bz + 2, -1, 0, 0);
            Takeover.Face high = new Takeover.Face(s.bx + 8, s.fy + 4, s.bz + 3, 1, 0, 0);
            Takeover.Face roof = new Takeover.Face(s.bx + 4, s.fy + 5, s.bz + 3, 0, 1, 0);
            int compared = 0;
            for (OrganKind kind : OrganKind.values()) {
                for (boolean big : new boolean[]{false, true}) {
                    Takeover.Face f = kind == OrganKind.TOWER ? roof : kind == OrganKind.SAC ? high : wall;
                    String name = kind.id + (big ? " 大" : " 小");
                    ForestOrgans.Organ o = Takeover.plant(level, data, f, big, kind, new Random(7L));
                    try {
                        long[] live = TakeoverShapes.plan(TakeoverShapes.live(level), o);
                        TakeoverSnapshot snap = TakeoverSnapshot.capture(level, f.x(), f.y(), f.z(), OrganFusion.SNAP_REACH, OrganFusion.SNAP_DOWN,
                                OrganFusion.SNAP_UP);
                        long[] fromSnap = TakeoverShapes.plan(snap, o);
                        helper.assertTrue(snap.totalMisses() == 0, name + ": 写しの外を読んだ " + snap.totalMisses());
                        helper.assertTrue(Arrays.equals(live, fromSnap), name + ": 写しから作った形が違う(" + (live == null ? "null" : live.length) + " / "
                                + (fromSnap == null ? "null" : fromSnap.length) + ")");
                        if (live != null) compared++;
                    } finally {
                        data.remove(o.id);
                    }
                }
            }
            helper.assertTrue(compared >= 8, "比べられた形が少ない: " + compared);
            // 別スレッドの道(decideAsync)でも同じ設計図になる
            ForestOrgans.Organ o = Takeover.plant(level, data, wall, true, OrganKind.PHASE, new Random(9L));
            try {
                long[] expect = TakeoverShapes.plan(level, o);
                OrganFusion.Result r = OrganFusion.Result.WAIT;
                for (int i = 0; i < 400 && r == OrganFusion.Result.WAIT; i++) {
                    r = OrganFusion.decideAsync(level, o);
                    if (r == OrganFusion.Result.WAIT) {
                        try {
                            Thread.sleep(5L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                helper.assertTrue(r == OrganFusion.Result.FUSED, "別スレッドの形作りが終わらない・取りやめた: " + r);
                helper.assertTrue(Arrays.equals(expect, o.fusion), "別スレッドの設計図が主スレッドと違う");
            } finally {
                data.remove(o.id);
            }
        } finally {
            StructureFusionGameTests.clearBox(level, box);
            s.cleanup();
        }
        helper.succeed();
    }
}
