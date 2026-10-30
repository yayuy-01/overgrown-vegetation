package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.SporeFieldTable;
import com.yayuy.overgrownvegetation.network.SporeDustPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;

/**
 * 画面側の胞子の空気(2026-10-23 胞子の場と風 G)。サーバーが場の更新ごとに送る {@link SporeDustPayload} から、
 * まわり 13 × 13 チャンクの浮遊の濃度(プルーム)・プレイヤーのいる所の濃度(緑の霧)・近くの巨大樹の梢(葉から流れる胞子)を持つ。
 * 濃度は 5 秒おきにしか来ないので、霧はなめらかに寄せる。
 */
public final class ClientSporeAir {

    private static ClientLevel lastLevel;
    private static int minCx, minCz, size;
    private static byte[] air = new byte[0];
    /** 受け取ったプレイヤーの所の濃度(%)と、画面に効かせている値(なめらかに寄せる)。 */
    private static float target, shown;
    private static int[] trees = new int[0];
    /** 嵐で霧を最大へ寄せる(H)。0〜1。 */
    private static float stormBoost;

    private ClientSporeAir() {
    }

    static void accept(SporeDustPayload p) {
        resetIfLevelChanged();
        int oldMinCx = minCx, oldMinCz = minCz;
        boolean hadGrid = air.length > 0;
        minCx = p.minCx();
        minCz = p.minCz();
        size = p.size();
        air = p.air();
        target = p.hereTenths() / 10.0F;
        trees = p.trees();
        fronts = p.fronts();
        ClientLevel lv = Minecraft.getInstance().level;
        frontsTick = lv != null ? lv.getGameTime() : 0L;
        // テレポート・次元移動の後は前の値から寄せない(窓が 3 チャンク以上跳んだ・次元が替わった最初の値はそのまま。2026-10-24)
        if (snapNext || hadGrid && Math.max(Math.abs(minCx - oldMinCx), Math.abs(minCz - oldMinCz)) > 3) shown = target;
        snapNext = false;
    }

    /** 次の値をなめらかに寄せずにそのまま使う(次元が替わった直後)。 */
    private static boolean snapNext = true;

    private static void resetIfLevelChanged() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != lastLevel) {
            lastLevel = level;
            air = new byte[0];
            trees = new int[0];
            fronts = new int[0];
            target = shown = 0.0F;
            snapNext = true;
        }
    }

    /** 毎フレーム呼ぶ(霧の色を決める所から)。約 3 秒で寄せる。 */
    static void advance(float frameSeconds) {
        resetIfLevelChanged();
        float k = 1.0F - (float) Math.exp(-Math.max(0.0F, frameSeconds) / 3.0F);
        shown += (target - shown) * k;
        float storm = ClientStorm.sporeStorm();
        stormBoost += (storm - stormBoost) * k;
    }

    /** 画面に効かせているプレイヤーの所の濃度(%)。 */
    public static float percentHere() {
        return shown;
    }

    /** 緑の霧の強さ 0〜1(濃度が始まりの値で 0、満ちる値で 1。嵐の間は最大へ)。 */
    public static float fogStrength() {
        SporeFieldTable t = DesignData.sporeField();
        float s = (float) Mth.clamp((shown - t.fogStartPercent()) / (t.fogFullPercent() - t.fogStartPercent()), 0.0D, 1.0D);
        return Math.max(s, stormBoost);
    }

    /** チャンクの浮遊の濃度(%)。送られた範囲の外は 0。 */
    public static float airPercent(int cx, int cz) {
        int dx = cx - minCx, dz = cz - minCz;
        if (dx < 0 || dz < 0 || dx >= size || dz >= size || air.length != size * size) return 0.0F;
        return (air[dz * size + dx] & 0xFF) * 2.0F;
    }

    public static int gridMinCx() {
        return minCx;
    }

    public static int gridMinCz() {
        return minCz;
    }

    public static int gridSize() {
        return air.length == size * size ? size : 0;
    }

    private static int[] fronts = new int[0];
    private static long frontsTick;

    /** 地割れの圧の前線 {中心 x, z, 受け取ったときの半径, 速さ × 100(ブロック/秒), 止まる半径}(ブロック、2026-10-24 追記 19)。 */
    public static int[] fronts() {
        return fronts;
    }

    /** 前線を受け取ったゲーム時刻(半径を今へ進めるのに使う)。 */
    public static long frontsTick() {
        return frontsTick;
    }

    /** 近くの巨大樹の梢 {x, z, (梢の高さ << 8) | 育ち具合 0〜255, …}。 */
    public static int[] trees() {
        return trees;
    }
}
