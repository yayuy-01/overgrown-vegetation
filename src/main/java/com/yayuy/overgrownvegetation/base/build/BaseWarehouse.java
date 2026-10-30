package com.yayuy.overgrownvegetation.base.build;

import com.yayuy.overgrownvegetation.ally.AllyItems;
import com.yayuy.overgrownvegetation.base.AmmoCrateBlockEntity;
import com.yayuy.overgrownvegetation.block.DecontaminatorBlockEntity;
import com.yayuy.overgrownvegetation.data.DefenseTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.spore.SporeFilters;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 基地の倉庫(順 2 の着手順 4 = 整備、2026-10-04)。<b>ユーザー指定(10-02)</b>: 荷捌き場の倉庫の中に、貯蓄を使って機関砲弾・補修材・新しいフィルターなどが
 * 補充され、工兵はそれを使う / 使用済みのフィルターは除染機で使い回す / 使用済みと未使用のフィルターは 1 基地に除去装置 × 2 の分だけで、それ以上は増えない。
 *
 * <p>倉庫 = 計画の倉庫テント・鉄骨の倉庫(物流区。正面の搬入口を荷捌き場とみなす)。無い基地は弾薬庫の弾薬箱を倉庫の代わりにする(Claude 仮決め)。
 * 品は本物のアイテムとして箱に入る(弾は弾薬箱、ほかはチェストと樽)。プレイヤーも取れる —— 取った分は次の買い足しで貯蓄から買い直す
 * (信頼の段 = 着手順 5 で扱いを決め直す)。
 *
 * <p>フィルターの数え方(Claude の読み): 倉庫の中(除染機の中と、工兵が運んでいる途中を含む)の使用済み + 未使用が除去装置 × 2 まで。
 * 除去装置に付いている 1 枚は数えない。洗えば減らないので、新しく買うのは足りない分だけ。
 */
public final class BaseWarehouse {

    /** 倉庫に置く品。 */
    public enum Supply {
        ROUNDS("rounds"),
        REDSTONE("redstone"),
        FILTER("filter"),
        REPAIR("repair");

        /** {@code defense.json} の {@code upkeepCosts} の名前。 */
        public final String costKey;

        Supply(String costKey) {
            this.costKey = costKey;
        }

        public Item item() {
            return switch (this) {
                case ROUNDS -> ModItems.AUTOCANNON_ROUND.get();
                case REDSTONE -> Items.REDSTONE;
                case FILTER -> ModItems.SPORE_FILTER.get();
                case REPAIR -> AllyItems.REPAIR_MATERIAL.get();
            };
        }

        public boolean is(ItemStack stack) {
            return !stack.isEmpty() && stack.is(item());
        }

        /** 値段の 1 回分の数(弾は 16 発 = 作業台 1 回分、ほかは 1 個)。 */
        public int lot() {
            return this == ROUNDS ? 16 : 1;
        }
    }

    /** 倉庫 1 棟: 弾薬箱・棚(樽とチェスト)・除染機の場所と、工兵が立つ所(除染機の前・無ければ入口の前)。 */
    public record Store(List<BlockPos> crates, List<BlockPos> shelves, List<BlockPos> decontaminators, BlockPos stand) {

        public List<BlockPos> all() {
            List<BlockPos> out = new ArrayList<>(crates.size() + shelves.size() + decontaminators.size());
            out.addAll(crates);
            out.addAll(shelves);
            out.addAll(decontaminators);
            return out;
        }
    }

    // ---------- 読む ----------

    /** 倉庫の箱が全部読み込まれているか(読み込まれていないチャンクのブロックを読むと読み込みが起きる)。 */
    public static boolean loaded(ServerLevel level, Store store) {
        for (BlockPos p : store.all()) {
            if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static Container container(ServerLevel level, BlockPos p) {
        if (level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(p);
        return be instanceof Container c ? c : null;
    }

    /** 倉庫(箱・棚・除染機)の中の {@code supply} の数。 */
    public static int count(ServerLevel level, Store store, Supply supply) {
        int n = 0;
        for (BlockPos p : store.all()) {
            Container c = container(level, p);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (supply.is(s)) {
                    n += s.getCount();
                }
            }
        }
        return n;
    }

    /** 基地の倉庫ぜんぶの {@code supply} の数。 */
    public static int count(ServerLevel level, List<Store> stores, Supply supply) {
        int n = 0;
        for (Store s : stores) {
            n += count(level, s, supply);
        }
        return n;
    }

    /** 倉庫の中のまだ使えるフィルター(目詰まりしていない)の数。除染機の中で洗っている途中のものは数えない。 */
    public static int usableFilters(ServerLevel level, Store store) {
        int n = 0;
        for (BlockPos p : store.all()) {
            if (store.decontaminators().contains(p)) {
                continue;
            }
            Container c = container(level, p);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (SporeFilters.isUsable(c.getItem(i))) {
                    n++;
                }
            }
        }
        return n;
    }

    // ---------- 出し入れ ----------

    /**
     * 倉庫から {@code supply} を {@code max} 個まで取り出す(取れた分のアイテム)。フィルターはまだ使えるもの(目詰まりしていない、すり減りの少ない順)だけで、
     * 除染機の中のものは取らない(洗い終えたものは見回りで棚へ戻す)。
     */
    public static List<ItemStack> take(ServerLevel level, Store store, Supply supply, int max) {
        List<ItemStack> out = new ArrayList<>();
        int need = max;
        if (supply == Supply.FILTER) {
            while (need > 0) {
                Container best = null;
                int bestSlot = -1;
                int bestDamage = Integer.MAX_VALUE;
                for (BlockPos p : store.crates()) {
                    // 弾薬箱にフィルターは入れないが、プレイヤーが入れたものは使う
                    Container c = container(level, p);
                    int[] r = bestFilter(c, bestDamage);
                    if (r != null) {
                        best = c;
                        bestSlot = r[0];
                        bestDamage = r[1];
                    }
                }
                for (BlockPos p : store.shelves()) {
                    Container c = container(level, p);
                    int[] r = bestFilter(c, bestDamage);
                    if (r != null) {
                        best = c;
                        bestSlot = r[0];
                        bestDamage = r[1];
                    }
                }
                if (best == null) {
                    break;
                }
                out.add(best.removeItem(bestSlot, 1));
                best.setChanged();
                need--;
            }
            return out;
        }
        List<BlockPos> order = new ArrayList<>(supply == Supply.ROUNDS ? store.crates() : store.shelves());
        order.addAll(supply == Supply.ROUNDS ? store.shelves() : store.crates());
        for (BlockPos p : order) {
            Container c = container(level, p);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize() && need > 0; i++) {
                ItemStack s = c.getItem(i);
                if (!supply.is(s)) {
                    continue;
                }
                ItemStack got = c.removeItem(i, Math.min(need, s.getCount()));
                need -= got.getCount();
                merge(out, got);
                c.setChanged();
            }
            if (need <= 0) {
                break;
            }
        }
        return out;
    }

    /** 容器 {@code c} の中で、すり減りが {@code than} より少ない使えるフィルター {スロット, すり減り}(無ければ null)。 */
    @Nullable
    private static int[] bestFilter(@Nullable Container c, int than) {
        if (c == null) {
            return null;
        }
        int[] best = null;
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (SporeFilters.isUsable(s) && s.getDamageValue() < than && (best == null || s.getDamageValue() < best[1])) {
                best = new int[]{i, s.getDamageValue()};
            }
        }
        return best;
    }

    private static void merge(List<ItemStack> out, ItemStack got) {
        if (got.isEmpty()) {
            return;
        }
        for (ItemStack s : out) {
            if (ItemStack.isSameItemSameComponents(s, got) && s.getCount() + got.getCount() <= s.getMaxStackSize()) {
                s.grow(got.getCount());
                return;
            }
        }
        out.add(got);
    }

    /**
     * {@code stack} を倉庫へ入れる(入らなかった残りを返す)。弾は弾薬箱から、ほかは棚から。目詰まりしたフィルターは除染機に空きがあれば除染機へ。
     */
    public static ItemStack put(ServerLevel level, Store store, ItemStack stack) {
        ItemStack rest = stack.copy();
        if (SporeFilters.is(rest) && SporeFilters.needsWashing(rest)) {
            for (BlockPos p : store.decontaminators()) {
                rest = insert(container(level, p), rest);
                if (rest.isEmpty()) {
                    return rest;
                }
            }
        }
        List<BlockPos> order = new ArrayList<>(Supply.ROUNDS.is(rest) ? store.crates() : store.shelves());
        order.addAll(Supply.ROUNDS.is(rest) ? store.shelves() : store.crates());
        for (BlockPos p : order) {
            rest = insert(container(level, p), rest);
            if (rest.isEmpty()) {
                break;
            }
        }
        return rest;
    }

    /** 容器 {@code c} へ入るだけ入れる(残りを返す)。容器の決まり({@link Container#canPlaceItem})と 1 スロットの上限を守る。 */
    private static ItemStack insert(@Nullable Container c, ItemStack stack) {
        if (c == null || stack.isEmpty()) {
            return stack;
        }
        int limit = Math.min(c.getMaxStackSize(), stack.getMaxStackSize());
        for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
            for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
                if (!c.canPlaceItem(i, stack)) {
                    continue;
                }
                ItemStack s = c.getItem(i);
                if (pass == 0 && !s.isEmpty() && ItemStack.isSameItemSameComponents(s, stack) && s.getCount() < limit) {
                    int move = Math.min(limit - s.getCount(), stack.getCount());
                    s.grow(move);
                    stack.shrink(move);
                    c.setChanged();
                } else if (pass == 1 && s.isEmpty()) {
                    int move = Math.min(limit, stack.getCount());
                    c.setItem(i, stack.split(move));
                    c.setChanged();
                }
            }
        }
        return stack;
    }

    /** 倉庫に {@code stack} のうち何個入るか(数えるだけ。除染機・容器の決まりは入れるときと同じ)。 */
    static int room(ServerLevel level, Store store, ItemStack stack) {
        int room = 0;
        List<BlockPos> order = new ArrayList<>(Supply.ROUNDS.is(stack) ? store.crates() : store.shelves());
        order.addAll(Supply.ROUNDS.is(stack) ? store.shelves() : store.crates());
        for (BlockPos p : order) {
            Container c = container(level, p);
            if (c == null) {
                continue;
            }
            int limit = Math.min(c.getMaxStackSize(), stack.getMaxStackSize());
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (!c.canPlaceItem(i, stack)) {
                    continue;
                }
                ItemStack s = c.getItem(i);
                if (s.isEmpty()) {
                    room += limit;
                } else if (ItemStack.isSameItemSameComponents(s, stack)) {
                    room += Math.max(0, limit - s.getCount());
                }
            }
        }
        return room;
    }

    // ---------- 買い足し ----------

    /** 倉庫の目安(これまで買い足す)。{@code turrets}・{@code purifiers} は計画の設備の数。 */
    public static int target(Supply supply, int level, int turrets, int purifiers) {
        DefenseTable t = DesignData.defense();
        return switch (supply) {
            case ROUNDS -> turrets * t.baseUpkeepStock("roundsPerTurret");
            case REDSTONE -> purifiers * t.baseUpkeepStock("redstonePerPurifier");
            case FILTER -> purifiers * t.baseUpkeepStock("filtersPerPurifier");
            case REPAIR -> level * t.baseUpkeepStock("repairPerLevel");
        };
    }

    /**
     * 倉庫の品を目安まで貯蓄で買い足す(貯蓄が足りる分・箱に入る分だけ。値段は 1 回分 = 弾 16 発・ほかは 1 個ごと)。
     * フィルターの数には工兵が運んでいる途中の {@code carriedFilters} 枚も入れる。使った貯蓄。
     */
    static int restock(ServerLevel level, BaseSites data, BaseSite site, List<Store> stores, int turrets, int purifiers, int carriedFilters) {
        if (stores.isEmpty()) {
            return 0;
        }
        DefenseTable t = DesignData.defense();
        int spent = 0;
        for (Supply supply : Supply.values()) {
            int have = count(level, stores, supply) + (supply == Supply.FILTER ? carriedFilters : 0);
            int want = target(supply, site.level(), turrets, purifiers) - have;
            int price = t.baseUpkeepCost(supply.costKey);
            int lot = supply.lot();
            int stackMax = new ItemStack(supply.item()).getMaxStackSize();
            while (want > 0) {
                int lots = (want + lot - 1) / lot;
                if (price > 0) {
                    lots = Math.min(lots, site.stock / price);
                }
                int n = Math.min(lots * lot, stackMax);
                if (n <= 0) {
                    break; // 貯蓄が足りない
                }
                lots = (n + lot - 1) / lot;
                ItemStack stack = new ItemStack(supply.item(), n);
                Store into = null;
                for (Store s : stores) {
                    if (room(level, s, stack) >= n) {
                        into = s;
                        break;
                    }
                }
                if (into == null) {
                    break; // 箱がいっぱい
                }
                ItemStack rest = put(level, into, stack);
                if (!rest.isEmpty()) {
                    break;
                }
                site.stock -= lots * price;
                spent += lots * price;
                want -= n;
            }
        }
        if (spent > 0) {
            data.changed();
        }
        return spent;
    }

    /**
     * 倉庫の中の出し入れ(買い足しと同じ見回りで): 除染機で洗い終えたフィルターを棚へ戻し、棚の使用済み(すり減ったもの)を除染機の空きへ入れる。
     */
    static void housekeep(ServerLevel level, Store store) {
        for (BlockPos p : store.decontaminators()) {
            Container dc = container(level, p);
            if (!(dc instanceof DecontaminatorBlockEntity decon)) {
                continue;
            }
            decon.setBaseSupplied(true); // 基地の除染機は電池なしで動く(2026-10-24、除染機の電池化。機関砲台と同じ)
            for (int i = 0; i < DecontaminatorBlockEntity.SLOT_COUNT; i++) {
                ItemStack s = dc.getItem(i);
                if (SporeFilters.is(s) && !SporeFilters.needsWashing(s)) {
                    ItemStack rest = shelve(level, store, s.copy());
                    if (rest.isEmpty()) {
                        dc.setItem(i, ItemStack.EMPTY);
                        dc.setChanged();
                    }
                }
            }
            for (int i = 0; i < DecontaminatorBlockEntity.SLOT_COUNT; i++) { // 電池の枠(5)には入れない
                if (!dc.getItem(i).isEmpty()) {
                    continue;
                }
                ItemStack worn = takeWorn(level, store);
                if (worn.isEmpty()) {
                    break;
                }
                dc.setItem(i, worn);
                dc.setChanged();
            }
        }
    }

    private static ItemStack shelve(ServerLevel level, Store store, ItemStack stack) {
        ItemStack rest = stack;
        for (BlockPos p : store.shelves()) {
            rest = insert(container(level, p), rest);
            if (rest.isEmpty()) {
                break;
            }
        }
        return rest;
    }

    /** 棚から、すり減ったフィルター(目詰まりしたものから)を 1 枚取る。無ければ空。 */
    private static ItemStack takeWorn(ServerLevel level, Store store) {
        Container best = null;
        int bestSlot = -1;
        int bestDamage = 0;
        for (BlockPos p : store.shelves()) {
            Container c = container(level, p);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (SporeFilters.is(s) && SporeFilters.needsWashing(s) && s.getDamageValue() > bestDamage) {
                    best = c;
                    bestSlot = i;
                    bestDamage = s.getDamageValue();
                }
            }
        }
        if (best == null) {
            return ItemStack.EMPTY;
        }
        ItemStack out = best.removeItem(bestSlot, 1);
        best.setChanged();
        return out;
    }

    /** 倉庫の弾薬箱か(ほかの箱はチェストと樽)。 */
    static boolean isCrate(BlockEntity be) {
        return be instanceof AmmoCrateBlockEntity;
    }

    /** {@code supply} を倉庫から取れるか(1 個でも)。 */
    static boolean has(ServerLevel level, Store store, Supply supply) {
        return supply == Supply.FILTER ? usableFilters(level, store) > 0 : count(level, store, supply) > 0;
    }

    /** 倉庫の中のフィルターの数(使用済み + 未使用。除染機の中も)。 */
    static int filters(ServerLevel level, List<Store> stores) {
        return count(level, stores, Supply.FILTER);
    }

    /** {@code p} の容器を空にして、中身を返す(汚染された樽・チェストを元に戻すときに中身を移す)。 */
    static List<ItemStack> drain(ServerLevel level, BlockPos p) {
        List<ItemStack> out = new ArrayList<>();
        Container c = container(level, p);
        if (c == null) {
            return out;
        }
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty()) {
                out.add(s.copy());
                c.setItem(i, ItemStack.EMPTY);
            }
        }
        c.setChanged();
        return out;
    }

    /** {@code p} の容器へ {@code items} を入れる(入らなかった分は捨てずに足元へ落とす)。 */
    static void fill(ServerLevel level, BlockPos p, List<ItemStack> items) {
        Container c = container(level, p);
        for (ItemStack s : items) {
            ItemStack rest = insert(c, s);
            if (!rest.isEmpty()) {
                net.minecraft.world.Containers.dropItemStack(level, p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5, rest);
            }
        }
    }

    /** 判定用: {@code stack} が {@code supply} か。 */
    static Predicate<ItemStack> of(Supply supply) {
        return supply::is;
    }

    private BaseWarehouse() {
    }
}
