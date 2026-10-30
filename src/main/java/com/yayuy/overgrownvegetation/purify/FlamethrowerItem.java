package com.yayuy.overgrownvegetation.purify;

import com.yayuy.overgrownvegetation.difficulty.ExperimentalFeatures;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 火炎放射器(順 2 の着手順 9、2026-10-08)。<b>ユーザー指定</b>: 浄化はプレイヤーの火炎放射器と焼けた荒地(0.2.0 の範囲、10-08)・
 * 研究で解放する(研究ノード「浄化」がレシピを開く。フェーズの条件は付けない、10-08)。
 *
 * <p>Claude 仮決め: 右クリックを押している間、{@value #SPRAY_INTERVAL} tick ごとに前へ炎を吹く(届くのは {@value #RANGE} ブロック、
 * 中身は {@link Burning#spray})。燃料は持ち物の石炭か木炭を自動で使い、1 個で {@value #FUEL_PER_ITEM} tick(10 秒)。
 * 残りの燃料はアイテムに覚えさせ、{@value #FUEL_STEP} tick ごとに減らす(毎 tick 書き換えると持ち物の同期が毎 tick 飛ぶ)。
 * 押し始めに燃料が無ければ断る。クリエイティブは燃料が要らない。壊れない。この MOD で初めての「長押しで使う」道具。
 */
public class FlamethrowerItem extends Item {

    /** 炎が届く距離(ブロック)。 */
    public static final double RANGE = 8.0D;
    /** 炎を吹く間隔(tick)。 */
    public static final int SPRAY_INTERVAL = 2;
    /** 石炭・木炭 1 個で吹ける長さ(tick)。 */
    public static final int FUEL_PER_ITEM = 200;
    /** 残りの燃料を減らす間隔(tick)。 */
    public static final int FUEL_STEP = 10;
    /** 長押しの上限(実質無制限)。 */
    private static final int USE_DURATION = 72000;

    public FlamethrowerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!ExperimentalFeatures.enabled(level)) {
            // 実験的要素がオフのワールドでは使えない(未完成の同盟軍の持ち物。2026-10-09 ユーザー指定「まだ触らせたくない」)
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("message.overgrown_vegetation.experimental_off")
                        .withStyle(ChatFormatting.RED), true);
            }
            return InteractionResultHolder.fail(stack);
        }
        if (!player.getAbilities().instabuild && fuel(stack) <= 0 && findFuel(player) < 0) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("message.overgrown_vegetation.flamethrower.no_fuel")
                        .withStyle(ChatFormatting.RED), true);
            }
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return USE_DURATION;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.NONE;
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remainingUseDuration) {
        if (!(level instanceof ServerLevel serverLevel) || !(entity instanceof Player player)) {
            return;
        }
        int used = getUseDuration(stack, entity) - remainingUseDuration;
        if (used % FUEL_STEP == 0 && !player.getAbilities().instabuild && !drawFuel(player, stack, FUEL_STEP)) {
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.flamethrower.no_fuel")
                    .withStyle(ChatFormatting.RED), true);
            player.stopUsingItem();
            return;
        }
        if (used % SPRAY_INTERVAL == 0) {
            Burning.spray(serverLevel, player, RANGE, true);
        }
        if (used % 8 == 0) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FIRE_AMBIENT, SoundSource.PLAYERS,
                    1.2F, 0.8F + level.random.nextFloat() * 0.3F);
        }
        if (used == 0) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS,
                    0.6F, 0.8F);
        }
    }

    // ------------------------------------------------------------------ 燃料

    /** 残りの燃料(tick)。 */
    public static int fuel(ItemStack stack) {
        return Math.max(0, stack.getOrDefault(PurifyRegistry.FLAMETHROWER_FUEL.get(), 0));
    }

    /**
     * 燃料を {@code step} tick ぶん使う。残りが無ければ持ち物の石炭か木炭を 1 個使って {@value #FUEL_PER_ITEM} tick 足す。
     *
     * @return 使えたら true(燃料が尽きて足せなければ false)
     */
    public static boolean drawFuel(Player player, ItemStack stack, int step) {
        int fuel = fuel(stack);
        if (fuel <= 0) {
            int slot = findFuel(player);
            if (slot < 0) {
                return false;
            }
            player.getInventory().getItem(slot).shrink(1);
            fuel += FUEL_PER_ITEM;
        }
        stack.set(PurifyRegistry.FLAMETHROWER_FUEL.get(), Math.max(0, fuel - step));
        return true;
    }

    /** 持ち物の石炭・木炭の場所(無ければ -1)。 */
    public static int findFuel(Player player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(ItemTags.COALS)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.overgrown_vegetation.flamethrower.desc").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.overgrown_vegetation.flamethrower.fuel", (fuel(stack) + 19) / 20)
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
