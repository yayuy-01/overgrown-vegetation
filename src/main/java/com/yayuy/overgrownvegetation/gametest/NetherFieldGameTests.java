package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.NetherLedger;
import com.yayuy.overgrownvegetation.corruption.NetherLedgerData;
import com.yayuy.overgrownvegetation.corruption.PortalCorruptionData;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.spore.field.NetherSporeFieldServer;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * ネザーの胞子の場(2026-10-23 胞子の場と風 F): 風の無い場で発芽してネザーの記録に入る・ポータル越しに両向きで染み出す・
 * 記録の数が発芽したチャンクの数と合う。テスト場から遠いチャンクを使い、終わったら片付ける。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class NetherFieldGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(NetherFieldGameTests.class);
        }

        private Registration() {
        }
    }

    private NetherFieldGameTests() {
    }

    private static ServerLevel nether(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (nether == null) helper.fail("テスト用サーバーにネザーが無い");
        else if (!NetherSporeFieldServer.running()) helper.fail("ネザーの胞子の場が動いていない");
        return nether != null && NetherSporeFieldServer.running() ? nether : null;
    }

    /** 降り積もりがしきい値を越えたネザーのチャンクは発芽し、ネザーの記録(書き換え待ち)に入る。 */
    @GameTest(template = "gametest_empty7", batch = "spore_field_world", timeoutTicks = 200)
    public static void netherFieldSprouts(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        int cx = 1_210_000, cz = -1_210_000;
        long key = ChunkPos.asLong(cx, cz);
        // 2026-11-11: ネザーの場は自分の値(nether_spread.json の spores)。上限の中になるよう、すぐ近くを起点にする
        Double thN = NetherSporeFieldServer.withFieldForTests(f -> f.p.germThreshold * 2);
        double th = thN == null ? DesignData.sporeField().field().germThreshold : thN;
        com.yayuy.overgrownvegetation.corruption.NetherLedger.reachForTests(nether, new ChunkPos(cx + 3, cz), 0);
        NetherSporeFieldServer.watchForTests(cx, cz);
        boolean sprouted = false;
        for (int i = 0; i < 30 && !sprouted; i++) {
            NetherSporeFieldServer.withFieldForTests(f -> {
                f.depositPoint(cx, cz, th * 3);
                return null;
            });
            // 発芽の抽選は時刻で決まるので、回ごとに違う時刻を回す(同じ 60 回を繰り返さない)
            NetherSporeFieldServer.catchUpForTests(nether, DesignData.sporeField().updateTicks() * 60L * (i + 1), true);
            sprouted = Boolean.TRUE.equals(NetherSporeFieldServer.withFieldForTests(f -> f.isCorrupted(cx, cz)));
        }
        NetherLedgerData ledger = NetherLedgerData.get(nether);
        boolean recorded = ledger.isReached(key); // 2026-11-11: 発芽 = 汚染の入口(段階 1 から。過繁茂は段階 3)
        NetherSporeFieldServer.withFieldForTests(f -> {
            f.unsprout(cx, cz);
            f.clearDepositAround(cx, cz, 1.5D);
            f.extractAround(cx, cz, 1.5D, Double.POSITIVE_INFINITY);
            return null;
        });
        ledger.forgetForTests(key);
        if (!sprouted) {
            helper.fail("ネザーの場で発芽しない");
            return;
        }
        if (!recorded) {
            helper.fail("発芽したチャンクがネザーの記録に入らない");
            return;
        }
        helper.succeed();
    }

    /** 場で発芽したチャンクの数だけネザーの記録が増える(同じチャンクは 2 度数えない)。 */
    @GameTest(template = "gametest_empty7", batch = "spore_field_world")
    public static void netherLedgerCountMatchesSprouts(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        NetherLedgerData ledger = NetherLedgerData.get(nether);
        long[] keys = {ChunkPos.asLong(1_220_000, 5), ChunkPos.asLong(1_220_001, 5), ChunkPos.asLong(1_220_002, 5)};
        for (long k : keys) ledger.forgetForTests(k); // 前の回に残った起点の森(下の 1_220_000, 9)が胞子で届いていると +1・+2 にしかならない(2026-11-18 揺れるテストの調べ)
        NetherLedger.reachForTests(nether, new ChunkPos(1_220_000, 9), 0); // 上限(起点から 128 チャンク)の中にする(2026-11-11)
        int before = ledger.reachedCount(); // 2026-11-11: 発芽は汚染の入口(段階の時計の始まり)
        NetherLedger.acceptFieldSprouts(nether, keys);
        NetherLedger.acceptFieldSprouts(nether, new long[]{keys[0]}); // もう一度来ても数えない
        int after = ledger.reachedCount();
        for (long k : keys) ledger.forgetForTests(k);
        ledger.forgetForTests(ChunkPos.asLong(1_220_000, 9)); // 起点の森も残さない(次の回にここから胞子が広がらないよう)
        if (after - before != keys.length) {
            helper.fail("記録の数が合わない: " + before + " → " + after + "(期待 +" + keys.length + ")");
            return;
        }
        helper.succeed();
    }

    /**
     * つながったポータルの対では、ネザー側のポータルのチャンクの胞子がオーバーワールド側へ染み出す。オーバーワールド → ネザーは染み出さない
     * (2026-11-11 ユーザー決定: 片向き。オーバーワールド → ネザーは最初の根が担う)。
     */
    @GameTest(template = "gametest_empty7", batch = "spore_field_world", timeoutTicks = 200)
    public static void portalSeepsOnlyToOverworld(GameTestHelper helper) {
        ServerLevel nether = nether(helper);
        if (nether == null) return;
        ServerLevel ow = helper.getLevel();
        BlockPos owPortal = new BlockPos(1_230_000 * 16 + 4, 70, 9 * 16 + 4);
        BlockPos nePortal = new BlockPos(1_240_000 * 16 + 4, 70, 9 * 16 + 4);
        int ocx = owPortal.getX() >> 4, ocz = owPortal.getZ() >> 4, ncx = nePortal.getX() >> 4, ncz = nePortal.getZ() >> 4;
        PortalCorruptionData data = PortalCorruptionData.get(ow.getServer());
        data.addPair(owPortal, nePortal);
        SporeFieldServer.watchForTests(ocx, ocz);
        NetherSporeFieldServer.watchForTests(ncx, ncz);
        int u = DesignData.sporeField().updateTicks();
        Runnable clean = () -> {
            SporeFieldServer.withFieldForTests(f -> {
                f.extractAround(ocx, ocz, 1.5D, Double.POSITIVE_INFINITY);
                f.clearDepositAround(ocx, ocz, 1.5D);
                return null;
            });
            NetherSporeFieldServer.withFieldForTests(f -> {
                f.extractAround(ncx, ncz, 1.5D, Double.POSITIVE_INFINITY);
                f.clearDepositAround(ncx, ncz, 1.5D);
                return null;
            });
        };
        clean.run();
        // オーバーワールド → ネザー
        SporeFieldServer.withFieldForTests(f -> {
            f.emitPoint(ocx, ocz, 2.0D);
            return null;
        });
        SporeFieldServer.catchUpForTests(ow, u);            // オーバーワールドの写し(濃い)
        NetherSporeFieldServer.catchUpForTests(nether, u, false); // 当てはめで染み出しを積む
        NetherSporeFieldServer.catchUpForTests(nether, u, false); // 積んだ分を取り込む
        SporeFieldServer.Cell inNether = NetherSporeFieldServer.cell(ncx, ncz);
        double toNether = inNether == null ? 0 : inNether.conc();
        // ネザー → オーバーワールド(オーバーワールド側を空にしてから)
        SporeFieldServer.withFieldForTests(f -> {
            f.extractAround(ocx, ocz, 1.5D, Double.POSITIVE_INFINITY);
            return null;
        });
        NetherSporeFieldServer.withFieldForTests(f -> {
            f.emitPoint(ncx, ncz, 2.0D);
            return null;
        });
        SporeFieldServer.catchUpForTests(ow, u);
        NetherSporeFieldServer.catchUpForTests(nether, u, false);
        SporeFieldServer.catchUpForTests(ow, u);
        SporeFieldServer.Cell inOw = SporeFieldServer.cell(ocx, ocz);
        double toOverworld = inOw == null ? 0 : inOw.conc();
        clean.run();
        data.clearPairsForTests();
        if (toNether > 1e-6) {
            helper.fail("オーバーワールド側の胞子がネザー側へ染み出した(片向きのはず): " + toNether);
            return;
        }
        if (toOverworld <= 0.0D) {
            helper.fail("ネザー側の胞子がオーバーワールド側へ染み出さない");
            return;
        }
        helper.succeed();
    }
}
