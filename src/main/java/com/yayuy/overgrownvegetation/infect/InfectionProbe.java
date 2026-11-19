package com.yayuy.overgrownvegetation.infect;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.corpse.CorpseCollapse;
import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * 感染の試し棒(2026-11-19 ユーザー「汎用感染システムが他の mod でうまくいくか確かめるように、相手を即死させて感染形態にさせる棒的なもの」)。
 * 確かめ用 —— クリエイティブのタブにだけ出し、レシピは無い。使えるのはクリエイティブか OP(権限 2)だけ。
 *
 * <ul>
 *     <li><b>殴る</b>({@link #strike}): 相手を<b>本物の感染の死の道筋で</b>即死させる。{@code SporeExposureTracker#hurtMob} と同じく、胞子のダメージ
 *     ({@code spore_infection})を当て、弾かれたら {@link GenericInfection#onSporeHitBlocked} へ回す(耐性を持つ Mob の道筋)。
 *     その後の様子から、どの道筋になったか(G1 の種 / 汎用の感染体 / 変われずに死んだ / 死ななかった)と、変われなかった理由を 1 行で返す。</li>
 *     <li><b>スニーク + 右クリック</b>({@link #diagnose}): 殺さずに、感染の死で何が起きるはずかと理由だけを見る。村人・馬などは右クリックを
 *     自分で使うので、アイテムの右クリックではなく {@link PlayerInteractEvent.EntityInteract}(相手より先に来る)で受ける。</li>
 *     <li>プレイヤーには効かない。もう植物側の Mob(G1・G4 以降・変わった Mob)は殺さずに「植物側」とだけ返す。</li>
 *     <li>部位に分かれた Mob(エンダードラゴンやほかの MOD のボス)は、部位を殴っても本体に当てる。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class InfectionProbe {

    private InfectionProbe() {
    }

    private static final String KEY = "item." + OvergrownVegetation.MODID + ".infection_probe.";

    /** どの道筋になったか。 */
    public enum Kind {
        /** 専用の G1 の死体へ崩れ落ちた。 */
        G1,
        /** 汎用の感染体に変わった。 */
        GENERIC,
        /** 変われずに死んだ(萌芽体が生えることがある)。 */
        DIED,
        /** 死ななかった(無敵・ダメージの上限・ほかの MOD が死を止めた など)。 */
        SURVIVED,
        /** もう植物側なので何もしない。 */
        PLANT_SIDE,
        /** プレイヤー・生き物でない相手には効かない。 */
        NOT_TARGET
    }

    /**
     * 結果。
     *
     * @param species   G1 になった(なる)種
     * @param refusal   汎用の感染体になれない理由(G1 になったときは {@code HAS_G1})
     * @param viaBlocked 胞子のダメージが弾かれ、弾かれた分を数える道筋で変わった
     */
    public record Outcome(Kind kind, @Nullable G1Species species, @Nullable GenericInfection.Refusal refusal, boolean boss,
                          boolean viaBlocked, float healthBefore, float healthAfter) {
    }

    /** 部位(エンダードラゴンの胴など)なら本体。 */
    public static Entity resolve(Entity hit) {
        return hit instanceof PartEntity<?> part ? part.getParent() : hit;
    }

    /** 使ってよい人か(クリエイティブか OP)。 */
    public static boolean mayUse(Player player) {
        return player.isCreative() || player.hasPermissions(2);
    }

    /** ダメージの量: 体力 + 衝撃吸収の 2 倍(1000 より下にしない)。大きすぎる値(無限など)はほかの MOD の計算を壊すので使わない。 */
    private static float lethalAmount(LivingEntity target) {
        return Math.max(1000.0F, (target.getHealth() + target.getAbsorptionAmount()) * 2.0F);
    }

    // ---------------------------------------------------------------- 殴る

    /** 相手を感染の死の道筋で即死させ、結果を返す(サーバーだけ)。 */
    public static Outcome strike(ServerLevel level, Entity hit) {
        Entity e = resolve(hit);
        if (!(e instanceof LivingEntity target) || target instanceof Player || !target.isAlive()) {
            return new Outcome(Kind.NOT_TARGET, null, null, false, false, 0.0F, 0.0F);
        }
        float before = target.getHealth() + target.getAbsorptionAmount();
        if (com.yayuy.overgrownvegetation.spore.SporeExposureTracker.isPlantSide(target)) {
            return new Outcome(Kind.PLANT_SIDE, null, GenericInfection.Refusal.PLANT_SIDE, false, false, before, before);
        }
        G1Species species = G1Species.ofVictim(target);
        GenericInfection.Refusal refusal = GenericInfection.deathTurnRefusal(target); // 死ぬ前に(死ぬと REMOVED になる)
        boolean boss = GenericInfection.isBossType(target);

        // SporeExposureTracker#hurtMob と同じ判定: 入ったか = 死んだ・変わった・体力が減った
        float amount = lethalAmount(target);
        boolean returned = target.hurt(ModDamageTypes.sporeInfection(level), amount);
        boolean landed = returned && (target.isDeadOrDying() || target.isRemoved() || GenericInfection.isTurned(target)
                || target.getHealth() + target.getAbsorptionAmount() < before - 1.0E-4F);
        boolean viaBlocked = false;
        if (!landed) {
            // 胞子のダメージの種類に耐性を持つ Mob(Cataclysm など)の本物の道筋: 弾かれた分を数えて、体力に届いたら変わる
            viaBlocked = GenericInfection.onSporeHitBlocked(level, target, amount);
        }
        float after = target.isAlive() ? target.getHealth() + target.getAbsorptionAmount() : 0.0F;

        Kind kind;
        if (GenericInfection.isTurned(target)) {
            kind = Kind.GENERIC;
        } else if (CorpseCollapse.isCollapsing(target)) {
            kind = Kind.G1;
        } else if (target.isDeadOrDying() || target.isRemoved()) {
            kind = Kind.DIED;
        } else {
            kind = Kind.SURVIVED;
        }
        return new Outcome(kind, species, refusal, boss, viaBlocked && kind == Kind.GENERIC, before, after);
    }

    // ---------------------------------------------------------------- 見るだけ

    /** 殺さずに、感染の死で何が起きるはずかを返す。 */
    public static Outcome diagnose(Entity hit) {
        Entity e = resolve(hit);
        if (!(e instanceof LivingEntity target) || target instanceof Player) {
            return new Outcome(Kind.NOT_TARGET, null, null, false, false, 0.0F, 0.0F);
        }
        float hp = target.getHealth() + target.getAbsorptionAmount();
        if (com.yayuy.overgrownvegetation.spore.SporeExposureTracker.isPlantSide(target)) {
            return new Outcome(Kind.PLANT_SIDE, null, GenericInfection.Refusal.PLANT_SIDE, false, false, hp, hp);
        }
        G1Species species = G1Species.ofVictim(target);
        GenericInfection.Refusal refusal = GenericInfection.deathTurnRefusal(target);
        // 表の種は G1 の死体へ(G1Infection はピースフルを見ない)。それ以外は変われるか
        Kind kind = species != null ? Kind.G1 : refusal == null ? Kind.GENERIC : Kind.DIED;
        return new Outcome(kind, species, refusal, GenericInfection.isBossType(target), false, hp, hp);
    }

    // ---------------------------------------------------------------- 文

    /** チャットに出す 1 行。{@code predicted} = 見るだけ(「〜になる」)。 */
    public static Component message(Entity hit, Outcome o, boolean predicted) {
        Entity e = resolve(hit);
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        MutableComponent line = Component.literal("[").append(Component.translatable(KEY + "tag")).append("] ")
                .withStyle(ChatFormatting.DARK_GREEN);
        line.append(e.getDisplayName().copy().withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (" + id + "): ").withStyle(ChatFormatting.GRAY));
        String p = predicted ? "predict." : "result.";
        MutableComponent body = switch (o.kind()) {
            case G1 -> Component.translatable(KEY + p + "g1",
                    o.species() == null ? Component.literal("?") : o.species().entityType().getDescription()).withStyle(ChatFormatting.YELLOW);
            case GENERIC -> {
                MutableComponent c = Component.translatable(KEY + p + "generic").withStyle(ChatFormatting.GREEN);
                if (o.viaBlocked()) c.append(Component.translatable(KEY + "via_blocked").withStyle(ChatFormatting.GRAY));
                yield c;
            }
            case DIED -> Component.translatable(KEY + p + "died", reason(o.refusal())).withStyle(ChatFormatting.RED);
            case SURVIVED -> Component.translatable(KEY + "result.survived",
                    String.format(Locale.ROOT, "%.1f", o.healthBefore()), String.format(Locale.ROOT, "%.1f", o.healthAfter()))
                    .withStyle(ChatFormatting.GOLD);
            case PLANT_SIDE -> Component.translatable(KEY + "plant_side").withStyle(ChatFormatting.GRAY);
            case NOT_TARGET -> Component.translatable(KEY + "not_target").withStyle(ChatFormatting.GRAY);
        };
        line.append(body);
        if (o.boss() && (o.kind() == Kind.GENERIC || predicted)) {
            line.append(Component.translatable(KEY + "boss",
                    String.format(Locale.ROOT, "%.1f", GenericInfection.spec().bossMaxHealthFactor())).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        // 見るだけのときは、ふだんの遊びで感染の死まで届くかの手がかりも
        if (predicted && e instanceof LivingEntity le && o.kind() != Kind.PLANT_SIDE && o.kind() != Kind.NOT_TARGET) {
            if (le.getType().is(ModTags.SPORE_EXPOSURE_IMMUNE)) line.append(Component.translatable(KEY + "exposure_immune").withStyle(ChatFormatting.GRAY));
            if (le.isInvulnerable()) line.append(Component.translatable(KEY + "invulnerable").withStyle(ChatFormatting.GRAY));
        }
        return line;
    }

    private static Component reason(@Nullable GenericInfection.Refusal r) {
        return r == null ? Component.translatable(KEY + "refusal.unknown") : Component.translatable(KEY + "refusal." + r.key());
    }

    // ---------------------------------------------------------------- スニーク + 右クリック

    /** スニーク + 右クリックで見るだけ。相手の右クリック(村人の取引・馬に乗る)より先に止める。 */
    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (!player.isShiftKeyDown() || !event.getItemStack().is(ModItems.INFECTION_PROBE.get())) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide()));
        if (event.getLevel().isClientSide()) return;
        if (!mayUse(player)) {
            player.sendSystemMessage(Component.translatable(KEY + "no_permission").withStyle(ChatFormatting.RED));
            return;
        }
        Entity target = event.getTarget();
        player.sendSystemMessage(message(target, diagnose(target), true));
    }
}
