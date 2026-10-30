package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.base.build.BaseEconomy;
import com.yayuy.overgrownvegetation.base.build.BaseSite;
import com.yayuy.overgrownvegetation.base.build.BaseSites;
import com.yayuy.overgrownvegetation.base.build.BaseWatch;
import com.yayuy.overgrownvegetation.base.plan.BaseTerrain;
import com.yayuy.overgrownvegetation.base.plan.BaseType;
import com.yayuy.overgrownvegetation.data.DefenseTable;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 見張り台の早期警報(順 2 の着手順 8 の最小限、2026-10-08、{@link BaseWatch})。
 *
 * <p>基地は遠い決まった座標に台帳だけで載せ(チャンクは作らない)、同じ tick のうちに外す(道のテストと同じ。座標は道のテストと重ねない)。
 * 予告のときに向きを振って侵攻もその向きで来ることは {@code TdGameTests#warningRollsDirectionAndInvasionKeepsIt}。
 * 全員のチャットに届くところは GameTest にプレイヤーがいないので見ない(Claude のテストクライアントで見る)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class BaseWatchGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(BaseWatchGameTests.class);
        }

        private Registration() {
        }
    }

    private static final BaseTerrain FLAT = BaseTerrain.sample((x, z) -> 64);

    /** 台帳だけの基地を並べる遠い場所(道のテストは z −30000〜−26000)。 */
    private static final int X0 = -30000;
    private static final int Z0 = -20000;
    private static final int Y = 64;

    private static BaseSite addSite(BaseSites data, List<BaseSite> added, ResourceKey<Level> dim, int x, int z) {
        BaseSite site = data.add(dim, new BlockPos(x, Y, z), 0, 4242L, 1, FLAT, true, BaseType.PURIFICATION);
        added.add(site);
        return site;
    }

    /**
     * 見張る基地の選び方と知らせる文。ターゲット (X0, Z0) から東へ: 別のディメンションの基地 50・陥落した基地 100・
     * 陥落していない基地 300・範囲の外の基地 800。
     * <ul>
     *     <li>範囲 512: いちばん近いのは別のディメンションと陥落した基地でも、知らせるのは 300 の基地(800 は数えない)。</li>
     *     <li>範囲 200: 無し(陥落した基地は知らせない)。範囲 0: 無し(0 は知らせない)。ネザーのターゲットにはネザーの基地。</li>
     *     <li>文: 一方向は {@code watch_td_from}(番号・向き・上から 2 桁に丸めた規模)、全方位は {@code watch_td_around}(番号・規模)。</li>
     *     <li>丸め: 99.6 → 100、2,437.6 → 2,400、12,345 → 12,000、87.4 → 87、負 → 0。</li>
     *     <li>同梱の {@code defense.json} の {@code watchRadius} が Java の既定値と同じ(設計値は 3 か所)。</li>
     * </ul>
     */
    @GameTest(template = "gametest_empty7", timeoutTicks = 20)
    public static void watchtowerPicksNearestLiveBase(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        BaseSites data = BaseSites.get(server);
        BlockPos target = new BlockPos(X0, Y, Z0);
        List<BaseSite> added = new ArrayList<>();
        try {
            BaseSite nether = addSite(data, added, Level.NETHER, X0 + 50, Z0);
            BaseSite fallen = addSite(data, added, Level.OVERWORLD, X0 + 100, Z0);
            BaseSite live = addSite(data, added, Level.OVERWORLD, X0 + 300, Z0);
            addSite(data, added, Level.OVERWORLD, X0 + 800, Z0);
            BaseEconomy.fallAtBirth(server, data, fallen);
            helper.assertTrue(fallen.fallen(), "陥落の印が付かない");

            BaseSite picked = BaseWatch.spotter(data, Level.OVERWORLD, target, 512.0D);
            helper.assertTrue(picked == live, "範囲 512 で見張る基地が #" + live.id + "(300)でない: "
                    + (picked == null ? "無し" : "#" + picked.id));
            helper.assertTrue(BaseWatch.spotter(data, Level.OVERWORLD, target, 200.0D) == null,
                    "範囲 200 なのに基地が選ばれた(陥落した基地は知らせない)");
            helper.assertTrue(BaseWatch.spotter(data, Level.OVERWORLD, target, 0.0D) == null, "範囲 0 なのに基地が選ばれた");
            helper.assertTrue(BaseWatch.spotter(data, Level.NETHER, target, 512.0D) == nether, "ネザーのターゲットにネザーの基地が選ばれない");

            Component from = BaseWatch.message(live, com.yayuy.overgrownvegetation.td.TdDirection.NORTHEAST, 2437.6D);
            TranslatableContents fc = (TranslatableContents) from.getContents();
            Object[] fa = fc.getArgs();
            helper.assertTrue("message.overgrown_vegetation.base.watch_td_from".equals(fc.getKey()) && fa.length == 3,
                    "一方向の文のキー・引数の数が違う: " + fc.getKey() + " " + Arrays.toString(fa));
            helper.assertTrue(Integer.valueOf(live.id).equals(fa[0]), "一方向の文の基地の番号が違う: " + fa[0]);
            helper.assertTrue(fa[1] instanceof Component c && c.getContents() instanceof TranslatableContents t
                            && "td.overgrown_vegetation.direction.northeast".equals(t.getKey()),
                    "一方向の文の向きが北東の名前でない: " + fa[1]);
            helper.assertTrue("2,400".equals(fa[2]), "一方向の文の規模が 2,400 でない: " + fa[2]);

            Component around = BaseWatch.message(live, com.yayuy.overgrownvegetation.td.TdDirection.AROUND, 87.4D);
            TranslatableContents ac = (TranslatableContents) around.getContents();
            Object[] aa = ac.getArgs();
            helper.assertTrue("message.overgrown_vegetation.base.watch_td_around".equals(ac.getKey()) && aa.length == 2
                            && Integer.valueOf(live.id).equals(aa[0]) && "87".equals(aa[1]),
                    "全方位の文が違う: " + ac.getKey() + " " + Arrays.toString(aa));

            double[] inputs = {-5.0D, 99.6D, 2437.6D, 12345.0D};
            long[] expected = {0L, 100L, 2400L, 12000L};
            for (int i = 0; i < inputs.length; i++) {
                long got = BaseWatch.roundSize(inputs[i]);
                helper.assertTrue(got == expected[i], "規模の丸め " + inputs[i] + " → " + got + "(" + expected[i] + " のはず)");
            }
        } finally {
            for (BaseSite s : added) {
                data.remove(s);
            }
        }

        DefenseTable bundled;
        try (java.io.InputStream in = BaseWatchGameTests.class.getResourceAsStream("/overgrown_vegetation/design/defense.json")) {
            bundled = DefenseTable.fromJson(new com.google.gson.Gson().fromJson(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8), DefenseTable.Json.class));
        } catch (Exception e) {
            throw new AssertionError("design/defense.json が読めない: " + e);
        }
        helper.assertTrue(bundled.baseWatchRadius() == DefenseTable.DEFAULT.baseWatchRadius(),
                "defense.json の watchRadius " + bundled.baseWatchRadius() + " が既定値 " + DefenseTable.DEFAULT.baseWatchRadius() + " と違う");
        helper.succeed();
    }

    private BaseWatchGameTests() {
    }
}
