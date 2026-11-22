package com.yayuy.overgrownvegetation.survival;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.network.SurvivalHudPayload;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.util.FakePlayer;
import com.yayuy.overgrownvegetation.port.network.PacketDistributor;
import com.yayuy.overgrownvegetation.port.attachment.OvAttach;

/**
 * HUD の 2 本のゲージ(暴露量のバー・疲労の列)の値を作り、刻みが変わったときだけクライアントへ送る(2026-11-13 サバイバルの背骨 段 1)。
 * 送った鍵は疲労の添付に持つ(作り直された添付 = 復活・ログイン・次元の移動では送り直す)。
 */
public final class SurvivalSync {

    /** 暴露量・段階の進みの刻み(バーは半分ずつ 81 画素 × 2)。 */
    static final int BAR_STEPS = 1024;
    /** 疲労の刻み(列の半分のアイコン = 5%)。 */
    static final int FATIGUE_STEPS = 1000;

    private SurvivalSync() {
    }

    /** バーの値: 感染していなければ暴露量の割合、感染中は今の段階の進み(0〜1)。 */
    public static float barFraction(Player player) {
        int stage = SporeInfectionEffects.stageOf(player);
        return stage > 0 ? stageProgress(player) : (float) SporeExposureTracker.fraction(player);
    }

    /** 今の段階に入ってからの進み(0〜1)。未感染は 0。11-15 から段の進みは暴露量(たまった量 ÷ 1 段の量)。 */
    public static float stageProgress(Player player) {
        return SporeInfectionEffects.stageFraction(player);
    }

    /** 送る値を作る(テストからも呼ぶ)。 */
    public static SurvivalHudPayload payload(Player player) {
        int stage = SporeInfectionEffects.stageOf(player);
        float exposure = quantize((float) SporeExposureTracker.fraction(player), BAR_STEPS);
        float progress = quantize(stageProgress(player), BAR_STEPS);
        float fatigue = quantize((float) (FatigueTracker.value(player) / 100.0D), FATIGUE_STEPS);
        float from = (float) (FatigueTracker.spec().effectsFrom() / 100.0D);
        return new SurvivalHudPayload(exposure, stage, progress, fatigue, from);
    }

    public static void send(Player player) {
        if (!(player instanceof ServerPlayer sp) || player instanceof FakePlayer || sp.connection == null) return;
        SurvivalHudPayload p = payload(player);
        long key = ((long) Math.round(p.exposure() * BAR_STEPS))
                | ((long) Math.round(p.stageProgress() * BAR_STEPS) << 12)
                | ((long) Math.round(p.fatigue() * FATIGUE_STEPS) << 24)
                | ((long) Math.min(255, p.stage()) << 36)
                | ((long) Math.round(p.effectsFrom() * 100.0F) << 44);
        Fatigue f = OvAttach.getData(player, ModAttachments.FATIGUE);
        if (f.sentKey == key) return;
        f.sentKey = key;
        PacketDistributor.sendToPlayer(sp, p);
    }

    private static float quantize(float v, int steps) {
        return Math.round(Mth.clamp(v, 0.0F, 1.0F) * steps) / (float) steps;
    }
}
