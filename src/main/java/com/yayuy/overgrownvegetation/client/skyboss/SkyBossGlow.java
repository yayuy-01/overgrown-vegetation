package com.yayuy.overgrownvegetation.client.skyboss;

import com.yayuy.overgrownvegetation.skyboss.SkyBossFlight;

/**
 * 電紋・避雷針・扇の先の光り方(2026-10-19)。光る層({@link SkyBossGlowLayer}・シェーダー {@code sky_boss_glow})に渡す値を、両側が同じ式で進める
 * 飛び方の値({@link SkyBossFlight} の溜め・予告・放電・雷の届いた tick)から出す。
 * <ul>
 *   <li>決定「溜まるにつれ、避雷針から電紋に光が満ちていく」→ 光の先 = 溜め(電紋の型の赤 = 避雷針からの割合がこれより小さい所が光る)。</li>
 *   <li>決定「溜まっている間は電紋がうっすら光り続ける(= 今は撃てる、の合図)」→ 溜まりきったらゆっくり脈打つ弱い光。</li>
 *   <li>雷が避雷針に届くと、道具の {@code SkyGlaucusFlow} と同じ流れ(1.6 秒で避雷針から刃の先へ、0.45 秒で消える)。</li>
 *   <li>予告: だんだん強く・速く脈打ち、避雷針と扇の先も光る。決定「放電中は電紋が激しく発光し、避雷針も発光する」→ 放電中はちらつく強い光。</li>
 * </ul>
 * 光の強さ・脈の速さはすべて仮。色は道具の電紋の光の色(扇の先の色から取った値。{@code SkyGlaucusFlow.GLOW / SOFT})。
 */
final class SkyBossGlow {

    /** 光の先の明るい黄緑と、後ろの柔らかい黄緑(道具 SkyGlaucusFlow の GLOW・SOFT)。 */
    static final int GLOW = 0xBAFF00;
    static final int SOFT = 0x9CC608;
    /** 雷を受けたときの流れ(秒。道具と同じ)。 */
    private static final double FLOW_TICKS = 1.6 * 20.0;
    /** 放電が終わってから光が消えるまで(tick)。 */
    private static final double FADE_TICKS = 30.0;

    /** シェーダーに渡す値。 */
    static float front;
    static float level;
    static float pulse;
    static float rod;
    static float tip;
    static float bud;
    static float flicker;

    /** 時刻 t(描画の時刻)の光り方を出す。何も光らなければ false。 */
    static boolean compute(SkyBossFlight f, double t) {
        float c = Math.max(0.0F, Math.min(1.0F, f.charge));
        front = c;
        level = 0.0F;
        pulse = -1.0F;
        rod = 0.0F;
        tip = 0.0F;
        bud = 0.0F;
        flicker = 0.0F;
        if (c > 0.0F) {
            level = c >= 1.0F ? (float) (0.2 + 0.07 * Math.sin(t * 0.08)) : 0.3F;
            tip = 0.25F * c;
        }
        if (f.shudderAt != SkyBossFlight.NONE && t >= f.shudderAt) {
            double p = (t - f.shudderAt) / FLOW_TICKS;
            if (p < 2.0) {
                pulse = (float) p;
            }
        }
        // 10-19 の技: 蕾の芯(決定「開く 1 秒前に芯が光る → 撃つたびに芯が脈打つ」)
        int bp = f.budsPhase(t);
        if (bp == 1) {
            bud = (float) Math.min(1.0, (t - f.budsAt) / com.yayuy.overgrownvegetation.skyboss.SkyBossTuning.AA_GLOW_TICKS) * 0.9F;
        } else if ((bp == 2 || bp == 3) && f.budsKind == com.yayuy.overgrownvegetation.skyboss.SkyBossBeam.BUDS_KIND) {
            // 転がりのビーム: 開いた蕾の芯が強く光り続ける(ビームの元)
            bud = (float) (0.9 + 0.1 * hash((long) Math.floor(t * 0.5) * 7L));
        } else if (bp == 2 || bp == 3) {
            bud = (float) (0.65 + 0.3 * Math.max(0.0, Math.sin(t * 0.33)));
        } else if (bp == 4) {
            bud = (float) Math.max(0.0, (f.budsEnd - t) / com.yayuy.overgrownvegetation.skyboss.SkyBossTuning.BUDS_CLOSE_TICKS) * 0.5F;
        }
        // 眠っている間は電紋がゆっくり脈打つ(10-19 夕方。決定「夜は扇を半分たたんで眠り、電紋がゆっくり脈打つ」。強さ・速さは仮)
        if (f.restKind == com.yayuy.overgrownvegetation.skyboss.SkyBossFlight.REST_SLEEP) {
            double beat = 0.5 + 0.5 * Math.sin(t * (2.0 * Math.PI / 160.0));
            front = 1.0F;
            level = (float) Math.max(level, 0.06 + 0.16 * beat * beat);
        }
        if (f.chargingUp(t)) {
            double span = Math.max(1.0, f.dischargeAt - f.chargeUpAt);
            double k = Math.max(0.0, Math.min(1.0, (t - f.chargeUpAt) / span));
            double beat = 0.5 + 0.5 * Math.sin(t * (0.15 + 0.5 * k));
            front = 1.0F;
            level = (float) Math.max(level, 0.3 + 0.45 * k + 0.15 * k * beat);
            rod = (float) (0.3 + 0.6 * k * (0.7 + 0.3 * beat));
            tip = (float) Math.max(tip, 0.3 + 0.5 * k);
        }
        if (f.discharging(t)) {
            double n = hash((long) Math.floor(t * 0.5));
            front = 1.0F;
            level = 0.9F;
            flicker = (float) (0.8 + 0.2 * n);
            rod = (float) (0.85 + 0.15 * hash((long) Math.floor(t) * 31L));
            tip = 0.9F;
        } else if (f.dischargeEnd != SkyBossFlight.NONE && t >= f.dischargeEnd && t < f.dischargeEnd + FADE_TICKS
                && f.dischargeAt != SkyBossFlight.NONE && f.dischargeAt < f.dischargeEnd) {
            // 終わった・ショートした直後: 速く消える
            float k = (float) (1.0 - (t - f.dischargeEnd) / FADE_TICKS);
            front = 1.0F;
            level = Math.max(level, 0.8F * k * k);
            rod = Math.max(rod, 0.8F * k * k);
            tip = Math.max(tip, 0.6F * k * k);
        }
        return level > 0.005F || pulse >= 0.0F || rod > 0.005F || tip > 0.005F || bud > 0.005F || flicker > 0.0F;
    }

    private static double hash(long v) {
        long h = v * 0x9E3779B97F4A7C15L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (h >>> 11) * 0x1.0p-53;
    }

    private SkyBossGlow() {
    }
}
