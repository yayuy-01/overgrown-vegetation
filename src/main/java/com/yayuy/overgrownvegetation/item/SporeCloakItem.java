package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.List;

/**
 * 胞子まといの外套(2026-10-16、②)。倒した G1 から取った死んだ胞子を染み込ませ、植物に「もう寄生された体」と思わせる。
 * <ul>
 *     <li>ユーザー指定: 体の追加防具の枠 / 代償(浸食度が上がる)は浸食度を作り直すときに決める(今は無い)。</li>
 *     <li>仮決め: グループ 1・2 の Mob は着ている人を狙わない。ただし植物側の Mob を殴ってから {@link #AGGRO_TICKS}(5 秒)の間は効かない /
 *     汚染された羊毛 6 + 胞子 1 で作る。</li>
 * </ul>
 * 止めるのは {@code Mob#setTarget} の手前の 1 か所({@link LivingChangeTargetEvent}、寄生植物装備と同じやり方)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public class SporeCloakItem extends Item implements ExtraArmorItem {

    public static final int AGGRO_TICKS = 100;
    private static final String AGGRO_KEY = "OvCloakAggroUntil";

    public SporeCloakItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public ExtraArmor.Part extraArmorPart() {
        return ExtraArmor.Part.BODY;
    }

    @Override
    public ResourceLocation wornTexture() {
        return null;
    }

    /** 外套が今効いているか(着ていて、最近植物側を殴っていない)。 */
    public static boolean disguised(Player player) {
        if (!(ExtraArmor.worn(player, ExtraArmor.Part.BODY).getItem() instanceof SporeCloakItem)) {
            return false;
        }
        return player.level().getGameTime() >= player.getPersistentData().getLong(AGGRO_KEY);
    }

    /** 外套にだまされる Mob か(グループ 1・2)。 */
    public static boolean fooled(LivingEntity mob) {
        MobGroup group = MobGroup.of(mob);
        return group == MobGroup.GROUP_1 || group == MobGroup.GROUP_2;
    }

    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() instanceof Player player && fooled(event.getEntity()) && disguised(player)) {
            event.setCanceled(true);
        }
    }

    /** 植物側の Mob を殴ったら、しばらく外套が効かない。 */
    @SubscribeEvent
    public static void onHurt(LivingIncomingDamageEvent event) {
        if (event.getSource().getEntity() instanceof Player player && MobGroup.of(event.getEntity()) != null
                && ExtraArmor.worn(player, ExtraArmor.Part.BODY).getItem() instanceof SporeCloakItem) {
            player.getPersistentData().putLong(AGGRO_KEY, player.level().getGameTime() + AGGRO_TICKS);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.overgrown_vegetation.extra_armor.body").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.overgrown_vegetation.spore_cloak").withStyle(ChatFormatting.DARK_GRAY));
    }
}
