package com.yayuy.overgrownvegetation.gear.attach;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;

/**
 * 防具の付け物 1 種(2026-11-21 夜)。付け物の台で防具の枠にはめる。働きは {@link ArmorAttachments}・{@link AttachEffects}。
 *
 * <p>体に着た姿は GeckoLib の立体モデル({@code geo/armor_attachment/<id>_<部位>.geo.json} と {@code textures/armor_attachment/<id>.png}。
 * 描くのは {@code client/attach/AttachmentLayer})。付け物ごとにファイルを分けてあるので、Blockbench で作った物に差し替えられる。
 */
public class ArmorAttachmentItem extends Item implements GeoItem {

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    public ArmorAttachmentItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(getDescriptionId() + ".desc").withStyle(ChatFormatting.GRAY));
        AttachTable.Entry e = ArmorAttachments.entry(stack);
        if (e != null) {
            Component parts = Component.empty();
            boolean first = true;
            for (String p : e.parts) {
                if (!first) parts = parts.copy().append("・");
                parts = parts.copy().append(Component.translatable("armor_attachment.overgrown_vegetation.part." + p));
                first = false;
            }
            tooltip.add(Component.translatable("armor_attachment.overgrown_vegetation.fits", parts).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 動かない付け物なのでコントローラーは無し。
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }
}
