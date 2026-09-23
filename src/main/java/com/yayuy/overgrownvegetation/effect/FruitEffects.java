package com.yayuy.overgrownvegetation.effect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;

/**
 * 過繫茂バイオームで採れる<b>実</b>を食べ終わったときに付く効果をまとめて配る。
 *
 * <ul>
 *     <li>{@link ModItems#OVERGROWN_GLOWBERRY 熟した垂れ草の光る果実} —
 *         {@link ModMobEffects#WELL_FED 「栄養満点！」}(毎 tick 食料ゲージ +1)と
 *         {@link ModMobEffects#SAVORY_SCENT 「香ばしいにおい」}(過繫茂 Mob からの被検知距離 +)。</li>
 *     <li>{@link ModItems#UNDEVELOPED_SEED 未発達な種子} — 再生・耐性(バニラ)と
 *         「香ばしいにおい」。実が未熟なぶん効き目は短いが、傷を癒やして身を固める。</li>
 *     <li>{@link ModItems#LARGE_UNDEVELOPED_FRUIT_ITEM 大きな未発達な果実} — 同じ顔ぶれを桁違いに
 *         長く強くした代わりに、<b>胞子感染が一段進み</b>、さらに数分後に激しい空腹が来る
 *         ({@link DelayedHungerTracker})。詳しくは {@link #eatLargeFruit}。</li>
 * </ul>
 *
 * <h2>アイテム側の {@code food(...)} ではなくイベントで付けている</h2>
 * {@code FoodProperties} に効果を書くと、持続や強さが<b>アイテム登録時に固定</b>される —
 * {@code effects.json} はもっと後に読まれるので、そこから数値を変えられなくなる。
 * このMODの効果はすべて設計データ({@link EffectTable})で調整できるようにしてあるので、
 * 食べ終わりのイベント({@link LivingEntityUseItemEvent.Finish})で<b>その場で読んで</b>付ける。
 *
 * <p>{@code Finish} は「食べ終わった直後」に飛ぶ。食べるのを途中でやめた場合は飛ばない
 * ({@code Stop} の方に行く)ので、かじりかけで効果だけ貰うことはできない。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class FruitEffects {

    @SubscribeEvent
    public static void onFinishEating(LivingEntityUseItemEvent.Finish event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) {
            return;
        }
        EffectTable fx = DesignData.effects();
        if (event.getItem().is(ModItems.OVERGROWN_GLOWBERRY.get())) {
            apply(entity, ModMobEffects.WELL_FED, fx.glowFruitWellFedTicks(), 0);
            apply(entity, ModMobEffects.SAVORY_SCENT, fx.glowFruitSavoryScentTicks(), 0);
        } else if (event.getItem().is(ModItems.UNDEVELOPED_SEED.get())) {
            apply(entity, MobEffects.REGENERATION, fx.seedRegenerationTicks(), fx.seedRegenerationAmplifier());
            apply(entity, MobEffects.DAMAGE_RESISTANCE, fx.seedResistanceTicks(), fx.seedResistanceAmplifier());
            apply(entity, ModMobEffects.SAVORY_SCENT, fx.seedSavoryScentTicks(), 0);
        } else if (event.getItem().is(ModItems.LARGE_UNDEVELOPED_FRUIT_ITEM.get())) {
            eatLargeFruit(entity, fx);
        }
    }

    /**
     * 「大きな未発達な果実」を食べたときの一式。<b>順番に意味がある</b>。
     *
     * <ol>
     *     <li>まずバフ(再生III・耐性II・栄養満点！)とデバフ(香ばしいにおい)を付ける。</li>
     *     <li>次に胞子感染を<b>一段進める</b>。未感染なら胞子感染1、1 なら 2、2 なら 3。
     *         <b>すでに胞子感染3 を持っていた場合は、切れるのを待たずにその場で満了時の結末</b>
     *         ({@link SporeInfectionExpiryHandler#applyExpiryOutcome})が起きる ——
     *         既定ではプレイヤーは死に、その場に汚染シダが生える。</li>
     *     <li>最後に、<b>まだ生きていれば</b>遅れて来る空腹を予約する
     *         ({@link DelayedHungerTracker})。3 の前に 2 を済ませてあるので、
     *         この果実で死んだプレイヤーに予約だけが残ることはない。</li>
     * </ol>
     */
    private static void eatLargeFruit(LivingEntity entity, EffectTable fx) {
        apply(entity, MobEffects.REGENERATION, fx.largeFruitRegenerationTicks(), fx.largeFruitRegenerationAmplifier());
        apply(entity, MobEffects.DAMAGE_RESISTANCE, fx.largeFruitResistanceTicks(), fx.largeFruitResistanceAmplifier());
        apply(entity, ModMobEffects.WELL_FED, fx.largeFruitWellFedTicks(), 0);
        apply(entity, ModMobEffects.SAVORY_SCENT, fx.largeFruitSavoryScentTicks(), 0);

        if (fx.largeFruitEscalatesInfection() && entity.level() instanceof ServerLevel level) {
            MobEffectInstance infection = entity.getEffect(ModMobEffects.SPORE_INFECTION);
            int next = infection == null ? 0 : infection.getAmplifier() + 1;
            if (next > EffectTable.MAX_LEVEL_INDEX) {
                // もう最高段階。「切れたときの効果」を今ここで適用する。
                SporeInfectionExpiryHandler.applyExpiryOutcome(level, entity, EffectTable.MAX_LEVEL_INDEX);
            } else {
                SporeInfectionEffects.infect(entity, next);
            }
        }

        if (entity instanceof Player player && player.isAlive()) {
            DelayedHungerTracker.schedule(player, fx.largeFruitHungerDelayTicks());
        }
    }

    /** 持続が 0 以下なら「その効果は付けない」の意味にする(設計データで個別に切れるように)。 */
    private static void apply(LivingEntity entity, Holder<MobEffect> effect, int ticks, int amplifier) {
        if (ticks > 0) {
            entity.addEffect(new MobEffectInstance(effect, ticks, amplifier, false, true, true));
        }
    }

    private FruitEffects() {
    }
}
