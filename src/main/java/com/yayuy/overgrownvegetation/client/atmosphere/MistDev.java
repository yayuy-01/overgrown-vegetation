package com.yayuy.overgrownvegetation.client.atmosphere;

/**
 * 霧(第 3 段)の開発用の口(Claude のテストクライアントの DevBridge の {@code fog}。撮り比べと重さの測定のためにこのセッションだけ替える)。
 * {@code fog status} = 今の霧 / {@code fog march on|off} = 細部(レイマーチ)の出し入れ / {@code fog time on|off} = GPU の時間を測る /
 * {@code fog silhouette on|off} = 巨大樹のシルエットの出し入れ / {@code fog stat} = 測った平均。返事は {@code FOG …}。
 */
public final class MistDev {

    public static String command(String arg) {
        String[] a = arg.trim().split("\s+");
        switch (a[0]) {
            case "march" -> MistMarch.devOff = a.length > 1 && a[1].equalsIgnoreCase("off");
            case "silhouette" -> GiantSilhouettes.devOff = a.length > 1 && a[1].equalsIgnoreCase("off");
            case "time" -> MistMarch.timing = a.length > 1 && a[1].equalsIgnoreCase("on");
            case "stat" -> {
                return "FOG 細部 " + MistMarch.takeStat();
            }
            case "status", "" -> {
            }
            default -> {
                return "FOG 知らない項目 " + a[0];
            }
        }
        return "FOG " + MistFog.describe() + " 細部 " + (MistMarch.willDraw() ? "描く" : "描かない") + (MistMarch.devOff ? "(止めた)" : "");
    }

    private MistDev() {
    }
}
