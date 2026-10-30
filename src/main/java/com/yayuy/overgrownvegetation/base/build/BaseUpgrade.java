package com.yayuy.overgrownvegetation.base.build;

import com.yayuy.overgrownvegetation.base.BaseBlocks;
import com.yayuy.overgrownvegetation.base.plan.BasePlan;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * 基地のレベルを上げる費用と手続き(2026-10-01)。ユーザー指定「プレイヤーがレベルを上げて拡張できる」「レベルは 1〜5」。
 * 費用は仮決め(貯蓄の仕組みができたら貯蓄から払う形に変える)。管理端末をしゃがんで右クリックすると、持ち物から払って工事が始まる。
 */
public final class BaseUpgrade {

    /** レベル {@code to} へ上げる費用(建材と鉄。仮決め)。 */
    public static List<ItemStack> cost(int to) {
        return switch (to) {
            case 2 -> List.of(new ItemStack(BaseBlocks.REINFORCED_CONCRETE.get(), 64), new ItemStack(Items.IRON_INGOT, 32));
            case 3 -> List.of(new ItemStack(BaseBlocks.REINFORCED_CONCRETE.get(), 128), new ItemStack(Items.IRON_INGOT, 64),
                    new ItemStack(BaseBlocks.ASPHALT.get(), 64));
            case 4 -> List.of(new ItemStack(BaseBlocks.REINFORCED_CONCRETE.get(), 256), new ItemStack(Items.IRON_BLOCK, 16),
                    new ItemStack(BaseBlocks.ASPHALT.get(), 128));
            case 5 -> List.of(new ItemStack(BaseBlocks.REINFORCED_CONCRETE.get(), 512), new ItemStack(Items.IRON_BLOCK, 32),
                    new ItemStack(BaseBlocks.ASPHALT.get(), 256));
            default -> List.of();
        };
    }

    /** 費用の一覧の文(「鉄筋コンクリート ×64、鉄インゴット ×32」)。 */
    public static MutableComponent describe(List<ItemStack> cost) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < cost.size(); i++) {
            if (i > 0) {
                out.append("、");
            }
            out.append(cost.get(i).getHoverName()).append(" ×" + cost.get(i).getCount());
        }
        return out;
    }

    /** 持ち物で払えるか(クリエイティブは常に払える)。 */
    public static boolean canPay(Player player, List<ItemStack> cost) {
        if (player.getAbilities().instabuild) {
            return true;
        }
        for (ItemStack need : cost) {
            if (count(player.getInventory(), need.getItem()) < need.getCount()) {
                return false;
            }
        }
        return true;
    }

    /** 払う(先に {@link #canPay} で確かめておく)。 */
    public static void pay(Player player, List<ItemStack> cost) {
        if (player.getAbilities().instabuild) {
            return;
        }
        Inventory inv = player.getInventory();
        for (ItemStack need : cost) {
            int left = need.getCount();
            for (int i = 0; i < inv.getContainerSize() && left > 0; i++) {
                ItemStack s = inv.getItem(i);
                if (s.is(need.getItem())) {
                    int take = Math.min(left, s.getCount());
                    s.shrink(take);
                    left -= take;
                }
            }
        }
        inv.setChanged();
    }

    private static int count(Inventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    public static boolean isMax(int level) {
        return level >= BasePlan.MAX_LEVEL;
    }

    private BaseUpgrade() {
    }
}
