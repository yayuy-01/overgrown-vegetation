package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.VanillaGameEvent;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * ヴォラムネス({@link VoramnesEntity})の「振動で位置を探る」耳(2026-10-01)。
 *
 * <h2>ユーザーが決めたこと(採用の案)</h2>
 * 走る・跳ぶ振動で位置を探り、真下から丸呑みにする。<b>スニークで歩けば気付かれない</b>。
 *
 * <h2>作り</h2>
 * バニラの振動({@link GameEvent#STEP 足音}・{@link GameEvent#HIT_GROUND 着地})を NeoForge の {@link VanillaGameEvent} で受け、
 * 読み込まれているヴォラムネスへ配る。<b>毎 tick の探索はしない</b>(足音が出たときだけ動く)。
 * 配るのは生き物の振動全部(プレイヤー・プレイヤーが乗って操る馬など・過繁茂側以外の Mob)。2026-10-09 のユーザー指定
 * (「過繁茂 mob 以外に敵対していない場合がある」)で、Mob の足音にも反応するようにした(10-01 の仮決めではプレイヤーの振動だけだった)。
 * 牛の群れに振り回されないよう、今の標的の音が新しいうちはほかの Mob の振動では標的を替えない(プレイヤーの振動は Mob の標的より先。
 * {@link VoramnesEntity#onVibration})。Mob はスプリントしないので、ふつうに歩く足音の距離({@value #QUIET_RANGE})でだけ聞こえる
 * (跳んで着地したときは大きな振動)。
 *
 * <h2>聞こえる距離(すべて仮決め)</h2>
 * <table>
 *     <tr><th>振動</th><th>距離</th></tr>
 *     <tr><td>走る足音(スプリント)</td><td>{@value #LOUD_RANGE}</td></tr>
 *     <tr><td>跳んで着地(落ちた高さ {@value #LOUD_LANDING} 以上)</td><td>{@value #LOUD_RANGE}</td></tr>
 *     <tr><td>ふつうに歩く足音・小さな段差の着地</td><td>{@value #QUIET_RANGE}</td></tr>
 *     <tr><td>プレイヤーが乗った馬などの足音</td><td>{@value #LOUD_RANGE}</td></tr>
 *     <tr><td><b>スニーク</b>・羊毛/カーペットの上・クリエイティブ/観戦者</td><td>聞こえない</td></tr>
 * </table>
 * 上下は {@value #MAX_DY} ブロックまで(高い足場の上・深い洞窟の足音は届かない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class VoramnesSenses {

    /** 走る・跳ぶ振動が届く距離(ブロック、仮)。 */
    public static final double LOUD_RANGE = 24.0D;
    /** ふつうに歩く足音が届く距離(ブロック、仮)。 */
    public static final double QUIET_RANGE = 6.0D;
    /** 振動が届く上下の差(ブロック、仮)。 */
    public static final double MAX_DY = 12.0D;
    /** これ以上の高さから着地すると「跳んだ」大きな振動(ブロック、仮。ジャンプ 1 回は約 1.25)。 */
    public static final float LOUD_LANDING = 0.8F;

    /** サーバーに読み込まれているヴォラムネス(弱参照。{@link VoramnesEntity#onAddedToLevel} / {@code onRemovedFromLevel} で出し入れ)。 */
    private static final Set<VoramnesEntity> LISTENERS = Collections.newSetFromMap(new WeakHashMap<>());

    private VoramnesSenses() {
    }

    static void register(VoramnesEntity worm) {
        LISTENERS.add(worm);
    }

    static void unregister(VoramnesEntity worm) {
        LISTENERS.remove(worm);
    }

    /**
     * 振動の届く距離(ブロック)。0 なら聞こえない。GameTest から直接呼ぶ。
     *
     * @param cause   振動を出したもの(足音の主・着地した本人・馬など)
     * @param landing 着地の振動か(false なら足音)
     * @param surface 踏んだブロック(分からなければ null)
     */
    public static double noiseRange(Entity cause, boolean landing, @Nullable BlockState surface) {
        if (surface != null && surface.is(BlockTags.DAMPENS_VIBRATIONS)) {
            return 0.0D; // 羊毛・カーペットの上は音が出ない(スカルクセンサーと同じ)
        }
        if (cause instanceof LivingEntity mount && !(cause instanceof Player)
                && mount.getControllingPassenger() instanceof Player rider) {
            // 乗って操っている馬など。蹄の音は大きい(乗ったままスニークはできない)
            return silentPlayer(rider) ? 0.0D : LOUD_RANGE;
        }
        if (!(cause instanceof LivingEntity living)) {
            return 0.0D;
        }
        if (living instanceof Player player && silentPlayer(player)) {
            return 0.0D;
        }
        if (living.isShiftKeyDown() || living.isCrouching()) {
            return 0.0D; // スニーク(ユーザー指定: スニークで歩けば気付かれない)
        }
        if (landing) {
            return living.fallDistance >= LOUD_LANDING ? LOUD_RANGE : QUIET_RANGE;
        }
        return living.isSprinting() ? LOUD_RANGE : QUIET_RANGE;
    }

    /** 振動の主。乗って操っている馬などならその乗り手(呑む相手はプレイヤーのほう)。 */
    @Nullable
    public static LivingEntity noiseMaker(Entity cause) {
        if (cause instanceof LivingEntity mount && !(cause instanceof Player)
                && mount.getControllingPassenger() instanceof Player rider) {
            return rider;
        }
        return cause instanceof LivingEntity living ? living : null;
    }

    private static boolean silentPlayer(Player player) {
        return player.isCreative() || player.isSpectator();
    }

    /**
     * 振動をヴォラムネスへ配る。足音・着地のほかは捨てる。読み込まれた個体が 1 体も居なければ何もしない。
     * 呼ばれる回数は多い(全エンティティの足音)ので、安い判定から落とす。
     */
    @SubscribeEvent
    public static void onVanillaGameEvent(VanillaGameEvent event) {
        if (LISTENERS.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Holder<GameEvent> kind = event.getVanillaEvent();
        boolean landing = kind.is(GameEvent.HIT_GROUND);
        if (!landing && !kind.is(GameEvent.STEP)) {
            return;
        }
        Entity cause = event.getCause();
        if (cause == null) {
            return;
        }
        if (!(cause instanceof LivingEntity) || cause instanceof VoramnesEntity) {
            return;
        }
        Vec3 pos = event.getEventPosition();
        BlockState surface = event.getContext().affectedState();
        for (VoramnesEntity worm : LISTENERS) {
            if (worm.level() != level || worm.isRemoved()) {
                continue;
            }
            worm.onVibration(pos, cause, landing, surface);
        }
    }
}
