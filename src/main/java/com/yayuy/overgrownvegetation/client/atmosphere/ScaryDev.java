package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.atmosphere.sound.FarPresenceSound;

/**
 * 怖い現象 A・D・E の確かめ用の命令(試験用クライアントの devbridge から。2026-11-16)。{@code scary <中身>}、返事は {@code SCARY …}。
 * <ul>
 *     <li>{@code shadow [手前の距離]} — 林冠の影を今いる所の上へ通す(既定は 150 ブロック手前から)。</li>
 *     <li>{@code figure [種] [距離]} — 霧の影を出す。距離を書くと、見ている向きのまっすぐ先のその距離へ条件を見ずに置く(形の確かめ)。
 *     種は falcimnes などか {@code any}。</li>
 *     <li>{@code figure clear} — 今の霧の影を消す。{@code always on|off} — 霧・昼夜の条件を見ない(霧の無い所で形を見る用)。</li>
 *     <li>{@code sound [ファイル名]} — 遠くの音を 1 回鳴らす(far_crawl_1 など。省くとくじ)。</li>
 *     <li>{@code info} — 3 つの様子。</li>
 * </ul>
 */
public final class ScaryDev {

    private ScaryDev() {
    }

    public static String run(String arg) {
        String[] a = arg == null ? new String[0] : arg.trim().split("\\s+");
        String what = a.length > 0 ? a[0] : "info";
        try {
            switch (what) {
                case "shadow":
                    return PassingShadow.force(a.length > 1 ? Double.parseDouble(a[1]) : 150.0);
                case "figure":
                    if (a.length > 1 && a[1].equals("clear")) {
                        FogFigures.clear();
                        return FogFigures.describe();
                    }
                    return FogFigures.force(a.length > 1 && !a[1].equals("any") ? a[1] : "", a.length > 2 ? Double.parseDouble(a[2]) : 0.0);
                case "always":
                    FogFigures.devAlways = a.length > 1 && a[1].equals("on");
                    return "霧の影: 条件を見ない = " + FogFigures.devAlways;
                case "sound":
                    return FarPresenceSound.force(a.length > 1 ? a[1] : "");
                default:
                    return PassingShadow.describe() + " / " + FogFigures.describe() + " / " + FarPresenceSound.describe() + " / " + DenseFog.describe();
            }
        } catch (RuntimeException e) {
            return "失敗: " + e;
        }
    }
}
