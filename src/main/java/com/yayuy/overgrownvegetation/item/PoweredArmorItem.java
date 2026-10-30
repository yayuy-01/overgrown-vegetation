package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.GearTable;
import com.yayuy.overgrownvegetation.registry.ModDataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 「パワードアーマー」(順 1 の着手順 6、2026-10-03)— 防具の 5 段目。4 部位それぞれアイテムは 1 個だけ登録し、
 * Mk({@link ModDataComponents#POWERED_ARMOR_MK})とスキルツリー({@link ModDataComponents#POWERED_ARMOR_TREE})は
 * スタックが持つ。見た目は Mk・ツリーで変わらない(ユーザー指定)。
 *
 * <ul>
 *   <li>土台の数値は {@code gear.json} の {@code armor.powered_armor}(強化クロロフィライト合金と同じ防御 30 = バニラの上限、
 *   強靭 5/部位 = 計 20 で上限)</li>
 *   <li>防御・強靭のツリーは属性ではなく被害を減らす({@link PoweredArmorEffects})。耐久は {@link #getMaxDamage} で</li>
 *   <li>過繁茂の Mob からの被害 10%/部位は強化合金と同じタグ {@code #overgrown_vegetation:overgrowth_resistant}</li>
 * </ul>
 *
 * <p>描画は GeckoLib の立体モデル(着たとき)。<b>モデルはユーザーが Blockbench で作ったもの</b>(2026-10-05 に差し替え。
 * 色は Claude が tools/powered-suit/paint.sh で塗った)—— 差し替えは {@code geo/armor/powered_armor.geo.json} と
 * {@code textures/armor/powered_armor.png} の 2 ファイルだけで、コードは触らなくてよい(ボーン名を変えるときだけ
 * {@code PoweredArmorModel})。アイテムのアイコンは平らな 16×16({@code textures/item/powered_armor_*.png})。
 */
public class PoweredArmorItem extends ArmorItem implements GeoItem {

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public PoweredArmorItem(Holder<ArmorMaterial> material, Type type, Properties properties) {
        super(material, type, properties);
    }

    // ---------- スタックが持つ値 ----------

    public static PoweredArmorTree tree(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.POWERED_ARMOR_TREE.get(), PoweredArmorTree.NONE);
    }

    /** ツリーを書き換える(全部 0 ならコンポーネントを外す —— 作り立てのものと重なるように)。 */
    public static void setTree(ItemStack stack, PoweredArmorTree tree) {
        if (tree.isEmpty()) {
            stack.remove(ModDataComponents.POWERED_ARMOR_TREE.get());
        } else {
            stack.set(ModDataComponents.POWERED_ARMOR_TREE.get(), tree);
        }
    }

    public static int mk(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.POWERED_ARMOR_MK.get(), 1);
    }

    /** 付いているカートリッジの中身。付いていなければ null(着手順 8)。 */
    @Nullable
    public static CartridgeContents cartridge(ItemStack stack) {
        return stack.get(ModDataComponents.POWERED_ARMOR_CARTRIDGE.get());
    }

    // ---------- 性能 ----------

    /** 耐久のツリー 1 段ごとに {@code poweredArmor.durabilityPerLevel} ぶん増える。 */
    @Override
    public int getMaxDamage(ItemStack stack) {
        int base = super.getMaxDamage(stack);
        int level = tree(stack).durability();
        if (level <= 0) {
            return base;
        }
        return (int) Math.round(base * (1.0D + level * DesignData.gear().poweredArmor().durabilityPerLevel()));
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable(this.getDescriptionId(stack)).append(" Mk." + mk(stack));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        PoweredArmorTree tree = tree(stack);
        GearTable.PoweredArmor cfg = DesignData.gear().poweredArmor();
        tooltip.add(line("defense", tree.defense(), percent(tree.defense() * cfg.defensePerLevel())));
        tooltip.add(line("toughness", tree.toughness(), percent(tree.toughness() * cfg.piercingPerLevel())));
        tooltip.add(line("durability", tree.durability(), percent(tree.durability() * cfg.durabilityPerLevel())));
        CartridgeContents cart = cartridge(stack);
        if (cart != null) {
            tooltip.add(Component.translatable("tooltip.overgrown_vegetation.powered_armor.cartridge").withStyle(ChatFormatting.AQUA));
            CartridgeItem.appendContents(cart, tooltip);
        }
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.powered_armor.where").withStyle(ChatFormatting.DARK_GRAY));
        super.appendHoverText(stack, context, tooltip, flag);
    }

    private static Component line(String branch, int level, String amount) {
        return Component.translatable("tooltip.overgrown_vegetation.powered_armor." + branch, level, amount)
                .withStyle(level > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    /** 0.04 → "4"(小数が要る値だけ 1 桁)。 */
    public static String percent(double ratio) {
        double v = ratio * 100.0D;
        return Math.abs(v - Math.rint(v)) < 1e-6 ? String.valueOf((long) Math.rint(v)) : String.format(Locale.ROOT, "%.1f", v);
    }

    // ---------- GeckoLib ----------

    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private HumanoidModel<?> renderer;

            @Override
            public <T extends LivingEntity> HumanoidModel<?> getGeoArmorRenderer(@Nullable T livingEntity, ItemStack itemStack,
                    @Nullable EquipmentSlot equipmentSlot, @Nullable HumanoidModel<T> original) {
                if (this.renderer == null) {
                    this.renderer = new com.yayuy.overgrownvegetation.client.PoweredArmorRenderer();
                }
                return this.renderer;
            }
        });
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 静的モデル(ユーザーのモデルが動くようになったら足す)。
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }
}
