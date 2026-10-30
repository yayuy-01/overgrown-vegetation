package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.item.BatteryItem;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 機械の電池(2026-10-16、㉔。ユーザー指定「機関砲台・ビームの盾・攻城砲は電池が無いと動かない(プレイヤーが置いた物)。同盟軍の基地の機械は電池なしで動く。
 * 電池は働いている間だけ減る」)。
 * <ul>
 *     <li>地脈電池を持って機械を右クリック → 電池を入れる。前に入っていた電池は(残りがあれば残りのまま、空なら空の電池で)手元に戻る。</li>
 *     <li>減り方(仮決め): 砲台は敵を狙っている間 1 tick に 1、盾は展開している間 1 tick に 1、攻城砲は 1 発に {@link #SHOT_COST}。満タンは {@link BatteryItem#CAPACITY}(2026-10-22 から 1 時間。地脈電池にまとめたとき 10 分から延ばした)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class MachineBattery {

    /** 攻城砲 1 発の電気(1 分ぶん。仮決め)。 */
    public static final int SHOT_COST = 1200;

    /** 電池で動く機械の中身。 */
    public interface Holder {
        int batteryCharge();

        void setBatteryCharge(int charge);

        boolean hasBattery();

        void setHasBattery(boolean has);

        /** 電池を持って右クリックで入れ替えてよいか(電池の枠に電池以外が入っている機械は false。地脈炉の燃料枠のバケツなど)。 */
        default boolean canSwapBattery() {
            return true;
        }

        /** 同盟軍の基地が置いた機械(電池なしで動く)。 */
        default boolean baseSupplied() {
            return false;
        }

        void batteryChanged();
    }

    private MachineBattery() {
    }

    public static boolean hasEnergy(Holder h) {
        return h.baseSupplied() || h.batteryCharge() > 0;
    }

    public static boolean hasEnergy(Holder h, int amount) {
        return h.baseSupplied() || h.batteryCharge() >= amount;
    }

    /** {@code amount} だけ減らす(基地の機械は減らない)。 */
    public static void drain(Holder h, int amount) {
        if (h.baseSupplied() || h.batteryCharge() <= 0) {
            return;
        }
        h.setBatteryCharge(Math.max(0, h.batteryCharge() - amount));
        h.batteryChanged();
    }

    /** 電池を入れ替える。前の電池を返す(入っていなければ空)。 */
    public static ItemStack swap(Holder h, ItemStack battery) {
        ItemStack back = ItemStack.EMPTY;
        if (h.hasBattery()) {
            back = BatteryItem.stackFor(h.batteryCharge());
        }
        h.setBatteryCharge(BatteryItem.charge(battery));
        h.setHasBattery(true);
        h.batteryChanged();
        return back;
    }

    public static void save(Holder h, CompoundTag tag) {
        tag.putInt("BatteryCharge", h.batteryCharge());
        tag.putBoolean("HasBattery", h.hasBattery());
    }

    public static void load(Holder h, CompoundTag tag) {
        h.setBatteryCharge(tag.getInt("BatteryCharge"));
        h.setHasBattery(tag.getBoolean("HasBattery"));
    }

    /**
     * 電池の入った地脈灯から忌避灯を作ったら、その電池を忌避灯へ引き継ぐ(2026-10-24、転用の改造レシピ。作業台のレシピは部品を写せないので
     * 結果の枠の品に載せる。作業台の結果を計算した直後に {@code mixin/CraftingMenuMixin} から呼ぶ —— シフトクリックで作っても引き継ぐように)。
     *
     * @return 載せたら true
     */
    public static boolean carryLampBattery(net.minecraft.world.inventory.CraftingContainer grid, ItemStack result) {
        if (!result.is(ModItems.REPELLENT_LAMP_ITEM.get())) {
            return false;
        }
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack in = grid.getItem(i);
            Integer charge = in.is(ModItems.LEY_LAMP.get())
                    ? in.get(com.yayuy.overgrownvegetation.registry.ModDataComponents.BATTERY_CHARGE.get()) : null;
            if (charge != null) {
                result.set(com.yayuy.overgrownvegetation.registry.ModDataComponents.BATTERY_CHARGE.get(), charge);
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack stack = event.getItemStack();
        if (!stack.is(ModItems.BATTERY.get())) {
            return;
        }
        BlockEntity be = event.getLevel().getBlockEntity(event.getPos());
        if (!(be instanceof Holder holder)) {
            return;
        }
        if (!holder.canSwapBattery()) {
            return; // 電池以外が入っている枠は入れ替えない(その品が消えないように)。いつもの右クリック(窓を開く)へ
        }
        Player player = event.getEntity();
        if (!event.getLevel().isClientSide()) {
            ItemStack one = stack.copyWithCount(1);
            ItemStack back = swap(holder, one);
            stack.consume(1, player);
            if (!back.isEmpty() && !player.getInventory().add(back)) {
                player.drop(back, false);
            }
            event.getLevel().playSound(null, event.getPos(), SoundEvents.COPPER_PLACE, SoundSource.BLOCKS, 1.0F, 1.3F);
            player.displayClientMessage(Component.translatable("message.overgrown_vegetation.battery.inserted",
                    holder.batteryCharge() / 1200).withStyle(ChatFormatting.GRAY), true);
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide()));
    }
}
