package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;

/**
 * <b>空気の霞み具合</b>(0〜1。提案 2、2026-10-30 ユーザー採用「光の筋は空気に何かあるときだけはっきり見える」)。光の筋・木漏れ日のうっすらした筋・
 * 筋の中の光る胞子の数に掛ける(地面に落ちた光 = 木漏れ日の斑点と筋の足元のにじみには掛けない。空気ではなく地面の明るさなので)。
 *
 * <p>乾いた午後は {@code hazeDry}(仮 0.45)。次のいちばん強いもので 1 へ寄せる:
 * <ul>
 *     <li>朝の湿り気: 朝 5〜8 時は 1、8〜9 時で 0 へ(4〜5 時で出てくる)。</li>
 *     <li>雨上がり: 雨の間と、雨が止んでから {@code afterRainMinutes}(仮 4 分)かけて 0 へ。</li>
 *     <li>胞子の濃さ: サーバーから 5 秒ごとに届くプレイヤーの所の胞子濃度(%、{@code SporeAirPayload})が {@code sporeHazeFrom} → {@code sporeHazeFull} で 0 → 1。</li>
 * </ul>
 * 変わり目は 2 秒の時定数で寄せる。数値はすべて仮決め。
 */
public final class AirHaze {

    private static final float SMOOTH_TICKS = 40.0F;

    private static float haze = 1.0F;
    private static float sporePercent;
    private static float sporeShown;
    /** 雨が止んでからの tick(雨の間は 0。一度も降っていなければ大きい値)。 */
    private static long sinceRain = Long.MAX_VALUE / 2;

    /** 開発用のログ: 届いている胞子濃度(%)。 */
    static float sporePercent() {
        return sporePercent;
    }

    /** 今の霞み具合(0〜1)。 */
    public static float haze() {
        return haze;
    }

    /** サーバーから届いたプレイヤーの所の胞子濃度(%)。 */
    public static void setSporePercent(float percent) {
        sporePercent = Math.max(0.0F, percent);
    }

    static void clear() {
        haze = 1.0F;
        sporePercent = 0.0F;
        sporeShown = 0.0F;
        sinceRain = Long.MAX_VALUE / 2;
    }

    /** 毎 tick({@link Atmosphere} から)。 */
    static void tick(ClientLevel level) {
        var t = DesignData.atmosphere();
        long day = Math.floorMod(level.getDayTime(), 24000L);
        // 5:00 = 23000、8:00 = 2000、9:00 = 3000(0 = 6:00)。4:00(22000)から出てくる。
        float morning;
        if (day >= 22000L) {
            morning = SunLight.smooth(Mth.clamp((day - 22000L) / 1000.0F, 0.0F, 1.0F));
        } else if (day <= 2000L) {
            morning = 1.0F;
        } else {
            morning = 1.0F - SunLight.smooth(Mth.clamp((day - 2000L) / 1000.0F, 0.0F, 1.0F));
        }
        float rain = level.getRainLevel(1.0F);
        if (rain > 0.2F) {
            sinceRain = 0L;
        } else if (sinceRain < Long.MAX_VALUE / 2) {
            sinceRain++;
        }
        float afterTicks = Math.max(1.0F, t.afterRainMinutes() * 1200.0F);
        float afterRain = Math.max(rain, 1.0F - SunLight.smooth(Mth.clamp(sinceRain / afterTicks, 0.0F, 1.0F)));
        sporeShown += (sporePercent - sporeShown) * 0.05F;
        float spore = SunLight.smooth(Mth.clamp((sporeShown - t.sporeHazeFrom()) / (t.sporeHazeFull() - t.sporeHazeFrom()), 0.0F, 1.0F));
        float target = t.hazeDry() + (1.0F - t.hazeDry()) * Math.max(morning, Math.max(afterRain, spore));
        haze += (target - haze) / SMOOTH_TICKS;
    }

    private AirHaze() {
    }
}
