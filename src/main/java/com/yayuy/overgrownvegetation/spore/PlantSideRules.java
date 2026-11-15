package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 植物側の「死ぬと破裂する」「殴った Mob を必ず感染させる」の対象(2026-11-15 ユーザー決定。正本は Vault
 * 「構想と進捗/植物側の死の破裂と攻撃の感染(11-15)」)。
 *
 * <ul>
 *   <li>対象 = G1(段階 0 も)・G4〜G10 のタグ、または汎用の感染体(変わった印 = {@link #TRANSFORMED})。G2・G3 は入れない
 *   (生態系で生きているだけ)。</li>
 *   <li>破裂だけの外し = タグ {@code overgrown_vegetation:death_burst_exempt}(11-15 ユーザー決定: 根付き・苗床・根の守り手は「枯れて崩れる」のまま、
 *   萌芽体は外す、ペディキュラは段 2 で自前の破れ方)、
 *   G1 の進化体(すでに自前の death_burst で破裂する)、エンダードラゴン(死の演出が 200 tick あり、戦いの終わりがそこに付いている)。</li>
 * </ul>
 * サーバー(破裂)とクライアント(膨らみ)の両方がここを読む —— 片方だけで膨らむ・破裂するずれを出さない。
 */
public final class PlantSideRules {

    /** 自前の死に方を持つので破裂させない種(11-15 の一覧。ユーザーの答えで json を直す)。 */
    public static final TagKey<EntityType<?>> DEATH_BURST_EXEMPT =
            TagKey.create(Registries.ENTITY_TYPE, OvergrownVegetation.id("death_burst_exempt"));

    /** 破裂・必ず感染の対象のグループ(G2・G3 を除く)。 */
    private static final List<TagKey<EntityType<?>>> GROUPS = List.of(ModTags.GROUP_1, ModTags.GROUP_4, ModTags.GROUP_5,
            ModTags.GROUP_6, ModTags.GROUP_7, ModTags.GROUP_8, ModTags.GROUP_9, ModTags.GROUP_10);

    /**
     * 汎用の感染体(変わった印)か。土台の側(汎用感染システム)が自分の印の問い合わせを差し込む口。差し込むまでは誰も変わっていない。
     * サーバー側の判定。クライアントは {@code client/infect/GenericInfectionClient} を見る({@link #burstsWith})。
     */
    public static volatile Predicate<LivingEntity> TRANSFORMED = e -> false;

    private PlantSideRules() {
    }

    public static boolean isTransformed(LivingEntity e) {
        return TRANSFORMED.test(e);
    }

    /** G1・G4〜G10 のタグのどれかか(変わった印は見ない)。 */
    public static boolean inBurstGroups(EntityType<?> type) {
        for (TagKey<EntityType<?>> g : GROUPS) {
            if (type.is(g)) return true;
        }
        return false;
    }

    /** 殴った相手(プレイヤー以外の Mob)を必ず感染させる側か。 */
    public static boolean isInfector(LivingEntity attacker) {
        return inBurstGroups(attacker.getType()) || isTransformed(attacker);
    }

    /** 死ぬと破裂する側か(サーバー)。 */
    public static boolean bursts(LivingEntity e) {
        return burstsWith(e, isTransformed(e));
    }

    /** 死ぬと破裂する側か。{@code transformed} = 変わった印(クライアントは自分の同期の表から渡す)。 */
    public static boolean burstsWith(LivingEntity e, boolean transformed) {
        if (!transformed && !inBurstGroups(e.getType())) return false;
        if (e.getType().is(DEATH_BURST_EXEMPT)) return false;
        if (e instanceof com.yayuy.overgrownvegetation.entity.DevelopedParasitized) return false;
        return !(e instanceof EnderDragon);
    }

    /**
     * 破裂する tick(deathTime)。15〜18 を個体ごとにずらす(大の襲撃で一斉に死んでも同じ tick に重ならない)。
     * バニラは 20 tick 目に白い煙を出して消すので、それより前。クライアントは同じ式で膨らみの進みを出す(id はサーバーと同じ)。【仮】
     */
    public static int burstTick(LivingEntity e) {
        return deathAnimTicks(e) + BURST_TICK_MIN + Math.floorMod(e.getId() * 31, BURST_TICK_SPREAD);
    }

    /**
     * 死に際のアニメを持つ種の、そのアニメの長さ(tick)。膨らみはこの後に始まる(11-15 ユーザー決定「アニメの後に破裂」)。無い種は 0。
     * 値は各 Mob の倒れる長さと同じにする: ファルキムネス {@code DEATH_TICKS}(移り 4 + 1.5 秒)・フィロセンス {@code DEATH_TICKS}(2 秒)・
     * アクムネス death(1.5 秒 + 移り 2)。
     */
    public static int deathAnimTicks(LivingEntity e) {
        Integer t = DEATH_ANIM_TICKS.get(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()));
        return t == null ? 0 : t;
    }

    private static final Map<ResourceLocation, Integer> DEATH_ANIM_TICKS = Map.of(
            OvergrownVegetation.id("falcimnes"), 34,
            OvergrownVegetation.id("phyllosens"), 40,
            OvergrownVegetation.id("acumnes"), 32);

    /** 【仮】膨らみの長さの最短(tick)と、ずらしの幅。 */
    public static final int BURST_TICK_MIN = 15, BURST_TICK_SPREAD = 4;
}
