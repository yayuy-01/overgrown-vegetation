package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.effect.ClingingSporesEffect;
import com.yayuy.overgrownvegetation.effect.PhyllosensGuidanceEffect;
import com.yayuy.overgrownvegetation.effect.PhyllosensWatchEffect;
import com.yayuy.overgrownvegetation.effect.SavoryScentEffect;
import com.yayuy.overgrownvegetation.effect.SporeInfectionMobEffect;
import com.yayuy.overgrownvegetation.effect.SporophyllSporesEffect;
import com.yayuy.overgrownvegetation.effect.WaterRotEffect;
import com.yayuy.overgrownvegetation.effect.WellFedEffect;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMobEffects {

    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, OvergrownVegetation.MODID);

    /**
     * 「胞子感染」— 汚染ブロックの上に長時間滞在すると付与されるデバフ。
     * 付与判定は {@link com.yayuy.overgrownvegetation.effect.SporeInfectionTracker}、
     * 満了時の最終効果は {@link SporeInfectionMobEffect} を参照。
     */
    public static final DeferredHolder<MobEffect, SporeInfectionMobEffect> SPORE_INFECTION =
            MOB_EFFECTS.register("spore_infection",
                    () -> new SporeInfectionMobEffect(MobEffectCategory.HARMFUL, 0x6C4A82));

    /**
     * 「Philosense is watching you」— フィロセンスに見られ、汚染側に居場所を知られている<b>プレイヤー</b>の印。
     * これが付いている間、{@link #PHYLLOSENS_GUIDANCE 誘導}を受けた Mob が距離を問わず襲いに来る。
     * 付与は {@link com.yayuy.overgrownvegetation.entity.PhyllosensInfluence} の一箇所。
     */
    public static final DeferredHolder<MobEffect, PhyllosensWatchEffect> PHYLLOSENS_WATCH =
            MOB_EFFECTS.register("phyllosens_watch",
                    () -> new PhyllosensWatchEffect(MobEffectCategory.HARMFUL, 0x8FD36A));

    /**
     * 「フィロセンスの誘導」— フィロセンスから居場所を教わった<b>味方(グループ1〜10)</b>の印。
     * {@link #PHYLLOSENS_WATCH 視線}持ちのプレイヤーへ、距離も視界も無視して向かうようになる。
     */
    public static final DeferredHolder<MobEffect, PhyllosensGuidanceEffect> PHYLLOSENS_GUIDANCE =
            MOB_EFFECTS.register("phyllosens_guidance",
                    () -> new PhyllosensGuidanceEffect(MobEffectCategory.BENEFICIAL, 0xC7E36B));

    /**
     * 「スポロフィルの胞子」— スポロフィルの傘の下にいる<b>味方</b>のバフ。
     * 攻撃が相手の防御力を半分無視し、防具の耐久値を大きく削る
     * ({@link com.yayuy.overgrownvegetation.effect.SporophyllCombatHandler})。
     */
    public static final DeferredHolder<MobEffect, SporophyllSporesEffect> SPOROPHYLL_SPORES =
            MOB_EFFECTS.register("sporophyll_spores",
                    () -> new SporophyllSporesEffect(MobEffectCategory.BENEFICIAL, 0xD8C24A));

    /**
     * 「纏わりついた胞子」— スポロフィルの傘の下にいる<b>敵</b>のデバフ。
     * グループ4 の Mob からの被検知距離が伸び、<b>移動速度と攻撃力が落ちる</b>
     * (後者 2 つは効果自身の能力値修正。2026-09-16 にバニラ効果から移した)。
     */
    public static final DeferredHolder<MobEffect, ClingingSporesEffect> CLINGING_SPORES =
            MOB_EFFECTS.register("clinging_spores",
                    () -> new ClingingSporesEffect(MobEffectCategory.HARMFUL, 0x8A7A3C));

    /**
     * 「水ぐされ」— 水に 30 秒以上浸かった<b>グループ1〜10</b>の Mob が起こす腐り。
     * 毎秒 1 ダメージ。浸かっているあいだは張り直され続け、上がってからは 30 秒で切れる。
     * 水中を生息域にしている Mob({@link com.yayuy.overgrownvegetation.entity.AquaticOvergrownMob} と
     * {@code #overgrown_vegetation:water_rot_immune})は対象外。
     * 付与判定は {@link com.yayuy.overgrownvegetation.effect.WaterRotTracker}。
     */
    public static final DeferredHolder<MobEffect, WaterRotEffect> WATER_ROT =
            MOB_EFFECTS.register("water_rot",
                    () -> new WaterRotEffect(MobEffectCategory.HARMFUL, 0x4E7A6B));

    /**
     * 「栄養満点！」— {@link com.yayuy.overgrownvegetation.registry.ModItems#OVERGROWN_GLOWBERRY
     * 熟した垂れ草の光る果実}を食べると付くバフ。付いているあいだ<b>毎 tick 食料ゲージが 1 回復</b>する
     * ({@link WellFedEffect})。付与は {@link com.yayuy.overgrownvegetation.effect.FruitEffects}。
     */
    public static final DeferredHolder<MobEffect, WellFedEffect> WELL_FED =
            MOB_EFFECTS.register("well_fed",
                    () -> new WellFedEffect(MobEffectCategory.BENEFICIAL, 0xBEC201));

    /**
     * 「香ばしいにおい」— 同じ果実を食べると {@link #WELL_FED} と<b>対で</b>付くデバフ。
     * <b>過繫茂 Mob(グループ1〜10)からの被検知距離が 15 ブロック伸びる</b>
     * ({@link SavoryScentEffect}、判定は
     * {@link com.yayuy.overgrownvegetation.effect.SporeInfectionDetection})。
     */
    public static final DeferredHolder<MobEffect, SavoryScentEffect> SAVORY_SCENT =
            MOB_EFFECTS.register("savory_scent",
                    () -> new SavoryScentEffect(MobEffectCategory.HARMFUL, 0xE1DF23));

    public static void register(IEventBus modBus) {
        MOB_EFFECTS.register(modBus);
    }

    private ModMobEffects() {
    }
}
