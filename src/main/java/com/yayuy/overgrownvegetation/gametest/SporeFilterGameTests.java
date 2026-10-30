package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.SporeDeviceBlock;
import com.yayuy.overgrownvegetation.block.SporeDeviceBlockEntity;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.EffectTable;
import com.yayuy.overgrownvegetation.block.DecontaminatorBlockEntity;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.spore.SporeFilters;
import com.yayuy.overgrownvegetation.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * <b>胞子フィルター</b>(胞子除去装置の消耗品)のテスト
 * (ユーザー指定 2026-09-21「浄化器のフィルターを消耗品に」)。
 *
 * <p>ここで守りたいのは 2 つ。
 * <ul>
 *     <li><b>フィルターが無ければ動かない</b> —— これが崩れると「置けば永久に効く設備」に戻り、
 *     維持費という設計そのものが消える。</li>
 *     <li><b>燃料を食わない</b> —— 止まっているのにレッドストーンだけ減ると、
 *     プレイヤーからは「何かに吸われている」としか見えない。</li>
 * </ul>
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SporeFilterGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(SporeFilterGameTests.class);
        }

        private Registration() {
        }
    }

    private static final BlockPos AT = new BlockPos(2, 1, 2);

    /** フィルターが無いあいだは動かず、電池も減らない。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void purifierWithoutFilterStaysOff(GameTestHelper helper) {
        SporeDeviceBlockEntity device = place(helper);
        device.setItem(SporeDeviceBlockEntity.SLOT_BATTERY, com.yayuy.overgrownvegetation.item.BatteryItem.withCharge(com.yayuy.overgrownvegetation.item.BatteryItem.CAPACITY));
        helper.runAfterDelay(60L, () -> {
            if (device.isActive()) {
                helper.fail("フィルターが無いのに動いている");
                return;
            }
            if (!device.needsFilter()) {
                helper.fail("フィルター切れとして扱われていない");
                return;
            }
            int left = device.batteryCharge();
            if (left != com.yayuy.overgrownvegetation.item.BatteryItem.CAPACITY) {
                helper.fail("止まっているのに電池が減っている(残り " + left + "/" + com.yayuy.overgrownvegetation.item.BatteryItem.CAPACITY + ")");
                return;
            }
            if (helper.getBlockState(AT).getValue(SporeDeviceBlock.LIT)) {
                helper.fail("止まっているのに点いている");
                return;
            }
            helper.succeed();
        });
    }

    /** フィルターを挿せば動き出す。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 200)
    public static void purifierRunsOnceFiltered(GameTestHelper helper) {
        SporeDeviceBlockEntity device = place(helper);
        device.setItem(SporeDeviceBlockEntity.SLOT_BATTERY, com.yayuy.overgrownvegetation.item.BatteryItem.withCharge(com.yayuy.overgrownvegetation.item.BatteryItem.CAPACITY));
        device.setItem(SporeDeviceBlockEntity.SLOT_FILTER, new ItemStack(ModItems.SPORE_FILTER.get()));
        helper.runAfterDelay(60L, () -> {
            if (!device.isActive()) {
                helper.fail("フィルターを挿したのに動かない");
                return;
            }
            if (device.needsFilter()) {
                helper.fail("フィルターが見えていない");
                return;
            }
            helper.succeed();
        });
    }

    /**
     * <b>働いたぶんだけ減る</b>。胞子が 1 個も取れないきれいな空気でも、
     * サイクルぶんの目詰まりは進む —— ここが 0 だと、濃度 0 の場所では永久に保ってしまう。
     */
    @GameTest(template = "gametest_empty7")
    public static void filterWearsEvenInCleanAir(GameTestHelper helper) {
        EffectTable.DeviceSpec spec = DesignData.effects().purifier();
        if (!spec.requiresFilter()) {
            helper.fail("除去装置がフィルターを要求しない設定になっている");
            return;
        }
        if (spec.filterWearPerCycle() <= 0) {
            helper.fail("サイクルぶんの減りが 0 —— 濃度 0 の場所でフィルターが永久に保つ");
            return;
        }
        if (spec.filterWearPerSpore() <= 0) {
            helper.fail("胞子 1 個ぶんの減りが 0 —— 濃い場所でも同じ速さで保ってしまう");
            return;
        }
        // 満タンのフィルターが、濃度 100% でどれくらい保つか(目安の確認)
        int atFull = spec.filterWearPerCycle()
                + (int) Math.round(spec.sporesPerCycleAtFull()) * spec.filterWearPerSpore();
        int cycles = ModItems.FILTER_DURABILITY / Math.max(1, atFull);
        if (cycles < 10) {
            helper.fail("濃度 100% で " + cycles + " サイクルしか保たない(交換が忙しすぎる)");
            return;
        }
        helper.succeed();
    }

    /**
     * <b>使い切っても壊れない</b>(ユーザー指定 2026-09-21「除染装置で再使用できるように」)。
     * 消してしまうと洗いようがないので、耐久は「残り 1」で止める。
     */
    @GameTest(template = "gametest_empty7")
    public static void spentFilterIsNotDestroyed(GameTestHelper helper) {
        ItemStack filter = new ItemStack(ModItems.SPORE_FILTER.get());
        SporeFilters.wear(filter, ModItems.FILTER_DURABILITY * 4);   // 使い切るより多く働かせる
        if (filter.isEmpty()) {
            helper.fail("使い切ったフィルターが消えた(洗えなくなる)");
            return;
        }
        if (!SporeFilters.isSpent(filter)) {
            helper.fail("目詰まりとして扱われていない");
            return;
        }
        if (SporeFilters.isUsable(filter)) {
            helper.fail("目詰まりしているのにまだ使える扱い");
            return;
        }
        if (!SporeFilters.needsWashing(filter)) {
            helper.fail("洗う余地が無い扱いになっている");
            return;
        }
        helper.succeed();
    }

    /** 除染装置に入れると洗い上がり、また使えるようになる。 */
    @GameTest(template = "gametest_empty7", timeoutTicks = 400)
    public static void decontaminatorWashesASpentFilter(GameTestHelper helper) {
        BlockPos at = new BlockPos(4, 1, 4);
        helper.setBlock(at, ModBlocks.DECONTAMINATOR.get().defaultBlockState());
        DecontaminatorBlockEntity washer = (DecontaminatorBlockEntity) helper.getBlockEntity(at);
        ItemStack filter = new ItemStack(ModItems.SPORE_FILTER.get());
        SporeFilters.wear(filter, ModItems.FILTER_DURABILITY * 4);
        if (!washer.canPlaceItem(0, filter)) {
            helper.fail("除染装置が目詰まりしたフィルターを受け付けない");
            return;
        }
        washer.setItem(0, filter);
        if (washer.isWorking()) {
            helper.fail("電池が無いのに動いている");
            return;
        }
        washer.setItem(DecontaminatorBlockEntity.SLOT_BATTERY,
                com.yayuy.overgrownvegetation.item.BatteryItem.withCharge(com.yayuy.overgrownvegetation.item.BatteryItem.CAPACITY));
        if (!washer.isWorking()) {
            helper.fail("フィルターを入れたのに動いていない");
            return;
        }
        // 洗い上がるまで待たず、1 サイクルぶん減ったことだけ見る(全部洗うと 4 分半かかる)
        int before = washer.getItem(0).getDamageValue();
        helper.runAfterDelay(200L, () -> {
            ItemStack now = washer.getItem(0);
            if (now.isEmpty()) {
                helper.fail("洗っている途中でフィルターが消えた");
                return;
            }
            if (now.getDamageValue() >= before) {
                helper.fail("洗っても目詰まりが減らない (" + before + " → " + now.getDamageValue() + ")");
                return;
            }
            helper.succeed();
        });
    }

    private static SporeDeviceBlockEntity place(GameTestHelper helper) {
        helper.setBlock(AT, ModBlocks.SPORE_PURIFIER.get().defaultBlockState());
        // 台帳(SporeDeviceData)からは外す。見るのは装置そのもの(動くか・燃料・フィルター)で、台帳に載ると効果半径 48 ブロック =
        // 隣のテスト場の汚染を確率で止め、片付けないのでバッチが終わっても効き続ける(2026-10-18、30 ブロック先の
        // CoveredBlockGameTests#lavaAndBasaltBecomeCoveredBasalt が実行の 4 割ほどで落ちていた)
        com.yayuy.overgrownvegetation.spore.SporeDeviceData.get(helper.getLevel()).remove(helper.absolutePos(AT));
        return (SporeDeviceBlockEntity) helper.getBlockEntity(AT);
    }

    private SporeFilterGameTests() {
    }
}
