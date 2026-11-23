package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.phase.PhaseEvents;
import com.yayuy.overgrownvegetation.phase.PhaseOmens;
import com.yayuy.overgrownvegetation.sky.CloudWeather;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/**
 * フェーズが上がったときの印 F2(一斉に芽吹く)・F4(短く弱い胞子の嵐)。上がった時だけ 1 回起き、下げた時は起きない。
 * 嵐の差し込みと芽吹きの並びは世界に 1 つなので、自分だけのバッチで 1 本にまとめる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PhaseOmenGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterPhaseOmenGameTests(RegisterGameTestsEvent event) { // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える
            event.register(PhaseOmenGameTests.class);
        }

        private Registration() {
        }
    }

    private PhaseOmenGameTests() {
    }

    @GameTest(template = "gametest_empty7", batch = "phase_omens", timeoutTicks = 20)
    public static void sproutAndStormOnlyWhenAdvancing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PhaseData data = PhaseData.get(level);
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        ChunkPos chunk = new ChunkPos(at);
        boolean added = data.adoptOvergrownChunk(level, chunk);
        try {
            PhaseOmens.clearForTests();
            CloudWeather.clearForcedStorm();
            PhaseOmens.setCentersForTests(List.of(at));
            // 下げた・同じ: 何も起きない
            MinecraftForge.EVENT_BUS.post(new PhaseEvents.PhaseChanged(level, 3, 1, false));
            MinecraftForge.EVENT_BUS.post(new PhaseEvents.PhaseChanged(level, 5, 3, false));
            helper.assertTrue(PhaseOmens.queuedSprouts() == 0 && CloudWeather.forcedStorm() == null, "下げたのに芽吹き・嵐が起きた");
            // F1 → F2: 芽吹きだけ
            MinecraftForge.EVENT_BUS.post(new PhaseEvents.PhaseChanged(level, 1, 2, false));
            helper.assertTrue(PhaseOmens.queuedSprouts() >= 1, "F2 に上がっても、汚染域の芽吹きが並ばない");
            helper.assertTrue(CloudWeather.forcedStorm() == null, "F2 で嵐が来た");
            int before = PhaseOmens.queuedSprouts();
            helper.assertTrue(PhaseOmens.step(level, before) == before && PhaseOmens.queuedSprouts() == 0, "並べた芽吹きを走らせきれない");
            // F3 → F4: 嵐を 1 回だけ。少し後に始まり、雷の間は風が弱い
            MinecraftForge.EVENT_BUS.post(new PhaseEvents.PhaseChanged(level, 3, 4, false));
            CloudWeather.Rain storm = CloudWeather.forcedStorm();
            helper.assertTrue(storm != null && PhaseOmens.queuedSprouts() == 0, "F4 で嵐が差し込まれない・芽吹きが並んだ");
            long now = level.getDayTime();
            helper.assertTrue(storm.from() > now && storm.to() - storm.from() == PhaseOmens.STORM_RAIN_TICKS
                    && storm.thunderTo() - storm.thunderFrom() == PhaseOmens.STORM_THUNDER_TICKS, "嵐の時刻が違う: " + storm);
            helper.assertTrue(CloudWeather.rainAt(storm.thunderFrom() + 1) == storm, "差し込んだ嵐が天気の予定に入っていない");
            // 1 つ飛ばしで上がる(コマンド): 通ったフェーズの印を出す
            CloudWeather.clearForcedStorm();
            MinecraftForge.EVENT_BUS.post(new PhaseEvents.PhaseChanged(level, 1, 5, false));
            helper.assertTrue(PhaseOmens.queuedSprouts() >= 1 && CloudWeather.forcedStorm() != null, "F1 → F5 で F2・F4 の印が出ない");
            helper.assertTrue(PhaseOmens.crossed(4, 5, 4) == false && PhaseOmens.crossed(3, 4, 4), "通ったかの判定が違う");
            helper.succeed();
        } finally {
            PhaseOmens.clearForTests();
            PhaseOmens.setCentersForTests(null);
            CloudWeather.clearForcedStorm();
            PrevailingWind.stormScale = 1.0D;
            if (!CloudWeather.serverActive()) level.setWeatherParameters(1, 0, false, false); // 予定が止まっている時の、バニラの天気で降らせた分を戻す
            if (added) data.forgetOvergrownChunk(chunk);
        }
    }

    /** 弱い嵐: 嵐の風は倍率の分だけ弱い(卓越風の計算)。 */
    @GameTest(template = "gametest_empty7", batch = "phase_omens", timeoutTicks = 20)
    public static void weakStormBlowsWeaker(GameTestHelper helper) {
        PrevailingWind.Weather storm = new PrevailingWind.Weather(true, true, 0L, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE);
        PrevailingWind.Sample full = new PrevailingWind.Sample(), weak = new PrevailingWind.Sample();
        try {
            PrevailingWind.stormScale = 1.0D;
            PrevailingWind.sample(1L, 12000L, storm, full);
            PrevailingWind.stormScale = PhaseOmens.STORM_SCALE;
            PrevailingWind.sample(1L, 12000L, storm, weak);
        } finally {
            PrevailingWind.stormScale = 1.0D;
        }
        helper.assertTrue(full.storm && weak.storm && Math.abs(weak.strength - full.strength * PhaseOmens.STORM_SCALE) < 1.0E-6D,
                "弱い嵐の風が倍率どおりでない: " + full.strength + " → " + weak.strength);
        helper.succeed();
    }
}
