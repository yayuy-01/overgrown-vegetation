package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.entity.SporophyllAura;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModMobEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * スポロフィルの傘のバフ(ユーザー指定 2026-09-26「範囲を 64 にする代わりに耐性バフを配るのをやめ、再生は 2 ではなく 1」)。
 * <ul>
 *     <li>傘の下の味方には、バニラの効果が effects.json の<b>種類ごとの段階</b>で付き、-1 の種類(いまは耐性)は付かない。
 *     独自バフ「スポロフィルの胞子」も付く。</li>
 *     <li>味方でないもの(ハスク)には「纏わりついた胞子」だけが付き、バフは付かない。</li>
 *     <li>除去装置で剥がすときも、配っていない種類(耐性)には触らない —— 木の実など他所から付いた耐性が残る。</li>
 * </ul>
 * 段階は数字を決め打ちせず effects.json から読む(mod マネージャーで変えてもテストは通る)。
 *
 * <p>自分だけのバッチ: 同じバッチのテストは同じ世界に並んで同時に走るので、ほかのスポロフィルが近いと効果の出どころが混ざる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SporophyllGameTests {

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "sporophyll";

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(SporophyllGameTests.class);
        }

        private Registration() {
        }
    }

    /** 傘の下のアルボラ(味方)とハスク(味方でない)に付く効果を見る。配るのは 20 tick に 1 回なので 45 tick 待つ。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 100, batch = BATCH)
    public static void umbrellaGivesEachBuffAtItsOwnLevel(GameTestHelper helper) {
        // ほかのテスト場に残った胞子除去装置の圏内に入ることがある(2026-09-26 に実際に入って、効果が毎回剥がされた)。
        // 圏内では傘の効果が剥がれるので、このテストの間だけ装置の台帳を外す(TD の GameTest と同じ仕掛け)。
        long[][] devices = com.yayuy.overgrownvegetation.spore.SporeDeviceData.isolateForTests(helper.getLevel());
        // 床は相対 y = 1。スポロフィルは幅が 3 を超えうるので真ん中に、ほかは隅に置く。
        Mob sporophyll = helper.spawnWithNoFreeWill(ModEntities.SPOROPHYLL.get(), new BlockPos(3, 2, 3));
        Mob arbora = helper.spawnWithNoFreeWill(ModEntities.ARBORA.get(), new BlockPos(0, 2, 6));
        arbora.setNoAi(true);
        Mob husk = helper.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(6, 2, 0));
        husk.setNoAi(true);
        EffectTable fx = DesignData.effects();

        helper.runAfterDelay(45L, () -> {
            com.yayuy.overgrownvegetation.spore.SporeDeviceData.restoreForTests(helper.getLevel(), devices);
            // 落ちたときに原因を絞れるよう、スポロフィルの状態と両者の効果の一覧を添える。
            String state = "スポロフィル: 生存 " + sporophyll.isAlive() + "・tick " + sporophyll.tickCount
                    + "・他所の除去装置の圏内(テスト中は外していた) " + com.yayuy.overgrownvegetation.spore.SporeDeviceData.insidePurifier(
                            helper.getLevel(), arbora.blockPosition())
                    + " / アルボラ " + arbora.getActiveEffects().stream().map(e -> e.getDescriptionId() + "×" + e.getAmplifier()).toList()
                    + " / ハスク " + husk.getActiveEffects().stream().map(e -> e.getDescriptionId() + "×" + e.getAmplifier()).toList();
            boolean ok = expect(helper, arbora, MobEffects.REGENERATION, fx.sporophyllRegenerationAmplifier(), "アルボラの再生", state)
                    && expect(helper, arbora, MobEffects.DAMAGE_BOOST, fx.sporophyllStrengthAmplifier(), "アルボラの攻撃力上昇", state)
                    && expect(helper, arbora, MobEffects.DAMAGE_RESISTANCE, fx.sporophyllResistanceAmplifier(), "アルボラの耐性", state)
                    && expect(helper, arbora, ModMobEffects.SPOROPHYLL_SPORES, 0, "アルボラのスポロフィルの胞子", state)
                    && expect(helper, husk, ModMobEffects.CLINGING_SPORES, fx.clingingDebuffAmplifier(), "ハスクの纏わりついた胞子", state)
                    && expect(helper, husk, MobEffects.REGENERATION, -1, "ハスクの再生", state)
                    && expect(helper, husk, MobEffects.DAMAGE_BOOST, -1, "ハスクの攻撃力上昇", state)
                    && expect(helper, husk, ModMobEffects.SPOROPHYLL_SPORES, -1, "ハスクのスポロフィルの胞子", state);
            if (ok) {
                helper.succeed();
            }
        });
    }

    /** 除去装置の圏内で剥がすのは、いま配っている種類と「スポロフィルの胞子」だけ。 */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void purgeLeavesBuffsItDoesNotGive(GameTestHelper helper) {
        Mob arbora = helper.spawnWithNoFreeWill(ModEntities.ARBORA.get(), new BlockPos(3, 2, 3));
        arbora.setNoAi(true);
        EffectTable fx = DesignData.effects();
        Holder<MobEffect>[] vanilla = vanillaBuffs();
        int[] levels = {fx.sporophyllRegenerationAmplifier(), fx.sporophyllStrengthAmplifier(), fx.sporophyllResistanceAmplifier()};
        for (Holder<MobEffect> effect : vanilla) {
            arbora.addEffect(new MobEffectInstance(effect, 600, 0));
        }
        arbora.addEffect(new MobEffectInstance(ModMobEffects.SPOROPHYLL_SPORES, 600, 0));

        SporophyllAura.purgeBuffs(arbora);

        for (int i = 0; i < vanilla.length; i++) {
            boolean given = levels[i] >= 0;
            if (arbora.hasEffect(vanilla[i]) == given) {
                helper.fail(vanilla[i].getRegisteredName() + (given
                        ? " は傘が配る種類なのに剥がれていない"
                        : " は傘が配らない種類なのに剥がれた(他所から付いた効果を巻き込んでいる)"));
                return;
            }
        }
        if (arbora.hasEffect(ModMobEffects.SPOROPHYLL_SPORES)) {
            helper.fail("スポロフィルの胞子が剥がれていない");
            return;
        }
        helper.succeed();
    }

    @SuppressWarnings("unchecked")
    private static Holder<MobEffect>[] vanillaBuffs() {
        return new Holder[]{MobEffects.REGENERATION, MobEffects.DAMAGE_BOOST, MobEffects.DAMAGE_RESISTANCE};
    }

    /**
     * {@code amplifier} が -1 なら付いていないこと、0 以上ならその段階で付いていることを確かめる。
     * 落ちたら {@code state}(周りの様子)を添える。
     */
    private static boolean expect(GameTestHelper helper, LivingEntity entity, Holder<MobEffect> effect,
                                  int amplifier, String what, String state) {
        MobEffectInstance got = entity.getEffect(effect);
        if (amplifier < 0) {
            if (got != null) {
                helper.fail(what + " は配らない設定なのに付いている(段階 " + got.getAmplifier() + ") —— " + state);
                return false;
            }
            return true;
        }
        if (got == null || got.getAmplifier() != amplifier) {
            helper.fail(what + " が段階 " + amplifier + " で付いていない: "
                    + (got == null ? "付いていない" : "段階 " + got.getAmplifier()) + " —— " + state);
            return false;
        }
        return true;
    }

    private SporophyllGameTests() {
    }
}
