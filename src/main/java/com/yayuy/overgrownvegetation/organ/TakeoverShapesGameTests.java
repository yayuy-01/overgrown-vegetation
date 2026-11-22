package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

import java.util.List;
import java.util.Random;

/**
 * 建物用の器官 5 種類の形(2026-11-20。{@link TakeoverShapes})。テスト場は {@link StructureFusionGameTests} と同じ家(床 y = fy の石、壁 fy+1〜fy+4、屋根 fy+5)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class TakeoverShapesGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(TakeoverShapesGameTests.class);
        }

        private Registration() {
        }
    }

    private TakeoverShapesGameTests() {
    }

    /**
     * 種類ごと・大きさごとに: 建つ・芯が置かれて外の空気に触れる(壊せる)。種類ごとの形の要:
     * 塔は屋根より上へ伸びる / 嚢の芯は吊り元より下で床より上(下の空きに収まる)/ 井戸の根(茎)が地面まで届く / 苗床は卵の莢が壁に付く / フェーズは嚢の塊。
     */
    @GameTest(template = "gametest_empty7", batch = "takeover_shapes", timeoutTicks = 400)
    public static void fiveKindsBuildWithReachableCore(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StructureFusionGameTests.Site s = StructureFusionGameTests.prepare(level, 6_407_200, 6_400_000);
        ForestOrgans data = ForestOrgans.get(level);
        BoundingBox box = new BoundingBox(s.bx - 30, s.fy + 1, s.bz - 30, s.bx + 40, s.fy + 60, s.bz + 40);
        try {
            StructureFusionGameTests.register(s, BuiltinStructures.VILLAGE_PLAINS, List.of(s.house));
            Takeover.Face wall = new Takeover.Face(s.bx, s.fy + 3, s.bz + 2, -1, 0, 0);
            Takeover.Face high = new Takeover.Face(s.bx + 8, s.fy + 4, s.bz + 3, 1, 0, 0);
            Takeover.Face roof = new Takeover.Face(s.bx + 4, s.fy + 5, s.bz + 3, 0, 1, 0);
            for (OrganKind kind : OrganKind.values()) {
                for (boolean big : new boolean[]{false, true}) {
                    Takeover.Face f = kind == OrganKind.TOWER ? roof : kind == OrganKind.SAC ? high : wall;
                    String name = kind.id + (big ? " 大" : " 小");
                    ForestOrgans.Organ o = Takeover.plant(level, data, f, big, kind, new Random(5L));
                    int id = o.id;
                    try {
                        int n = OrganWorld.buildNow(level, data, o);
                        helper.assertTrue(n > 0 && o.fusion != null, name + ": 建たない");
                        BlockPos a = o.anchor();
                        boolean reach = false, stalkOnGround = false, egg = false, sac = false;
                        int coreMaxY = Integer.MIN_VALUE, coreMinY = Integer.MAX_VALUE, top = Integer.MIN_VALUE;
                        for (long e : o.fusion) {
                            int m = OrganBlueprint.material(e);
                            BlockPos q = a.offset(OrganBlueprint.dx(e), OrganBlueprint.dy(e), OrganBlueprint.dz(e));
                            if (m == OrganBlueprint.M_CORE && level.getBlockState(q).is(OrganBlocks.ORGAN_CORE.get())) {
                                coreMaxY = Math.max(coreMaxY, q.getY());
                                coreMinY = Math.min(coreMinY, q.getY());
                                for (Direction d : Direction.values()) if (level.getBlockState(q.relative(d)).isAir()) reach = true;
                            }
                            if (m == OrganBlueprint.M_STALK && level.getBlockState(q).is(OrganBlocks.ORGAN_STALK.get())) {
                                top = Math.max(top, q.getY());
                                if (q.getY() == s.fy + 1) stalkOnGround = true;
                            }
                            if (m == OrganBlueprint.M_EGG && level.getBlockState(q).is(OrganBlocks.ORGAN_EGG_MASS.get())) egg = true;
                            if (m == OrganBlueprint.M_SAC && level.getBlockState(q).is(OrganBlocks.ORGAN_SAC.get())) sac = true;
                        }
                        helper.assertTrue(coreMaxY != Integer.MIN_VALUE, name + ": 芯が置かれていない");
                        helper.assertTrue(reach, name + ": 芯が外の空気に触れない(壊せない)");
                        switch (kind) {
                            case TOWER -> helper.assertTrue(top >= f.y() + (big ? 12 : 4), name + ": 角笛が屋根から伸びていない(いちばん上 " + (top - f.y()) + ")");
                            case SAC -> helper.assertTrue(coreMinY <= f.y() && coreMinY > s.fy, name + ": 芯が吊り元より上か床の下にある(垂れる袋は底、下に空きが無ければ座った袋の横腹。" + coreMinY + "〜" + coreMaxY + ")");
                            case LEY -> helper.assertTrue(stalkOnGround, name + ": 根が地面まで届いていない");
                            case NURSERY -> helper.assertTrue(egg, name + ": 莢(卵塊)が無い");
                            case PHASE -> helper.assertTrue(sac, name + ": 襞の塊(嚢)が無い");
                        }
                    } finally {
                        // この器官の置いた物だけ消す(家は残す。突き破った穴は残ってよい)
                        if (o.fusion != null) {
                            BlockPos a = o.anchor();
                            for (long e : o.fusion) {
                                BlockPos q = a.offset(OrganBlueprint.dx(e), OrganBlueprint.dy(e), OrganBlueprint.dz(e));
                                if (OvergrownVegetation.MODID.equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(q).getBlock()).getNamespace())) {
                                    level.setBlock(q, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2 | 16);
                                }
                            }
                        }
                        for (net.minecraft.world.entity.Entity t : level.getEntitiesOfClass(OrganTentacle.class, net.minecraft.world.phys.AABB.of(box))) t.discard();
                        data.remove(id);
                    }
                }
            }
        } finally {
            StructureFusionGameTests.clearBox(level, box);
            s.cleanup();
        }
        helper.succeed();
    }
}
