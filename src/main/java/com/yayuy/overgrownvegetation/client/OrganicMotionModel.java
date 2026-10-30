package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.client.critter.BoneMath;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * G1 の進化体の手続きの有機的な動き(2026-10-24、ユーザー了承)。キーのアニメ(歩き・攻撃)の上に、骨ごとに足す:
 * <ul>
 *   <li><b>鼓動</b>(胞子嚢): 骨を膨らませて縮める。周期と位相は骨ごと・個体ごとにずらす。傷が深いほど速く大きく(破裂の直前)。</li>
 *   <li><b>緩急のある揺れ</b>(触手): ゆっくりした揺れに、ときどき来る速い振れを重ねる(2 つの周期 + 個体ごとの種)。</li>
 * </ul>
 * 骨を書いたら {@link GeoBone#resetStateChanges()} で印を下ろす(書いた角度が毎フレーム積み上がらないように。CritterMotion と同じ)。
 * 遠くて動きを止めている個体({@link LodGeoModel})には呼ばれない。設定 {@link VisualQuality#organicMotion()} で切れる。数値はどれも仮決め。
 */
public class OrganicMotionModel<T extends LivingEntity & GeoAnimatable> extends G1GeoModel<T> {

    private final String[] pulseBones;
    private final float pulseAmp;
    private final boolean pulseHurtFaster;
    private final String[] swayBones;
    private final float swayDeg;

    /**
     * @param pulseBones      脈打たせる骨(胞子嚢)
     * @param pulseAmp        膨らむ量(0.06 = 6%)
     * @param pulseHurtFaster 傷が深いほど速く大きく脈打つ(破裂する種)
     * @param swayBones       揺らす骨(触手)
     * @param swayDeg         ゆっくりした揺れの大きさ(度)。速い振れはこの 1.6 倍
     */
    public OrganicMotionModel(String id, String[] pulseBones, float pulseAmp, boolean pulseHurtFaster, String[] swayBones, float swayDeg) {
        super(id);
        this.pulseBones = pulseBones;
        this.pulseAmp = pulseAmp;
        this.pulseHurtFaster = pulseHurtFaster;
        this.swayBones = swayBones;
        this.swayDeg = swayDeg;
    }

    @Override
    public void setCustomAnimations(T e, long instanceId, AnimationState<T> state) {
        super.setCustomAnimations(e, instanceId, state);
        if (!VisualQuality.organicMotion() || e.isDeadOrDying()) {
            return;
        }
        float time = (e.tickCount + state.getPartialTick()) / 20.0F;   // 秒
        long seed = e.getUUID().getLeastSignificantBits();

        if (pulseBones.length > 0) {
            float hurt = pulseHurtFaster ? Mth.clamp(1.0F - e.getHealth() / e.getMaxHealth(), 0.0F, 1.0F) : 0.0F;
            float speed = 1.0F + 2.0F * hurt;
            float amp = pulseAmp * (1.0F + 1.2F * hurt);
            for (int i = 0; i < pulseBones.length; i++) {
                GeoBone b = getAnimationProcessor().getBone(pulseBones[i]);
                if (b == null) {
                    continue;
                }
                float period = 1.5F + 1.5F * rand(seed, i);                 // 1.5〜3 秒
                float phase = rand(seed, i + 100);
                float u = time * speed / period + phase;
                float s = Mth.sin((float) Math.PI * (u - Mth.floor(u)));  // 0 → 1 → 0(半周期の山)
                float beat = s * s * s;                                    // 鋭く膨らんでゆっくり戻る
                float k = 1.0F + amp * (0.6F + 0.4F * rand(seed, i + 200)) * beat;
                b.setScaleX(b.getScaleX() * k);
                b.setScaleY(b.getScaleY() * k);
                b.setScaleZ(b.getScaleZ() * k);
                b.resetStateChanges();
            }
        }

        if (swayBones.length > 0) {
            for (int i = 0; i < swayBones.length; i++) {
                GeoBone b = getAnimationProcessor().getBone(swayBones[i]);
                if (b == null) {
                    continue;
                }
                float slowT = 3.5F + 2.0F * rand(seed, i + 300);                 // ゆっくりの周期 3.5〜5.5 秒
                float burstT = 7.0F + 4.0F * rand(seed, i + 400);                // 速い振れが来る間隔 7〜11 秒
                float ph = 6.2832F * rand(seed, i + 500);
                float slow = Mth.sin(6.2832F * time / slowT + ph);
                float slow2 = Mth.sin(6.2832F * time / (slowT * 0.63F) + ph * 1.7F);
                float env = Mth.sin(6.2832F * time / burstT + ph * 0.5F);
                env = env > 0 ? env * env * env * env * env * env : 0;          // たまにだけ立ち上がる
                float fast = Mth.sin(6.2832F * time / 0.32F + ph) * env;
                float x = swayDeg * (0.7F * slow + 0.3F * slow2) + 1.6F * swayDeg * fast;
                float z = swayDeg * 0.6F * (0.6F * slow2 - 0.4F * slow) + 0.8F * swayDeg * fast;
                BoneMath.add(b, x * Mth.DEG_TO_RAD, 0.0F, z * Mth.DEG_TO_RAD);
                b.resetStateChanges();
            }
        }
    }

    /** 個体の種と番号から 0〜1 の数(毎フレーム同じ値)。 */
    private static float rand(long seed, int i) {
        long h = seed * 0x9E3779B97F4A7C15L + i * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 29;
        return (h >>> 40) / (float) (1L << 24);
    }
}
