package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModLizards;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.EntityRenderersEvent;

/** 「トカゲ型」の Mob のクライアントの登録(2026-11-15)。共有の {@code OvergrownVegetationClient} は並行するセッションも触るので、ここに寄せた。 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class LizardClient {

    private LizardClient() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModLizards.PILIMNES.get(), PilimnesRenderer::new);
        event.registerEntityRenderer(ModLizards.PILIMNES_SPINE.get(), SpineRenderer::pilimnes);
        // 狩る側の環境生物のトカゲ 2 種(11-16 段 1): 骨の名前を渡すだけ。脚の IK の範囲はピリムネスと同じ(付け根 Y ±75・Z ±45・2 つ目 X ±50・Z ±60)
        double[] range = {75, 45, 50, 60};
        event.registerEntityRenderer(ModLizards.PETROLACOSAURUS.get(), ctx -> new com.yayuy.overgrownvegetation.client.lizard.SimpleLizardRenderer<>(ctx,
                new com.yayuy.overgrownvegetation.client.lizard.SimpleLizardModel<>(com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity.ID,
                        com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity.RENDER_SCALE,
                        new com.yayuy.overgrownvegetation.client.lizard.SimpleLizardModel.Bones("body", "hips", "neck", null, null, "head",
                                new String[]{"tail1", "tail2", "tail3", "tail4"},
                                new String[]{"arm_l", "forearm_l"}, new String[]{"arm_r", "forearm_r"},
                                new String[]{"thigh_l", "shin_l"}, new String[]{"thigh_r", "shin_r"}), range),
                com.yayuy.overgrownvegetation.entity.PetrolacosaurusEntity.RENDER_SCALE, 0.5F));
        event.registerEntityRenderer(ModLizards.IANTHASAURUS.get(), ctx -> new com.yayuy.overgrownvegetation.client.lizard.SimpleLizardRenderer<>(ctx,
                new com.yayuy.overgrownvegetation.client.lizard.SimpleLizardModel<>(com.yayuy.overgrownvegetation.entity.IanthasaurusEntity.ID,
                        com.yayuy.overgrownvegetation.entity.IanthasaurusEntity.RENDER_SCALE,
                        new com.yayuy.overgrownvegetation.client.lizard.SimpleLizardModel.Bones("body", null, "neck", null, null, "head",
                                new String[]{"tail1", "tail2", "tail3"},
                                new String[]{"arm_l", "forearm_l"}, new String[]{"arm_r", "forearm_r"},
                                new String[]{"thigh_l", "shin_l"}, new String[]{"thigh_r", "shin_r"}), range),
                com.yayuy.overgrownvegetation.entity.IanthasaurusEntity.RENDER_SCALE, 0.6F));
        // ヒロノムス: 首の骨が無いので、頭を首の付け根の役に(見回しと首の打ち消しは頭で)
        event.registerEntityRenderer(ModLizards.HYLONOMUS.get(), ctx -> new com.yayuy.overgrownvegetation.client.lizard.SimpleLizardRenderer<>(ctx,
                new com.yayuy.overgrownvegetation.client.lizard.SimpleLizardModel<>(com.yayuy.overgrownvegetation.entity.HylonomusEntity.ID, com.yayuy.overgrownvegetation.entity.HylonomusEntity.RENDER_SCALE,
                        new com.yayuy.overgrownvegetation.client.lizard.SimpleLizardModel.Bones("body", "hips", "head", null, null, null,
                                new String[]{"tail1", "tail2", "tail3"},
                                new String[]{"arm_l", "forearm_l"}, new String[]{"arm_r", "forearm_r"},
                                new String[]{"thigh_l", "shin_l"}, new String[]{"thigh_r", "shin_r"}), range),
                com.yayuy.overgrownvegetation.entity.HylonomusEntity.RENDER_SCALE, 0.35F));
        // コエルロサウラウス(11-16 段 1): 登る・休むは体を立てて幹へ寄せ、滑空は旋回で倒す(CoelurosauravusRenderer)。翼 wing_* は動かさない
        event.registerEntityRenderer(ModLizards.COELUROSAURAVUS.get(), ctx -> new com.yayuy.overgrownvegetation.client.lizard.CoelurosauravusRenderer(ctx,
                new com.yayuy.overgrownvegetation.client.lizard.SimpleLizardModel<>(com.yayuy.overgrownvegetation.entity.CoelurosauravusEntity.ID, com.yayuy.overgrownvegetation.entity.CoelurosauravusEntity.RENDER_SCALE,
                        new com.yayuy.overgrownvegetation.client.lizard.SimpleLizardModel.Bones("body", "hips", "neck", null, null, "head",
                                new String[]{"tail1", "tail2", "tail3", "tail4"},
                                new String[]{"arm_l", "forearm_l"}, new String[]{"arm_r", "forearm_r"},
                                new String[]{"thigh_l", "shin_l"}, new String[]{"thigh_r", "shin_r"}), range),
                com.yayuy.overgrownvegetation.entity.CoelurosauravusEntity.RENDER_SCALE, 0.55F));
    }
}
