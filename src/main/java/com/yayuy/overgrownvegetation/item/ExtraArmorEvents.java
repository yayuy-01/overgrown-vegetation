package com.yayuy.overgrownvegetation.item;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 追加防具({@link ExtraArmor})の死んだときの扱い。アタッチメントは死をまたいで写さない設定なので:
 * <ul>
 *     <li>keepInventory が切れていれば、持ち物と一緒にその場へ落とす({@link LivingDropsEvent})。</li>
 *     <li>keepInventory なら、生き返った体へ写す({@link PlayerEvent.Clone})。</li>
 * </ul>
 * 次元の移動ではアタッチメントがそのまま引き継がれる。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class ExtraArmorEvents {

    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Player player) || player.level().isClientSide()
                || player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            return;
        }
        ExtraArmor armor = ExtraArmor.of(player);
        for (int i = 0; i < ExtraArmor.SIZE; i++) {
            ItemStack stack = armor.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemEntity drop = new ItemEntity(player.level(), player.getX(), player.getEyeY() - 0.3D, player.getZ(), stack);
            drop.setDefaultPickUpDelay();
            event.getDrops().add(drop);
            armor.set(i, ItemStack.EMPTY);
        }
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath() || !event.getEntity().level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            return;
        }
        ExtraArmor from = ExtraArmor.of(event.getOriginal());
        ExtraArmor to = ExtraArmor.of(event.getEntity());
        for (int i = 0; i < ExtraArmor.SIZE; i++) {
            to.set(i, from.get(i).copy());
        }
    }

    /** 1 秒ごとに防御力の修飾を付け直す(ログイン・リスポーン・次元移動には「入れた」が無いため)。 */
    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide() && player.tickCount % 20 == 0) {
            ExtraArmor.syncAttributes(player);
        }
    }

    /** 誰かがプレイヤーを追跡し始めたら、そのプレイヤーの追加防具を見た目のために送る。 */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof Player target
                && event.getEntity() instanceof net.minecraft.server.level.ServerPlayer watcher) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(watcher,
                    new com.yayuy.overgrownvegetation.network.ExtraArmorPayload(target.getId(), ExtraArmor.of(target).copyList()));
        }
    }

    /**
     * 防御力のある追加防具(布鎧など)は、ふつうの防具と同じく防具の効く一撃を受けるとすり減る
     * (バニラと同じ「受けたダメージ ÷ 4、最低 1」。2026-10-15 の仮決め)。呼吸覆いのような防御 0 の品は減らない。
     */
    @SubscribeEvent
    public static void onDamaged(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof Player player) || !(player.level() instanceof net.minecraft.server.level.ServerLevel level)
                || event.getSource().is(net.minecraft.tags.DamageTypeTags.BYPASSES_ARMOR) || event.getOriginalDamage() <= 0.0F) {
            return;
        }
        int wear = Math.max(1, (int) (event.getOriginalDamage() / 4.0F));
        ExtraArmor armor = ExtraArmor.of(player);
        for (int i = 0; i < ExtraArmor.SIZE; i++) {
            ItemStack stack = armor.get(i);
            if (stack.getItem() instanceof ExtraArmorItem e && e.extraArmorDefense() > 0 && stack.isDamageableItem()) {
                final int index = i;
                stack.hurtAndBreak(wear, level, player, item -> level.playSound(null, player.getX(), player.getY(), player.getZ(),
                        net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_LEATHER.value(), net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.6F));
                if (stack.isEmpty()) {
                    armor.set(index, ItemStack.EMPTY);
                    ExtraArmor.syncAttributes(player);
                    ExtraArmor.broadcast(player);
                }
            }
        }
    }

    private ExtraArmorEvents() {
    }
}
