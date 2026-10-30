package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.DorgaArchelausTitanicaEntity;
import com.yayuy.overgrownvegetation.entity.PhyllosensEntity;
import com.yayuy.overgrownvegetation.entity.ScutimnesEntity;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * この MOD の GeckoLib Mob のアニメーションを、カメラから遠いときは<b>完全に止める</b>(描画 LOD)。
 * {@link LodGeoModel} から毎フレーム問い合わせられる。
 *
 * <p>GeckoLib はエンティティを描くたびに全コントローラのキーフレーム補間と全ボーンの行列計算を行う。
 * Mob が数百体いると、これがクライアント側で最も重い処理になる。
 * カメラのいるチャンクから {@code mobRenderLodAnimateChunkRadius}(既定 3)チャンクより外にいる Mob は<b>レストポーズで固まる</b> —
 * 位置・体の向きの補間は GeckoLib のレンダラー側で毎フレーム行われるので、
 * 止まるのは手足・翅などの細かい動きだけ。
 *
 * <h2>なぜ「数フレームに 1 回だけ更新する」ではいけないのか(2026-09-13 に作り直した)</h2>
 * <p>元は距離に応じて 2〜4 フレームに 1 回だけ骨を更新し、残りのフレームは「前回の姿勢のまま」
 * 描くつもりだった。ところが<b>GeckoLib の {@code GeoBone} はモデル単位で使い回される</b> —
 * 同じ種類の Mob が何体いても、骨のオブジェクトは 1 セットしかない。
 * 更新を飛ばしたフレームに残っているのは「前回の<b>自分の</b>姿勢」ではなく
 * <b>直前に描かれた別の個体の姿勢</b>で、更新するフレームとしないフレームで姿勢が飛び交い、
 * <b>痙攣しているように見えていた</b>。
 *
 * <p>そこで「間引く」のをやめて「止める」ことにした。止めるフレームでは
 * {@link LodGeoModel} が骨をレストポーズ({@code getInitialSnapshot})へ戻すので、
 * どの個体を描いても必ず同じ姿勢になり、飛び交いようがない。
 *
 * <h2>動かす体数の予算(2026-09-25、ユーザー承認 2026-09-24「近い数十体だけアニメ」)</h2>
 * <p>半径だけで決めると、群れが 48 ブロック以内に集まったとき(基地の前に溜まる・レイド)に全員が動いてしまい、
 * いちばん重い場面で LOD が効かない。そこで<b>半径の中でも、画面に映っている Mob のうちカメラに近い
 * {@code mobRenderLodAnimateBudget} 体(既定 64、仮決め 2026-09-25)だけ</b>を動かす。
 * 半径の外・予算の外のどちらかに当たればレストポーズ(両方の条件が同時に効く)。0 で予算なし。
 * <ul>
 *   <li><b>いつ決めるか</b>: 1 フレームに 1 回、{@link RenderLevelStageEvent.Stage#AFTER_SKY} で
 *       {@code entitiesForRendering()} を 1 周して決める。バニラのエンティティ描画より前に必ず来て、
 *       エンティティの描画と同じ視錐台を受け取れる。描画の途中(Mob 1 体ごとの問い合わせ)で数えると、
 *       先に描かれた Mob ほど予算を取ってしまい「近い順」にならない。</li>
 *   <li><b>何を数えるか</b>: この MOD の {@link LodGeoModel} で描かれる Mob のうち、半径の中にいて、
 *       バニラが今フレーム描くと判断するもの({@code EntityRenderDispatcher#shouldRender} —— 視錐台の外と、
 *       {@link EntityFogCull} が霧の奥で消すものは予算を使わない)。常に動かす 3 種と、
 *       アニメーションコントローラを 1 つも持たない Mob(ペディキュラ)は数えない。</li>
 *   <li><b>予算で止めるのは「数えて外れた」個体だけ</b>(2026-09-25、レビュー指摘への対応): 数えた id を
 *       {@link #counted} に残し、そこに入っていて {@link #animated} に入っていないものだけを止める。
 *       数えていない個体 —— Iris などの影のパス(独自の視錐台で描くので、カメラの後ろの Mob は数えていない)、
 *       霧が晴れていくフレームの霧の縁(霧の設定が {@code AFTER_SKY} の後に来るので、数えた後で霧の終端が伸びる)、
 *       鏡・ポータルのように別のカメラから描く MOD —— は、止める側に倒さず半径だけで決める。</li>
 *   <li><b>近い K 体の選び方</b>: 大きさ K の最大ヒープ(距離の 2 乗)で、全体を並べ替えずに選ぶ。</li>
 *   <li><b>境目のちらつき止め</b>: 前フレームに動かした個体は、距離を 0.85 倍(仮決め 2026-09-25)して比べる。
 *       同じくらいの距離の 2 体が毎フレーム入れ替わって、動いたり止まったりを繰り返さないため。</li>
 *   <li><b>覚えるのは id だけ</b>({@link IntOpenHashSet})。Entity も Level も握らないので、ワールドを
 *       移ってもぶら下がらない。念のため切断・クライアントのワールドを捨てるときに空にする。</li>
 *   <li>まだ 1 度も決めていないとき(ワールドに入った直後、描画 MOD が {@code AFTER_SKY} を飛ばす場合)は
 *       予算を使わず、半径だけで決める —— 「全員止まる」側には倒さない。</li>
 * </ul>
 * <p>状態は描画スレッド(= クライアントのメインスレッド)だけが触る。{@link LevelEvent.Unload} は統合サーバーの
 * ワールドのぶんがサーバーのスレッドから来るので、クライアントのワールドのときだけ空にする。
 *
 * <h2>対象外にしている Mob</h2>
 * <p><b>攻城戦ボス</b>: 全長 615 ブロックあり、常にカメラから数百ブロック離れている。距離で止めると
 * <b>永遠に止まったまま</b>になるうえ、1 体しか居ないので負荷対策の意味も無い。
 * ボスのモデル({@link DorgaArchelausTitanicaModel})はそもそも {@link LodGeoModel} を継承していないが、
 * 将来つなぎ替えられても止まらないよう、ここでも明示的に外してある。
 *
 * <p><b>フィロセンス</b>(ユーザー要望 2026-09-14): <b>遠くから見て索敵中だと分かること</b>が
 * この Mob の役目そのもの —— 首を上げ花を開いた姿が「いま撒かれている」の合図なのに、
 * 48 ブロック(既定 3 チャンク)でレストポーズに戻ってしまっては伝わらない。
 * 湧く数も少ない(`OvergrownSpawnProfile` で weight 0.8 / clusterMax 1 / spawnChance 0.08 のレア枠)ので、
 * 常時動かしても負荷にならない。
 *
 * <p><b>スクティムネス</b>(2026-09-24): レストポーズ(モデルの初期姿勢)は<b>盾を下ろした</b>姿勢で、ふだんの盾を上げた姿勢
 * (待機アニメ)と逆になる。盾は遠距離攻撃をしてくる相手には距離に関係なく下ろす(ユーザー指定)ので、遠くから弓・銃で狙う
 * プレイヤーにとって「盾を下ろしているか(撃っても弾かれるか)」は遠くからこそ見えてほしい。止めると上げ下げが見えず、
 * 上げていても下ろして見える。湧く数はファルキムネスと同じレア枠(weight 0.6 / clusterMax 1)なので常時動かす。
 *
 * <p><b>ペディキュラ</b>(2026-09-25、レビュー指摘への対応): アニメーションコントローラを 1 つも持たない
 * (回転は {@code PediculaRenderer} が付ける)。キーフレームが無いので、止めても見た目も重さも変わらない。
 * それなのに予算の席を 1 つ取り、動く Mob を押し出していたので、<b>数えもしないし止めもしない</b>。
 * 判定は種類ではなく「コントローラが 0 個か」({@link #hasNoAnimationControllers})。
 *
 * <p><b>植物型の Mob</b>(汚染シダ・汚染水草・ハイヴノード)は動きが小さな揺れだけだが、今は<b>ほかの Mob と同じく予算を使う</b>。
 * 汚染された拠点の近くでは 48 ブロック以内に 20 体ほど居ることがあり、そのぶん動く Mob が減る。
 * 外すか、動く Mob の後回しにするかはユーザーに確認していない(DEV_NOTES「G4 の群れ(v2、2026-09-25)」の未決)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class MobRenderLod {

    /**
     * 前フレームに動かした個体の距離に掛ける倍率(境目のちらつき止め)。仮決め 2026-09-25。
     * 0.85 なら、予算の境目にいる個体は「今動いている側より 15% 以上近い」相手が来るまで席を譲らない。
     */
    private static final double HYSTERESIS = 0.85D;
    /** 距離は 2 乗で比べるので、倍率も 2 乗しておく。 */
    private static final double HYSTERESIS_SQR = HYSTERESIS * HYSTERESIS;

    /** 今フレーム動かす個体の id(予算に入ったもの)。{@link #onRenderLevelStage} が毎フレーム作り直す。 */
    private static IntOpenHashSet animated = new IntOpenHashSet();
    /** 前フレームに動かした個体の id(ヒステリシス用)。{@link #animated} と毎フレーム入れ替えて使い回す。 */
    private static IntOpenHashSet previous = new IntOpenHashSet();
    /**
     * 今フレーム予算の候補として数えた個体の id(動かすものも止めるものも入る)。{@link #onRenderLevelStage} が
     * 毎フレーム作り直す。予算で止めるのは「ここに入っていて {@link #animated} に入っていない」個体だけで、
     * ここに無い個体は半径だけで決める(理由はクラスの説明)。
     */
    private static final IntOpenHashSet counted = new IntOpenHashSet();
    /** 予算が決まっているか。false の間は予算で止めない(半径だけで決める)。 */
    private static boolean budgetReady;

    /** 近い K 体を選ぶ最大ヒープ(根がいちばん遠い)。距離の 2 乗と id を並べて持つ。 */
    private static double[] heapDistSqr = new double[0];
    private static int[] heapId = new int[0];
    private static int heapSize;

    /** このフレームは {@code entity} のアニメーションを止めるか(= レストポーズで描くか)。 */
    public static boolean shouldStopAnimation(Entity entity) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.mobRenderLodEnabled.get()) {
            return false;
        }
        // ワールドに追加されていないもの(スポーンエッグのプレビュー等)は対象外。
        // コントローラの無い Mob(ペディキュラ)は止めても何も変わらないので、半径でも予算でも止めない。
        if (!entity.isAddedToLevel() || alwaysAnimated(entity) || hasNoAnimationControllers(entity)) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null || mc.level == null) {
            return false;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        if (outsideAnimateRadius(entity, cam, cfg.mobRenderLodAnimateChunkRadius.get())) {
            return true;
        }
        // 半径の中でも、近い順の予算に入らなかったものは止める(2026-09-25、ユーザー承認 2026-09-24)。
        // 止めるのは今フレーム数えて外れた個体だけ。数えていない個体(影のパス・霧の縁・別カメラ)は半径だけで決める。
        int id = entity.getId();
        return budgetReady && counted.contains(id) && !animated.contains(id);
    }

    /**
     * 判定はチャンク単位(2026-09-13、要望): カメラのいるチャンクから半径 N チャンク(チェビシェフ距離)の
     * 正方形の中にいる Mob だけ動かす。距離(ブロック)ではなくチャンクなので、境界は常にチャンクの境目。
     * 予算を決める側({@link #onRenderLevelStage})も同じ式で「半径の中の Mob」だけを数える —— 半径の外は
     * どうせ止まるので、そこに予算の席を渡さない(正方形の角の中より、辺のすぐ外のほうがカメラに近いことがある)。
     */
    private static boolean outsideAnimateRadius(Entity entity, Vec3 cam, int radius) {
        int camChunkX = Mth.floor(cam.x) >> 4;
        int camChunkZ = Mth.floor(cam.z) >> 4;
        int entityChunkX = entity.getBlockX() >> 4;
        int entityChunkZ = entity.getBlockZ() >> 4;
        return Math.abs(entityChunkX - camChunkX) > radius || Math.abs(entityChunkZ - camChunkZ) > radius;
    }

    /** 距離に関係なく必ず動かすもの。理由はクラスの説明を参照。予算も使わない。 */
    private static boolean alwaysAnimated(Entity entity) {
        return entity instanceof DorgaArchelausTitanicaEntity
                || entity instanceof PhyllosensEntity
                || entity instanceof ScutimnesEntity;
    }

    /**
     * アニメーションコントローラを 1 つも持たない Mob か(今はペディキュラだけ。2026-09-25)。
     * キーフレームが無いので、止めても見た目は変わらない(GeckoLib はどのコントローラも触らなかった骨を
     * 初期姿勢へ戻すので、止めない経路でも骨はレストポーズと同じ)し、軽くもならない。予算の席を取って
     * 動く Mob を押し出さないよう、数えもしないし止めもしない。
     *
     * <p>エンティティの GeckoLib のキャッシュは 1 体に 1 つの {@code AnimatableManager}
     * ({@code InstancedAnimatableInstanceCache})。id は描画側({@code GeoEntityRenderer#getInstanceId})と同じ
     * {@code getId()} を渡す。まだ作られていなければここで作られる(中で {@code registerControllers} が呼ばれる)が、
     * 描けばどのみち同じものが作られるので害は無い。
     *
     * <p>注意: コントローラが無くても {@code setCustomAnimations} で骨を手続き的に動かすモデルを将来作ると、
     * ここで対象外になり距離に関係なく動き続ける(止まる側ではなく動く側に倒れるので見た目は壊れないが、LOD が効かない)。
     * そういう Mob を {@link LodGeoModel} に足すときは見直すこと。
     */
    private static boolean hasNoAnimationControllers(Entity entity) {
        return entity instanceof GeoAnimatable geo
                && geo.getAnimatableInstanceCache().getManagerForId(entity.getId()).getAnimationControllers().isEmpty();
    }

    /**
     * 1 フレームに 1 回、今フレーム動かす個体(カメラに近い K 体)を決める。エンティティの描画より前に来る
     * {@code AFTER_SKY} で、バニラがエンティティの描画に使うのと同じ視錐台・カメラを使う。
     */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        int budget = cfg.mobRenderLodAnimateBudget.get();
        Minecraft mc = Minecraft.getInstance();
        Frustum frustum = event.getFrustum();
        if (!cfg.mobRenderLodEnabled.get() || budget <= 0 || mc.level == null || frustum == null) {
            clear();
            return;
        }

        // 前フレームの結果を previous へ回し、animated は空にして作り直す(集合は使い回す)。
        IntOpenHashSet swap = previous;
        previous = animated;
        animated = swap;
        animated.clear();
        counted.clear();
        if (heapId.length != budget) {
            heapDistSqr = new double[budget];
            heapId = new int[budget];
        }
        heapSize = 0;

        Vec3 cam = event.getCamera().getPosition();
        int radius = cfg.mobRenderLodAnimateChunkRadius.get();
        EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
        for (Entity entity : mc.level.entitiesForRendering()) {
            // 安い判定から: GeckoLib でない(バニラの Mob・アイテム等)→ 常に動かす 3 種 → 半径の外 →
            // LodGeoModel で描かれない → コントローラが無い(ペディキュラ)→ 今フレーム描かれない、の順に外す。
            if (!(entity instanceof GeoAnimatable)
                    || alwaysAnimated(entity)
                    || outsideAnimateRadius(entity, cam, radius)
                    || !usesLodModel(dispatcher, entity)
                    || hasNoAnimationControllers(entity)) {
                continue;
            }
            // バニラの描画ループと同じ条件(視錐台・描画距離・EntityFogCull の霧の奥、乗っている Mob は常に描く)。
            if (!dispatcher.shouldRender(entity, frustum, cam.x, cam.y, cam.z)
                    && !(mc.player != null && entity.hasIndirectPassenger(mc.player))) {
                continue;
            }
            int id = entity.getId();
            counted.add(id);
            double distSqr = entity.distanceToSqr(cam);
            if (previous.contains(id)) {
                distSqr *= HYSTERESIS_SQR;
            }
            offer(distSqr, id, budget);
        }
        for (int i = 0; i < heapSize; i++) {
            animated.add(heapId[i]);
        }
        budgetReady = true;
    }

    /**
     * この MOD の {@link LodGeoModel} で描かれる Mob か(= レストポーズで描ける = 予算を使う側か)。
     * GeckoLib の Mob でも {@link LodGeoModel} を継承していないモデル(レイドビーコン・砲台など)は
     * 止められないので、予算の席を渡さない。
     */
    private static boolean usesLodModel(EntityRenderDispatcher dispatcher, Entity entity) {
        Object renderer = dispatcher.getRenderer(entity);
        return renderer instanceof GeoEntityRenderer<?> geo && geo.getGeoModel() instanceof LodGeoModel<?>;
    }

    /** 大きさ {@code capacity} の最大ヒープに入れる。満杯なら、根(いちばん遠い)より近いときだけ根と入れ替える。 */
    private static void offer(double distSqr, int id, int capacity) {
        if (heapSize < capacity) {
            int i = heapSize++;
            while (i > 0) {
                int parent = (i - 1) >>> 1;
                if (heapDistSqr[parent] >= distSqr) {
                    break;
                }
                heapDistSqr[i] = heapDistSqr[parent];
                heapId[i] = heapId[parent];
                i = parent;
            }
            heapDistSqr[i] = distSqr;
            heapId[i] = id;
            return;
        }
        if (distSqr >= heapDistSqr[0]) {
            return;
        }
        int i = 0;
        int half = heapSize >>> 1;
        while (i < half) {
            int child = 2 * i + 1;
            int right = child + 1;
            if (right < heapSize && heapDistSqr[right] > heapDistSqr[child]) {
                child = right;
            }
            if (heapDistSqr[child] <= distSqr) {
                break;
            }
            heapDistSqr[i] = heapDistSqr[child];
            heapId[i] = heapId[child];
            i = child;
        }
        heapDistSqr[i] = distSqr;
        heapId[i] = id;
    }

    /** 予算を捨てる(次に決めるまでは半径だけで判定する)。描画スレッドから呼ぶこと。 */
    private static void clear() {
        animated.clear();
        previous.clear();
        counted.clear();
        heapSize = 0;
        budgetReady = false;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    /** 次元の移動・切断で ClientLevel が捨てられる(シングルではサーバー側の Unload も来るので、クライアントのものだけ)。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            clear();
        }
    }

    private MobRenderLod() {
    }
}
