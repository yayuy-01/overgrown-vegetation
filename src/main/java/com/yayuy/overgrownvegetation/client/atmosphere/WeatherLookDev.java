package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.data.WeatherLookEye;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import java.util.Arrays;
import java.util.Locale;

/**
 * 天気の見た目の残り(2026-11-16、{@link WeatherLook})の確かめ用の命令(試験用クライアントの {@code wx …}。返事は {@code WX …})。
 * <ul>
 *     <li>{@code wx} / {@code wx status}: 今の値と各演出の様子。</li>
 *     <li>{@code wx w <fog> <calm> <gale> <after> <thunder> <pre>}: 届いた天気を上書き({@link WeatherClient#debugOverride})。{@code wx w off} で戻す。
 *     ({@code /ov weather} で強制できるのは fog・calm・gale だけなので、雷雨の後・前触れ・雷雨の強さはここで動かす。)</li>
 *     <li>{@code wx off <名前>} / {@code wx on [名前]}: 演出を 1 つ止める・戻す(far・sprite・drift・elmo・arcus・calm・gale・layers)。前後の絵と重さの比べ用。</li>
 *     <li>{@code wx set <節>.<名前> <数…>}: 値を 1 つ差し替える(節は storm・calm・gale。{@code wx set reset} で全部戻す)。{@code wx values} で一覧。</li>
 *     <li>{@code wx far}: 一番近い遠い積乱雲を今すぐ光らせる。{@code wx sprite [距離] [yaw] [雲の天辺]}: スプライトを 1 つ出す(既定は 2500 ブロック先・見ている向き)。</li>
 * </ul>
 */
public final class WeatherLookDev {

    private WeatherLookDev() {
    }

    public static String command(String arg) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            return "WX ワールドの中で";
        }
        String[] a = arg == null || arg.isBlank() ? new String[0] : arg.trim().split("\\s+");
        String what = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        try {
            switch (what) {
                case "", "status" -> {
                    return "WX " + WeatherLook.describe() + " / 止めている " + WeatherLook.OFF;
                }
                case "w" -> {
                    if (a.length > 1 && a[1].equals("off")) {
                        WeatherClient.clearDebugOverride();
                        return "WX 上書きを戻した";
                    }
                    float[] v = new float[6];
                    for (int i = 0; i < 6; i++) {
                        v[i] = a.length > i + 1 ? Float.parseFloat(a[i + 1]) : 0.0F;
                    }
                    WeatherClient.debugOverride(v[0], v[1], v[2], v[3], v[4], v[5]);
                    return String.format("WX 上書き 濃霧 %.2f 凪 %.2f 強風 %.2f 雷雨の後 %.2f 雷雨 %.2f 前触れ %.2f", v[0], v[1], v[2], v[3], v[4], v[5]);
                }
                case "sky" -> { // 空の試し(11-18): 丸み・地平線のかすみ・低めの筋雲
                    return com.yayuy.overgrownvegetation.client.sky.SkyDev.set(a.length > 1 ? a[1] : "", a.length > 2 ? Float.parseFloat(a[2]) : 0.0F);
                }
                case "amute" -> { // 雰囲気の演出を名前で止める(切り分け用)。clear で全部戻す
                    if (a.length > 1 && a[1].equals("clear")) {
                        Atmosphere.DEV_MUTE.clear();
                    } else if (a.length > 1) {
                        Atmosphere.DEV_MUTE.add(a[1]);
                    }
                    return "WX amute " + Atmosphere.DEV_MUTE;
                }
                case "shared" -> { // 雲の一覧の使い回し(11-18 試し。前後の絵と bench の比べ用)
                    com.yayuy.overgrownvegetation.client.sky.VoxelClouds.sharedStorms = a.length > 1 && a[1].equals("on");
                    return "WX shared " + com.yayuy.overgrownvegetation.client.sky.VoxelClouds.sharedStorms;
                }
                case "off" -> {
                    WeatherLook.OFF.add(a[1]);
                    return "WX 止めた " + WeatherLook.OFF;
                }
                case "on" -> {
                    if (a.length > 1) {
                        WeatherLook.OFF.remove(a[1]);
                    } else {
                        WeatherLook.OFF.clear();
                    }
                    return "WX 止めている " + WeatherLook.OFF;
                }
                case "set" -> {
                    if (a.length > 1 && a[1].equals("reset")) {
                        WeatherLook.devStorm = null;
                        WeatherLook.devCalm = null;
                        WeatherLook.devGale = null;
                        return "WX 値を戻した";
                    }
                    String[] key = a[1].split("\\.", 2);
                    float[] v = new float[a.length - 2];
                    for (int i = 0; i < v.length; i++) {
                        v[i] = Float.parseFloat(a[i + 2]);
                    }
                    WeatherLookEye.Params base = switch (key[0]) {
                        case "storm" -> WeatherLook.storm();
                        case "calm" -> WeatherLook.calmParams();
                        case "gale" -> WeatherLook.gale();
                        default -> null;
                    };
                    if (base == null || key.length < 2 || !base.has(key[1])) {
                        return "WX 無い名前 " + a[1] + "(節は storm・calm・gale)";
                    }
                    WeatherLookEye.Params next = base.with(key[1], v);
                    switch (key[0]) {
                        case "storm" -> WeatherLook.devStorm = next;
                        case "calm" -> WeatherLook.devCalm = next;
                        default -> WeatherLook.devGale = next;
                    }
                    return "WX " + a[1] + " = " + Arrays.toString(key[1].endsWith("Color") ? next.rgb(key[1]) : new float[]{next.f(key[1])});
                }
                case "far" -> {
                    // far hold = 光りっぱなしにする(撮る用)。far free = 戻す。
                    if (a.length > 1) {
                        HorizonLightning.devHold = a[1].equals("hold");
                    }
                    return "WX " + HorizonLightning.force(level, player);
                }
                case "arcus" -> {
                    // arcus <前の縁までの距離> = その位置で止める(正 = 風上にある)。arcus off = 自動へ戻す。
                    ArcusCloud.devLead = a.length > 1 && !a[1].equals("off") ? Double.parseDouble(a[1]) : Double.NaN;
                    if (Double.isNaN(ArcusCloud.devLead)) {
                        ArcusCloud.clear();
                    }
                    return "WX " + ArcusCloud.describe() + " 風下の向き(yaw)" + Math.round(ArcusCloud.downwindYaw());
                }
                case "elmo" -> {
                    return "WX " + StElmo.nearest(player);
                }
                case "sprite" -> {
                    double dist = a.length > 1 ? Double.parseDouble(a[1]) : 2500.0;
                    double yaw = Math.toRadians(a.length > 2 ? Double.parseDouble(a[2]) : player.getYRot());
                    double x = player.getX() - Math.sin(yaw) * dist;
                    double z = player.getZ() + Math.cos(yaw) * dist;
                    Sprites.spawn(x, z, a.length > 3 ? Float.parseFloat(a[3]) : 1200.0F);
                    return String.format("WX スプライト %.0f %.0f", x, z);
                }
                case "values" -> {
                    return "WX storm " + WeatherLook.storm().describe() + " / calm " + WeatherLook.calmParams().describe() + " / gale "
                            + WeatherLook.gale().describe();
                }
                default -> {
                    return "WX 使い方: status / w <fog calm gale after thunder pre>|off / off|on <名前> / set <節.名前> <数> / far / sprite / values";
                }
            }
        } catch (RuntimeException e) {
            return "WX 失敗 " + e;
        }
    }
}
