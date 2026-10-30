package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * 細かい胞子({@link FineSporeParticle})を<b>クライアントで出し続ける側</b>の間引き
 * (2026-09-25、全体監査 #468「粒子の見直し」でユーザー承認 2026-09-24)。
 *
 * <p>スポロフィルの傘は 1 体で毎 tick 2 粒、ヴェロフィルの航跡は 1 体で 2 tick ごとに 6 粒を出す。
 * どちらも<b>体数に比例して際限なく増える</b>うえ、効いていたのはバニラの「32 ブロックより先は出さない」と
 * 粒子設定だけだった。群れが基地の前に溜まると、見えもしない粒を何千も動かすことになる。そこで
 * <ul>
 *   <li><b>距離</b>: カメラから {@value #FAR_DISTANCE} ブロックより遠い粒は {@value #FAR_KEEP_ONE_IN} 粒に 1 粒だけ出す。
 *       粒は 0.035〜0.075 ブロックしかなく、その距離では 1〜2 ピクセルなので、減っても気付かれにくい。</li>
 *   <li><b>全体の上限</b>: ここを通した細かい胞子(傘と航跡)が {@value #FINE_SPORE_BUDGET} 粒ほど生きていたら出さない。
 *       上限の半分を超えたところから出す確率をなだらかに下げる —— いきなり打ち切ると
 *       「tick の中で先に処理された個体だけが粒を出す」偏りが見えるので。</li>
 * </ul>
 *
 * <p><b>数え方</b>: 生きている粒を直接数えると、{@code ParticleEngine} が上限超えで古い粒を黙って捨てたときや
 * ワールドを移ったとき({@code remove()} を通らない)に数がずれて、二度と戻らない。
 * そこで「直近 {@code FineSporeParticle.MAX_LIFETIME + 1} tick に<b>ここで許した</b>数」を数える。寿命より前に作った粒は
 * 必ず消えているので、これは<b>ここを通した粒が生きている数の上限</b>になり、ずれても寿命ぶんの tick で自然に直る。
 *
 * <p><b>数えるのはここを通した粒だけ</b>(2026-09-25 のレビューで直した)。最初は {@link FineSporeParticle.Provider} で
 * 細かい胞子をすべて数えていたが、フィロセンスの索敵は 3 tick ごとに 34 + 22 粒、9 tick ごとに柱の 46 粒を
 * <b>512 ブロック先まで強制表示で</b>送ってくる(クライアントは強制表示の粒を距離も粒子設定も見ずに作る)。
 * 窓 35 tick で約 830 粒 —— 1 体で上限を使い切り、索敵の間(既定 30 秒)は見えもしない遠くのフィロセンスのせいで、
 * カメラのすぐ横のスポロフィル・ヴェロフィルの胞子までほぼ全部止まっていた。サーバーから 4 tick ごとに届く粒
 * ({@code VelophyllGroundSporeGoal} 30 粒・{@code InvaderSporeCrouchGoal} 24 粒・{@code ShielderSporeGoal} 6 粒)も窓を食っていた。
 * これらはバニラの 32 ブロック・粒子設定の制限と、出す Mob の数の方で抑えられているので、ここの上限には入れない。
 * したがって上限は「細かい胞子の総数」ではなく「傘と航跡の総数」の上限。
 *
 * <p>{@code true} を返すのは<b>バニラが実際に作る粒だけ</b>にしてある(呼んだ側は強制でない {@code level.addParticle} で出すので、
 * {@value #VANILLA_CUTOFF} ブロックより先と粒子設定「最小」はバニラが捨てる。それを数えると、作られない粒で窓が埋まる)。
 * 粒子設定「減少」ではバニラがさらに 3 粒に 1 粒を捨てるが、それはここからは見えないので数に入る
 * (最大 1.5 倍の見積もりになり、間引きが少し早く掛かる。粒を減らしたい設定なので害は無い)。
 *
 * <p><b>ここを通すのはクライアントで毎 tick 出し続ける Mob だけ</b>(スポロフィルの傘・ヴェロフィルの航跡)。
 * フィロセンスの索敵の粒は「遠くからでも見えること」がユーザー指定(2026-09-14)なので通さない ——
 * 間引きも数えるのも粒を作る側で行い、{@link FineSporeParticle.Provider} 自体は何も捨てず、何も数えない。
 *
 * <p>状態はクライアントのメインスレッドだけが触る(呼び出し元のクライアント側のエンティティの tick と
 * {@link ClientTickEvent} はどちらもメインスレッド)。統合サーバーのスレッドは粒を作らず、ここも呼ばない
 * (呼び出し元はどちらも {@code isClientSide()} の分岐の中)。
 * ワールドを抜けるときは念のため数を捨てる({@link LevelEvent.Unload} はサーバー側のワールドでも
 * サーバーのスレッドから飛んでくるので、クライアントのワールドのときだけ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ParticleThrottle {

    /** これより遠い粒を間引く(ブロック)。仮決め 2026-09-25。バニラは 32 ブロックより先をそもそも出さない。 */
    private static final double FAR_DISTANCE = 24.0D;
    private static final double FAR_DISTANCE_SQR = FAR_DISTANCE * FAR_DISTANCE;
    /** 遠くでは何粒に 1 粒を出すか。仮決め 2026-09-25。 */
    private static final int FAR_KEEP_ONE_IN = 3;
    /**
     * バニラが強制でない粒を捨てる距離(ブロック。{@code LevelRenderer#addParticleInternal} の 1024 = 32²)。
     * ここより先は許さず、数にも入れない。
     */
    private static final double VANILLA_CUTOFF = 32.0D;
    private static final double VANILLA_CUTOFF_SQR = VANILLA_CUTOFF * VANILLA_CUTOFF;
    /**
     * 直近 {@link #WINDOW} tick にここで許してよい数。仮決め 2026-09-25。窓で数えるので、カメラの近くの
     * スポロフィル(毎 tick 2 粒 = 窓あたり 70 粒)なら約 11 体、ヴェロフィル(2 tick ごとに 6 粒 = 窓あたり 105 粒)なら
     * 約 7 体が出し続ける数に当たる。半分の {@link #FINE_SPORE_SOFT} を超えたところ(スポロフィル約 6 体)から間引き始める。
     */
    private static final int FINE_SPORE_BUDGET = 800;
    /** ここを超えたら出す確率を下げ始める(上限の半分)。 */
    private static final int FINE_SPORE_SOFT = FINE_SPORE_BUDGET / 2;
    /** 数える窓(tick)。細かい胞子の寿命の上限 + 1。 */
    private static final int WINDOW = FineSporeParticle.MAX_LIFETIME + 1;

    /** tick ごとの「ここで許した数」の輪。{@link #slot} が今の tick。 */
    private static final int[] CREATED = new int[WINDOW];
    private static int slot;
    /** {@link #CREATED} の合計 = 直近 {@link #WINDOW} tick にここで許した数。 */
    private static int recent;

    /**
     * {@link FineSporeParticle} を 1 粒、ここで出してよいか。クライアントで毎 tick 粒を出す Mob が
     * 1 粒ごとに聞き、{@code true} なら<b>必ずその粒を出す</b>(ここで数えてしまうので)。
     * {@code random} はその Mob の乱数でよい(クライアント側の乱数なのでサーバーとずれない)。
     */
    public static boolean allowFineSpore(double x, double y, double z, RandomSource random) {
        // 品質の段階の「粒の量」(2026-10-23): 上限と出す確率の両方に掛ける。
        float amount = VisualQuality.particles();
        int budget = Math.max(2, Math.round(FINE_SPORE_BUDGET * amount));
        int soft = budget / 2;
        if (recent >= budget || amount < 1.0F && random.nextFloat() >= amount) {
            return false;
        }
        if (recent > soft
                && random.nextInt(budget - soft) < recent - soft) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        // バニラが捨てる粒は先に断る(数に入れると、作られない粒で窓が埋まる)。
        if (mc.options.particles().get() == ParticleStatus.MINIMAL) {
            return false;
        }
        if (mc.gameRenderer != null) {
            Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
            double distSqr = camera.distanceToSqr(x, y, z);
            if (distSqr > VANILLA_CUTOFF_SQR) {
                return false;
            }
            if (distSqr > FAR_DISTANCE_SQR && random.nextInt(FAR_KEEP_ONE_IN) != 0) {
                return false;
            }
        }
        CREATED[slot]++;
        recent++;
        return true;
    }

    /** 窓を 1 tick 進める。一時停止中は粒も歳を取らないので、窓も止めておく。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (recent == 0 || Minecraft.getInstance().isPaused()) {
            return;
        }
        slot = (slot + 1) % WINDOW;
        recent -= CREATED[slot];
        CREATED[slot] = 0;
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            clear();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    public static void clear() {
        java.util.Arrays.fill(CREATED, 0);
        slot = 0;
        recent = 0;
    }

    private ParticleThrottle() {
    }
}
