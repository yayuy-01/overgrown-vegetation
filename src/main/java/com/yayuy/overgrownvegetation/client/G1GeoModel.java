package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import software.bernie.geckolib.core.animatable.GeoAnimatable;

/**
 * G1 の感染体のモデル(2026-10-09 の作り直し)。geo・アニメ・絵は {@code <id>.geo.json}・{@code <id>.animation.json}・
 * {@code textures/entity/<id>.png}。どれも生成器 {@code tools/g5-models/mobs/G1*.java} の生成物(2026-11-08 から作り直し 2 回目の版を {@code tools/g5-models/g1/install_v2.sh} で写す。亜種の絵は無い)。
 */
public class G1GeoModel<T extends Entity & GeoAnimatable> extends LodGeoModel<T> {

    private final String id;
    private final ResourceLocation model;
    private final ResourceLocation animation;
    private final ResourceLocation texture;
    /** 半透明で描くか(寄生されたガスト・ブレイズの胞子嚢。ペディキュラと同じ半透明の絵、2026-10-09)。 */
    private final boolean translucent;

    public G1GeoModel(String id) {
        this(id, false);
    }

    public G1GeoModel(String id, boolean translucent) {
        this.id = id;
        this.translucent = translucent;
        this.model = OvergrownVegetation.id("geo/" + id + ".geo.json");
        this.animation = OvergrownVegetation.id("animations/" + id + ".animation.json");
        this.texture = OvergrownVegetation.id("textures/entity/" + id + ".png");
    }

    @Override
    public ResourceLocation getModelResource(T animatable) {
        return model;
    }

    @Override
    public ResourceLocation getTextureResource(T animatable) {
        return texture;
    }

    @Override
    public ResourceLocation getAnimationResource(T animatable) {
        return animation;
    }

    /** 1 段目の手続きの骨(クリーパーの空の莢・据えたまま射るスケルトンの足の根。2026-11-13)。キーのアニメの後に重ねる。 */
    @Override
    public void setCustomAnimations(T animatable, long instanceId, software.bernie.geckolib.core.animation.AnimationState<T> animationState) {
        super.setCustomAnimations(animatable, instanceId, animationState);
        if (animatable instanceof com.yayuy.overgrownvegetation.entity.DevelopedParasitized d) {
            G1DevelopedRig.apply(this, d, animationState);
        } else if (animatable instanceof com.yayuy.overgrownvegetation.entity.RootedParasitized r) {
            // 根付き: 体は固定のまま、蔦の腕だけを標的へ回す(骨 grab_swing の y 回転。2026-11-13)。
            // 符号はゲーム内で確かめた(11-13): 体の正面から左回りの角度を、そのまま骨の y 回転に足すと標的の側へ向く(逆にすると反対側へ伸びた)
            // 骨は種ごと: ゾンビ grab_swing・エンダーマン reach_shoulder・スケルトン ballista_aim(G1Rooted.Skill#aimBone)
            String boneName = r.rooted().skill.aimBone;
            software.bernie.geckolib.cache.object.GeoBone swing = boneName == null ? null : (software.bernie.geckolib.cache.object.GeoBone) getAnimationProcessor().getBone(boneName);
            if (swing != null) {
                float yaw = r.aimYaw();
                if (r.deathTime > 0) {
                    // 枯れて崩れる動きは正面向きで作ってある: 頭の 10 tick で向きを 0 へ戻す(向きを付けたままだと弓の腕が地面へ潜る)
                    float k = Math.max(0.0F, 1.0F - (r.deathTime + animationState.getPartialTick()) / 10.0F);
                    yaw = net.minecraft.util.Mth.wrapDegrees(yaw) * k;
                }
                swing.setRotY(swing.getRotY() + yaw * net.minecraft.util.Mth.DEG_TO_RAD);
                swing.resetStateChanges();
            }
            // 豚の根の顎: 骨 root_jaw_carry を、同期した位置(体の左・前へのブロック数)へ動かし、縛っている間は大きくする。
            // 静止の位置からの差を骨の位置に入れる(モデルの単位 = 1/16 ブロック。前 = −Z、左 = −X。GeckoLib は位置の x を逆の符号で持つ)
            if (r.rooted().skill == com.yayuy.overgrownvegetation.entity.g1.G1Rooted.Skill.BIND) {
                software.bernie.geckolib.cache.object.GeoBone jaw = (software.bernie.geckolib.cache.object.GeoBone) getAnimationProcessor().getBone("root_jaw_carry");
                if (jaw != null) {
                    float dLeft = (r.jawLeft() - com.yayuy.overgrownvegetation.entity.RootedParasitized.JAW_REST_LEFT) * 16.0F;
                    float dFront = (r.jawFront() - com.yayuy.overgrownvegetation.entity.RootedParasitized.JAW_REST_FRONT) * 16.0F;
                    jaw.setPosX(jaw.getPosX() + dLeft);
                    jaw.setPosZ(jaw.getPosZ() - dFront);
                    float s = r.jawScale();
                    jaw.setScaleX(jaw.getScaleX() * s);
                    jaw.setScaleY(jaw.getScaleY() * s);
                    jaw.setScaleZ(jaw.getScaleZ() * s);
                    jaw.resetStateChanges();
                }
            }
        } else if (animatable instanceof com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster m) {
            G1OmenRig.apply(this, m, animationState); // 司令塔の前兆(進化の前の震え。2026-11-13)
        }
    }

    /** 胞子嚢のある種は半透明(GeckoLib の既定の cutout だと半透明の画素が不透明の塊になる。PediculaModel と同じ)。 */
    @Override
    public net.minecraft.client.renderer.RenderType getRenderType(T animatable, ResourceLocation texture) {
        return this.translucent ? net.minecraft.client.renderer.RenderType.entityTranslucent(texture)
                : super.getRenderType(animatable, texture);
    }
}
