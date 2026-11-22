package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.EcoCellsPayload;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;

/**
 * サーバーから届く「まわりの升の環境生物の賑わい」の受け口(2026-11-17。{@link EcoCellsPayload})。
 * 見た目だけの小さな生き物の数を升の頭数に合わせるため。値を持つだけで、寄せる(滑らかにする)のは見た目の側。
 *
 * <ul>
 *   <li>{@link #liveliness(double, double)} = そのブロック座標の升の賑わい 0〜1(環境生物の満員に対する割合の平均)。
 *   {@link #prey}・{@link #hunter} で別々にも。</li>
 *   <li>値の無い所(届く前・範囲の外・オーバーワールドの外・台帳が切ってある)は 1.0(満員扱い)。</li>
 *   <li>サーバーは 2 秒おきに調べ、中心の升か値が変わったときだけ送る。ログアウトで消す。</li>
 *   <li>確かめ用の上書き {@link #debugOverride}: 上書き中はどこでもその値。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class EcoCellsClient {

    private static volatile EcoCellsPayload last = EcoCellsPayload.NONE;
    private static boolean received;
    private static long lastTick;
    private static volatile float override = -1.0F;

    private EcoCellsClient() {
    }

    public static void accept(EcoCellsPayload payload) {
        last = payload;
        received = true;
        var mc = net.minecraft.client.Minecraft.getInstance();
        lastTick = mc.level != null ? mc.level.getGameTime() : 0L;
    }

    /** そのブロック座標の升の賑わい(0〜1)。 */
    public static float liveliness(double x, double z) {
        return (prey(x, z) + hunter(x, z)) * 0.5F;
    }

    /** そのブロック座標の升の獲物の環境生物の頭数 ÷ 満員(0〜1)。 */
    public static float prey(double x, double z) {
        float o = override;
        if (o >= 0.0F) return o;
        EcoCellsPayload p = last;
        int i = index(p, x, z);
        return i < 0 ? 1.0F : EcoCellsPayload.unpack(p.prey()[i]);
    }

    /** そのブロック座標の升の狩る側の環境生物の頭数 ÷ 満員(0〜1)。 */
    public static float hunter(double x, double z) {
        float o = override;
        if (o >= 0.0F) return o;
        EcoCellsPayload p = last;
        int i = index(p, x, z);
        return i < 0 ? 1.0F : EcoCellsPayload.unpack(p.hunter()[i]);
    }

    private static int index(EcoCellsPayload p, double x, double z) {
        if (p.empty()) return -1;
        int s = 4 + p.shift();
        return p.index(((int) Math.floor(x)) >> s, ((int) Math.floor(z)) >> s);
    }

    /** 1 通でも届いたか。 */
    public static boolean received() {
        return received;
    }

    /** 最後に届いたときのクライアントの tick。 */
    public static long lastTick() {
        return lastTick;
    }

    /** 今持っている中身(確かめ・デバッグ表示用)。 */
    public static EcoCellsPayload last() {
        return last;
    }

    /** 確かめ用: どこでも value(0〜1)にする。clearDebugOverride で戻す。 */
    public static void debugOverride(float value) {
        override = Math.max(0.0F, Math.min(1.0F, value));
    }

    public static void clearDebugOverride() {
        override = -1.0F;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        last = EcoCellsPayload.NONE;
        received = false;
        lastTick = 0L;
        override = -1.0F;
    }
}
