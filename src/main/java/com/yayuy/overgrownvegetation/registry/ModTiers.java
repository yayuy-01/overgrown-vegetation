package com.yayuy.overgrownvegetation.registry;

import com.google.common.base.Suppliers;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.GearTable;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;

import java.util.function.Supplier;

/**
 * MOD のツール階層({@link Tier})。耐久・採掘速度・攻撃力ボーナス・エンチャント適性は
 * {@link GearTable}(= {@code config/overgrown_vegetation/gear.json})から読む。
 *
 * <p>{@code getIncorrectBlocksForDrops()} はバニラの「この階層では正しく採掘できないブロック」タグを
 * 流用している(クロロフィライト=鉄ツール相当、合金=ダイヤツール相当)。
 */
public enum ModTiers implements Tier {

    /** 「クロロフィライトツール」— 鉄とダイヤの中間の性能。耐久は鉄と同じ。修理素材は精錬後の「クロロフィライト」。 */
    CHLOROPHYLLITE("chlorophyllite", BlockTags.INCORRECT_FOR_IRON_TOOL,
            () -> ModItems.CHLOROPHYLLITE),

    /** 「クロロフィライト合金ツール」— 採掘・攻撃はダイヤ相当。ただし耐久は鉄より少し上どまりの最上位。 */
    CHLOROPHYLLITE_ALLOY("chlorophyllite_alloy", BlockTags.INCORRECT_FOR_DIAMOND_TOOL,
            () -> ModItems.CHLOROPHYLLITE_ALLOY);

    private final String key;
    private final TagKey<Block> incorrectForDrops;
    private final Supplier<Ingredient> repairIngredient;

    ModTiers(String key, TagKey<Block> incorrectForDrops, Supplier<? extends ItemLike> repairItem) {
        this.key = key;
        this.incorrectForDrops = incorrectForDrops;
        this.repairIngredient = Suppliers.memoize(() -> Ingredient.of(repairItem.get()));
    }

    private GearTable.ToolSpec spec() {
        return DesignData.gear().tool(key);
    }

    /** gear.json でのこのセットの識別子。 */
    public String key() {
        return key;
    }

    /**
     * ツール種別({@code axe}/{@code pickaxe}/{@code shovel}/{@code hoe}/{@code sword}/{@code shield})
     * ごとの {@link Tier} ビュー。耐久だけを {@code gear.json} の種別別の値に差し替える
     * (採掘速度・攻撃力ボーナス・エンチャント適性・修理素材はセット共通のまま)。
     */
    public Tier forTool(String toolType) {
        return new Tier() {
            @Override
            public int getUses() {
                return spec().durability(toolType);
            }

            @Override
            public float getSpeed() {
                return ModTiers.this.getSpeed();
            }

            @Override
            public float getAttackDamageBonus() {
                return ModTiers.this.getAttackDamageBonus();
            }

            @Override
            public TagKey<Block> getIncorrectBlocksForDrops() {
                return incorrectForDrops;
            }

            @Override
            public int getEnchantmentValue() {
                return ModTiers.this.getEnchantmentValue();
            }

            @Override
            public Ingredient getRepairIngredient() {
                return repairIngredient.get();
            }
        };
    }

    @Override
    public int getUses() {
        return spec().uses();
    }

    @Override
    public float getSpeed() {
        return spec().speed();
    }

    @Override
    public float getAttackDamageBonus() {
        return spec().attackDamageBonus();
    }

    @Override
    public TagKey<Block> getIncorrectBlocksForDrops() {
        return incorrectForDrops;
    }

    @Override
    public int getEnchantmentValue() {
        return spec().enchantmentValue();
    }

    @Override
    public Ingredient getRepairIngredient() {
        return repairIngredient.get();
    }
}
