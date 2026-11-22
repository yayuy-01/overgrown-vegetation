package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.front.ForeignInfection;
import com.yayuy.overgrownvegetation.front.FrontDirector;
import com.yayuy.overgrownvegetation.front.Fronts;
import com.yayuy.overgrownvegetation.front.UnitTerrain;
import com.yayuy.overgrownvegetation.horde.UnitPurpose;
import com.yayuy.overgrownvegetation.horde.VirtualHordes;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 前線と部隊 区切り 5(2026-11-21): ほかの感染系 MOD の印の升へ部隊が向かい、印を消して森で上書きする。印の保存と復元。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class ForeignInfectionGameTests {

    private static final String BATCH = "front_foreign";

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(ForeignInfectionGameTests.class);
        }

        private Registration() {
        }
    }

    private ForeignInfectionGameTests() {
    }

    @GameTest(template = "gametest_empty7", batch = BATCH, timeoutTicks = 20)
    public static void unitsCleanForeignCellAndOvergrowIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ForeignInfection foreign = ForeignInfection.get(level.getServer());
        Fronts fronts = Fronts.get(level.getServer());
        VirtualHordes h = VirtualHordes.load(new CompoundTag());
        PhaseData pd = PhaseData.get(level);
        int x = 23_000_000, z = 23_000_000;
        int cellX = ((x >> 4) >> 2) + 2, cellZ = (z >> 4) >> 2;
        Fronts.Front f = fronts.addSourceForTests(x + 0.5D, z + 0.5D);
        try {
            UnitTerrain.terrainForTests = (cx, cz) -> UnitTerrain.LAND;
            foreign.add(cellX, cellZ, 1.0D);
            VirtualHordes.Swarm s = h.newUnit(x, 64, z, f.id(), UnitPurpose.DEFEND, level.getGameTime());
            s.addHeads(BuiltInRegistries.ENTITY_TYPE.getKey(G1Species.ZOMBIE.entityType()), 20, 100.0D);
            long target = FrontDirector.expandTargetForTests(level, h, f);
            helper.assertTrue(target == (((long) cellX << 32) | (cellZ & 0xFFFFFFFFL)), "印の升を行き先にしない");
            s.assign(UnitPurpose.EXPAND, (cellX * 4 + 2) * 16.0D + 8.0D, (cellZ * 4 + 2) * 16.0D + 8.0D, level.getGameTime());
            for (int i = 0; i < 40; i++) h.move(level, 100, DesignData.perf());
            helper.assertTrue(s.arrived(), "印の升に着かない");
            for (int i = 0; i < 20 && foreign.mark(cellX, cellZ) > 0.0D; i++) {
                s.assign(UnitPurpose.EXPAND, s.tx(), s.tz(), level.getGameTime()); // 食べる間に合わせる
                h.move(level, 1, DesignData.perf());
                FrontDirector.expand(level, h, 1.0D);
            }
            helper.assertTrue(foreign.mark(cellX, cellZ) == 0.0D, "部隊がいても印が消えない");
            helper.assertTrue(pd.isOvergrownChunk(new ChunkPos(cellX * 4 + 1, cellZ * 4 + 1)), "印が消えた升が森で上書きされない");
            ForeignInfection back = ForeignInfection.load(foreign.save(new CompoundTag(), level.registryAccess()));
            helper.assertTrue(back.marks().size() == foreign.marks().size(), "印が復元されない");
            helper.succeed();
        } finally {
            UnitTerrain.terrainForTests = null;
            foreign.clearForTests();
            for (int dx = 0; dx < 4; dx++) for (int dz = 0; dz < 4; dz++) pd.forgetOvergrownChunk(new ChunkPos(cellX * 4 + dx, cellZ * 4 + dz));
            fronts.fronts().remove(f);
        }
    }
}
