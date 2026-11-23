package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraftforge.client.event.RenderLevelStageEvent;

/**
 * 【1.20.1 だけの物・G5 の助け手】1.21 は AFTER_ENTITIES〜AFTER_PARTICLES の間、RenderSystem の描画の行列にカメラの回転を入れておく
 * (1.21 の {@code LevelRenderer#renderLevel})。1.20.1 は入れず、代わりに {@code event.getPoseStack()} にカメラの回転を入れて渡す。
 * カメラからの位置をそのまま頂点に書いて自前の器で描く所(地脈の大潮・昇る胞子の光)は、この間だけ 1.21 と同じ行列にする。
 * (G6 の {@code client/shader/PortRender#pushStageModelView} と同じ決まり。統合で 1 つにまとめてよい)
 */
public final class PortStageView {

    private PortStageView() {
    }

    /** 1.21 でこの段の間 RenderSystem の描画の行列にカメラの回転が入っていて、1.20.1 では入っていない段か。 */
    private static boolean differs(RenderLevelStageEvent.Stage stage) {
        return stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES || stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES
                || stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || stage == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS
                || stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES;
    }

    /** 描画の行列をこの段の 1.21 の値にする。積んだら true({@link #pop} に渡す)。 */
    public static boolean push(RenderLevelStageEvent event) {
        if (!differs(event.getStage())) {
            return false;
        }
        PoseStack stack = RenderSystem.getModelViewStack();
        stack.pushPose();
        stack.last().pose().set(event.getPoseStack().last().pose());
        RenderSystem.applyModelViewMatrix();
        return true;
    }

    public static void pop(boolean pushed) {
        if (pushed) {
            RenderSystem.getModelViewStack().popPose();
            RenderSystem.applyModelViewMatrix();
        }
    }
}
