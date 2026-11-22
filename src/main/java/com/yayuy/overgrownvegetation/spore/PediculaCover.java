package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.server.ServerStoppedEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * ペディキュラ({@link com.yayuy.overgrownvegetation.entity.PediculaEntity})が<b>いま上にいる</b>
 * チャンクの台帳(2026-09-23 旧版。2026-11-15 に作り直した PediculaEntity へ戻した: 能力の決定 11-14「真下の胞子濃度を一気に上げる(いるチャンクは 100%)」)。
 *
 * <p>ユーザー指定「いる時だけ 100% にしてほしい」。胞子濃度をチャンクへ積むのではなく、
 * 上にいる間だけ {@link SporeConcentration} が読み取り時に 100% として扱う
 * (読み取りの最後に、今の値とこの値の大きい方)。立ち去れば元の濃度へ戻る。
 *
 * <h2>しくみ</h2>
 * 個体が<b>毎 tick</b> 自分のいるチャンクへ「最後に見た時刻」と濃度を書き、読む側は
 * 直近 {@value #FRESH_TICKS} tick 以内に書かれたチャンクだけを「いる」とみなす。
 * 倒された・消えた・読み込みが外れて止まった個体は書かなくなるので、<b>後始末をしなくても
 * すぐに効果が切れる</b>(消える経路ごとに外す処理を書くと、どれかで必ず漏れる)。
 * 古い項目は {@value #SWEEP_INTERVAL} tick ごとにまとめて捨てる。
 *
 * <p>読む側は {@link SporeConcentration#reading}(地上の値だけ。地下の層には効かせない)と {@link SporeConcentration#percentAt(ServerLevel, net.minecraft.world.level.ChunkPos)}。
 * 閉じた部屋の中は外の値 × 部屋の倍率になるので、拠点の上を通ると部屋に胞子がゆっくりたまる(籠城が苦しくなる)。
 *
 * <p>胞子濃度はキャッシュされるが、この上乗せは<b>キャッシュの外</b>で足すので、
 * キャッシュを捨てなくても入った瞬間・出た瞬間に効く。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class PediculaCover {

    /** これより古い書き込みは「もういない」。1 tick の取りこぼし(処理順)に余裕を持たせて 2。 */
    private static final long FRESH_TICKS = 2L;
    /** 古い項目をまとめて捨てる間隔(tick)。 */
    private static final long SWEEP_INTERVAL = 200L;

    /** チャンク 1 つぶんの記録。 */
    private static final class Mark {
        long gameTime;
        double percent;
    }

    private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Mark>> MARKS = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long> LAST_SWEEP = new HashMap<>();

    /**
     * ワールドを閉じたら台帳を空にする(キーはディメンションだけなので、同じワールドを開き直すと
     * 閉じる直前の書き込みが数 tick だけ「いる」扱いで残る。起動し直したときと同じ状態に戻す)。
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        MARKS.clear();
        LAST_SWEEP.clear();
    }

    /**
     * {@code chunkKey} の上にいまいる、と書く。同じ tick に同じチャンクへ複数の個体が書いたら
     * 大きいほうの濃度を残す。
     */
    public static void mark(ServerLevel level, long chunkKey, double percent) {
        if (percent <= 0.0D) {
            return;
        }
        long now = level.getGameTime();
        Long2ObjectOpenHashMap<Mark> marks = MARKS.computeIfAbsent(level.dimension(), k -> new Long2ObjectOpenHashMap<>());
        sweep(level.dimension(), marks, now);
        Mark mark = marks.get(chunkKey);
        if (mark == null) {
            mark = new Mark();
            marks.put(chunkKey, mark);
        } else if (mark.gameTime == now) {
            mark.percent = Math.max(mark.percent, Math.min(100.0D, percent));
            return;
        }
        mark.gameTime = now;
        mark.percent = Math.min(100.0D, percent);
    }

    /** {@code chunkKey} の上にいまペディキュラがいれば、その間の胞子濃度(%)。いなければ 0。 */
    public static double percentAt(ServerLevel level, long chunkKey) {
        Long2ObjectOpenHashMap<Mark> marks = MARKS.get(level.dimension());
        if (marks == null || marks.isEmpty()) {
            return 0.0D;
        }
        Mark mark = marks.get(chunkKey);
        return mark != null && isFresh(mark, level.getGameTime()) ? mark.percent : 0.0D;
    }

    /**
     * 新しい書き込みか。<b>時刻が逆転している</b>(別のワールドを開き直した後に残っていた記録)ものも古い扱い ——
     * ゲーム内時刻はワールドごとなので、差が負になりうる。
     */
    private static boolean isFresh(Mark mark, long now) {
        long age = now - mark.gameTime;
        return age >= 0L && age <= FRESH_TICKS;
    }

    private static void sweep(ResourceKey<Level> dimension, Long2ObjectOpenHashMap<Mark> marks, long now) {
        Long last = LAST_SWEEP.get(dimension);
        if (last != null && now - last >= 0L && now - last < SWEEP_INTERVAL) {
            return;
        }
        LAST_SWEEP.put(dimension, now);
        marks.values().removeIf(mark -> !isFresh(mark, now));
    }

    private PediculaCover() {
    }
}
