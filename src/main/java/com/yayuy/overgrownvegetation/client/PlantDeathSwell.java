package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.client.infect.GenericInfectionClient;
import com.yayuy.overgrownvegetation.spore.PlantSideRules;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * 植物側の死の破裂の膨らみ(2026-11-15 ユーザー決定。サーバーは {@code corpse/PlantSideBurst})。アニメは作らず、ここで体全体を
 * 震わせながら膨らませる。バニラ・GeckoLib・ほかの MOD の描き方のどれにも効くよう、{@code EntityRenderDispatcher} が描き手を呼ぶ所
 * ({@code mixin/client/EntityRenderDispatcherDeathSwellMixin})で姿勢を変える。
 *
 * <p>バニラの横倒しと赤い被弾色は、どちらの描き方も {@code deathTime}・{@code hurtTime} から出すので、描く間だけ 0 にして戻す。
 * 数はすべて【仮】。
 */
public final class PlantDeathSwell {

    /** 膨らみきったときの横・縦の倍率の増え。【仮】 */
    static final float SWELL_XZ = 0.45F, SWELL_Y = 0.28F;
    /** 震えの幅(体の幅に対する割合)。初め → 破裂の直前。【仮】 */
    static final float SHAKE_START = 0.015F, SHAKE_END = 0.07F;

    private PlantDeathSwell() {
    }

    /** この Mob を膨らませるか(死に際で、破裂する側)。 */
    public static boolean applies(LivingEntity e) {
        return e.deathTime > 0 && PlantSideRules.burstsWith(e, GenericInfectionClient.of(e) != null);
    }

    /** 膨らみの進み(0〜1)。サーバーが破裂させる tick と同じ式。 */
    public static float progress(LivingEntity e, float partialTick) {
        return Mth.clamp((e.deathTime - 1 + partialTick) / (float) PlantSideRules.burstTick(e), 0.0F, 1.0F);
    }

    /** 足元を原点にした姿勢へ、震えと膨らみをかける。 */
    public static void apply(PoseStack poseStack, LivingEntity e, float partialTick) {
        float t = progress(e, partialTick);
        float ease = t * Mth.sqrt(t); // 終わりほど早く膨らむ
        float age = e.tickCount + partialTick;
        float amp = Mth.lerp(t, SHAKE_START, SHAKE_END) * Math.max(0.4F, e.getBbWidth());
        int seed = e.getId();
        float dx = amp * (Mth.sin(age * 3.7F + seed) + 0.5F * Mth.sin(age * 8.3F + seed * 1.7F)) / 1.5F;
        float dz = amp * (Mth.cos(age * 4.1F + seed * 0.7F) + 0.5F * Mth.sin(age * 7.1F + seed * 2.3F)) / 1.5F;
        float dy = amp * 0.4F * Mth.sin(age * 5.3F + seed * 0.3F);
        poseStack.translate(dx, dy, dz);
        float xz = 1.0F + SWELL_XZ * ease;
        poseStack.scale(xz, 1.0F + SWELL_Y * ease, xz);
    }
}
