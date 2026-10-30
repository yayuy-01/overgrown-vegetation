package com.yayuy.overgrownvegetation.defense;

import com.yayuy.overgrownvegetation.block.BeamShieldBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 遮蔽盾({@link BeamShieldBlockEntity})の台帳(2026-09-24 軽量化、監査 1230)。
 *
 * <p>ビームの被害者 1 体ごとに {@link BeamShieldBlockEntity#shielded} が周り 5×5 チャンクの
 * <b>全ブロックエンティティ</b>を総なめしていたので、盾の座標だけをワールドごとに持つ。
 *
 * <h2>「チャンクにある盾」を必ず含む(多すぎるのは構わない)</h2>
 * 引く側は台帳の座標を<b>チャンクのブロックエンティティ表で引き直して</b>確かめるので、台帳に余分な座標
 * (壊された盾・読み込まれていないチャンクの盾)が残っていても結果は変わらない。逆に<b>漏れ</b>があると
 * 遮るはずの盾を見落とすので、登録はチャンクの表に入る瞬間({@code setLevel}。{@code onLoad} は
 * 1 tick 遅れる)に行い、外すのはチャンクの読み込み解除のときと、引き直して盾が無かったときだけにする
 * (同じ座標の盾が入れ替わるとき、古い方の {@code setRemoved} は新しい方を入れた<b>後</b>に来るため、
 * そこで外すと新しい方が漏れる)。
 *
 * <p>サーバー側だけ。ワールドを閉じたら {@link DefenseLifecycle} が空にする。
 */
public final class BeamShields {

    private static final Map<ResourceKey<Level>, Map<BlockPos, BeamShieldBlockEntity>> LOADED = new ConcurrentHashMap<>();

    /** チャンクのブロックエンティティ表に入った(読み込み・設置)。 */
    public static void add(Level level, BeamShieldBlockEntity shield) {
        LOADED.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>())
                .put(shield.getBlockPos().immutable(), shield);
    }

    /** その盾が台帳に載っていれば外す(同じ座標に別の盾が載っていれば触らない)。 */
    public static void remove(Level level, BlockPos pos, BeamShieldBlockEntity shield) {
        Map<BlockPos, BeamShieldBlockEntity> map = LOADED.get(level.dimension());
        if (map != null) {
            map.remove(pos, shield);
        }
    }

    /** 引き直して盾が無かった座標を外す。 */
    public static void forget(Level level, BlockPos pos) {
        Map<BlockPos, BeamShieldBlockEntity> map = LOADED.get(level.dimension());
        if (map != null) {
            map.remove(pos);
        }
    }

    /** そのワールドで台帳に載っている盾の座標(実物かどうかは呼ぶ側が確かめる)。 */
    public static Set<BlockPos> positions(Level level) {
        Map<BlockPos, BeamShieldBlockEntity> map = LOADED.get(level.dimension());
        return map != null ? map.keySet() : Collections.emptySet();
    }

    public static void clearAll() {
        LOADED.clear();
    }

    private BeamShields() {
    }
}
