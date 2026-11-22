package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.entity.action.ActionDef;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.UseAnim;

/**
 * ファルキムネスの個体の性格(段 3、2026-11-08。決定は Vault「行動と移動の作り直し」の「地形に合わせた歩き方と個体の性格」と
 * 「ファルキムネス」13 節)。
 *
 * <p>性格は型に分けず、<b>連続した 3 本のつまみ</b>(0〜1)で持つ: 慎重さ・攻撃性・回り込み。湧いたときに 1 本ずつ
 * <b>U 字の分布</b>(両端が出やすい。{@link #roll})で振る —— 中くらいの個体ばかりで性格が読めなくならないように。
 *
 * <p><b>どのつまみも 0.5 なら段 2 までと同じ振る舞い</b>(技の重み・間合い・待ちは (値 − 0.5) で傾ける)。違うのは連続技だけ。
 *
 * <p>数値は {@code actions.json} の擬似の技 {@code "personality"}(extra だけ)と、各技の extra({@code cautious}・{@code aggressive}・
 * {@code flank} = 重みの掛け数、{@code counter} = 慎重な個体が待っている間にも返せる技、{@code next.<結果>.<技>} = 連続技の 2 段目の重み)。
 * すべて【仮】。
 */
public final class FalcimnesPersonality {

    /** {@code actions.json} の擬似の技(選ばれない。extra だけを持つ)。 */
    public static final String ID = "personality";

    /** 1 段目の結果(連続技の 2 段目を選ぶ鍵)。 */
    public static final int RESULT_NONE = 0, RESULT_HIT = 1, RESULT_BLOCKED = 2, RESULT_MISSED = 3;

    private FalcimnesPersonality() {
    }

    /** U 字の分布(逆正弦分布)で 0〜1 を 1 つ振る。両端の 0.1 以内に約 4 割が入る。 */
    public static float roll(RandomSource random) {
        double u = random.nextDouble();
        return (float) ((1.0D - Math.cos(Math.PI * u)) * 0.5D);
    }

    /** 掛け数 k をつまみ v で傾ける: v = 1 で ×k、v = 0 で ×1/k、v = 0.5 で ×1。 */
    public static double tilt(double k, float v) {
        if (k <= 0.0D) return 1.0D;
        return Math.pow(k, (v - 0.5D) * 2.0D);
    }

    /** 技の重みの掛け数(3 本のつまみの積)。 */
    public static double weight(ActionDef d, float caution, float aggression, float flank) {
        return tilt(d.get("cautious", 1.0D), caution) * tilt(d.get("aggressive", 1.0D), aggression) * tilt(d.get("flank", 1.0D), flank);
    }

    /** 0.5 から片側へのずれ(0〜1)。0.5 以下は 0。 */
    public static double over(float v) {
        return Math.max(0.0D, (v - 0.5D) * 2.0D);
    }

    /** 構えの重み {畳む, 開く}: 慎重さの片側 × (1 − 攻撃性の片側)・その逆(両方強い個体は打ち消し合う)。描画と生成器の FalcPosture と同じ。 */
    public static float[] postureWeights(float caution, float aggression) {
        float oc = (float) over(caution), oa = (float) over(aggression);
        return new float[]{oc * (1 - oa), oa * (1 - oc)};
    }

    /** 歩幅の倍率(慎重 = 小股 0.75、荒い = 大股 1.25)【仮】。足の置き方の踏み替えるずれと先回りに掛ける。 */
    public static double stride(float caution, float aggression) {
        float[] w = postureWeights(caution, aggression);
        return 1.0D - 0.25D * w[0] + 0.25D * w[1];
    }

    public static String resultKey(int result) {
        return switch (result) {
            case RESULT_HIT -> "hit";
            case RESULT_BLOCKED -> "blocked";
            default -> "missed";
        };
    }

    /**
     * 相手の隙(慎重な個体が攻めに行く合図)【仮】: 振った直後・盾を下ろした・背を向けた・弓を引き終えた・食べる / 飲むのを始めた。
     * {@code wasBlocking} は 1 tick 前に盾を構えていたか。
     */
    public static boolean opening(LivingEntity mob, LivingEntity t, boolean wasBlocking) {
        // 背を向けた: 相手の向きと、相手から見た自分の向きのずれが 110 度より大きい
        double dx = mob.getX() - t.getX(), dz = mob.getZ() - t.getZ();
        float toMob = (float) (Math.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float off = Math.abs(net.minecraft.util.Mth.wrapDegrees(toMob - t.getYHeadRot()));
        if (off > 110.0F) return true;
        if (!(t instanceof Player p)) return false;
        if (p.swinging && p.swingTime <= 2) return true;
        if (wasBlocking && !p.isBlocking()) return true;
        if (p.isUsingItem()) {
            var item = p.getUseItem();
            if ((item.getItem() instanceof BowItem || item.getItem() instanceof CrossbowItem) && p.getTicksUsingItem() >= 20) return true;
            UseAnim anim = item.getUseAnimation();
            if (anim == UseAnim.EAT || anim == UseAnim.DRINK) return true;
        }
        return false;
    }
}
