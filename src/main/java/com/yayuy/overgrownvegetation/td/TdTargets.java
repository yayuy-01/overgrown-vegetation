package com.yayuy.overgrownvegetation.td;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 読み込まれているターゲットブロック({@link TargetBlockEntity})の台帳(TD イベント、2026-09-25)。
 *
 * <p>TD の引き金({@code TdTicker} が「汚染チャンクの近くにあるターゲットブロック」を探す)と、群れが
 * ターゲットに殴りかかるとき({@code TdSiege} → {@link #get} → {@link TargetBlockEntity#damage})に使う。
 * 世界中のブロックエンティティを総なめしないで済むように、ターゲットブロックの座標だけをワールドごとに持つ。
 *
 * <h2>台帳 = 「いま読み込まれているターゲットブロック」</h2>
 * <ul>
 *     <li><b>載せる</b>: {@code setLevel}(チャンクのブロックエンティティ表に入る瞬間)と {@code onLoad}。
 *     {@code onLoad} は次の tick まで遅れるので、置いた直後の 1 tick に群れの攻撃や引き金の検索が
 *     取りこぼさないよう {@code setLevel} でも載せる(遮蔽盾の台帳 {@code defense/BeamShields} と同じ理由)。</li>
 *     <li><b>外す</b>: {@code onChunkUnloaded} と {@code setRemoved}。どちらも「その実物が載っているときだけ」外す
 *     ({@link Map#remove(Object, Object)})。同じ座標のブロックエンティティが入れ替わるとき、古い方の
 *     {@code setRemoved} は新しい方を載せた<b>後</b>に来るので、座標だけで外すと新しい方が消えてしまう。</li>
 * </ul>
 * 「本当に壊された(プレイヤーが掘った・敗北で消えた)」かどうかはここでは判定しない —— チャンクの読み込み解除と
 * 区別できないので、{@link TargetBlock#onRemove} の側で見る。
 *
 * <p>サーバー側だけ。ワールド(ディメンション)を閉じたとき・サーバーを止めたときに空にする
 * (ブロックエンティティ = ワールドへの参照を、別のワールドへ持ち越さないため)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class TdTargets {

    private static final Map<ResourceKey<Level>, Map<BlockPos, TargetBlockEntity>> LOADED = new ConcurrentHashMap<>();

    /** そのワールドで読み込まれているターゲットブロックの座標(呼んだ時点の写し。回しながら壊しても安全)。 */
    public static Collection<BlockPos> loaded(ServerLevel level) {
        Map<BlockPos, TargetBlockEntity> map = LOADED.get(level.dimension());
        return map != null ? List.copyOf(map.keySet()) : List.of();
    }

    /**
     * その座標の読み込まれているターゲットブロック。無い・読み込まれていない・もう壊れたなら null。
     *
     * <p>{@code level.getBlockEntity} は読み込まれていないチャンクを<b>読み込んでしまう</b>ので使わない
     * (群れの攻撃は 20 tick ごとに何十体ぶんも来る)。
     */
    @Nullable
    public static TargetBlockEntity get(ServerLevel level, BlockPos pos) {
        Map<BlockPos, TargetBlockEntity> map = LOADED.get(level.dimension());
        if (map == null) {
            return null;
        }
        TargetBlockEntity target = map.get(pos);
        if (target == null) {
            return null;
        }
        if (target.isRemoved() || target.getLevel() != level) {
            map.remove(pos, target); // 台帳の余り(外し損ねたもの)
            return null;
        }
        return target;
    }

    /** チャンクのブロックエンティティ表に入った(設置・読み込み)。 */
    static void add(Level level, TargetBlockEntity target) {
        LOADED.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>())
                .put(target.getBlockPos().immutable(), target);
    }

    /** その実物が台帳に載っていれば外す(同じ座標に別の実物が載っていれば触らない)。 */
    static void remove(Level level, BlockPos pos, TargetBlockEntity target) {
        Map<BlockPos, TargetBlockEntity> map = LOADED.get(level.dimension());
        if (map != null) {
            map.remove(pos, target);
        }
    }

    /** 全部空にする(サーバー停止時。GameTest から呼んでもよい)。 */
    public static void clearAll() {
        LOADED.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clearAll();
    }

    /** ディメンションを閉じたら、そのワールドのぶんを捨てる(ブロックエンティティ経由でワールドを掴み続けない)。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LOADED.remove(level.dimension());
        }
    }

    private TdTargets() {
    }
}
