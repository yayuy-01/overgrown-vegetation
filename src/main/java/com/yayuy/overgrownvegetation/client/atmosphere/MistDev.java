package com.yayuy.overgrownvegetation.client.atmosphere;

/**
 * 霧(第 3 段)の開発用の口(Claude のテストクライアントの DevBridge の {@code fog}。撮り比べと重さの測定のためにこのセッションだけ替える)。
 * {@code fog status} = 今の霧 / {@code fog march on|off} = 細部(レイマーチ)の出し入れ / {@code fog time on|off} = GPU の時間を測る /
 * {@code fog silhouette on|off} = 巨大樹のシルエットの出し入れ / {@code fog forest on|off} = 胞子の霧の出し入れ(haze <濃さ> で壁の手前の霞、負で設計値) / {@code fog stat} = 測った平均。返事は {@code FOG …}。
 */
public final class MistDev {

    public static String command(String arg) {
        String[] a = arg.trim().split("\s+");
        switch (a[0]) {
            case "march" -> MistMarch.devOff = a.length > 1 && a[1].equalsIgnoreCase("off");
            case "silhouette" -> GiantSilhouettes.devOff = a.length > 1 && a[1].equalsIgnoreCase("off");
            case "forest" -> {
                if (a.length > 2 && a[1].equalsIgnoreCase("haze")) {
                    MistFog.devForestDensity = Float.parseFloat(a[2]);
                } else {
                    MistFog.devForestOff = a.length > 1 && a[1].equalsIgnoreCase("off");
                }
            }
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
        return "FOG バニラの霧 " + com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart() + "〜" + com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd()
                + " " + MistFog.describe() + " 細部 " + (MistMarch.willDraw() ? "描く" : "描かない") + (MistMarch.devOff ? "(止めた)" : "");
    }

    private MistDev() {
    }
}
