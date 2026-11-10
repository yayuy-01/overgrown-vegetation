package com.yayuy.overgrownvegetation.client.atmosphere.heat;

/**
 * 熱水の森の見え方の確かめ用の口(2026-11-10 段階 1)。DevBridge の {@code fog heat …}({@code MistDev} から渡す)。撮り比べのためにこのセッションだけ替える。
 * <ul>
 *     <li>{@code status} — 度合い・数えた割合・暗さ・霧</li>
 *     <li>{@code off} / {@code on} — 暗さと霧を両方止める / 戻す(「今」の写真)</li>
 *     <li>{@code dark on|off} / {@code mist on|off} — 片方だけ</li>
 *     <li>{@code strength <0〜1>} — 暗さの強さ(負で設計値)</li>
 *     <li>{@code end <ブロック>} — 霧の終わり(負で設計値)/ {@code shape sphere|cylinder|design}</li>
 *     <li>{@code force <0〜1>} — 度合いを固定(負で数える)</li>
 * </ul>
 * 返事は {@code HEAT …}。
 */
public final class HeatFxDev {

    private HeatFxDev() {
    }

    public static String command(String arg) {
        String[] a = arg.trim().split("\\s+");
        switch (a[0]) {
            case "on" -> {
                HeatFxShade.devOff = false;
                HeatFxFog.devOff = false;
            }
            case "off" -> {
                HeatFxShade.devOff = true;
                HeatFxFog.devOff = true;
            }
            case "dark" -> HeatFxShade.devOff = a.length > 1 && a[1].equalsIgnoreCase("off");
            case "mist" -> HeatFxFog.devOff = a.length > 1 && a[1].equalsIgnoreCase("off");
            case "strength" -> HeatFxShade.devStrength = Float.parseFloat(a[1]);
            case "end" -> HeatFxFog.devEnd = Float.parseFloat(a[1]);
            case "shape" -> HeatFxFog.devCylinder = a[1].equalsIgnoreCase("cylinder") ? 1 : a[1].equalsIgnoreCase("sphere") ? 0 : -1;
            case "force" -> HeatFxPresence.devForce = Float.parseFloat(a[1]);
            // 11-10 段階 2: codes on|off(目印。切ると脈動・昼夜の明るさも効かない)/ pulse on|off / time <0〜23999>|auto(偽の空の時刻を固定)
            case "codes" -> HeatFxGlow.devCodesOff = a.length > 1 && a[1].equalsIgnoreCase("off");
            case "pulse" -> HeatFxGlow.devPulseOff = a.length > 1 && a[1].equalsIgnoreCase("off");
            case "time" -> HeatFxSky.devTime = a.length > 1 && !a[1].equalsIgnoreCase("auto") ? Long.parseLong(a[1]) : -1L;
            case "scan" -> {
                // 11-10 追記 1: 前の数え方と今の数え方を、今の場所で漏れなく数えて比べる
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.level == null) {
                    return "HEAT ワールドの外";
                }
                return "HEAT scan " + HeatFxPresence.scan(mc.level, mc.gameRenderer.getMainCamera().getPosition());
            }
            case "status", "" -> {
            }
            default -> {
                return "HEAT 知らない項目 " + a[0] + "(status|on|off|dark on/off|mist on/off|strength n|end n|shape sphere/cylinder/design|force n)";
            }
        }
        return "HEAT " + status();
    }

    /**
     * 暗視の間は暗さを弱める({@link HeatFxShade#strength})。11-10 追記 5: 撮影で暗視を付けていて「暗さ 0.00」と出たのを、表示の誤りと見分けられるように、
     * 暗視の間は弱める前の値と理由を添える。
     */
    private static String nightVisionNote() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null || !player.hasEffect(net.minecraft.world.effect.MobEffects.NIGHT_VISION) || HeatFxShade.devOff) {
            return "";
        }
        return "(暗視で弱めた。弱める前 " + String.format("%.2f", HeatFxShade.baseStrength()) + ")";
    }

    static String status() {
        return "有効 " + HeatFx.enabled() + " 度合い " + String.format("%.2f", HeatFxPresence.presence())
                + (HeatFxPresence.devForce >= 0.0F ? "(固定)" : "") + " 割合 " + String.format("%.3f", HeatFxPresence.ratio())
                + " 暗さ " + (HeatFxShade.devOff ? "止め" : String.format("%.2f", HeatFxShade.strength())) + nightVisionNote() + " " + HeatFxFog.describe()
                + " / " + HeatFxGlow.describe();
    }
}
