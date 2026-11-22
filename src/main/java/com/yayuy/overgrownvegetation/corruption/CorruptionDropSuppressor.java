package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

/**
 * 汚染の処理が<b>その場で</b>引き起こした破壊のドロップを消す(要望: 汚染でアイテムをばらまかない)。
 *
 * <p>{@link net.minecraft.world.level.block.Block#UPDATE_SUPPRESS_DROPS} を立てても、
 * バニラの {@code Level#markAndNotifyBlock} は形状更新の連鎖へ渡すフラグからこのビットを落とす
 * ({@code flags & -34})。そのため汚染の処理が隣を {@code UPDATE_ALL} で書き換えると、連鎖の先で支えを失った
 * バニラの植物は {@code Block#updateOrDestroy} → {@code Level#destroyBlock(pos, true)} でアイテムを落とす。
 * フラグでは届かないこの連鎖を、{@link #enter}〜{@link #exit} の間だけ {@link BlockDropsEvent} を
 * 取り消すことで止める。
 *
 * <p>{@link CorruptionRegistry#placeCorrupted} 自身は {@code UPDATE_KNOWN_SHAPE} で形状更新の連鎖を
 * 打ち切るので、その中でこの範囲が効くのは近隣通知({@code neighborChanged})で壊れるもの
 * (支えを失ったレール・レッドストーンなど)だけ。過繁茂した地面の上に取り残された植物が<b>あとで</b>
 * 壊れる分は、それを引き起こす処理(汚染直後の装飾植物の設置など)を呼び出し側で包まないと止まらない。
 *
 * <p>変わるのは<b>アイテムと経験値が出ないことだけ</b>。壊れる・壊れないの判定、近隣更新、
 * 破壊パーティクルと音({@code destroyBlock} の {@code levelEvent 2001})はそのまま。
 * ルートの抽選と落下位置の乱数はイベントより前に引かれるので、乱数を引く順番も変わらない。
 * 取り消すと {@code spawnAfterBreak}(虫食いブロックのシルバーフィッシュなど)も呼ばれない。
 *
 * <p>範囲は<b>入れ子の深さ</b>で数え、スレッドごとに持つ({@link ThreadLocal})。ブロックの更新は
 * サーバースレッドで同期的に走るので、範囲の中で始まった連鎖は範囲が閉じる前に同じスレッドで終わる。
 * 予約 tick で 1 tick 後に壊れるもの(サトウキビ・サボテン・昆布など)は範囲の外なので対象外。
 *
 * <p><b>必ず try/finally で対にする</b>(閉じ忘れるとそのスレッドのドロップがすべて消える)。
 * 手軽に包むなら {@link #run}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class CorruptionDropSuppressor {

    /** 今の範囲の深さ(スレッドごと)。長さ 1 の配列にして、出入りのたびに箱詰めしない。 */
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    /** 範囲に入る。必ず {@link #exit} と try/finally で対にすること。 */
    public static void enter() {
        DEPTH.get()[0]++;
    }

    /** 範囲を出る。 */
    public static void exit() {
        int[] depth = DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
    }

    /** {@code action} を範囲の中で走らせる。 */
    public static void run(Runnable action) {
        enter();
        try {
            action.run();
        } finally {
            exit();
        }
    }

    /**
     * 2026-11-17 軽量化(ユーザー決定「汚染では落とさない」): 予算の番人に配線した周期処理(胞子の芽・菌糸の染み下り・湿地の木の片付け・
     * 苗木の追いつき成長など、{@link com.yayuy.overgrownvegetation.perf.PerfJob} の全部)の間も範囲の中とみなす。
     * 前線に落とし物が数百個たまり、サーバーの 1 tick の約 6〜8% を使っていた(F7 の前線・本物の写しで 223〜520 個)。
     * {@link com.yayuy.overgrownvegetation.perf.TickBudget#begin}〜{@code end} が立て・下ろす(周期処理は入れ子にしない決まり)。
     * 閉じ忘れても次の tick の頭で下ろす({@code TickBudget#onServerTickPre})。
     */
    private static final ThreadLocal<boolean[]> IN_JOB = ThreadLocal.withInitial(() -> new boolean[1]);

    /** 周期処理の範囲を立てる・下ろす({@link com.yayuy.overgrownvegetation.perf.TickBudget} から)。 */
    public static void setInPeriodicJob(boolean in) {
        IN_JOB.get()[0] = in;
    }

    /** このスレッドが今、範囲の中にいるか。 */
    public static boolean isActive() {
        return DEPTH.get()[0] > 0 || IN_JOB.get()[0];
    }

    @SubscribeEvent
    public static void onBlockDrops(BlockDropsEvent event) {
        if (isActive()) {
            event.setCanceled(true);
        }
    }

    private CorruptionDropSuppressor() {
    }
}
