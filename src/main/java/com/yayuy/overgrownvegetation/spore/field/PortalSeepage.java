package com.yayuy.overgrownvegetation.spore.field;

import com.yayuy.overgrownvegetation.corruption.PortalCorruptionData;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/**
 * <b>ポータル越しの胞子の染み出し</b>(2026-10-23 胞子の場と風 F。追記 15【決定】)。前の「ポータルの向こうに汚染の球を置く」専用の仕組みの代わり。
 *
 * <p>つながったネザーポータルの対({@link PortalCorruptionData#pairs})ごとに、ネザーの場を 1 回更新するたび、
 * 両側のポータルのチャンクの浮遊 + 林床の {@code sources.portalSeepPercent}%(仮決め 3%)を向こう側のポータルのチャンクへ移す。
 * 両向きとも同じ式で、移した分は元の側から引く(足し算の合計は変わらない)。向こう側で発芽するかは場まかせ。
 * 値は直前の写し(5 秒前)から読むので、ポータルのチャンクはプレイヤーがいなくても写すようにしておく。
 */
public final class PortalSeepage {

    private PortalSeepage() {
    }

    /** ネザーの場を当てはめた後(サーバースレッド)。 */
    static void afterUpdate(MinecraftServer server) {
        long[] pairs = PortalCorruptionData.get(server).pairs();
        if (pairs.length == 0) return;
        double frac = DesignData.sporeField().portalSeepPercent() / 100.0D;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            BlockPos ow = BlockPos.of(pairs[i]), ne = BlockPos.of(pairs[i + 1]);
            int ocx = ow.getX() >> 4, ocz = ow.getZ() >> 4, ncx = ne.getX() >> 4, ncz = ne.getZ() >> 4;
            SporeFieldServer.watch(ocx, ocz);
            NetherSporeFieldServer.watch(ncx, ncz);
            if (frac <= 0) continue;
            SporeFieldServer.Cell a = SporeFieldServer.cell(ocx, ocz);
            SporeFieldServer.Cell b = NetherSporeFieldServer.cell(ncx, ncz);
            double toNether = a == null ? 0 : a.conc() * frac;
            double toOverworld = b == null ? 0 : b.conc() * frac;
            if (toNether > 0) {
                SporeFieldServer.extractCell(ocx, ocz, toNether);
                NetherSporeFieldServer.emitPoint(ncx, ncz, toNether);
            }
            if (toOverworld > 0) {
                NetherSporeFieldServer.INPUTS.add(f -> f.extractAround(ncx, ncz, 0.0D, toOverworld));
                SporeFieldServer.emitCell(ocx, ocz, toOverworld);
            }
        }
    }

    /** テスト用: 1 回分の染み出しをすぐ積む。 */
    public static void seepNowForTests(MinecraftServer server) {
        afterUpdate(server);
    }
}
