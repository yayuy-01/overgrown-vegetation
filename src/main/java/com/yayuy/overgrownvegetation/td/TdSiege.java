package com.yayuy.overgrownvegetation.td;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.BarbedWireBlock;
import com.yayuy.overgrownvegetation.block.RepellentLampBlock;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.defense.RepellentLamps;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData;
import com.yayuy.overgrownvegetation.entity.InvaderNavigation;
import com.yayuy.overgrownvegetation.raid.EventMobs;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.SwarmRole;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmAttacker;
import com.yayuy.overgrownvegetation.swarm.goal.SwarmSiegeHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * TD イベントの<b>攻城</b>(TD 設計 v2、2026-09-25)。群れの Goal が決まった間隔で呼ぶ 2 つのフック
 * ({@link SwarmSiegeHooks})の中身と、その見張り。
 *
 * <h2>何をするか</h2>
 * <ul>
 *     <li><b>壊す壁を選ぶ</b>({@link #pick}、{@link SwarmSiegeHooks.BreachPicker})… 攻城(SIEGE)中にターゲットへの区間が 2 回続けて
 *     塞がれたとき、先導役の足元からターゲットへ水平にまっすぐ(DDA)マスをたどり、群れの体の包絡(幅 × 高さ、どちらも 3 まで)に
 *     かかる最初の「壊してよい」塊を返す。ユーザー指定(2026-09-24)「囲った拠点は壁を壊して入る」。
 *     ターゲットが先導役の足の段より 2 段以上下(床の下に埋めた・地下室)なら、真上の列を上から掘る({@link Column})。</li>
 *     <li><b>叩く</b>({@link #strike}、{@link SwarmSiegeHooks.BlockStrike})… 塊の輪で手が届く前衛が 20 tick に 1 回呼ぶ。
 *     ターゲットならその耐久を削り({@link TargetBlockEntity#damage})、壁なら座標ごとの削れ具合を貯めて壊す。</li>
 *     <li><b>忌避灯</b>… 拠点のそばの点いた忌避灯が寄り道を塞いでいれば、その灯りを「壊す壁」として選び、壊す代わりに
 *     胞子で<b>覆う</b>(既存の覆い。プレイヤーは右クリックで戻せる)。灯りまでの線に壁(ガラス板・柵・葉・拠点の壁)があれば先にその壁、
 *     床に埋めた・ターゲットの下の灯りなら上から掘る。仮決め 2026-09-25(TD 設計 v2 の敵対レビュー:
 *     ターゲットの横に灯りを 1 つ置くだけで、地上の攻め手が拠点に一切触れなくなるため)。</li>
 *     <li><b>着いたのに叩けない見張り</b>({@link #watchSiege})… 先導役が塊に着いた(塊の輪)のに {@value #SIEGE_STALL_TICKS} tick
 *     ターゲットに一度も当たらなければ、先導役からターゲットへ壁を探し直して壊す(壁の裏に置いたターゲット)。無ければ
 *     「着いた」を取り消して区間へ戻す(回り道を探す)。レビュー指摘 2026-09-25。</li>
 * </ul>
 *
 * <h2>一時停止は見ない</h2>
 * 拠点のまわりに誰もいない間の一時停止({@link TdEvents#isPaused})は<b>増援</b>だけを止める。攻め手が拠点で動いている限り、
 * 壁選び・叩く・見張りは続ける({@link TdEvents#isSiegeLive} = 侵攻中のターゲットか、だけを見る)。仮決め 2026-09-25(レビュー指摘:
 * 削りまで止めていた頃は、耐久が減ったら 130〜190 ブロック離れて削りを止め、自動の砲台に攻め手を片付けさせれば負けなかった)。
 *
 * <h2>なぜ忌避灯は見張りの tick でも選ぶか</h2>
 * 経路探索は灯りを知らないので、灯りの半径の向こうのターゲットへの区間は「届く」と出る。先導役は区間を歩いて半径に入り、
 * 忌避の Goal(優先度 1)に押し出され、また同じ区間を引く —— 区間は塞がれたことにならず({@link Swarm#consecutiveBlockedLegs} は 0 のまま)、
 * 壁を選ぶフックは一度も呼ばれない。だから {@value #CHECK_INTERVAL_TICKS} tick ごとの見張り({@link #onLevelTick})でも、
 * 先導役がターゲットへ向かう線の上に拠点のそばの灯りがあるかを見る。
 * 灯りを覆っている間(と、灯りの半径の中の壁を壊している間)は、群れの一員は灯りを避けない
 * ({@code SwarmGoals#breachIgnoresLamps} を {@code RepellentAvoidGoal} と {@code SwarmEngageGoal} が見る)。
 *
 * <h2>壁の削れ具合</h2>
 * 座標ごとに 1 つ(同じ壁を叩く群れの全員で共有 = 人数が多いほど速い。TD 設計「群れの効果」)。要る量は
 * {@code max(1, 硬さ) × breakHardnessFactor}(td.json、仮決め 10)で、汚染されたブロック(タグ {@code #corrupted}・苔で覆われたマス)は
 * さらに {@code corruptedBreakTimeMultiplier} 倍(ユーザー指定 2026-09-26「TD 時のみ汚染されたブロックは mob が破壊しやすく」、仮決め 0.25)。
 * ひびは採掘のひび 10 段階({@code destroyBlockProgress}。
 * 座標ごとに決まった負の番号で、ターゲットブロックのひびの番号とは範囲を分けてある)。壊し終えたら {@code destroyBlock}
 * (ふつうの塊は落とさない。ブロックエンティティ付きの塊 = プレイヤーの持ち物は落とし、中身は散らばる。どちらも仮決め)して、同じ線・同じ列の隣の塊が
 * まだ塞いでいれば続けて壊す(1 マスずつ区間 2 回ぶん待たせない)。
 * 壁を壊すのをやめた(群れが BREACH を抜けた・壁が別の物に替わった)座標の削れ具合は、見張りの tick で捨てる(ひびも消す)。
 *
 * <h2>行き詰まり</h2>
 * 選んだ壁(灯り)に {@value #BREACH_STALL_TICKS} tick 一度も当たらなければ(届かない高さ・壁の向こうの灯りなど)、
 * その群れはそこを {@value #SKIP_TICKS} tick 選ばない印を付けて攻城へ戻る。先導役が動いていない(読み込まれていない)間と、
 * 防衛側を<b>実際に殴っている</b>間は数えない(防衛側が近くに立っているだけでは止めない。レビュー指摘 2026-09-25)。
 *
 * <h2>状態</h2>
 * 静的に持つのはディメンションごとの削れ具合・群れごとの見張り(壁・着いてからの時刻)・選ばない印だけで、{@link Level} や
 * 個体への参照は持たない(座標と UUID だけ)。ディメンションの unload とサーバー停止で捨てる。フックはサーバーが立ち上がるたびに登録し直す
 * ({@link #install}。同じものを入れ直すだけなので何度呼んでもよい)。サーバースレッドだけが触る。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class TdSiege {

    /** 壊す壁を探す長さ(先導役から水平、ブロック)。TD 設計「障害物から ~6 ブロック以内」に余りを足した。仮決め 2026-09-25。 */
    static final double SCAN_DISTANCE = 8.0D;
    /** 包絡の幅・高さの上限(マス)。TD 設計「3×3 まで」。攻城の区間の包絡も同じ値で頭打ちする(群れ側と共有)。 */
    static final int MAX_ENVELOPE = SwarmSiegeHooks.MAX_BREACH_CELLS;
    /**
     * 群れが壁に開ける穴の小ささの下限(マス、幅・高さとも)。ユーザー指定 2026-09-25「自身だけが通れる穴を掘るのではなく、G5 などの
     * 大型 mob も通れるように大きな穴をあけようとするように」: スクティムネス(幅 2.97・高さ 2.59)が通れる 3 × 3。アルボラの手が届く
     * (足の段から 3 段目まで)大きさでもある。もっと背の高いファルキムネス(3.06)などは自分で上を削る({@link #digHole})。
     * それまでは群れの体の包絡の大きさで、アルボラだけの群れは 1 × 1 の穴しか開けなかった。
     */
    static final int SWARM_HOLE_CELLS = 3;
    /** 群れの外の攻め手が自分の体で開ける穴の大きさの上限(マス、幅・高さとも)。 */
    static final int MAX_HOLE_CELLS = 4;
    /** 穴の断面を手分けして叩いた座標の削れ具合を、最後に叩いてから残しておく間(tick)。 */
    private static final long DIG_KEEP_TICKS = 60L;
    /**
     * 包絡の断面に壊せる塊が無いとき(岩盤・溶岩に面している)、横へ何列ずらして探すか。
     * TD 設計「壊せない塊なら次の列を試す」。仮決め 2026-09-25(チェスト・樽は 2026-09-25 のユーザー指定から壊す)。
     */
    static final int SIDESTEP = 2;
    /** 足元の段でこの高さ以下の当たり(カーペット・下付きハーフ・雪)は歩いて越えられるので壁と数えない(ブロック)。 */
    static final double STEP_HEIGHT = 0.6D;
    /** たどる途中で下りてよい段(マス)。これより深い穴・崖・堀の先は壊しても通れないので探すのをやめる。仮決め 2026-09-25。 */
    static final int MAX_DROP = 2;
    /** 壊し終えた塊から、次に続けて壊す塊までの水平の距離(チェビシェフ、マス)。同じ断面か、奥の 1 枚まで。仮決め 2026-09-25。 */
    static final int CHAIN_REACH = 2;
    /** 見張りの間隔(tick)。 */
    static final int CHECK_INTERVAL_TICKS = 20;
    /** 見張りの位相(群れの司令塔と同じ tick に重ならないよう、ずらすだけ)。 */
    private static final int CHECK_PHASE = 7;
    /** 選んだ壁・灯りにこれだけ当たらなければ諦める(tick)。仮決め 2026-09-25。 */
    static final int BREACH_STALL_TICKS = 400;
    /** 諦めた壁・灯りを、その群れが選ばない間(tick)。仮決め 2026-09-25。 */
    static final int SKIP_TICKS = 1200;
    /**
     * 先導役が塊に着いた(塊の輪)のに、これだけターゲットに一度も当たらなければ壁を探し直す(tick)。ふつうは着いてから
     * 20〜60 tick で最初の一撃が入る。仮決め 2026-09-25(レビュー指摘: 1〜2 枚の壁のすぐ裏のターゲットで群れが永久に止まった)。
     */
    static final int SIEGE_STALL_TICKS = 200;
    /** 「防衛側を実際に殴っている」とみなす間(tick。最後に殴ってから)。その間は行き詰まりを数えない。仮決め 2026-09-25。 */
    static final int FIGHT_GRACE_TICKS = 100;
    /**
     * 防衛側を追っていて区間が塞がれたとき、その防衛側がターゲットからこれ以内(水平、ブロック)なら拠点の中にいるとみなし、
     * ターゲットの方向の壁を選ぶ(拠点の壁は防衛側もターゲットも囲っている)。これより遠い防衛側(崖の上・柱の上)を追っていて
     * 塞がれたときは壁を選ばない(関係のない丘・木を掘って、防衛側を追うのもやめてしまう。レビュー指摘 2026-09-25)。仮決め。
     */
    static final double DEFENDER_IN_BASE_RANGE = 16.0D;
    /** 先導役が灯りの半径 + これ以内にいれば「灯りに押し返されている」とみなす(ブロック)。仮決め 2026-09-25。 */
    static final double LAMP_NOTICE_MARGIN = 6.0D;
    /** 「拠点のそばの灯り」= ターゲットからこの距離以内(ブロック)。仮決め 2026-09-25。 */
    static final double LAMP_BASE_RANGE = 48.0D;
    /** 灯りの半径が先導役 → ターゲットの線にこれだけ足りなくても「線にかかる」とみなす(群れの体の幅ぶん、ブロック)。 */
    private static final double LAMP_PATH_SLACK = 1.0D;
    /** 灯りへの壁探しの長さの余り(灯りの半径 + {@value #LAMP_NOTICE_MARGIN} に足す、ブロック)。押し返された先導役から灯りまで届く長さ。 */
    private static final double LAMP_SCAN_EXTRA = 2.0D;
    /** 叩く音の間隔(座標ごと、tick)。前衛が何体もそろって叩くので間引く。 */
    private static final int SOUND_INTERVAL_TICKS = 10;
    /** ひびを送り直す間隔(tick)。クライアントは 400 tick 更新が無いとひびを消す。段階が変わったときは待たずに送る。 */
    private static final int CRACK_RESEND_TICKS = 100;

    private static final Map<ResourceKey<Level>, LevelState> LEVELS = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ 登録

    /** フックを群れの Goal へ登録する(サーバーが立ち上がるたび。入れ直しても同じ物)。 */
    public static void install() {
        SwarmSiegeHooks.setBreachPicker(TdSiege::pick);
        SwarmSiegeHooks.setBlockStrike(TdSiege::strike);
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        install();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LEVELS.remove(level.dimension());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LEVELS.clear();
    }

    // ------------------------------------------------------------------ 壊す壁を選ぶ

    /**
     * {@link SwarmSiegeHooks.BreachPicker}。攻城中の区間が続けて塞がれたとき {@code SwarmLeadGoal} が呼ぶ(20 tick に 1 回まで)。
     *
     * <p>先に壁(包絡にかかる最初の壊してよい塊。ターゲットが足の段より下なら真上の列)を探し、無ければ線を塞ぐ拠点のそばの灯りを
     * 返す(区間が塞がれているのは地形のせいで、灯りは経路探索に効かないため、壁を先に見る)。
     * ターゲットが今の侵攻のものでないときは選ばない。増援の一時停止は見ない(クラスの説明)。
     *
     * <p>塞がれた区間が<b>防衛側</b>へ向かうもの(攻城の leadGoal は防衛側がいればその位置)なら、その防衛側が拠点の中
     * (ターゲットから {@value #DEFENDER_IN_BASE_RANGE} 以内)にいるときだけ選ぶ。外の防衛側(崖・柱の上)を追って塞がれたのに
     * ターゲットの方向の壁を選ぶと、関係のない丘・木・建物を掘って BREACH に入り、防衛側を追うのもやめてしまう(レビュー指摘 2026-09-25)。
     *
     * @return 壊す壁(または覆う灯り)。null なら先導役は区間を引き続ける
     */
    @Nullable
    static BlockPos pick(Mob vanguard, Swarm swarm) {
        if (!(vanguard.level() instanceof ServerLevel level)) {
            return null;
        }
        BlockPos target = swarm.targetBlock();
        if (!TdEvents.isSiegeLive(level, target)) {
            return null;
        }
        LivingEntity defender = swarm.target();
        if (defender != null && defender.isAlive() && horizontalSq(defender.position(), Vec3.atCenterOf(target))
                > DEFENDER_IN_BASE_RANGE * DEFENDER_IN_BASE_RANGE) {
            return null;
        }
        if (!vanguard.onGround() && !vanguard.isInWater()) {
            // 跳んでいる・壁に張り付いている。足元の段が決まらないので、次に呼ばれたときに選ぶ。
            return null;
        }
        long now = level.getGameTime();
        // 崖・溝なら壁より先に橋(2026-09-25 のユーザー報告「崖でアルボラ以外が立ち往生」。TdBridges)。薄い壁は橋にしない。
        if (TdBridges.onLegsBlocked(level, vanguard, swarm, target, now)) {
            return null;
        }
        LevelState st = state(level);
        UUID id = swarm.id();
        Predicate<BlockPos> skipped = pos -> st.skipped(id, pos, now);
        Breach found = findBreach(level, vanguard, swarm, target, skipped, now);
        if (found == null) {
            found = lampBreach(level, vanguard, swarm, target, skipped, now);
        }
        if (found == null) {
            return null;
        }
        st.breaches.put(id, found);
        return found.pos;
    }

    /**
     * 先導役からターゲットへの壁: 水平の DDA({@link Scan})で最初の壊してよい塊、無ければターゲットが足の段より下のときの
     * 真上の列({@link Column})の最初の塊。どちらも無ければ null。
     */
    @Nullable
    private static Breach findBreach(ServerLevel level, Mob vanguard, Swarm swarm, BlockPos target,
                                     Predicate<BlockPos> skipped, long now) {
        int width = Math.max(blocks(envelopeWidth(swarm, vanguard)), SWARM_HOLE_CELLS);
        int height = Math.max(blocks(envelopeHeight(swarm, vanguard)), SWARM_HOLE_CELLS);
        Scan scan = Scan.of(vanguard.position(), target, width, height, SCAN_DISTANCE);
        Hole hole = scan != null ? scan.findHole(level, target, target, skipped) : null;
        if (hole != null) {
            Breach breach = new Breach(hole.first(), now, scan, null, target);
            breach.cells.addAll(hole.cells());
            return breach;
        }
        if (!Column.below(vanguard.position(), target)) {
            return null;
        }
        // 縦穴も横の穴と同じ幅(2026-09-25 のユーザー報告「1 マスの隙間に入れず」。Column の説明)。
        Column column = Column.toward(level, vanguard.position(), target, target, width);
        Hole shaft = column != null ? column.findHole(level, target, skipped) : null;
        if (shaft == null) {
            return null;
        }
        Breach breach = new Breach(shaft.first(), now, null, column, target);
        breach.cells.addAll(shaft.cells());
        return breach;
    }

    private static float envelopeWidth(Swarm swarm, Mob vanguard) {
        InvaderNavigation.Envelope envelope = swarm.envelope();
        return envelope != null ? envelope.width() : vanguard.getBbWidth();
    }

    private static float envelopeHeight(Swarm swarm, Mob vanguard) {
        InvaderNavigation.Envelope envelope = swarm.envelope();
        return envelope != null ? envelope.height() : vanguard.getBbHeight();
    }

    /**
     * テスト・調べもの用: {@code from}(足の位置)から {@code target} へ向けて、幅 {@code width} × 高さ {@code height} マスの
     * 包絡で最初に壊す壁。侵攻の状態・選ばない印は見ない。
     */
    @Nullable
    public static BlockPos scanWall(ServerLevel level, Vec3 from, BlockPos target, int width, int height) {
        Scan scan = Scan.of(from, target, width, height, SCAN_DISTANCE);
        return scan != null ? scan.find(level, target, target, pos -> false) : null;
    }

    /**
     * テスト・調べもの用: {@code from}(足の位置)より 2 段以上下の {@code goal} へ掘るとき、最初に壊す塊({@link Column})。
     * 掘らなくても手が届く高さ・掘れる列が無いときは null。{@code target} は壊さない塊(侵攻のターゲット)。
     */
    @Nullable
    public static BlockPos scanCover(ServerLevel level, Vec3 from, BlockPos goal, @Nullable BlockPos target) {
        if (!Column.below(from, goal)) {
            return null;
        }
        Column column = Column.toward(level, from, goal, target);
        return column != null ? column.find(level, target, pos -> false) : null;
    }

    /** 体の大きさ(ブロック、小数)→ 占めるマス数。バニラの評価器と同じ {@code floor(大きさ + 1)}、1〜{@value #MAX_ENVELOPE}。 */
    private static int blocks(float size) {
        return Mth.clamp(Mth.floor(size + 1.0F), 1, MAX_ENVELOPE);
    }

    /**
     * 先導役の足元からターゲットへの水平の DDA(マスを 1 つずつたどる)。
     *
     * @param ox       出発点 X(先導役の足)
     * @param oz       出発点 Z
     * @param feetY    出発点の足の段
     * @param dirX     向き(単位ベクトル)X
     * @param dirZ     向き Z
     * @param distance たどる長さ(行き先の手前まで。ターゲットへは {@value #SCAN_DISTANCE}、灯りへは灯りの半径 + 余りまで)
     * @param width    包絡の幅(マス)
     * @param height   包絡の高さ(マス)
     * @param side     幅 2 のとき 2 列目を出す側(先導役の中心がマスのどちら半分にいるか)
     * @param alongX   X 向きに進む(断面は Z 方向に並ぶ)か
     */
    /**
     * 壊す穴: 最初に壊す塊と、その断面の壊してよい塊の全部({@link Scan#findHole})。
     *
     * @param first 最初に壊す塊(群れの輪はこのまわりに立つ)
     * @param cells 断面の壊してよい塊(first を含む。下の段から)
     */
    record Hole(BlockPos first, List<BlockPos> cells) {
    }

    record Scan(double ox, double oz, int feetY, double dirX, double dirZ, double distance,
                int width, int height, int side, boolean alongX) {

        /** 「そのマスは通れる(次の足の段を返す)」以外の答え。 */
        private static final int BLOCKED = Integer.MIN_VALUE;
        private static final int STOP = Integer.MIN_VALUE + 1;

        @Nullable
        static Scan of(Vec3 from, BlockPos goal, int width, int height, double maxDistance) {
            double dx = goal.getX() + 0.5D - from.x;
            double dz = goal.getZ() + 0.5D - from.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1.0E-3D) {
                return null;
            }
            boolean alongX = Math.abs(dx) >= Math.abs(dz);
            double perp = alongX ? from.z : from.x;
            int side = perp - Math.floor(perp) >= 0.5D ? 1 : -1;
            return new Scan(from.x, from.z, Mth.floor(from.y + 1.0E-3D), dx / len, dz / len,
                    Math.min(maxDistance, len), Mth.clamp(width, 1, MAX_HOLE_CELLS),
                    Mth.clamp(height, 1, MAX_HOLE_CELLS), side, alongX);
        }

        /** {@link #findHole} の最初に壊す塊だけ。 */
        @Nullable
        BlockPos find(ServerLevel level, @Nullable BlockPos goal, @Nullable BlockPos target, Predicate<BlockPos> skipped) {
            Hole hole = findHole(level, goal, target, skipped);
            return hole != null ? hole.first() : null;
        }

        /**
         * 線をたどって最初に壊す塊。先導役のマスは見ない(立っているマス)。行き先({@code goal}。ターゲットか灯り)の列に着いたら・
         * 読み込まれていないチャンク・深い穴に当たったらやめる。1 段の段差(上に包絡ぶんの空き)は登れるので壁と数えず、その先は
         * 1 段上で続ける。包絡の断面に壊せる塊が無ければ(岩盤・溶岩に面している)横の列を試し(TD 設計「次の列を試す」)、
         * それも無ければ<b>そこでやめる</b>(null)。
         *
         * <p>レビュー指摘 2026-09-25: はじめは壊せない断面の奥のマスへ進んでいたので、壊せない壁の向こうの(誰も届かない)塊を選び、
         * 400 tick 当たらずに諦めて 1200 tick 後にまた選ぶ、を繰り返していた。
         *
         * <p>見つけた塊と一緒に、その断面(包絡の列 × 段)の壊してよい塊を全部返す({@link Hole#cells}。塊の輪の前衛が手分けして崩す。
         * 2026-09-25)。見つけた塊が横の列のもの(包絡の断面に壊せる塊が無かった)ならその 1 つだけ。
         *
         * @param goal   線の行き先(その列に着いたらやめる)
         * @param target 壊さない塊(侵攻のターゲット)
         */
        @Nullable
        Hole findHole(ServerLevel level, @Nullable BlockPos goal, @Nullable BlockPos target, Predicate<BlockPos> skipped) {
            int cx = Mth.floor(this.ox);
            int cz = Mth.floor(this.oz);
            int stepX = this.dirX > 1.0E-9D ? 1 : (this.dirX < -1.0E-9D ? -1 : 0);
            int stepZ = this.dirZ > 1.0E-9D ? 1 : (this.dirZ < -1.0E-9D ? -1 : 0);
            double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY
                    : (stepX > 0 ? cx + 1.0D - this.ox : this.ox - cx) / Math.abs(this.dirX);
            double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY
                    : (stepZ > 0 ? cz + 1.0D - this.oz : this.oz - cz) / Math.abs(this.dirZ);
            double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0D / Math.abs(this.dirX);
            double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0D / Math.abs(this.dirZ);
            int floorY = this.feetY;
            int[] envelope = envelopeOffsets();
            int[] candidates = candidateOffsets(envelope);
            // 長さ 8 の線がまたぐマスは高々 17。念のための上限。
            for (int guard = 0; guard < 64; guard++) {
                double t;
                if (tMaxX < tMaxZ) {
                    t = tMaxX;
                    cx += stepX;
                    tMaxX += tDeltaX;
                } else {
                    t = tMaxZ;
                    cz += stepZ;
                    tMaxZ += tDeltaZ;
                }
                if (t > this.distance) {
                    return null;
                }
                if (goal != null && cx == goal.getX() && cz == goal.getZ()) {
                    return null; // 行き先の列に着いた(その手前に壁は無かった)
                }
                int next = passable(level, cx, cz, floorY, envelope);
                if (next == STOP) {
                    return null;
                }
                if (next != BLOCKED) {
                    floorY = next;
                    continue;
                }
                // 塞がれたマスの断面で最初の壊してよい塊。無ければ(岩盤・溶岩の壁)ここでやめる —— その奥の塊には誰も届かない。
                BlockPos first = firstBreakable(level, cx, cz, floorY, candidates, target, skipped);
                if (first == null) {
                    return null;
                }
                List<BlockPos> cells = sectionCells(level, cx, cz, floorY, envelope, target, skipped);
                if (!cells.contains(first)) {
                    cells.clear();
                    cells.add(first);
                }
                return new Hole(first, cells);
            }
            return null;
        }

        /** 塞がれたマスの断面(包絡の列 × 段)の、壊してよい塊を全部(下の段から)。 */
        private List<BlockPos> sectionCells(ServerLevel level, int cx, int cz, int floorY, int[] envelope,
                                            @Nullable BlockPos target, Predicate<BlockPos> skipped) {
            List<BlockPos> cells = new ArrayList<>(envelope.length * this.height);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int row = 0; row < this.height; row++) {
                for (int off : envelope) {
                    column(pos, cx, cz, off, floorY + row);
                    BlockState state = loadedState(level, pos);
                    if (state == null) {
                        continue;
                    }
                    VoxelShape shape = state.getCollisionShape(level, pos);
                    if (shape.isEmpty() || (row == 0 && shape.max(Direction.Axis.Y) <= STEP_HEIGHT)) {
                        continue;
                    }
                    BlockPos found = pos.immutable();
                    if (!skipped.test(found) && breakable(level, found, state, target)) {
                        cells.add(found);
                    }
                }
            }
            return cells;
        }

        /**
         * そのマスを包絡が通れるか。通れるなら次の足の段、塞がれていれば {@link #BLOCKED}、たどれない
         * (読み込まれていない・深い穴)なら {@link #STOP}。
         */
        private int passable(ServerLevel level, int cx, int cz, int floorY, int[] envelope) {
            boolean any = false;
            boolean climbable = true;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int off : envelope) {
                for (int row = 0; row < this.height; row++) {
                    column(pos, cx, cz, off, floorY + row);
                    BlockState state = loadedState(level, pos);
                    if (state == null) {
                        return STOP;
                    }
                    VoxelShape shape = state.getCollisionShape(level, pos);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    double top = shape.max(Direction.Axis.Y);
                    if (row == 0 && top <= STEP_HEIGHT) {
                        continue; // カーペット・下付きハーフ・雪は踏み越える
                    }
                    any = true;
                    if (row > 0 || top > 1.0D) {
                        climbable = false; // 柵・塀(1.5)・頭の高さの塊は登れない
                    }
                }
            }
            if (!any) {
                // 通れる。足元が抜けていれば下りる(深ければやめる)。
                int y = floorY;
                for (int drop = 0; ; drop++) {
                    column(pos, cx, cz, 0, y - 1);
                    BlockState below = loadedState(level, pos);
                    if (below == null) {
                        return STOP;
                    }
                    if (!below.getCollisionShape(level, pos).isEmpty()) {
                        return y;
                    }
                    if (drop >= MAX_DROP) {
                        return STOP;
                    }
                    y--;
                }
            }
            if (climbable) {
                // 足元の段にだけ塊がある = 1 段の段差。上に包絡ぶんの空きがあれば登って進む。
                for (int off : envelope) {
                    column(pos, cx, cz, off, floorY + this.height);
                    BlockState state = loadedState(level, pos);
                    if (state == null) {
                        return STOP;
                    }
                    if (!state.getCollisionShape(level, pos).isEmpty()) {
                        return BLOCKED;
                    }
                }
                return floorY + 1;
            }
            return BLOCKED;
        }

        /** 塞がれたマスの断面で、最初の壊してよい塊(包絡の列 → 横の列、それぞれ下の段から)。 */
        @Nullable
        private BlockPos firstBreakable(ServerLevel level, int cx, int cz, int floorY, int[] candidates,
                                        @Nullable BlockPos target, Predicate<BlockPos> skipped) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int off : candidates) {
                for (int row = 0; row < this.height; row++) {
                    column(pos, cx, cz, off, floorY + row);
                    BlockState state = loadedState(level, pos);
                    if (state == null) {
                        continue;
                    }
                    VoxelShape shape = state.getCollisionShape(level, pos);
                    if (shape.isEmpty() || (row == 0 && shape.max(Direction.Axis.Y) <= STEP_HEIGHT)) {
                        continue;
                    }
                    BlockPos found = pos.immutable();
                    if (!skipped.test(found) && breakable(level, found, state, target)) {
                        return found;
                    }
                }
            }
            return null;
        }

        private void column(BlockPos.MutableBlockPos pos, int cx, int cz, int off, int y) {
            if (this.alongX) {
                pos.set(cx, y, cz + off);
            } else {
                pos.set(cx + off, y, cz);
            }
        }

        /** 包絡の断面の列(線からのずれ)。幅 1: 線の上、幅 2: 先導役の中心がある側へ 1 列、幅 3: 両側、幅 4: 両側 + 中心の側へもう 1 列。 */
        private int[] envelopeOffsets() {
            return switch (this.width) {
                case 1 -> new int[]{0};
                case 2 -> new int[]{0, this.side};
                case 3 -> new int[]{0, -1, 1};
                default -> new int[]{0, -1, 1, 2 * this.side};
            };
        }

        /** 包絡の列のあとに、横へ {@value #SIDESTEP} 列まで(近い順、同じ近さなら先導役の側から)。 */
        private int[] candidateOffsets(int[] envelope) {
            int min = 0;
            int max = 0;
            for (int off : envelope) {
                min = Math.min(min, off);
                max = Math.max(max, off);
            }
            List<Integer> extra = new ArrayList<>(SIDESTEP * 2);
            for (int k = 1; k <= SIDESTEP; k++) {
                extra.add(max + k);
                extra.add(min - k);
            }
            extra.sort((a, b) -> {
                int c = Integer.compare(Math.abs(a), Math.abs(b));
                return c != 0 ? c : Integer.compare(Integer.signum(b) == this.side ? 1 : 0, Integer.signum(a) == this.side ? 1 : 0);
            });
            int[] all = new int[envelope.length + extra.size()];
            System.arraycopy(envelope, 0, all, 0, envelope.length);
            for (int i = 0; i < extra.size(); i++) {
                all[envelope.length + i] = extra.get(i);
            }
            return all;
        }
    }

    /**
     * 足の段より下に埋まった塊(床の下のターゲット・地下室・床に埋めた灯り)へ<b>上から掘る縦穴</b>(列の四角
     * {@code minX..maxX} × {@code minZ..maxZ} を {@code topY} から {@code bottomY} まで、上の段から)。レビュー指摘 2026-09-25:
     * 水平の DDA だけだったので、ターゲットの真上に着くと「壁は無い」と答え、床の 1 段下のターゲットにはどの攻め手も届かなかった。
     *
     * <p><b>幅</b>: 群れは {@value #SWARM_HOLE_CELLS} × {@value #SWARM_HOLE_CELLS}(横の穴と同じ)、群れの外の攻め手は自分の体の幅、
     * 灯りは 1 マス。2026-09-25 のユーザー報告(runClient 3 回目)「ターゲットブロックを囲むと、攻めてくる mob が上に乗った状態で
     * 何もしなくなる。一部のアルボラがターゲットの真上のブロックは壊すが 1 マスの隙間に入れず、ターゲットを攻撃できない」:
     * はじめは 1 マス幅(床をむやみに崩さない。仮決め)で、アルボラ(幅 1 = 経路では 2 マス)も入れなかった。
     * いまは縁から手を伸ばしても届く({@link Swarm#BLOCK_REACH_DOWN_OPEN})うえ、深い縦穴にも大きい体が降りられる。
     *
     * <p><b>要の列</b>({@code keyX}, {@code keyZ}): 掘れること({@link #diggable})を求めるのはこの 1 列だけ(真上の列ならターゲットの
     * 真上)。まわりの列の掘れない塊(岩盤・溶岩に面した塊・灯り)は残して掘り進む。各段は要の列から崩す。
     */
    record Column(int keyX, int keyZ, int minX, int maxX, int minZ, int maxZ, int topY, int bottomY) {

        /**
         * {@code goal} が足の段({@code from})より 2 段以上下 = 足元の 1 段({@link Swarm#BLOCK_REACH_DOWN})より深く、掘らないと届かない。
         * 境目(塊の上面 = 足 − 届く深さ ちょうど)は「届かない」側に入れる: 手の届く箱の重なり({@link Swarm#canReachBlock} の
         * {@code AABB#intersects})は面が触れただけでは重ならないので、ここで「届く」と答えると誰も掘らず誰も叩けない
         * (はじめは許しを逆向きに引いていて、床の 2 段下のターゲットを掘らなかった。GameTest coverScanDigsDownToSunkenTarget、2026-09-25)。
         * 真上が空いていれば縁から 2 段下まで届くが、そのときは縦穴に掘る塊が無い({@link #findHole} が null)。
         */
        static boolean below(Vec3 from, BlockPos goal) {
            return goal.getY() + 1.0D <= from.y - Swarm.BLOCK_REACH_DOWN + 1.0E-3D;
        }

        /** 1 マス幅の {@link #toward(ServerLevel, Vec3, BlockPos, BlockPos, int)}。 */
        @Nullable
        static Column toward(ServerLevel level, Vec3 from, BlockPos goal, @Nullable BlockPos target) {
            return toward(level, from, goal, target, 1);
        }

        /**
         * {@code goal} へ掘る幅 {@code width} マスの縦穴。まず真上(足の段 〜 goal の 1 つ上。要の列は goal の真上)。要の列に
         * 壊せない塊(ターゲット・岩盤・溶岩に面した塊)があれば、goal の横 4 方向の縦穴(足の段 〜 goal と同じ段。要の列は goal の
         * 隣で、四角はそこから外へ広がる = 底に立てば goal が横にある)を先導役に近い順に。どれも掘れなければ null。
         */
        @Nullable
        static Column toward(ServerLevel level, Vec3 from, BlockPos goal, @Nullable BlockPos target, int width) {
            int w = Math.max(1, width);
            int lo = -(w - 1) / 2;
            int hi = lo + w - 1;
            int top = Mth.floor(from.y + 1.0E-3D);
            int gx = goal.getX();
            int gz = goal.getZ();
            Column above = new Column(gx, gz, gx + lo, gx + hi, gz + lo, gz + hi, top, goal.getY() + 1);
            if (above.diggable(level, target)) {
                return above;
            }
            List<Column> sides = new ArrayList<>(4);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                int sx = direction.getStepX();
                int sz = direction.getStepZ();
                int kx = gx + sx;
                int kz = gz + sz;
                // 向きの側へ 1〜w 列、横は goal を挟んで lo〜hi。
                int minX = sx == 0 ? gx + lo : Math.min(gx + sx, gx + sx * w);
                int maxX = sx == 0 ? gx + hi : Math.max(gx + sx, gx + sx * w);
                int minZ = sz == 0 ? gz + lo : Math.min(gz + sz, gz + sz * w);
                int maxZ = sz == 0 ? gz + hi : Math.max(gz + sz, gz + sz * w);
                sides.add(new Column(kx, kz, minX, maxX, minZ, maxZ, top, goal.getY()));
            }
            sides.sort(Comparator.comparingDouble(c -> {
                double dx = c.keyX + 0.5D - from.x;
                double dz = c.keyZ + 0.5D - from.z;
                return dx * dx + dz * dz;
            }));
            for (Column c : sides) {
                if (c.diggable(level, target)) {
                    return c;
                }
            }
            return null;
        }

        /** 要の列の当たりのある塊が全部壊してよい(読み込まれていないマスがあれば false)。 */
        boolean diggable(ServerLevel level, @Nullable BlockPos target) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = this.topY; y >= this.bottomY; y--) {
                pos.set(this.keyX, y, this.keyZ);
                BlockState state = loadedState(level, pos);
                if (state == null) {
                    return false;
                }
                if (!state.getCollisionShape(level, pos).isEmpty() && !breakable(level, pos, state, target)) {
                    return false;
                }
            }
            return true;
        }

        /** 上から最初の掘る塊({@link #findHole} の最初の塊)。無ければ null。 */
        @Nullable
        BlockPos find(ServerLevel level, @Nullable BlockPos target, Predicate<BlockPos> skipped) {
            Hole hole = findHole(level, target, skipped);
            return hole != null ? hole.first() : null;
        }

        /**
         * 上から最初の、まだ掘る塊が残っている段: その段の壊してよい塊の全部(要の列を先に、ほかは要の列に近い順)。
         * 要の列の塊が壊せない・選ばない印付きなら null(この縦穴はそこで行き止まり)。まわりの列の壊せない塊は飛ばす。
         * 全部空いていれば null(もう掘れている)。読み込まれていないマスがあれば null。
         */
        @Nullable
        Hole findHole(ServerLevel level, @Nullable BlockPos target, Predicate<BlockPos> skipped) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = this.topY; y >= this.bottomY; y--) {
                BlockPos key = null;
                pos.set(this.keyX, y, this.keyZ);
                BlockState keyState = loadedState(level, pos);
                if (keyState == null) {
                    return null;
                }
                if (!keyState.getCollisionShape(level, pos).isEmpty()) {
                    key = pos.immutable();
                    if (skipped.test(key) || !breakable(level, key, keyState, target)) {
                        return null;
                    }
                }
                List<BlockPos> cells = new ArrayList<>();
                for (int x = this.minX; x <= this.maxX; x++) {
                    for (int z = this.minZ; z <= this.maxZ; z++) {
                        if (x == this.keyX && z == this.keyZ) {
                            continue;
                        }
                        pos.set(x, y, z);
                        BlockState state = loadedState(level, pos);
                        if (state == null) {
                            return null;
                        }
                        if (state.getCollisionShape(level, pos).isEmpty()) {
                            continue;
                        }
                        BlockPos cell = pos.immutable();
                        if (!skipped.test(cell) && breakable(level, cell, state, target)) {
                            cells.add(cell);
                        }
                    }
                }
                if (key == null && cells.isEmpty()) {
                    continue;
                }
                int ky = y;
                cells.sort(Comparator.comparingDouble(c -> c.distSqr(new BlockPos(this.keyX, ky, this.keyZ))));
                if (key != null) {
                    cells.add(0, key);
                }
                return new Hole(cells.get(0), cells);
            }
            return null;
        }
    }

    /**
     * 壊してよい塊か。TD 設計 v2 に、レビュー指摘 2026-09-25 の手直し(※)を入れたもの:
     * <ul>
     *     <li>壊せる(硬さ ≥ 0。岩盤・バリア・エンドポータルの枠・スポナー類は名指しでも外す)。<b>侵攻のターゲット</b>は壊さない
     *     (削るのは耐久)。忌避灯は壊さない(覆う。別の口)。</li>
     *     <li>ブロックエンティティ付きの塊(チェスト・樽・かまど・シュルカーボックス・看板・旗・ベッド・予備のターゲットブロック・
     *     計測機など)も<b>中身があっても壊す</b>(ユーザー指定 2026-09-25「TD では樽やチェストなどのプレイヤーの持ち物も容赦なく
     *     破壊していく」)。壊した塊は落とし、中身は散らばる({@link #strikeBreach}。中身を消さないのは仮決め 2026-09-25)。
     *     それまでは中身を持つものだけ壊さず(アルボラの坑道と同じ決まり)、チェストや樽で 1 枚囲うだけでターゲットが無敵になっていた。
     *     坑道(TD の外)は今も中身を持つものを壊さない。</li>
     *     <li>溶岩(水以外の液体)を含まず、面してもいない(壊した瞬間に拠点・通り道が燃える)。※ 水は含んでいても面していてもよい
     *     (水浸しの半ブロック・壁の外の堀・2 枚の壁の間の水)。はじめは水も避けていたので、壁の外に水を張るか、内側を水浸しの
     *     半ブロックで裏打ちするだけで壁が壊れなくなり、ユーザー指定(2026-09-24)「囲った拠点は壁を壊して入る」が効かなかった。
     *     壊した穴から水が流れ込むのは攻められた結果として受け入れる(仮決め 2026-09-25、ユーザー確認待ち)。</li>
     * </ul>
     * 読み込まれていないチャンクは読まない(読み込んでしまう)。
     */
    static boolean breakable(ServerLevel level, BlockPos pos, BlockState state, @Nullable BlockPos target) {
        if (state.isAir() || level.isOutsideBuildHeight(pos)) {
            return false;
        }
        if (pos.equals(target) || state.getBlock() instanceof RepellentLampBlock) {
            return false;
        }
        if (state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER) || state.is(Blocks.END_PORTAL_FRAME)
                || state.is(Blocks.SPAWNER) || state.is(Blocks.TRIAL_SPAWNER) || state.is(Blocks.VAULT)) {
            return false; // スポナー類はプレイヤーが作れない(壊すと二度と戻らない)
        }
        if (state.getDestroySpeed(level, pos) < 0.0F) {
            return false;
        }
        LevelChunk chunk = chunkAt(level, pos);
        if (chunk == null) {
            return false;
        }
        if (state.hasBlockEntity() && chunk.getBlockEntity(pos) == null) {
            return false; // ブロックエンティティがまだ無い(置かれた直後・壊れた状態)。次に選ぶときに見直す
        }
        if (!waterOrEmpty(state.getFluidState())) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            FluidState fluid = loadedFluid(level, pos.relative(direction));
            if (fluid == null || !waterOrEmpty(fluid)) {
                return false; // 読み込まれていない隣は分からないので壊さない
            }
        }
        return true;
    }

    private static boolean waterOrEmpty(FluidState fluid) {
        return fluid.isEmpty() || fluid.is(FluidTags.WATER);
    }

    // ------------------------------------------------------------------ 忌避灯

    /**
     * 先導役のターゲットへの寄り道を塞いでいる、拠点のそばの点いた忌避灯を覆いに行く BREACH。灯りは近い順に<b>全部</b>見る:
     * <ul>
     *     <li>先導役が灯りの半径 + {@value #LAMP_NOTICE_MARGIN} 以内にいる(押し返されている・もうすぐ押し返される)。</li>
     *     <li>灯りがターゲットから {@value #LAMP_BASE_RANGE} 以内(拠点のそば。遠くの灯りのために寄り道しない)。</li>
     *     <li>灯りの半径が、先導役 → ターゲットの線にかかる(ターゲットが半径の中なら必ずかかる)。</li>
     * </ul>
     * 当てはまった灯りへ、先導役の足から水平の DDA(長さは灯りまで、半径 + 余りまで)で壁を探し、あればその壁を壊す
     * (ガラス板・柵・葉・拠点の壁の内側に置いた灯り)。無くて灯りが足の段より 2 段以上下(床に埋めた・ターゲットの下)なら
     * 上から掘る({@link Column})。掘れなければ次の灯りへ。どちらでもなければ灯りそのもの(覆う)。
     *
     * <p>レビュー指摘 2026-09-25: はじめは「いちばん近い灯り 1 つ」で「先導役の目から灯りまで塊が無い」ときだけ選んでいたが、
     * 押し返しは視線を見ないので、床の下・ターゲットの下・ガラスの向こうの灯りは攻め手を押し返すのに一度も覆われず、
     * 見えない囮の灯りを近くに置けば本当に塞いでいる灯りも無視された。壁の内側 1〜3 ブロックの灯りは先導役を壁から 9 ブロック以上
     * 押し返すので、長さ 8 の壁探しにも掛からなかった。
     */
    @Nullable
    private static Breach lampBreach(ServerLevel level, Mob vanguard, Swarm swarm, BlockPos target,
                                     Predicate<BlockPos> skipped, long now) {
        return lampBreach(level, vanguard, blocks(envelopeWidth(swarm, vanguard)), blocks(envelopeHeight(swarm, vanguard)),
                target, skipped, now);
    }

    /** {@link #lampBreach(ServerLevel, Mob, Swarm, BlockPos, Predicate, long)} の、包絡をマス数で渡す形。 */
    @Nullable
    private static Breach lampBreach(ServerLevel level, Mob vanguard, int width, int height, BlockPos target,
                                     Predicate<BlockPos> skipped, long now) {
        double radius = DesignData.defense().lampRadius();
        Vec3 from = vanguard.position();
        List<BlockPos> lamps = RepellentLamps.activeWithin(level, from, radius + LAMP_NOTICE_MARGIN);
        if (lamps.isEmpty()) {
            return null;
        }
        lamps.sort(Comparator.comparingDouble(p -> from.distanceToSqr(Vec3.atCenterOf(p))));
        Vec3 targetCentre = Vec3.atCenterOf(target);
        double reach = radius + LAMP_PATH_SLACK;
        for (BlockPos lamp : lamps) {
            if (skipped.test(lamp)) {
                continue;
            }
            Vec3 lampCentre = Vec3.atCenterOf(lamp);
            if (lampCentre.distanceToSqr(targetCentre) > LAMP_BASE_RANGE * LAMP_BASE_RANGE
                    || segmentDistanceSqr(lampCentre, from, targetCentre) > reach * reach) {
                continue;
            }
            Scan scan = Scan.of(from, lamp, width, height, radius + LAMP_NOTICE_MARGIN + LAMP_SCAN_EXTRA);
            BlockPos wall = scan != null ? scan.find(level, lamp, target, skipped) : null;
            if (wall != null) {
                return new Breach(wall, now, scan, null, lamp);
            }
            if (Column.below(from, lamp)) {
                Column column = Column.toward(level, from, lamp, target);
                if (column == null) {
                    continue; // 掘れない(岩盤・ターゲットの下で、横も掘れない)
                }
                BlockPos cover = column.find(level, target, skipped);
                if (cover != null) {
                    return new Breach(cover, now, null, column, lamp);
                }
                // 縦穴はもう空いている。灯りを覆いに降りる。
            }
            return new Breach(lamp, now, null, null, lamp);
        }
        return null;
    }

    /**
     * テスト・調べもの用: 先導役 {@code vanguard}(群れに入っていなくてよい。包絡は幅 {@code width} × 高さ {@code height} マス)が
     * {@code target} を攻めるとき、灯りのために選ぶ塊(灯りまでの壁・掘る塊・灯りそのもの)。侵攻の状態・選ばない印は見ない。
     */
    @Nullable
    public static BlockPos lampBreachFor(Mob vanguard, BlockPos target, int width, int height) {
        if (!(vanguard.level() instanceof ServerLevel level)) {
            return null;
        }
        Breach found = lampBreach(level, vanguard, width, height, target, pos -> false, level.getGameTime());
        return found != null ? found.pos : null;
    }

    /** 水平の距離の 2 乗。 */
    private static double horizontalSq(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    /** 点 {@code p} から線分 {@code a}–{@code b} までの距離の 2 乗。 */
    private static double segmentDistanceSqr(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double len2 = ab.lengthSqr();
        if (len2 < 1.0E-9D) {
            return p.distanceToSqr(a);
        }
        double t = Mth.clamp(p.subtract(a).dot(ab) / len2, 0.0D, 1.0D);
        return p.distanceToSqr(a.add(ab.scale(t)));
    }

    // ------------------------------------------------------------------ 叩く

    /**
     * {@link SwarmSiegeHooks.BlockStrike}。塊の輪で手が届く前衛が 20 tick に 1 回呼ぶ。
     * 群れのターゲットが今の侵攻のものでない間は削らない。増援の一時停止中でも削る(攻め手が動いている = 拠点がシミュレーションされて
     * いる。クラスの説明「一時停止は見ない」。はじめは TD 設計で「一時停止中は削らない」としていた)。
     *
     * @return 当てた(攻撃アニメを付ける)
     */
    static boolean strike(Mob member, Swarm swarm, BlockPos block, boolean breach) {
        if (!(member.level() instanceof ServerLevel level) || !member.isAlive()) {
            return false;
        }
        BlockPos target = swarm.targetBlock();
        if (!TdEvents.isSiegeLive(level, target)) {
            return false;
        }
        // ターゲットの耐久は役割ごとの量(siegeDamage)、壁・灯りは壁の量(wallDamage。2026-09-25 のユーザー指定で全員が壊す)。
        float amount = breach ? wallDamage(member) : siegeDamage(member, swarm);
        if (!(amount > 0.0F)) {
            return false; // 削らない役(ターゲット: フィロセンスはプレイヤー・砲台をビームで撃つ。壁: 空の群れ)
        }
        if (!breach) {
            if (!block.equals(target)) {
                return false;
            }
            TargetBlockEntity entity = TdTargets.get(level, target);
            if (entity == null) {
                return false;
            }
            // 着いたのに叩けない見張り(watchSiege)の時計を戻す。敗北の一撃のあとは群れの攻城が外れるので残っても使われない。
            state(level).siegeSince.put(swarm.id(), level.getGameTime());
            // 耐久が 0 になった一撃なら、damage の中で TdEvents.onTargetDestroyed が呼ばれている(敗北)。
            entity.damage(amount, member);
            return true;
        }
        return strikeBreach(level, member, swarm, block, target, amount);
    }

    /**
     * TD の護衛(群れに入らない G5〜G9。{@link TdAssaultGoal})が、手の届くターゲットブロックを 1 回叩く(2026-09-25)。
     * 量は攻撃力の属性。侵攻中のターゲットでなければ・攻撃力を持たなければ叩かない。壁は {@link #strikeWall}。
     *
     * @return 当てた(攻撃アニメを付ける)
     */
    static boolean strikeIndividual(Mob mob, BlockPos target) {
        if (!(mob.level() instanceof ServerLevel level) || !mob.isAlive() || !TdEvents.isSiegeLive(level, target)) {
            return false;
        }
        float amount = TdAssaultGoal.strikeAmount(mob);
        if (!(amount > 0.0F)) {
            return false;
        }
        TargetBlockEntity entity = TdTargets.get(level, target);
        if (entity == null) {
            return false;
        }
        entity.damage(amount, mob);
        return true;
    }

    /**
     * 塊を 1 回叩く量。TD 設計 v2 の siegeDamage: 前衛(アルボラ・スカトゥリウム)は攻撃力の属性(亜種・適応の補正込み)、
     * 支援(スポロフィル)・偵察(フィロセンス)は td.json の値(仮決め 2 / 0)。属性を持たない Mob で落ちないよう
     * {@code hasAttribute} で確かめる(持っていなければ支援の値)。空の群れ(ヴェロフィル)は塊を叩かない。
     */
    static float siegeDamage(Mob member, Swarm swarm) {
        SwarmRole role = swarm.role(member);
        if (role == null) {
            role = SwarmRole.of(member);
        }
        if (role == null) {
            return 0.0F;
        }
        // ワールドの難易度「敵の強さ」はターゲットを削る量にも掛かる(2026-09-25 ユーザー指定)。
        return WorldDifficultyData.scaleEnemyStrike(member, switch (role) {
            case FRONT -> member.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)
                    ? (float) member.getAttributeValue(Attributes.ATTACK_DAMAGE)
                    : DesignData.td().siegeDamageSupport();
            case SUPPORT -> DesignData.td().siegeDamageSupport();
            case RANGED_SCOUT -> DesignData.td().siegeDamageRangedScout();
            case AIR -> 0.0F;
        });
    }

    /** 壊す壁(または覆う灯り)を 1 回叩く。 */
    private static boolean strikeBreach(ServerLevel level, Mob member, Swarm swarm, BlockPos pos, BlockPos target,
                                        float amount) {
        LevelChunk chunk = chunkAt(level, pos);
        if (chunk == null) {
            return false;
        }
        long now = level.getGameTime();
        LevelState st = state(level);
        BlockState state = chunk.getBlockState(pos);
        Breach record = st.breaches.get(swarm.id());
        if (record == null || !record.pos.equals(pos)) {
            record = new Breach(pos, now, null, null, null);
            st.breaches.put(swarm.id(), record);
        }

        if (state.getBlock() instanceof RepellentLampBlock lampBlock) {
            if (!RepellentLampBlock.isActive(state)) {
                finishBreach(level, st, swarm, pos); // もう覆われている(ヴェロフィルの爆弾・ほかの群れ)
                return false;
            }
            Progress progress = st.progress.computeIfAbsent(pos.immutable(), k -> new Progress(true));
            progress.done += amount;
            record.lastProgress = now;
            level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, pos.getX() + 0.5D, pos.getY() + 0.6D, pos.getZ() + 0.5D,
                    6, 0.35D, 0.35D, 0.35D, 0.0D);
            if (now - progress.lastSoundTick >= SOUND_INTERVAL_TICKS) {
                progress.lastSoundTick = now;
                level.playSound(null, pos, SoundEvents.SPORE_BLOSSOM_HIT, SoundSource.HOSTILE, 0.8F, 0.7F);
            }
            if (progress.done >= required(level, pos, state)) {
                // 壊さずに覆う(既存の覆い。台帳から外れ、プレイヤーは右クリックで剥がせる)。仮決め 2026-09-25。
                lampBlock.setCovered(level, pos, state, true);
                finishBreach(level, st, swarm, pos);
            }
            return true;
        }

        // 壁。穴の断面(record.cells)を塊の輪の前衛で手分けして崩す: 叩くのは、選んだ壁と断面の残りのうち自分の手が届くいちばん近い塊。
        // 2026-09-25: ユーザー指定「G5 なども通れる大きな穴」で群れの穴が 3 × 3 になったので、全員で 1 マスずつ叩くと 9 倍かかる。
        boolean anchorSolid = solidBreakable(level, pos, state, target);
        BlockPos hit = null;
        BlockState hitState = null;
        double bestSq = Double.MAX_VALUE;
        if (anchorSolid && Swarm.canReachBlock(member, pos)) {
            hit = pos;
            hitState = state;
            bestSq = member.distanceToSqr(Vec3.atCenterOf(pos));
        }
        for (Iterator<BlockPos> it = record.cells.iterator(); it.hasNext(); ) {
            BlockPos cell = it.next();
            if (cell.equals(pos)) {
                continue;
            }
            BlockState cellState = loadedState(level, cell);
            if (cellState == null) {
                continue;
            }
            if (!solidBreakable(level, cell, cellState, target)) {
                it.remove(); // 崩れた・別の物に替わった
                continue;
            }
            if (!Swarm.canReachBlock(member, cell)) {
                continue;
            }
            double d = member.distanceToSqr(Vec3.atCenterOf(cell));
            if (d < bestSq) {
                bestSq = d;
                hit = cell;
                hitState = cellState;
            }
        }
        if (hit == null) {
            if (!anchorSolid) {
                // 選んだ壁が無くなった(誰かが壊した・別の物に替わった・水が来た)。断面の残りへ移るか、攻城へ戻して区間を引き直させる。
                record.cells.remove(pos);
                advanceBreach(level, st, swarm, record, target, pos, now);
                return false;
            }
            hit = pos; // 囲む Goal は選んだ壁に手が届くと見ている
            hitState = state;
        }
        record.lastProgress = now;
        if (damageWall(level, st, member, hit, hitState, amount, now)) {
            record.cells.remove(hit);
            BlockState anchorNow = loadedState(level, pos);
            if (hit.equals(pos) || anchorNow == null || !solidBreakable(level, pos, anchorNow, target)) {
                advanceBreach(level, st, swarm, record, target, pos, now);
            }
        }
        return true;
    }

    /**
     * 選んだ壁が崩れた・無くなった。穴の断面にまだ塊が残っていれば、残りのうち {@code old} にいちばん近い塊へ輪を移す。
     * 断面を崩し終えたら、同じ線・同じ列の隣がまだ塞いでいれば続けて壊す(区間 2 回ぶん待たずに、群れが通れる大きさの穴・縦穴まで)。
     * どちらも無ければ攻城へ戻す。
     */
    private static void advanceBreach(ServerLevel level, LevelState st, Swarm swarm, Breach record, BlockPos target,
                                      BlockPos old, long now) {
        if (shaftReachesTarget(swarm, record, target)) {
            st.breaches.remove(swarm.id());
            swarm.endBreach();
            return;
        }
        BlockPos next = null;
        double bestSq = Double.MAX_VALUE;
        for (Iterator<BlockPos> it = record.cells.iterator(); it.hasNext(); ) {
            BlockPos cell = it.next();
            BlockState cellState = loadedState(level, cell);
            if (cellState == null || !solidBreakable(level, cell, cellState, target)) {
                it.remove();
                continue;
            }
            double d = cell.distSqr(old);
            if (d < bestSq) {
                bestSq = d;
                next = cell;
            }
        }
        if (next == null) {
            UUID id = swarm.id();
            Predicate<BlockPos> skipped = p -> st.skipped(id, p, now);
            if (record.scan != null) {
                Hole hole = record.scan.findHole(level, record.goal, target, skipped);
                if (hole != null && Math.abs(hole.first().getX() - old.getX()) <= CHAIN_REACH
                        && Math.abs(hole.first().getZ() - old.getZ()) <= CHAIN_REACH) {
                    next = hole.first();
                    record.cells = new ArrayList<>(hole.cells());
                }
            } else if (record.column != null) {
                // 縦穴の次の段(その段の塊を全部、手分けして崩す)。
                Hole shaft = record.column.findHole(level, target, skipped);
                if (shaft != null) {
                    next = shaft.first();
                    record.cells = new ArrayList<>(shaft.cells());
                }
            }
        }
        if (next != null) {
            record.pos = next;
            record.lastProgress = now;
            swarm.setBreach(next);
            return;
        }
        st.breaches.remove(swarm.id());
        swarm.endBreach();
    }

    /**
     * ターゲットへ上から掘る縦穴で、先導役の手がもうターゲットに届くか(真上が空き、縁から手を伸ばせば届く。
     * {@link Swarm#BLOCK_REACH_DOWN_OPEN})。届くなら縦穴の残りを掘るより先に叩かせる(攻城へ戻す。先導役は穴の縁で「着いた」になり、
     * 塊の輪の一員は縁から叩く)。2026-09-25 のユーザー報告「囲ったターゲットの上に乗ったまま何もしない」。黒曜石の屋根なら
     * 3 × 3 を崩し終えるまで 9 倍かかるので、真上の 1 マスが空いた時点で叩き始める。仮決め。
     */
    private static boolean shaftReachesTarget(Swarm swarm, Breach record, @Nullable BlockPos target) {
        if (record.column == null || target == null || !target.equals(record.goal)) {
            return false;
        }
        Mob vanguard = swarm.vanguard();
        return vanguard != null && vanguard.isAlive() && Swarm.canReachBlock(vanguard, target);
    }

    /** 当たりを持っていて(鉄線は当たりが無くても)、壊してよい塊か。 */
    private static boolean solidBreakable(ServerLevel level, BlockPos pos, BlockState state, @Nullable BlockPos target) {
        boolean wire = state.getBlock() instanceof BarbedWireBlock;
        return (wire || !state.getCollisionShape(level, pos).isEmpty()) && breakable(level, pos, state, target);
    }

    /**
     * 壁の座標の削れ具合に {@code amount} を足す(ひび・叩く音)。要る量に届いたら壊す。
     * ふつうの塊は落とさない(TD 設計 v2、仮決め 2026-09-25。何百体が壁を崩すたびにドロップの山ができないように)。
     * ブロックエンティティ付きの塊(チェスト・樽・看板・旗・ベッド・予備のターゲットなど)はプレイヤーの持ち物なので落とす
     * (失わせない。仮決め 2026-09-25)。入れ物の中身はブロックの onRemove が散らばらせる(チェスト・樽・かまど …。
     * シュルカーボックスは中身ごと 1 個で落ちる)。
     *
     * @return 壊れた
     */
    private static boolean damageWall(ServerLevel level, LevelState st, Mob mob, BlockPos pos, BlockState state, float amount,
                                      long now) {
        Progress progress = st.progress.computeIfAbsent(pos.immutable(), k -> new Progress(false));
        progress.done += amount;
        progress.lastStrikeTick = now;
        double required = required(level, pos, state);
        if (progress.done < required) {
            int stage = Mth.clamp((int) Math.floor(progress.done / required * 10.0D), 0, 9);
            if (stage != progress.lastStage || now - progress.lastCrackTick >= CRACK_RESEND_TICKS) {
                level.destroyBlockProgress(crackId(pos), pos, stage);
                progress.lastStage = stage;
                progress.lastCrackTick = now;
            }
            if (now - progress.lastSoundTick >= SOUND_INTERVAL_TICKS) {
                progress.lastSoundTick = now;
                level.playSound(null, pos, state.getSoundType(level, pos, mob).getHitSound(), SoundSource.HOSTILE,
                        0.9F, 0.6F);
            }
            return false;
        }
        boolean drop = state.hasBlockEntity();
        st.progress.remove(pos);
        level.destroyBlockProgress(crackId(pos), pos, -1);
        level.destroyBlock(pos, drop, mob);
        return true;
    }

    // ------------------------------------------------------------------ 群れの外の攻め手・鉄線(2026-09-25)

    /**
     * 壁(と灯り)を 1 回叩く量。ユーザー指定 2026-09-25「TD 時のみ、すべての mob がブロックを破壊できるように。アルボラ以外の
     * 通常時はブロック破壊をしない mob は、アルボラよりもブロック破壊に 5 倍の時間がかかる」:
     * <ul>
     *     <li>アルボラ … 攻撃力の属性(亜種・適応・力の補正込み。今までの前衛と同じ)。</li>
     *     <li>それ以外 … アルボラの攻撃力の基礎値(mobs.json)÷ {@code otherBreakTimeMultiplier}(td.json、ユーザー指定 5)。
     *     それまでは前衛(スカトゥリウム)が自分の攻撃力、支援(スポロフィル)が 2、偵察(フィロセンス)は 0 で、護衛は壁を壊さなかった。</li>
     *     <li>空の群れ(ヴェロフィル)は叩かない。</li>
     * </ul>
     * ターゲットの耐久を削る量は今までどおり {@link #siegeDamage}。
     */
    static float wallDamage(Mob mob) {
        SwarmRole role = SwarmRole.of(mob);
        if (role == SwarmRole.AIR) {
            return 0.0F;
        }
        // ワールドの難易度「敵の強さ」は壁・鉄線・灯りを削る量にも掛かる(2026-09-25 ユーザー指定)。
        if (SwarmRole.isArbora(mob.getType())) {
            return WorldDifficultyData.scaleEnemyStrike(mob, mob.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)
                    ? (float) mob.getAttributeValue(Attributes.ATTACK_DAMAGE) : (float) arboraBaseAttack());
        }
        return WorldDifficultyData.scaleEnemyStrike(mob,
                (float) (arboraBaseAttack() / DesignData.td().otherBreakTimeMultiplier()));
    }

    /** アルボラの攻撃力の基礎値(mobs.json。無ければ 8)。 */
    static double arboraBaseAttack() {
        Double attack = DesignData.mobs().forId("arbora").attackDamage;
        return attack != null && attack > 0.0D ? attack : 8.0D;
    }

    /** 群れの外の攻め手の体の大きさ(ブロック)→ 穴のマス数(バニラの評価器と同じ {@code floor(大きさ + 1)}、1〜{@value #MAX_HOLE_CELLS})。 */
    private static int holeCells(float size) {
        return Mth.clamp(Mth.floor(size + 1.0F), 1, MAX_HOLE_CELLS);
    }

    /**
     * 群れの外の攻め手(護衛・群れから外れた一員)が塞がれたときに崩す穴(ユーザー指定 2026-09-25「体格の大きな mob は自身が通れる
     * サイズの大きな穴をあける」)。自分の足元から {@code goal} へ水平の DDA({@value #SCAN_DISTANCE} ブロックまで)、断面は自分の体の
     * 幅 × 高さ({@value #MAX_HOLE_CELLS} マスまで)。侵攻中でなければ・壁が無ければ null。
     */
    @Nullable
    static Hole digHole(Mob mob, BlockPos goal) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return null;
        }
        BlockPos target = TdEvents.activeTarget(level);
        if (target == null || !TdEvents.isSiegeLive(level, target)) {
            return null;
        }
        Scan scan = Scan.of(mob.position(), goal, holeCells(mob.getBbWidth()), holeCells(mob.getBbHeight()), SCAN_DISTANCE);
        Hole hole = scan != null ? scan.findHole(level, goal, target, pos -> false) : null;
        if (hole != null || !Column.below(mob.position(), goal)) {
            return hole;
        }
        // 足の段より 2 段以上下の goal(床の下・囲いの屋根の上から): 自分の体の幅の縦穴(2026-09-25、群れの縦穴と同じ手直し)。
        Column column = Column.toward(level, mob.position(), goal, target, holeCells(mob.getBbWidth()));
        return column != null ? column.findHole(level, target, pos -> false) : null;
    }

    /** まだ崩す壁か(当たりがあり壊してよい塊。侵攻中でなければ false)。群れの外の攻め手が穴の断面の残りを見る。 */
    static boolean isWall(Mob mob, BlockPos pos) {
        if (!(mob.level() instanceof ServerLevel level)) {
            return false;
        }
        BlockPos target = TdEvents.activeTarget(level);
        BlockState state = loadedState(level, pos);
        return target != null && state != null && TdEvents.isSiegeLive(level, target) && solidBreakable(level, pos, state, target);
    }

    /** {@link #strikeWall} の結果。 */
    enum WallStrike {
        /** 叩けない(侵攻中でない・もう無い・壊してはいけない塊・削る量が 0)。 */
        INVALID,
        /** 削った。 */
        DAMAGED,
        /** 壊れた。 */
        BROKEN
    }

    /** 群れの外の攻め手が壁を 1 回叩く({@link #wallDamage} の量。手が届くかは呼び出し側が見る)。 */
    static WallStrike strikeWall(Mob mob, BlockPos pos) {
        if (!(mob.level() instanceof ServerLevel level) || !mob.isAlive()) {
            return WallStrike.INVALID;
        }
        BlockPos target = TdEvents.activeTarget(level);
        if (target == null || !TdEvents.isSiegeLive(level, target)) {
            return WallStrike.INVALID;
        }
        BlockState state = loadedState(level, pos);
        float amount = wallDamage(mob);
        if (state == null || !(amount > 0.0F) || !solidBreakable(level, pos, state, target)) {
            return WallStrike.INVALID;
        }
        return damageWall(level, state(level), mob, pos, state, amount, level.getGameTime())
                ? WallStrike.BROKEN : WallStrike.DAMAGED;
    }

    /**
     * 有刺鉄線の中にいる攻め手が鉄線を切る(ユーザー指定 2026-09-25「アルボラが他の mob が通れるよう有刺鉄線を破壊するように」)。
     * {@code BarbedWireBlock#entityInside} から毎 tick 呼ばれる。侵攻中のイベントの印を持つ<b>アルボラだけ</b>、
     * {@link SwarmSiegeHooks#STRIKE_INTERVAL_TICKS} tick に 1 回、{@link #wallDamage} で叩く(鉄線の硬さ 2 → 20 削ると切れる。
     * アルボラの攻撃力 8 なら 3 回)。鉄線は当たりが無いので、経路は鉄線をふつうの地面として通る —— 踏み込んだアルボラが切り開いた跡を
     * 後ろが通る。
     */
    public static void onWireContact(ServerLevel level, Mob mob, BlockPos pos) {
        if (!SwarmRole.isArbora(mob.getType()) || !mob.isAlive()) {
            return;
        }
        String eventId = TdEvents.activeEventId(level);
        BlockPos target = TdEvents.activeTarget(level);
        if (eventId == null || target == null || !TdEvents.isSiegeLive(level, target)
                || !EventMobs.has(mob, EventMobs.Kind.TD, eventId)) {
            return;
        }
        LevelState st = state(level);
        long now = level.getGameTime();
        Long next = st.wireCooldown.get(mob.getId());
        if (next != null && now < next) {
            return;
        }
        st.wireCooldown.put(mob.getId(), now + SwarmSiegeHooks.STRIKE_INTERVAL_TICKS);
        BlockState state = level.getBlockState(pos);
        float amount = wallDamage(mob);
        if (!(state.getBlock() instanceof BarbedWireBlock) || !(amount > 0.0F) || !breakable(level, pos, state, target)) {
            return;
        }
        damageWall(level, st, mob, pos, state, amount, now);
        mob.swing(InteractionHand.MAIN_HAND);
        if (mob instanceof SwarmAttacker attacker) {
            attacker.playAttackAnimation();
        }
    }

    /**
     * 壊す(覆う)のに要る量 = {@code max(1, 硬さ) × breakHardnessFactor}(TD 設計 v2、係数は td.json・仮決め 10)。
     * 汚染されたブロック({@link #corrupted})はさらに {@code corruptedBreakTimeMultiplier} 倍(ユーザー指定 2026-09-26「TD 時のみ
     * 汚染されたブロックは mob が破壊しやすく」、td.json・仮決め 0.25)。どちらも 1 回の削りの量は変えないので、壊すのにかかる時間が同じ倍率で縮む。
     * ここを通るのは侵攻中の攻城だけ(ふだんのアルボラの坑道 {@code ArboraTunnelGoal} は別の決まりのまま)。
     */
    static double required(ServerLevel level, BlockPos pos, BlockState state) {
        double hardness = Math.max(1.0D, state.getDestroySpeed(level, pos));
        double amount = hardness * DesignData.td().breakHardnessFactor();
        if (corrupted(level, pos, state)) {
            amount *= DesignData.td().corruptedBreakTimeMultiplier();
        }
        return Math.max(1.0D, amount);
    }

    /**
     * 汚染されたブロックか: タグ {@code #corrupted}(汚染された土・石・原木・葉・板材・巨大樹の幹・硬化した幹など。プレイヤーが
     * 汚染された板材で建てた壁も入る)か、苔で覆われたマス(対応表に無いコンクリートなど。{@link OvergrownLayerData#isCovered})。
     */
    static boolean corrupted(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.is(ModTags.CORRUPTED)) {
            return true;
        }
        LevelChunk chunk = chunkAt(level, pos);
        return chunk != null && OvergrownLayerData.isCovered(chunk, pos, state);
    }

    /** 壁を壊すのをやめる(壊し終えた・覆った・別の物に替わった)。削れ具合とひびを捨てて攻城へ戻す。 */
    private static void finishBreach(ServerLevel level, LevelState st, Swarm swarm, BlockPos pos) {
        Progress progress = st.progress.remove(pos);
        if (progress != null && !progress.lamp) {
            level.destroyBlockProgress(crackId(pos), pos, -1);
        }
        st.breaches.remove(swarm.id());
        swarm.endBreach();
    }

    /**
     * 壁のひびの番号(座標ごとに決まった負の値)。エンティティの番号(正)とも、ターゲットブロックのひび
     * ({@code TargetBlockEntity#crackId}: -2^30 〜 -1)とも重ならない範囲(-2^31 〜 -2^30 - 1)に置く。
     */
    static int crackId(BlockPos pos) {
        return Integer.MIN_VALUE + (pos.hashCode() & 0x3FFFFFFF);
    }

    // ------------------------------------------------------------------ 見張り

    /**
     * {@value #CHECK_INTERVAL_TICKS} tick ごと: 攻城中の群れの灯りの見張り、着いたのに叩けない見張り、BREACH の行き詰まり、
     * 使われなくなった削れ具合の後片付け。群れが 1 つも攻城していないディメンションでは群れの一覧を 1 回見るだけ。
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.tickRateManager().runsNormally()) {
            return;
        }
        long now = level.getGameTime();
        if (Math.floorMod(now, (long) CHECK_INTERVAL_TICKS) != CHECK_PHASE) {
            return;
        }
        LevelState st = LEVELS.get(level.dimension());
        List<Swarm> sieging = null;
        for (Swarm swarm : SwarmManager.swarms(level)) {
            if (swarm.kind() == Swarm.Kind.GROUND && swarm.targetBlock() != null
                    && (swarm.mode() == Swarm.Mode.SIEGE || swarm.mode() == Swarm.Mode.BREACH)) {
                if (sieging == null) {
                    sieging = new ArrayList<>();
                }
                sieging.add(swarm);
            }
        }
        if (sieging == null && st == null) {
            return;
        }
        if (st == null) {
            st = state(level);
        }
        Set<UUID> live = new HashSet<>();
        Set<UUID> watched = new HashSet<>();
        Set<BlockPos> breaching = new HashSet<>();
        if (sieging != null) {
            for (Swarm swarm : sieging) {
                // 増援の一時停止は見ない(クラスの説明)。侵攻中のターゲットを攻めている群れだけ。
                boolean siegeLive = TdEvents.isSiegeLive(level, swarm.targetBlock());
                if (swarm.mode() == Swarm.Mode.SIEGE && siegeLive) {
                    checkLamp(level, st, swarm, now);
                }
                if (swarm.mode() == Swarm.Mode.SIEGE && siegeLive) {
                    watchSiege(level, st, swarm, now);
                    watched.add(swarm.id());
                }
                if (swarm.mode() == Swarm.Mode.BREACH && swarm.breachPos() != null) {
                    watchBreach(st, swarm, now);
                }
                if (swarm.mode() == Swarm.Mode.BREACH && swarm.breachPos() != null) {
                    live.add(swarm.id());
                    breaching.add(swarm.breachPos());
                }
            }
        }
        // BREACH を抜けた群れの見張りと、誰も壊していない座標の削れ具合(とひび)を捨てる。手分けして叩いている穴の断面・
        // 群れの外の攻め手・鉄線は、最後に叩いてから DIG_KEEP_TICKS の間は残す。
        st.breaches.keySet().retainAll(live);
        st.siegeSince.keySet().retainAll(watched);
        st.reachSince.keySet().retainAll(watched);
        st.wireCooldown.values().removeIf(until -> until < now);
        st.progress.entrySet().removeIf(e -> {
            if (breaching.contains(e.getKey()) || now - e.getValue().lastStrikeTick <= DIG_KEEP_TICKS) {
                return false;
            }
            if (!e.getValue().lamp) {
                level.destroyBlockProgress(crackId(e.getKey()), e.getKey(), -1);
            }
            return true;
        });
        st.pruneSkips(now);
        if (st.isEmpty()) {
            LEVELS.remove(level.dimension(), st);
        }
    }

    /** 攻城中の群れの先導役が、拠点のそばの灯りに寄り道を塞がれていれば、その灯りを覆いに行かせる(上の「なぜ」)。 */
    private static void checkLamp(ServerLevel level, LevelState st, Swarm swarm, long now) {
        Mob vanguard = swarm.vanguard();
        BlockPos target = swarm.targetBlock();
        if (vanguard == null || !vanguard.isAlive() || target == null) {
            return;
        }
        if (!vanguard.onGround() && !vanguard.isInWater()) {
            return; // 足の段が決まらない(壁を探す線が引けない)。次の見張りで
        }
        UUID id = swarm.id();
        Breach found = lampBreach(level, vanguard, swarm, target, pos -> st.skipped(id, pos, now), now);
        if (found == null) {
            return;
        }
        st.breaches.put(id, found);
        swarm.setBreach(found.pos);
    }

    /**
     * 着いたのに叩けない見張り(レビュー指摘 2026-09-25)。先導役が塊に着いた(塊の輪に入った)ら時計を回し、ターゲットに一撃入る
     * ({@link #strike})たびに戻す。{@value #SIEGE_STALL_TICKS} tick 一度も当たらなければ:
     * <ol>
     *     <li>先導役からターゲットへ壁を探し直す({@link #findBreach}: 水平の DDA → 足の段より下なら真上の列)。あれば壊す。
     *     ターゲットを外壁のすぐ内側・床の 1 段下に置いた拠点では、先導役が壁の外・床の上で「着いた」ことになり、先導の Goal が
     *     止まって壁選び({@code SwarmLeadGoal#tryBreach})が二度と呼ばれなかった。</li>
     *     <li>無ければ「着いた」を {@value #SIEGE_STALL_TICKS} tick 取り消して区間へ戻す({@link Swarm#holdSiegeArrival}。回り道・
     *     扉を探す。区間が塞がれれば壁選びが呼ばれる)。</li>
     * </ol>
     * 先導役が動いていない(読み込まれていない)間と、防衛側を実際に殴っている間は数えない。
     */
    private static void watchSiege(ServerLevel level, LevelState st, Swarm swarm, long now) {
        Mob vanguard = swarm.vanguard();
        UUID id = swarm.id();
        if (vanguard == null || !vanguard.isAlive() || swarm.blockEngageSlot(vanguard) == null) {
            st.siegeSince.remove(id); // 着いていない(区間を引いている)・動いていない
            st.reachSince.remove(id);
            return;
        }
        LivingEntity defender = swarm.target();
        if (defender != null && swarm.engageHitRecently(defender, FIGHT_GRACE_TICKS)) {
            st.siegeSince.put(id, now);
            return;
        }
        // 着いたのに先導役(群れでいちばん通りにくい体)の手がターゲットに届かない: 崖の上のターゲットに登れる一員(アルボラ)だけが
        // 届いて叩き、ほかは崖の下に立ったまま(2026-09-25 のユーザー報告「崖でアルボラ以外が立ち往生」)。一員が叩いていても
        // 下の見張りの時計は戻るので、先導役だけで数えて橋を頼む(TdBridges。架けている間は着いたことにしない)。
        BlockPos reachTarget = swarm.targetBlock();
        if (reachTarget != null && !Swarm.canReachBlock(vanguard, reachTarget) && (vanguard.onGround() || vanguard.isInWater())) {
            long stuck = st.reachSince.computeIfAbsent(id, k -> now);
            if (now - stuck >= SIEGE_STALL_TICKS) {
                st.reachSince.put(id, now);
                if (TdBridges.onLegsBlocked(level, vanguard, swarm, reachTarget, now)) {
                    return;
                }
            }
        } else {
            st.reachSince.remove(id);
        }
        long since = st.siegeSince.computeIfAbsent(id, k -> now);
        if (now - since < SIEGE_STALL_TICKS) {
            return;
        }
        st.siegeSince.put(id, now);
        BlockPos target = swarm.targetBlock();
        if (target == null || (!vanguard.onGround() && !vanguard.isInWater())) {
            return;
        }
        Breach found = findBreach(level, vanguard, swarm, target, pos -> st.skipped(id, pos, now), now);
        if (found != null) {
            st.breaches.put(id, found);
            swarm.setBreach(found.pos);
            return;
        }
        swarm.holdSiegeArrival(now + SIEGE_STALL_TICKS);
    }

    /**
     * BREACH の行き詰まり: 選んだ壁・灯りに {@value #BREACH_STALL_TICKS} tick 当たらなければ、そこを {@value #SKIP_TICKS} tick
     * 選ばない印を付けて攻城へ戻す(届かない高さの壁・壁の向こうの灯りで群れが止まり続けないように)。
     * 先導役が動いていない(読み込まれていない)間と、群れが防衛側を<b>実際に殴っている</b>間(塊の輪に入らないので当たらないのが
     * 当たり前)は数えない。レビュー指摘 2026-09-25: はじめは防衛側が標的にいるだけで数えなかったので、届かない所
     * (24 ブロック以内の塔の上など)に立つ防衛側が、届かない壁の BREACH をいつまでも生かしていた。
     */
    private static void watchBreach(LevelState st, Swarm swarm, long now) {
        BlockPos pos = swarm.breachPos();
        if (pos == null) {
            return;
        }
        Breach record = st.breaches.get(swarm.id());
        if (record == null || !record.pos.equals(pos)) {
            record = new Breach(pos, now, null, null, null);
            st.breaches.put(swarm.id(), record);
        }
        if (shaftReachesTarget(swarm, record, swarm.targetBlock())) {
            // 真上がもう空いていた(縦穴の要の列を先に誰かが崩した)。縁から叩ける。
            st.breaches.remove(swarm.id());
            swarm.endBreach();
            return;
        }
        LivingEntity defender = swarm.target();
        if (swarm.vanguard() == null || (defender != null && swarm.engageHitRecently(defender, FIGHT_GRACE_TICKS))) {
            record.lastProgress = now;
            return;
        }
        if (now - record.lastProgress >= BREACH_STALL_TICKS) {
            st.skip(swarm.id(), pos, now + SKIP_TICKS);
            st.breaches.remove(swarm.id());
            swarm.endBreach();
        }
    }

    // ------------------------------------------------------------------ 状態

    /** テスト・調べもの用: 群れの攻城の状態(壊している壁・断面の残り・選ばない印・着いてからの時刻)。 */
    static String debugState(ServerLevel level, UUID swarm) {
        LevelState st = LEVELS.get(level.dimension());
        if (st == null) {
            return "攻城の状態なし";
        }
        Breach b = st.breaches.get(swarm);
        Map<BlockPos, Long> skips = st.skips.get(swarm);
        return "breach=" + (b == null ? "なし" : b.pos + " 残り " + b.cells.size() + (b.column != null ? " 縦穴" : b.scan != null ? " 横穴" : ""))
                + " / 選ばない印 " + (skips == null ? 0 : skips.size()) + " / 着いた " + st.siegeSince.get(swarm);
    }

    private static LevelState state(ServerLevel level) {
        return LEVELS.computeIfAbsent(level.dimension(), k -> new LevelState());
    }

    @Nullable
    private static LevelChunk chunkAt(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** 読み込まれているときだけ読む(ServerLevel#getBlockState は読み込まれていないチャンクを読み込んでしまう)。建築範囲の外は空気。 */
    @Nullable
    private static BlockState loadedState(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        LevelChunk chunk = chunkAt(level, pos);
        return chunk != null ? chunk.getBlockState(pos) : null;
    }

    @Nullable
    private static FluidState loadedFluid(ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) {
            return Blocks.VOID_AIR.defaultBlockState().getFluidState();
        }
        LevelChunk chunk = chunkAt(level, pos);
        return chunk != null ? chunk.getFluidState(pos) : null;
    }

    /** 座標ごとの削れ具合(同じ壁を叩く全員で共有)。 */
    private static final class Progress {
        final boolean lamp;
        double done;
        /** 最後に叩いた時刻(手分けして叩く穴の断面・群れの外の攻め手・鉄線は、叩き続けている間だけ残す)。 */
        long lastStrikeTick = Long.MIN_VALUE / 4L;
        int lastStage = -1;
        long lastCrackTick = Long.MIN_VALUE / 4L;
        long lastSoundTick = Long.MIN_VALUE / 4L;

        Progress(boolean lamp) {
            this.lamp = lamp;
        }
    }

    /** 群れごとの BREACH の見張り(いま壊している座標・最後に当たった時刻・続けて壊すための線か列)。 */
    private static final class Breach {
        BlockPos pos;
        long lastProgress;
        /** 壁を選んだときの線(壊し終えたら同じ線で次の塊を探す)。掘る列・灯りそのもの・見張りが作ったものは null。 */
        @Nullable
        final Scan scan;
        /** 上から掘る列(壊し終えたら同じ列の次の塊)。線・灯りそのもの・見張りが作ったものは null。 */
        @Nullable
        final Column column;
        /** 線・列の行き先(ターゲットか灯り)。線はこの列に着いたら止まる。 */
        @Nullable
        final BlockPos goal;
        /** 穴の断面の、まだ壊していない塊({@link Hole#cells})。塊の輪の前衛が手分けして叩く。線で選んだ壁のときだけ。 */
        List<BlockPos> cells = new ArrayList<>();

        Breach(BlockPos pos, long now, @Nullable Scan scan, @Nullable Column column, @Nullable BlockPos goal) {
            this.pos = pos.immutable();
            this.lastProgress = now;
            this.scan = scan;
            this.column = column;
            this.goal = goal != null ? goal.immutable() : null;
        }
    }

    /** ディメンションごとの攻城の状態。 */
    private static final class LevelState {
        final Map<BlockPos, Progress> progress = new HashMap<>();
        final Map<UUID, Breach> breaches = new HashMap<>();
        /** 群れ → 塊に着いた(か、最後にターゲットに当たった)時刻({@link #watchSiege})。 */
        final Map<UUID, Long> siegeSince = new HashMap<>();
        /** 群れ → 着いたのに先導役の手がターゲットに届かなくなった時刻({@link #watchSiege}。崖の下 → 橋)。 */
        final Map<UUID, Long> reachSince = new HashMap<>();
        /** 群れ → 選ばない座標 → いつまで。 */
        final Map<UUID, Map<BlockPos, Long>> skips = new HashMap<>();
        /** 鉄線を切るアルボラ(エンティティの番号)→ 次に切ってよい時刻({@link #onWireContact})。 */
        final Map<Integer, Long> wireCooldown = new HashMap<>();

        boolean skipped(UUID swarm, BlockPos pos, long now) {
            Map<BlockPos, Long> map = this.skips.get(swarm);
            if (map == null) {
                return false;
            }
            Long until = map.get(pos);
            return until != null && until > now;
        }

        void skip(UUID swarm, BlockPos pos, long until) {
            this.skips.computeIfAbsent(swarm, k -> new HashMap<>()).put(pos.immutable(), until);
        }

        void pruneSkips(long now) {
            for (Map<BlockPos, Long> map : this.skips.values()) {
                map.values().removeIf(until -> until <= now);
            }
            this.skips.values().removeIf(Map::isEmpty);
        }

        boolean isEmpty() {
            return this.progress.isEmpty() && this.breaches.isEmpty() && this.siegeSince.isEmpty() && this.reachSince.isEmpty()
                    && this.skips.isEmpty()
                    && this.wireCooldown.isEmpty();
        }
    }

    private TdSiege() {
    }
}
