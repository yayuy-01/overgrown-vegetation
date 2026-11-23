package com.yayuy.overgrownvegetation.difficulty;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.research.Research;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.util.Set;

/**
 * 作業台・持ち物の 2×2・クラフターで「この作り方は今作れないか」(研究の鍵と実験的要素。{@code CraftingMenuResearchLockMixin}・
 * {@code CrafterResearchLockMixin} が読む)。2026-11-23 夜に、研究の鍵に実験的要素の「防具の付け物」を足して 1 つの口にした。
 */
public final class ExperimentalRecipes {

    /** 「防具の付け物」がオフで閉じる作り方(付け物の台と、口覆いの布以外の付け物)。 */
    public static final Set<ResourceLocation> ATTACHMENT_RECIPES = Set.of(
            OvergrownVegetation.id("attachment_bench"),
            OvergrownVegetation.id("storage_belt"),
            OvergrownVegetation.id("bark_plate"),
            OvergrownVegetation.id("conduit_sash"));

    private ExperimentalRecipes() {
    }

    public static boolean locked(MinecraftServer server, ResourceLocation recipe) {
        if (Research.recipeLocked(server, recipe)) return true;
        return ATTACHMENT_RECIPES.contains(recipe)
                && !ExperimentalFeatures.enabled(server, ExperimentalFeatures.Feature.ARMOR_ATTACHMENTS);
    }
}
