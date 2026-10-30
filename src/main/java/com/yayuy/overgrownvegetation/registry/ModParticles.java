package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * このMODの独自パーティクル。
 */
public final class ModParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, OvergrownVegetation.MODID);

    /**
     * 「過繁茂の胞子」— 過繁茂バイオームに漂う胞子と同系の緑の粒。ただしバニラの
     * {@code spore_blossom_air} と違い、与えた初速で飛び、毎 tick の摩擦と重力で
     * 徐々に減速・落下する({@link com.yayuy.overgrownvegetation.client.OvergrownSporeParticle})。
     * フェーズ1開始の地割れ演出で、裂け目の底から花火のように噴き上げるのに使う。
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> OVERGROWN_SPORE =
            PARTICLE_TYPES.register("overgrown_spore", () -> new SimpleParticleType(false));

    /**
     * 「過繁茂の胞子」の短命版 — 見た目は {@link #OVERGROWN_SPORE} と同じで、寿命が 20〜40 tick と短く、
     * 不透明なブロックに埋まったら消える。爆弾の航跡・爆発、胞子腺、ペディキュラが弾けたときに使う
     * (2026-09-25、全体監査 #479/#991。長命版は地割れの噴出専用に戻した)。
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> OVERGROWN_SPORE_SHORT =
            PARTICLE_TYPES.register("overgrown_spore_short", () -> new SimpleParticleType(false));

    /**
     * 「細かい胞子」— {@link #OVERGROWN_SPORE} と同じ色・同じテクスチャだが、粒が 1/5 ほど小さく
     * 寿命も短い({@link com.yayuy.overgrownvegetation.client.FineSporeParticle})。
     * ヴェロフィルが飛行中に翼の後ろへ曳く航跡用。数を出して「細かい粉が筋を引く」見え方にする。
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FINE_SPORE =
            PARTICLE_TYPES.register("fine_spore", () -> new SimpleParticleType(false));

    /**
     * 「噴き出す胞子」— 萌芽体の胞子嚢がしぼむ瞬間に先端の 1 点から上へ勢いよく噴く小さな粒(2026-10-23。
     * {@link com.yayuy.overgrownvegetation.client.SporeJetParticle}・出す側は {@link com.yayuy.overgrownvegetation.client.SporeJets})。
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SPORE_JET =
            PARTICLE_TYPES.register("spore_jet", () -> new SimpleParticleType(false));

    /**
     * 「漂う胞子」— 汚染地域のバイオームの環境の粒(バイオームの {@code effects.particle})。見た目はバニラの {@code spore_blossom_air} と同じで、
     * 動きだけ汚染地域の風に乗る({@link com.yayuy.overgrownvegetation.client.DriftingSporeParticle}、2026-10-15 夜)。
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> DRIFTING_SPORE =
            PARTICLE_TYPES.register("drifting_spore", () -> new SimpleParticleType(false));

    /**
     * 空の巨大生物の大きな爆発・煙(2026-10-18 夜、仮)。バニラの爆発・焚き火の煙の絵で、大きさ(ブロック)と現れるまでの遅れ(tick)を速さの x・y で受け取る
     * ({@link com.yayuy.overgrownvegetation.client.skyboss.SkyBossParticles})。粒の設定を減らしていても出す。
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SKY_BOSS_BLAST =
            PARTICLE_TYPES.register("sky_boss_blast", () -> new SimpleParticleType(true));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SKY_BOSS_SMOKE =
            PARTICLE_TYPES.register("sky_boss_smoke", () -> new SimpleParticleType(true));

    public static void register(IEventBus modBus) {
        PARTICLE_TYPES.register(modBus);
    }

    private ModParticles() {
    }
}
