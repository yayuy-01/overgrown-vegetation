package com.yayuy.overgrownvegetation.perf;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.gametest.framework.GameTestServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * 水・溶岩の広がりの近道(2026-10-06 夜、ユーザーの質問「バニラの地形生成をどこまで軽くできるか」の試作。
 * 本体は {@link com.yayuy.overgrownvegetation.mixin.FlowingFluidSpreadMixin})。
 *
 * <p>バニラの {@code FlowingFluid#getSpread} は、横の 4 マスそれぞれについて「そこに何が流れ込むか」({@code getNewLiquid}。
 * そのマスの周り 6 マスを読み、NeoForge の {@code CreateFluidSourceEvent} も出す)を先に計算してから、
 * 「そもそも流れ込めるか」({@code canPassThrough} = 同じ液体の水源でない・壁の形が通す・液体を入れられるブロック)を調べる。
 * 流れ込めないマスでは先の計算は捨てられる。近道は、液体の種類に関係なく決まる「流れ込めない」を先に調べ、当たれば先の計算を省く
 * (液体を入れられるかが液体の種類で変わる {@code LiquidBlockContainer}(水没できるブロック)だけは今までどおり先に計算する)。
 * 流れ込めないマスでは先の計算の答えは使われないので、水の流れ方は 1 マスも変わらない。省くのは計算と、そのマスでの
 * {@code CreateFluidSourceEvent} の発行だけ(この mod は聞いていない)。真下のマス({@code spread} の先頭の {@code getNewLiquid})も同じ形で
 * 省く版を試したが、省けるのが元々軽い「下が石」ばかりで差が測れなかったので入れていない(同日夜)。
 *
 * <p>重い場面は生成の直後: 地下の帯水層の水は「後で 1 回流してみる」印が付き、チャンクが仕上がるときにサーバースレッドで一斉に流す
 * ({@code LevelChunk#postProcessGeneration})。計測(10-06 夜、新しい地形を飛ぶ)でサーバースレッドの 59% がこれで、その 98% が
 * {@code getSpread} の中、実際に水を置くのは 0.2% だった。近道を入れると横のマスの 99.8% で計算を省け、生成したチャンクあたりの
 * 水の後処理の時間が 約 6 割減った(同じシード・同じ道で切った回と比べて。チャンクの数は地形のノイズの時間で揃えた)。
 *
 * <p>GameTest のサーバーでは「検算」で動く: 省く判定をしたマスでも本来の計算をして、本当に流れ込めないかを確かめて数える
 * (返すのは本来の答えなので、テストの中の水はバニラそのもの)。食い違いがあれば {@link #mismatches()} に出る。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FluidSpreadShortcut {

    /** 近道を使うか(既定は使う。{@code /ov perf fluid off} で切れる。保存しない)。 */
    private static volatile boolean enabled = true;
    /** 検算(省く判定をしたマスでも本来の計算をして確かめる。本来の答えを返す)。 */
    private static volatile boolean checking;

    // 表示用の数(サーバースレッドから増やす。ほかのスレッドから呼ばれても表示がずれるだけ)。
    private static long neighbours;
    private static long skipped;
    private static long checked;
    private static long mismatches;

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean checking() {
        return checking;
    }

    public static void setChecking(boolean value) {
        checking = value;
    }

    /** 横のマスを 1 つ見た({@code skip} = 先の計算を省ける)。 */
    public static void count(boolean skip) {
        neighbours++;
        if (skip) {
            skipped++;
        }
    }

    /** 検算の結果({@code ok} = 本当に流れ込めなかった)。 */
    public static void verified(boolean ok) {
        checked++;
        if (!ok) {
            mismatches++;
            OvergrownVegetation.LOGGER.error("[FluidSpreadShortcut] 省いたマスに本当は流れ込めた(近道の条件の誤り)。検算 {} 件中 {} 件目", checked, mismatches);
        }
    }

    public static long neighbours() {
        return neighbours;
    }

    public static long skipped() {
        return skipped;
    }

    public static long checked() {
        return checked;
    }

    public static long mismatches() {
        return mismatches;
    }

    public static void resetCounts() {
        neighbours = 0;
        skipped = 0;
        checked = 0;
        mismatches = 0;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        resetCounts();
        enabled = true;
        checking = event.getServer() instanceof GameTestServer;
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        if (checked > 0) {
            OvergrownVegetation.LOGGER.info("[FluidSpreadShortcut] 検算 {} 件、食い違い {} 件(横のマス {} 件中 省けた {} 件)", checked, mismatches, neighbours, skipped);
        }
        checking = false;
    }

    private FluidSpreadShortcut() {
    }
}
