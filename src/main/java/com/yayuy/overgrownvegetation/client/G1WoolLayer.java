package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedCorpseBlockEntity;
import com.yayuy.overgrownvegetation.corpse.CorpseSpecies;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.DyeColor;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;


/**
 * G1 の羊の毛の層(2026-11-08。11-05 ユーザー「生きている姿の毛は元の羊の色(16 色)を引き継ぎ、苔でくすませる」)。
 * 毛の絵 {@code overgrown_sheep_wool.png}(突き破る前の死体は {@code overgrown_sheep_intact_wool.png})は灰色の明るさで塗ってあり、
 * 苔の所は抜いてある(色が掛からない)。それを同じ形にもう一度、元の羊の色を掛けて重ねる(バニラの羊の毛の層と同じ考え)。
 */
public abstract class G1WoolLayer<T extends GeoAnimatable> extends GeoRenderLayer<T> {

    static final String SHEEP = "overgrown_sheep";
    private static final ResourceLocation WOOL = OvergrownVegetation.id("textures/entity/" + SHEEP + "_wool.png");
    private static final ResourceLocation INTACT_WOOL = OvergrownVegetation.id("textures/entity/" + SHEEP + "_intact_wool.png");

    protected G1WoolLayer(GeoRenderer<T> renderer) {
        super(renderer);
    }

    /** 毛の色の id(DyeColor)。描かないなら -1。 */
    protected abstract int colorId(T animatable);

    protected abstract boolean intact(T animatable);

    @Override
    public void render(PoseStack poseStack, T animatable, BakedGeoModel model, RenderType renderType, MultiBufferSource bufferSource,
                       VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
        int id = colorId(animatable);
        if (id < 0) {
            return;
        }
        RenderType type = RenderType.entityCutoutNoCull(intact(animatable) ? INTACT_WOOL : WOOL);
        int colour = Sheep.getColor(DyeColor.byId(id));
        getRenderer().reRender(model, poseStack, bufferSource, animatable, type, bufferSource.getBuffer(type), partialTick, packedLight,
                packedOverlay, colour);
    }

    /** 死体ブロック用(羊の死体だけ描く)。 */
    public static final class Corpse extends G1WoolLayer<CorruptedCorpseBlockEntity> {

        public Corpse(GeoRenderer<CorruptedCorpseBlockEntity> renderer) {
            super(renderer);
        }

        @Override
        protected int colorId(CorruptedCorpseBlockEntity be) {
            return SHEEP.equals(CorpseSpecies.speciesOf(be.getBlockState().getBlock())) ? Math.max(be.woolColor(), 0) : -1;
        }

        @Override
        protected boolean intact(CorruptedCorpseBlockEntity be) {
            return CorruptedCorpseModel.isIntact(be, SHEEP);
        }
    }

    /** Mob 用(色を返す関数を渡す)。 */
    public static final class Mob<T extends net.minecraft.world.entity.LivingEntity & GeoAnimatable> extends G1WoolLayer<T> {

        private final java.util.function.ToIntFunction<T> color;

        public Mob(GeoRenderer<T> renderer, java.util.function.ToIntFunction<T> color) {
            super(renderer);
            this.color = color;
        }

        @Override
        protected int colorId(T animatable) {
            return color.applyAsInt(animatable);
        }

        @Override
        protected boolean intact(T animatable) {
            return false;
        }
    }

}
