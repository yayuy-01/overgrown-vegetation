package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.item.PoweredArmorItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderArmEvent;

/**
 * 一人称の腕を、パワードアーマーの胸当てを着ているときはアーマーの腕で描く(ユーザー指定 2026-10-05)。
 *
 * <p>{@link RenderArmEvent}(バニラの {@code PlayerRenderer#renderRightHand / renderLeftHand} が腕を描く直前。姿勢はもう腕の位置)を取り消して、
 * <ol>
 *     <li>素の腕だけをバニラの {@code renderHand} と同じ姿勢で描く(スキンの袖の層は描かない。三人称と同じく、アーマーを突き抜けるので)。</li>
 *     <li>同じ姿勢のプレイヤーモデルを元に、GeckoLib の防具の描画で<b>その腕の骨だけ</b>を描く。GeckoLib は胸当ての骨(胴・両腕)の表示を
 *     元のモデルの部位の表示から決める({@code applyBaseModel} → {@code applyBoneVisibilityBySlot})ので、元のモデルの胴ともう片方の腕を
 *     一時的に隠しておけば、描かれるのはその腕だけになる。位置・回転も元のモデルの腕から写される(三人称と同じ計算)。</li>
 * </ol>
 *
 * <p><b>罠: 元のモデルの {@code young} を false にしてから渡す。</b>{@code young} の初期値は true で、ふだんは三人称の描画
 * ({@code LivingEntityRenderer#render})が毎回 {@code isBaby()} で上書きしている。一人称では自分の体を描かないので true のまま残り、
 * GeckoLib が子ども用に半分の大きさ・位置で描いた(腕が小さく奥にずれて見えた。2026-10-05 にボーンの行列を記録して確認)。
 *
 * <p>描画は専用のレンダラー 1 つ(アイテムの描画が持つものとは別。モデルとテクスチャは同じ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class PoweredArmorFirstPersonArm {

    private static PoweredArmorRenderer renderer;

    private PoweredArmorFirstPersonArm() {
    }

    @SubscribeEvent
    public static void onRenderArm(RenderArmEvent event) {
        AbstractClientPlayer player = event.getPlayer();
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!(chest.getItem() instanceof PoweredArmorItem)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        EntityRenderer<? super AbstractClientPlayer> r = mc.getEntityRenderDispatcher().getRenderer(player);
        if (!(r instanceof PlayerRenderer playerRenderer)) {
            return;
        }
        event.setCanceled(true);
        PoseStack poseStack = event.getPoseStack();
        int light = event.getPackedLight();
        PlayerModel<AbstractClientPlayer> model = playerRenderer.getModel();
        ModelPart arm = event.getArm() == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;

        // 1. 素の腕(バニラの renderHand と同じ姿勢。一人称の腕は手が空のときだけなので、腕の構えは EMPTY)
        model.setAllVisible(true);
        model.young = false;
        model.riding = false;
        model.attackTime = 0.0F;
        model.crouching = false;
        model.swimAmount = 0.0F;
        model.rightArmPose = HumanoidModel.ArmPose.EMPTY;
        model.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        model.setupAnim(player, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        arm.xRot = 0.0F;
        arm.render(poseStack, event.getMultiBufferSource().getBuffer(RenderType.entitySolid(player.getSkin().texture())),
                light, OverlayTexture.NO_OVERLAY);

        // 2. アーマーの腕(元のモデルをその腕だけ見える状態にして GeckoLib に渡す)
        model.setAllVisible(false);
        arm.visible = true;
        if (renderer == null) {
            renderer = new PoweredArmorRenderer();
        }
        renderer.prepForRender(player, chest, EquipmentSlot.CHEST, model, event.getMultiBufferSource(),
                mc.getTimer().getGameTimeDeltaPartialTick(true), 0, 0, 0, 0);
        renderer.renderToBuffer(poseStack, null, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        model.setAllVisible(true);   // 次の描画の頭でも設定し直されるが、念のため戻す
    }
}
