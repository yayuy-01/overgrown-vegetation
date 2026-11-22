package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.CorpseCollapsePayload;
import com.yayuy.overgrownvegetation.registry.ModG1;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * G1 段階 0 のクライアントの登録(2026-11-08)。共有の {@code OvergrownVegetationClient} は並行するセッションも触るので、G1 の行はここに寄せた。
 * 大きさ・影はバニラのレンダラーと同じ(崩れ落ち → 死体 → 起き上がりで大きさが跳ねないように)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class G1Client {

    private G1Client() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModG1.CORRUPTED_CORPSE.get(), context -> new CorruptedCorpseRenderer());
        // 大きさ・影はバニラのレンダラーと同じ(村人 0.9375)
        event.registerEntityRenderer(ModG1.PARASITIZED_ZOMBIE.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_zombie", 1.0F, 0.5F));
        event.registerEntityRenderer(ModG1.PARASITIZED_BABY_ZOMBIE.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_baby_zombie", 1.0F, 0.3F));
        event.registerEntityRenderer(ModG1.PARASITIZED_VILLAGER.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_villager", 0.9375F, 0.5F));
        event.registerEntityRenderer(ModG1.PARASITIZED_COW.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_cow", 1.0F, 0.7F));
        event.registerEntityRenderer(ModG1.PARASITIZED_PIG.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_pig", 1.0F, 0.7F));
        event.registerEntityRenderer(ModG1.PARASITIZED_SHEEP.get(), ctx -> {
            G1GeoRenderer<com.yayuy.overgrownvegetation.entity.ParasitizedSheep> r = new G1GeoRenderer<>(ctx, "overgrown_sheep", 1.0F, 0.7F);
            r.addRenderLayer(new G1WoolLayer.Mob<>(r, s -> s.getColor().getId()));
            return r;
        });
        event.registerEntityRenderer(ModG1.PARASITIZED_CHICKEN.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_chicken", 1.0F, 0.3F));
        event.registerEntityRenderer(ModG1.PARASITIZED_CREEPER.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_creeper", 1.0F, 0.5F));
        event.registerEntityRenderer(ModG1.PARASITIZED_SKELETON.get(), ctx -> {
            G1GeoRenderer<com.yayuy.overgrownvegetation.entity.ParasitizedSkeleton> r = new G1GeoRenderer<>(ctx, "overgrown_skeleton", 1.0F, 0.5F);
            r.addRenderLayer(new G1HeldItemLayer<>(r, s -> s.getMainHandItem().isEmpty() ? null : s.getMainHandItem()));
            return r;
        });
        event.registerEntityRenderer(ModG1.PARASITIZED_SPIDER.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_spider", 1.0F, 0.8F));
        event.registerEntityRenderer(ModG1.PARASITIZED_ENDERMAN.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_enderman", 1.0F, 0.5F));
        event.registerEntityRenderer(ModG1.PARASITIZED_IRON_GOLEM.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_iron_golem", 1.0F, 0.7F));
        event.registerEntityRenderer(ModG1.PARASITIZED_WARDEN.get(), ctx -> new G1GeoRenderer<>(ctx, "overgrown_warden", 1.0F, 0.9F));
        // 1 段目 11 種(2026-11-12)。影は段階 0 と同じくらい
        for (com.yayuy.overgrownvegetation.entity.g1.G1Developed d : com.yayuy.overgrownvegetation.entity.g1.G1Developed.values()) {
            String id = d.id();
            float shadow = Math.min(0.9F, Math.max(0.3F, d.width * 0.6F));
            event.registerEntityRenderer(ModG1.developed(d).get(), ctx -> {
                G1GeoRenderer<com.yayuy.overgrownvegetation.entity.DevelopedParasitized> r = new G1GeoRenderer<>(ctx, id, 1.0F, shadow);
                if (d == com.yayuy.overgrownvegetation.entity.g1.G1Developed.ENDERMAN) {
                    r.addRenderLayer(new G1CarriedBlockLayer<>(r)); // 抱えた汚染した土(2026-11-13、能力の第 4 回)
                }
                return r;
            });
        }
        // 根付き(2026-11-13、司令塔の区切り 2)。素材は g1_<種>_rooted
        for (com.yayuy.overgrownvegetation.entity.g1.G1Rooted r : com.yayuy.overgrownvegetation.entity.g1.G1Rooted.values()) {
            String asset = r.assetName();
            float rootedShadow = Math.min(0.9F, Math.max(0.4F, r.width * 0.75F)); // 小さい種(鶏)に大きな影を付けない
            event.registerEntityRenderer(ModG1.rooted(r).get(), ctx -> new G1GeoRenderer<>(ctx, asset, 1.0F, rootedShadow));
        }
        event.registerEntityRenderer(ModG1.ROOT_GUARDIAN.get(), ctx -> new G1GeoRenderer<>(ctx, "g1_root_guardian", 1.0F, 0.8F));
        // 苗床 11 種(2026-11-13、司令塔の区切り 6)。素材は g1_<種>_nursery
        for (com.yayuy.overgrownvegetation.entity.g1.G1Nursery n : com.yayuy.overgrownvegetation.entity.g1.G1Nursery.values()) {
            String asset = n.assetName();
            float nurseryShadow = Math.min(0.9F, Math.max(0.4F, n.width * 0.75F));
            event.registerEntityRenderer(ModG1.nursery(n).get(), ctx -> new G1GeoRenderer<>(ctx, asset, 1.0F, nurseryShadow));
        }
        // 1 段目の胞子の煙(2026-11-12)は粒だけ(実体の tick が出す)。霞みは G1SporeHaze
        event.registerEntityRenderer(ModG1.SPORE_CLOUD.get(), net.minecraft.client.renderer.entity.NoopRenderer::new);
        // 投げる胞子の塊(2026-11-13)は胞子嚢のアイテムの絵(雪玉と同じ)
        event.registerEntityRenderer(ModG1.SPORE_LUMP.get(), net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
        // 胞子の乳だまり(2026-11-13、能力の第 2 回)は地面に貼り付いた板 1 枚
        event.registerEntityRenderer(ModG1.MILK_PUDDLE.get(), G1MilkPuddleRenderer::new);
        // 能力の第 3 回(2026-11-13): 落ちた莢はクリーパーの背の莢の箱 1 つ、弓の弾は胞子嚢のアイテムの絵(少し大きく)
        event.registerEntityRenderer(ModG1.SEED_POD.get(), G1SeedPodRenderer::new);
        event.registerEntityRenderer(ModG1.SPORE_BOLT.get(), ctx -> new net.minecraft.client.renderer.entity.ThrownItemRenderer<>(ctx, 1.25F, false));
        // 能力の第 4 回(2026-11-13): クモの根糸の束は繊維質のアイテムの絵
        event.registerEntityRenderer(ModG1.ROOTSILK_BOLT.get(), ctx -> new net.minecraft.client.renderer.entity.ThrownItemRenderer<>(ctx, 1.0F, false));
        // エンダーマンの叩きつけで突き上がる根は、気根の先の絵を逆さにした板
        event.registerEntityRenderer(ModG1.ROOT_SPIKE.get(), G1RootSpikeRenderer::new);
    }

    /** 風に乗る綿毛の粒(2026-11-13、羊の 1 段目の (c))。共有の {@code OvergrownVegetationClient} に足さず、G1 の行はここに寄せる。 */
    @SubscribeEvent
    public static void registerParticleProviders(net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModG1.FLUFF.get(), G1FluffParticle.Provider::new);
    }

    /** 崩れ落ちの知らせ(サーバー → クライアント)を受ける。通信の登録は {@code network/G1Network}。 */
    public static void onCollapse(CorpseCollapsePayload payload) {
        com.yayuy.overgrownvegetation.client.corpse.ClientCorpseCollapse.onPayload(payload);
    }
}
