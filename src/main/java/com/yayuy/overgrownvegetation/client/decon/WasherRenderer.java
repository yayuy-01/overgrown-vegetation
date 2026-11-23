package com.yayuy.overgrownvegetation.client.decon;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
import com.mojang.math.Axis;
import com.yayuy.overgrownvegetation.survival.decon.WasherBlock;
import com.yayuy.overgrownvegetation.survival.decon.WasherBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 地脈の洗い場の描画(2026-11-22)。槽・すのこ・管・はずみ車は GeckoLib の模型({@link WasherModel})。向きはブロックの {@code facing} を
 * GeckoLib が読んで回す(北 = 模型のまま、扉 = Blockbench の -Z)。槽の水は地形の模型で描くので、模型の水の箱は隠す。
 *
 * <p><b>洗っている物</b>(11-22 ユーザーの希望): 洗う前の枠の中身を {@link WasherBlockEntity#SHOWN_MAX} 個まで、水の面(高さ 8/16)に浮かべて描く。
 * 動いている間は水の中でゆっくり回り、浮き沈みする。止まるとその場で止まる。すのこのすき間は 1 画素なので、上から少し見える程度。
 */
public class WasherRenderer extends GeoBlockRenderer<WasherBlockEntity> {

    private static final float WATER_Y = 8.0F / 16.0F;
    /** 浮かべる位置(模型の座標・画素。槽の中は -6〜6)と、ずらす位相。 */
    private static final float[][] SPOTS = {{-3.0F, -2.0F, 0.0F}, {2.5F, -0.5F, 2.1F}, {-0.5F, 3.0F, 4.2F}};
    private static final float ITEM_SCALE = 0.35F;

    /** 洗い場ごとの「動いた時間」(止まっている間は進めない。描画だけの状態なので、中身ではなくここに持つ)。 */
    private final Map<WasherBlockEntity, float[]> spin = new WeakHashMap<>();

    public WasherRenderer() {
        super(new WasherModel<>());
    }

    static boolean running(WasherBlockEntity be) {
        return be.getBlockState().hasProperty(WasherBlock.RUNNING) && be.getBlockState().getValue(WasherBlock.RUNNING);
    }

    @Override
    public void preRender(PoseStack poseStack, WasherBlockEntity animatable, BakedGeoModel model, @Nullable MultiBufferSource bufferSource,
                          @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        if (!isReRender) {
            WasherModel.showParts(model, false, running(animatable));
        }
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, red, green, blue, alpha);
    }

    // 1.20.1(GeckoLib 4.8.4): GeoBlockRenderer#render は 1 つ目が BlockEntity で宣言されていて、型を絞って上書きできない(名前がぶつかる)。
    // render は this.animatable を入れて defaultRender を呼ぶだけ(重ねの値は使わない)なので、defaultRender を上書きする
    @Override
    public void defaultRender(PoseStack poseStack, WasherBlockEntity be, MultiBufferSource buffers, @Nullable RenderType renderType,
                              @Nullable VertexConsumer buffer, float yaw, float partialTick, int packedLight) {
        super.defaultRender(poseStack, be, buffers, renderType, buffer, yaw, partialTick, packedLight); // 槽の模型
        renderShown(be, partialTick, poseStack, buffers, packedLight);
    }

    private void renderShown(WasherBlockEntity be, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        List<ItemStack> shown = be.shown();
        Level level = be.getLevel();
        if (shown.isEmpty() || level == null) {
            return;
        }
        float now = level.getGameTime() + partialTick;
        float[] s = spin.computeIfAbsent(be, k -> new float[]{0.0F, now});
        float dt = Mth.clamp(now - s[1], 0.0F, 5.0F);
        s[1] = now;
        if (running(be)) {
            s[0] += dt;
        }
        float t = s[0];
        for (int i = 0; i < shown.size() && i < SPOTS.length; i++) {
            float[] spot = SPOTS[i];
            float phase = spot[2];
            float bob = Mth.sin(t * 0.08F + phase) * 0.7F / 16.0F;          // 浮き沈み ±0.7 画素
            float yaw = phase * 50.0F + t * 1.5F;                            // ゆっくり回る(1 秒に 30°)
            float tilt = Mth.sin(t * 0.06F + phase * 1.7F) * 10.0F;          // 少し傾ぐ
            poseStack.pushPose();
            poseStack.translate(0.5F + spot[0] / 16.0F, WATER_Y + 0.3F / 16.0F + bob, 0.5F + spot[1] / 16.0F);
            poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
            poseStack.mulPose(Axis.ZP.rotationDegrees(tilt));
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F)); // 水に寝かせる
            poseStack.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
            Minecraft.getInstance().getItemRenderer().renderStatic(shown.get(i), ItemDisplayContext.FIXED, packedLight,
                    OverlayTexture.NO_OVERLAY, poseStack, buffers, level, (int) be.getBlockPos().asLong() + i);
            poseStack.popPose();
        }
    }
}
