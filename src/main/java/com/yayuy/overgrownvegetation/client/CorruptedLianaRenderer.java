package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CorruptedLianaBlockEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * {@link CorruptedLianaBlockEntity} の始点・終点・垂れ具合から、蔦の経路を毎フレーム直接
 * ポリゴンで描画する。ブロックを並べる代わりにバニラのリード(引き紐、
 * {@code net.minecraft.client.renderer.entity.EntityRenderer#renderLeash}) と似た技術
 * (始点→終点を細かく分割したリボン状の帯を積む)を使うため、ブロックの格子に縛られない
 * 滑らかな経路になる。**水平方向は始点・終点を結ぶ直線のみで、曲線補間は垂直方向の sag による
 * 垂れにしか使わない**({@link #curvePoint} 参照)。
 *
 * <h2>ぶら下がり蔦(swag)</h2>
 * <p>{@link CorruptedLianaBlockEntity#hasSwag()} が true の蔦(長距離接続で重ねて生成された、
 * 垂れ具合が一番小さくない蔦)は、木の原木ブロックである {@link CorruptedLianaBlockEntity#getSourcePos}
 * / {@code getTargetPos} ではなく、{@link CorruptedLianaBlockEntity#getSwagStart}/
 * {@code getSwagEnd}(一番小さい蔦の曲線上の実座標、ブロック格子に縛られない)を始点・終点として
 * 使う。曲線の式そのものは通常の蔦と全く同じ(直線+sagの放物線)で、始点・終点として
 * 「どの座標を使うか」だけが違う。これにより、垂れ具合の大きい蔦が一番小さい蔦の曲線上の
 * 2点の間だけを、自分の垂れ具合でなめらかに垂れ下がる短いミニ曲線になる(ブンティング飾りの
 * ように、木の原木に届かない小さな蔦がいくつも垂れ下がって見える)。
 *
 * <h2>テクスチャ付き描画の仕組み</h2>
 * <p>バニラのリード用 {@code RenderType.leash()} は {@code POSITION_COLOR_LIGHTMAP}
 * (テクスチャ座標を持たない)専用実装のため、テクスチャを貼ることができない。そこでこのクラスは
 * 独自の {@link RenderType}({@link #MAIN_RENDER_TYPE}/{@link #DROOP_RENDER_TYPE})を、
 * {@code DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP}(バニラのリードのフォーマットに
 * テクスチャ座標(UV0)を1個足しただけ)+ バニラの「テキスト(文字)」描画が使っている
 * {@code RenderStateShard.RENDERTYPE_TEXT_SHADER} の組み合わせで定義している。この2つは
 * 頂点フォーマットが完全に一致するうえ、シェーダーの中身自体は「テクスチャ×頂点色×
 * ライトマップ」を掛け合わせるだけの汎用処理(文字特有の処理はグリフのテクスチャ側にあるだけで
 * シェーダー側には無い)なので、文字とは無関係な半透明テクスチャ付きリボンの描画にもそのまま
 * 使える(Minecraft本体にもMODコミュニティにも見られる定番の流用パターン)。
 *
 * <h2>三角形ストリップではなく四角形(QUADS)で描く理由</h2>
 * <p>以前のバージョンはバニラのリードと全く同じ「三角形ストリップ1本」で曲線を描いていたが、
 * これは同じフレーム内に存在する**他の蔦**(別の {@code CorruptedLianaBlockEntity})のリボンとも
 * 同じ {@link MultiBufferSource} バッファ・同じ描画モードを共有してしまう。三角形ストリップは
 * バッファに積まれた頂点列全体を1本の連続したストリップとして扱う({@link VertexFormat.Mode}
 * の {@code connectedPrimitives}) ため、ワールド中の蔦をすべて描き終えるまでの間に、無関係な
 * 蔦どうしを繋ぐ縮退三角形(細い筋状のノイズ)が紛れ込みうる。単色だった頃は目立たなかったが、
 * テクスチャを貼るとその筋にもテクスチャが伸びて表示され、はっきり見えてしまう。
 * このバージョンは主リボンも下記の垂れ蔦も {@link VertexFormat.Mode#QUADS} で描いており、
 * 四角形ごとに独立しているためこの問題が起きない({@link #addQuad} が最小単位)。
 *
 * <h2>垂れ蔦</h2>
 * <p>「蔦のところどころから鉛直方向に垂れる、より小さな蔦」を、新しい
 * {@code CorruptedLianaBlockEntity}/アンカーブロックを追加で置くのではなく、**このレンダラーが
 * 描くポリゴンとテクスチャを増やすだけ**で表現している({@link #addDroopingTendril} 参照)。
 * どの分割点に、どんな長さで、通常の見た目({@link #DROOP_TEXTURE})か発光する見た目
 * ({@link #DROOP_LIGHT_TEXTURE})かは、サーバー側の
 * {@code com.yayuy.overgrownvegetation.worldgen.TreeVineConnector#placeDroops} が生成時に
 * 1回だけ決めて {@link CorruptedLianaBlockEntity} に同期しており、このクラスはその結果を
 * 読んで描くだけ(自前で乱数は引かない——クラスの Javadoc「発光する垂れ蔦」参照)。垂れ蔦自体は
 * バニラの草花のような十字(直交2枚)ではなく**面1枚だけ**を主リボンと同じ向きで描き、
 * 専用テクスチャ(正方形、タイル表示せず1回だけ表示)を貼る。
 *
 * <p>{@link #getRenderBoundingBox} を必ずオーバーライドすること。既定値(マーカー自身の
 * 1x1x1)のままだと、実際に描く曲線がマーカーの位置から離れた場所まで伸びているにもかかわらず
 * 視錐台カリングの判定にはマーカーの位置(=ごく小さい範囲)しか使われない。プレイヤーが
 * マーカーに近づいて特定の角度になると、曲線が画面内にあってもマーカー自身を含む区画が
 * カリングされて曲線ごと描画されなくなる不具合になる({@code IBlockEntityRendererExtension}
 * のドキュメントに明記されている既知の仕様)。
 *
 * <h2>軽量化その1: 分割点の再計算をやめる(2026-08-24)</h2>
 * <p>見た目・仕様は一切変えず、同じ結果を導く計算量だけを削った。以前は分割点ごとの座標を
 * {@code curvePoint} が {@code new float[3]} を返す形で計算しており、1回の {@link #render} で
 * (主リボンの分割点 {@link #SEGMENTS}+1 個ぶん) ×(主リボンのループ・{@code buildLengthTable}・
 * 垂れ蔦ぶん)と**同じ分割点が何度も**別々の配列アロケーションとして再計算されていた
 * (1本の蔦につき毎フレーム70個以上の小さな配列がゴミになり、GC負荷の原因になっていた)。
 * 光量だけは {@link #render} の冒頭で毎フレーム計算し直す static スクラッチ配列
 * ({@link #SCRATCH_LIGHT})に書き込む(光は時間帯・周囲の光源で本当に変わりうるので、
 * 毎フレーム追従させる必要がある)。
 *
 * <h2>軽量化その2: 曲線ジオメトリはブロックエンティティ1個につき一度だけ計算する(2026-08-24)</h2>
 * <p>{@link CorruptedLianaBlockEntity} の始点・終点・sag は**生成時に1回設定されたら以後
 * 二度と変化しない**(そのクラスの Javadoc「静的な装飾」参照)。つまり光量以外——分割点の
 * 座標・始点からの累積経路長・主リボンの幅ベクトル・垂れ蔦の生成位置(間隔・生えるかどうか・
 * 長さを決める乱数列の消費結果)・ブロック中心への平行移動量のいずれも、そのブロック
 * エンティティが存在し続ける限りずっと同じ値になる。以前(軽量化その1の時点)はこれらを
 * それでも毎フレーム再計算していた。現在は初回の {@link #render} 呼び出し時に
 * {@link #buildGeometryCache} で1回だけ計算し、結果を {@link #GEOMETRY_CACHE}
 * ({@code WeakHashMap<ブロックエンティティ, キャッシュ>}、対応するブロックエンティティが
 * アンロード等で他から一切参照されなくなればエントリも自動的にGCされる)に控えて
 * 2フレーム目以降はそれを読むだけにした。数式は全く変えていないので、結果(頂点座標・UV)は
 * 既存と完全に同一——ちらつきも一切生じない。
 *
 * <h2>発光する垂れ蔦(2026-08-24)</h2>
 * <p>垂れ蔦の生成位置(間隔・生えるかどうか・長さ・発光する見た目かどうか)を決める乱数列の
 * 消費は、{@code overgrown_liana_droop_light}(実際に世界の明るさに影響する光源として機能する
 * 必要がある)の追加にあわせて、このクラスから
 * {@code com.yayuy.overgrownvegetation.worldgen.TreeVineConnector#placeDroops} へ移した——
 * 光の伝播はサーバー権威のワールドデータに基づくため、クライアント専用のこのクラスだけでは
 * 世界を実際に明るくできない。このクラスは今や、{@link CorruptedLianaBlockEntity} に既に
 * 決定・同期済みの垂れ蔦データ({@code getDroopIndices}/{@code getDroopLengths}/
 * {@code getDroopIsLight})を読んで描くだけ(自前で乱数は一切引かない)。発光する垂れ蔦は
 * 通常の垂れ蔦と全く同じ形・仕組みで描くが、テクスチャだけ {@link #DROOP_LIGHT_TEXTURE} に
 * 差し替える。
 */
public class CorruptedLianaRenderer implements BlockEntityRenderer<CorruptedLianaBlockEntity> {

    /** バニラのリードと同じ分割数。多いほど曲線が滑らかになる。 */
    private static final int SEGMENTS = 24;
    /** 主リボンの太さ。バニラのリード(0.025F)相当の初期値(0.05F)から4倍にしてある。 */
    private static final float WIDTH = 0.2F;
    /** テクスチャがワールド内で何ブロックの実長ごとに縦方向へ1回繰り返すか(主リボン・垂れ蔦共通)。 */
    private static final float TILE_LENGTH = 1.0F;

    private static final ResourceLocation MAIN_TEXTURE =
            OvergrownVegetation.id("textures/entity/overgrown_liana_vine.png");
    private static final ResourceLocation DROOP_TEXTURE =
            OvergrownVegetation.id("textures/entity/overgrown_liana_droop.png");
    private static final ResourceLocation DROOP_LIGHT_TEXTURE =
            OvergrownVegetation.id("textures/entity/overgrown_liana_droop_light.png");

    /**
     * 垂れ蔦が取りうる最大の長さ(ブロック単位)。垂れ蔦自体の長さは
     * {@code TreeVineConnector#placeDroops} が生成時に決めるが、その値は
     * {@link #getRenderBoundingBox} の余白計算にも必要なため、安全マージンとしてここに同じ値を
     * 複製してある(実際の長さがこれを超えることはない——超えても視錐台カリングが少し
     * 保守的になるだけで見た目には影響しない安全側の余白なので、多少のズレは実害が無い)。
     */
    private static final float DROOP_MAX_LENGTH = 0.9F;
    /**
     * 垂れ蔦の起点を、主リボンの中心線({@link #curvePoint} の位置、主リボン断面の下端に相当)
     * からどれだけ上へずらすか。0 のままだと主リボンの下端ぎりぎりから垂れ始め、宙に浮いて
     * いるように見えがちなので、主リボンの断面(太さ {@link #WIDTH})に重なる位置まで起点を
     * 持ち上げることで、蔦本体からしっかり垂れているように見せる。
     */
    private static final float DROOP_ORIGIN_LIFT = 0.15F;

    private static final RenderType MAIN_RENDER_TYPE = createRenderType("overgrown_vegetation_liana", MAIN_TEXTURE);
    private static final RenderType DROOP_RENDER_TYPE =
            createRenderType("overgrown_vegetation_liana_droop", DROOP_TEXTURE);
    private static final RenderType DROOP_LIGHT_RENDER_TYPE =
            createRenderType("overgrown_vegetation_liana_droop_light", DROOP_LIGHT_TEXTURE);

    /**
     * 分割点(0..{@link #SEGMENTS})ごとのパック済み光量を使い回す再利用可能なスクラッチ配列。
     * ブロックエンティティのレンダリングはレンダースレッド上で1本ずつ直列に行われる
     * (並行実行されない)ため、static な可変配列を毎フレーム上書きして使い回しても安全——
     * {@link #render} が毎回呼び出しの冒頭で全要素を書き直してから使うので、前回描画した
     * 別の蔦の値が混ざることもない。光量だけは(座標などと違って)時間帯や周囲の光源で
     * 変わりうるため、{@link #GEOMETRY_CACHE} には含めず毎フレーム計算し直す
     * (クラスの Javadoc「軽量化その1」参照)。
     */
    private static final int[] SCRATCH_LIGHT = new int[SEGMENTS + 1];

    /**
     * ブロックエンティティ1個につき一度だけ計算する曲線ジオメトリのキャッシュ
     * (クラスの Javadoc「軽量化その2」参照)。key はブロックエンティティの参照そのもの
     * (このクラスは独自の equals/hashCode を定義していないので参照同一性で比較される——
     * 同じインスタンスなら同じキャッシュを指すだけでよく、これで正しい)。
     * {@link WeakHashMap} なので、対応するブロックエンティティが他から一切参照されなくなれば
     * (チャンクアンロード等)エントリも自動的にGCされ、明示的な後始末は不要。
     */
    private static final Map<CorruptedLianaBlockEntity, CurveGeometryCache> GEOMETRY_CACHE = new WeakHashMap<>();

    /**
     * 曲線ジオメトリのキャッシュ本体。{@link #render} が最初の呼び出し時だけ
     * {@link #buildGeometryCache} で組み立て、以後は使い回す。
     *
     * @param sx/sy/sz          ブロック中心を原点にするための平行移動量(始点の相対座標)
     * @param px/pz             主リボンの幅の半分ぶんのベクトル(経路の水平成分に直交)
     * @param dirX/dirZ         垂れ蔦の面が向く方向(px/pzを90度回転させたもの、経路の進行方向そのもの)
     * @param x/y/z             分割点(0..{@link #SEGMENTS})ごとの相対座標
     * @param length            分割点ごとの、始点からの累積経路長
     * @param sourceLightPos/targetLightPos 光量取得用の代表位置
     * @param droopIndices/droopLengths/droopIsLight 垂れ蔦を実際に生やす分割点の添字・長さ・
     *                                      発光する見た目かどうか(いずれも
     *                                      {@link CorruptedLianaBlockEntity} から読んだ、
     *                                      サーバーが生成時に1回だけ決定した結果をそのまま
     *                                      コピーしただけ——クラスの Javadoc「発光する垂れ蔦」参照)
     */
    private record CurveGeometryCache(float sx, float sy, float sz, float px, float pz, float dirX, float dirZ,
            float[] x, float[] y, float[] z, float[] length,
            BlockPos sourceLightPos, BlockPos targetLightPos,
            int[] droopIndices, float[] droopLengths, boolean[] droopIsLight) {
    }

    public CorruptedLianaRenderer() {
    }

    /**
     * テクスチャ付きの蔦を描画するための独自RenderTypeを組み立てる。クラスのJavadoc参照。
     * 半透明合成({@code TRANSLUCENT_TRANSPARENCY})にしてあるので、テクスチャの縁を
     * 完全な不透明(アルファ0/255の2値)にする必要はなく、葉のような柔らかい輪郭も使える。
     */
    private static RenderType createRenderType(String name, ResourceLocation texture) {
        return RenderType.create(
                name,
                DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                VertexFormat.Mode.QUADS,
                256,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_TEXT_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setCullState(RenderStateShard.NO_CULL)
                        .createCompositeState(false)
        );
    }

    /**
     * 曲線が実際に占める範囲(始点〜終点を包む箱に、垂れ下がる分の下方向の余白と
     * リボンの太さぶんの余白を足したもの)をカリング判定用に返す。垂れ蔦の分の下方向の
     * 余白({@link #DROOP_MAX_LENGTH})も含めておく。
     * 始点・終点が未設定(サーバーからまだ同期されていない一瞬)の間は、マーカー自身の
     * 1x1x1(既定実装と同じ)にフォールバックする。
     */
    @Override
    public AABB getRenderBoundingBox(CorruptedLianaBlockEntity blockEntity) {
        AABB cached = blockEntity.getRenderBoxCache();
        if (cached != null) {
            return cached;
        }
        Vec3 source;
        Vec3 target;
        if (blockEntity.hasSwag()) {
            source = blockEntity.getSwagStart();
            target = blockEntity.getSwagEnd();
        } else {
            BlockPos sourcePos = blockEntity.getSourcePos();
            BlockPos targetPos = blockEntity.getTargetPos();
            if (sourcePos == null || targetPos == null) {
                // 未同期の一瞬。この状態はキャッシュしない(同期が届いたら本来の範囲を計算する)。
                return new AABB(blockEntity.getBlockPos());
            }
            source = Vec3.atLowerCornerOf(sourcePos);
            target = Vec3.atLowerCornerOf(targetPos).add(1.0, 1.0, 1.0);
        }
        AABB box = new AABB(source, target)
                .expandTowards(0.0, -blockEntity.getSag() - DROOP_MAX_LENGTH, 0.0)
                .inflate(0.5);
        // 毎フレーム 2 回(フラスタム判定 + 距離判定)呼ばれ、蔦の数だけ AABB を作っていたので、
        // 始点・終点・垂れが変わるまで使い回す(ブロックエンティティ側の setter/load が捨てる)。
        blockEntity.setRenderBoxCache(box);
        return box;
    }

    /** この BER の描画距離(ブロック)。バニラ既定の 64 より短い。 */
    private static final int VIEW_DISTANCE = 32;

    /**
     * 実際の判定は {@link #shouldRender} を全面的にオーバーライドして行うが、意図を明示するために
     * こちらも合わせてある。
     */
    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    /**
     * 蔦は最大 {@code vineMaxLength}(既定 30)ブロック離れた木同士を繋ぐため、アンカー(マーカー
     * ブロック)からの距離だけで判定すると、曲線の遠い側の近くに立っているのに描画されない、という
     * 不自然さが出る。そこで曲線が占める範囲({@link #getRenderBoundingBox})との最短距離が
     * {@link #VIEW_DISTANCE} ブロック以内かどうかで判定する。
     */
    @Override
    public boolean shouldRender(CorruptedLianaBlockEntity blockEntity, Vec3 cameraPos) {
        AABB box = getRenderBoundingBox(blockEntity);
        double dx = Math.max(0.0, Math.max(box.minX - cameraPos.x, cameraPos.x - box.maxX));
        double dy = Math.max(0.0, Math.max(box.minY - cameraPos.y, cameraPos.y - box.maxY));
        double dz = Math.max(0.0, Math.max(box.minZ - cameraPos.z, cameraPos.z - box.maxZ));
        return dx * dx + dy * dy + dz * dz <= (double) VIEW_DISTANCE * VIEW_DISTANCE;
    }

    @Override
    public void render(CorruptedLianaBlockEntity blockEntity, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        Level level = blockEntity.getLevel();
        if (level == null) {
            return;
        }

        // 曲線ジオメトリはこのブロックエンティティにつき一度だけ計算すればよい
        // (始点・終点・sagは生成後に変化しないため——クラスの Javadoc「軽量化その2」参照)。
        // 2回目以降の呼び出しではキャッシュを読むだけで、curvePoint/RandomSource消費/invSqrt
        // などの計算を一切やり直さない。
        CurveGeometryCache cache = GEOMETRY_CACHE.get(blockEntity);
        if (cache == null) {
            cache = buildGeometryCache(blockEntity);
            if (cache == null) {
                // 始点・終点が未設定(サーバーからまだ同期されていない一瞬)。次のフレームで
                // 再試行する(キャッシュに入れず、毎回 buildGeometryCache を呼び直させる)。
                return;
            }
            GEOMETRY_CACHE.put(blockEntity, cache);
        }

        // 光量だけは時間帯・周囲の光源で本当に変わりうるので、毎フレーム計算し直す。
        int sourceBlockLight = level.getBrightness(LightLayer.BLOCK, cache.sourceLightPos());
        int sourceSkyLight = level.getBrightness(LightLayer.SKY, cache.sourceLightPos());
        int targetBlockLight = level.getBrightness(LightLayer.BLOCK, cache.targetLightPos());
        int targetSkyLight = level.getBrightness(LightLayer.SKY, cache.targetLightPos());
        for (int i = 0; i <= SEGMENTS; i++) {
            SCRATCH_LIGHT[i] = lightAt(i, sourceBlockLight, targetBlockLight, sourceSkyLight, targetSkyLight);
        }

        poseStack.pushPose();
        poseStack.translate(cache.sx(), cache.sy(), cache.sz());
        Matrix4f pose = poseStack.last().pose();

        VertexConsumer mainBuffer = bufferSource.getBuffer(MAIN_RENDER_TYPE);
        for (int i = 0; i < SEGMENTS; i++) {
            addRibbonSegment(mainBuffer, pose, cache, i);
        }

        // 垂れ蔦: どの分割点に生えるか・どんな長さか・発光する見た目かどうかは、サーバー側の
        // TreeVineConnector#placeDroops が生成時に既に決定済み(クラスの Javadoc「発光する
        // 垂れ蔦」参照)なので、ここでは同期済みの配列を読んで添字を舐めるだけ。
        //
        // 注意: droopBuffer/droopLightBuffer を2つとも先にまとめて取得してはいけない。
        // MAIN_RENDER_TYPE/DROOP_RENDER_TYPE/DROOP_LIGHT_RENDER_TYPE はバニラの「固定」
        // RenderType集合に含まれないカスタム RenderType のため、MultiBufferSource 実装
        // (レベル描画時)は共有の一時バッファを使い回しており、**同時に「書き込み中」に
        // できるのは1つだけ**——別の RenderType を bufferSource.getBuffer() で要求した時点で、
        // 直前の RenderType 用バッファは即座に閉じられてしまう(以後そこへ書き込もうとすると
        // 「Not building!」で例外になる)。実際に2026-08-24、発光する垂れ蔦の追加時にこの2行を
        // ループの外へ先出ししてしまい、木が生えて新しい蔦が描画された瞬間にクラッシュする
        // 不具合を起こした。安全なパターンは主リボン(mainBuffer)がそうしているように、
        // 「そのバッファへの書き込みを全て終えてから次のRenderTypeを要求する」こと——
        // ここでは1本ごとに必要な方だけをその場で取得する(同じ種類が連続する場合は
        // 内部的に軽い同一判定で済むため、余計なコストにはならない)。
        int[] droopIndices = cache.droopIndices();
        float[] droopLengths = cache.droopLengths();
        // 発光しているかどうかは「蔦の実」の熟成/収穫で後から変わりうるので、ジオメトリキャッシュ
        // (生成時に1回だけ組む)ではなくブロックエンティティから毎フレーム読み直す。分割点の座標・
        // 長さ・生える位置は変わらないのでキャッシュのままでよい。
        boolean[] droopIsLight = blockEntity.getDroopIsLight();
        for (int i = 0; i < droopIndices.length; i++) {
            boolean lit = i < droopIsLight.length && droopIsLight[i];
            VertexConsumer buffer = bufferSource.getBuffer(lit ? DROOP_LIGHT_RENDER_TYPE : DROOP_RENDER_TYPE);
            addDroopingTendril(buffer, pose, cache, droopIndices[i], droopLengths[i]);
        }

        poseStack.popPose();
    }

    /**
     * {@code blockEntity} の始点・終点・swag・sag から、曲線ジオメトリ一式を1回だけ計算して
     * {@link CurveGeometryCache} にまとめる。始点・終点が未設定(サーバーからまだ同期されて
     * いない一瞬)なら {@code null} を返す(呼び出し元はこのフレームの描画を諦め、次のフレーム
     * で再試行する——キャッシュには入れない)。
     *
     * <p>ここでの計算内容自体は、以前(軽量化その1の時点)まで {@link #render} 本体にあった
     * ものをそのまま移しただけで、数式は一切変えていない。
     */
    @Nullable
    private static CurveGeometryCache buildGeometryCache(CorruptedLianaBlockEntity blockEntity) {
        boolean hasSwag = blockEntity.hasSwag();
        // 実際の描画に使う始点・終点の絶対座標(ブロック中心 +0.5 込み)。swag ならそのまま実座標、
        // そうでなければ木の原木ブロックの中心。
        double sourceX;
        double sourceY;
        double sourceZ;
        double targetX;
        double targetY;
        double targetZ;
        // ライト取得用の代表位置(必ず空気/開けた場所になるよう選ぶ、下記コメント参照)。
        BlockPos sourceLightPos;
        BlockPos targetLightPos;

        if (hasSwag) {
            Vec3 swagStart = blockEntity.getSwagStart();
            Vec3 swagEnd = blockEntity.getSwagEnd();
            sourceX = swagStart.x;
            sourceY = swagStart.y;
            sourceZ = swagStart.z;
            targetX = swagEnd.x;
            targetY = swagEnd.y;
            targetZ = swagEnd.z;
            // swag の始点・終点は元々ほかの蔦の曲線上(=開けた空間)の点なので、
            // 不透明ブロック回避のオフセットは不要でそのまま丸めた位置で良い。
            sourceLightPos = BlockPos.containing(swagStart);
            targetLightPos = BlockPos.containing(swagEnd);
        } else {
            BlockPos source = blockEntity.getSourcePos();
            BlockPos target = blockEntity.getTargetPos();
            if (source == null || target == null) {
                return null;
            }
            sourceX = source.getX() + 0.5;
            sourceY = source.getY() + 0.5;
            sourceZ = source.getZ() + 0.5;
            targetX = target.getX() + 0.5;
            targetY = target.getY() + 0.5;
            targetZ = target.getZ() + 0.5;

            // source/target は木の原木(不透明ブロック)そのものの座標であり、そこに格納されている
            // 光レベルは隣接する空気(=実際にリボンが描かれる場所)よりも大幅に暗いことが多い
            // (不透明ブロック自身の光レベルは、その面の外側の空気の光レベルとは別物)。
            // そのため、幹から相手側へ1歩踏み出した位置(TreeVineConnector#placeVine が主アンカーを
            // 置くのと同じ位置、必ず空気/置換可能なマス)でライトを取得することで、実際にリボンが
            // 見えている空間の明るさに近い値を使う。
            int dirX = Integer.signum(target.getX() - source.getX());
            int dirY = Integer.signum(target.getY() - source.getY());
            int dirZ = Integer.signum(target.getZ() - source.getZ());
            sourceLightPos = source.offset(dirX, dirY, dirZ);
            targetLightPos = target.offset(-dirX, -dirY, -dirZ);
        }

        BlockPos self = blockEntity.getBlockPos();
        // ブロック中心を通したい。始点を原点にして、そこからの相対座標で全部計算する
        // (バニラの renderLeash が「エンティティの位置」を原点にするのと同じ考え方)。
        float sx = (float) (sourceX - self.getX());
        float sy = (float) (sourceY - self.getY());
        float sz = (float) (sourceZ - self.getZ());
        float dx = (float) (targetX - sourceX);
        float dy = (float) (targetY - sourceY);
        float dz = (float) (targetZ - sourceZ);
        float sag = blockEntity.getSag();

        // 進行方向の水平成分に直交するベクトルを求め、主リボンの幅にする。
        // 水平方向の移動がほぼ無い(真上/真下に近い)蔦は invSqrt(0) で破綻するので、
        // その場合は X 軸方向に幅を持たせる。
        float horizontalLenSq = dx * dx + dz * dz;
        float perpX;
        float perpZ;
        if (horizontalLenSq > 1.0E-5F) {
            float invLen = Mth.invSqrt(horizontalLenSq);
            perpX = dz * invLen;
            perpZ = -dx * invLen;
        } else {
            perpX = 1.0F;
            perpZ = 0.0F;
        }
        float px = perpX * WIDTH / 2.0F;
        float pz = perpZ * WIDTH / 2.0F;
        // 垂れ蔦の面は perpX/perpZ(主リボンの幅の向き、経路と直交)ではなく、経路の
        // 進行方向そのもの(perpX/perpZ を90度回転させただけ)を向かせる
        // ({@link #addDroopingTendril} 参照)。
        float dirX = -perpZ;
        float dirZ = perpX;

        // 分割点(0..SEGMENTS)ごとの座標・始点からの累積経路長を1回のループでまとめて計算する。
        float[] x = new float[SEGMENTS + 1];
        float[] y = new float[SEGMENTS + 1];
        float[] z = new float[SEGMENTS + 1];
        float[] length = new float[SEGMENTS + 1];
        for (int i = 1; i <= SEGMENTS; i++) {
            float t = (float) i / SEGMENTS;
            float px_i = dx * t;
            float py_i = dy * t - 4.0F * sag * t * (1.0F - t);
            float pz_i = dz * t;
            float stepDx = px_i - x[i - 1];
            float stepDy = py_i - y[i - 1];
            float stepDz = pz_i - z[i - 1];
            x[i] = px_i;
            y[i] = py_i;
            z[i] = pz_i;
            length[i] = length[i - 1] + Mth.sqrt(stepDx * stepDx + stepDy * stepDy + stepDz * stepDz);
        }

        // 垂れ蔦(どの分割点に、どんな長さで、発光する見た目かどうか)は、サーバー側の
        // TreeVineConnector#placeDroops が生成時に既に決定し、ブロックエンティティに同期済み
        // ——ここでは配列をそのまま読むだけ(クラスの Javadoc「発光する垂れ蔦」参照)。
        return new CurveGeometryCache(sx, sy, sz, px, pz, dirX, dirZ, x, y, z, length,
                sourceLightPos, targetLightPos,
                blockEntity.getDroopIndices(), blockEntity.getDroopLengths(), blockEntity.getDroopIsLight());
    }

    /**
     * 分割点 {@code index} における、始点・終点のライトマップを線形補間したパック済み光量。
     *
     * <p>{@link #render} の冒頭にある事前計算ループから、分割点1個につきちょうど1回だけ
     * 呼ばれる(以前は主リボンの各区間が両端で毎回呼んでいたため、境界の分割点が2回ずつ
     * 計算される無駄があった——クラスの Javadoc「軽量化」参照)。
     */
    private static int lightAt(int index, int sourceBlockLight, int targetBlockLight,
            int sourceSkyLight, int targetSkyLight) {
        float t = (float) index / SEGMENTS;
        int blockLight = (int) Mth.lerp(t, (float) sourceBlockLight, (float) targetBlockLight);
        int skyLight = (int) Mth.lerp(t, (float) sourceSkyLight, (float) targetSkyLight);
        return LightTexture.pack(blockLight, skyLight);
    }

    /**
     * 主リボンの区間 {@code [index, index+1]} を、互いに直交する2枚の平面(十字)ぶんの
     * 四角形2枚として追加する({@link #MAIN_TEXTURE} をV方向(経路長)に繰り返し貼る)。
     * 以前の三角形ストリップ版で作っていた4隅の頂点(A/B/C/D)を、そのまま四角形として
     * 独立させただけで見た目は変わらない(クラスのJavadoc参照)。
     *
     * <p>分割点の座標・累積経路長は {@link #buildGeometryCache} が計算済みの {@code cache} から
     * 読むだけ、光量だけは {@link #render} が毎フレーム書き直す {@link #SCRATCH_LIGHT} から読む
     * (このメソッド自身は曲線の式を一切計算しない——クラスのJavadoc「軽量化」参照)。
     */
    private static void addRibbonSegment(VertexConsumer buffer, Matrix4f pose, CurveGeometryCache cache, int index) {
        float px = cache.px();
        float pz = cache.pz();
        float p0x = cache.x()[index];
        float p0y = cache.y()[index];
        float p0z = cache.z()[index];
        float p1x = cache.x()[index + 1];
        float p1y = cache.y()[index + 1];
        float p1z = cache.z()[index + 1];
        float v0 = cache.length()[index] / TILE_LENGTH;
        float v1 = cache.length()[index + 1] / TILE_LENGTH;
        int light0 = SCRATCH_LIGHT[index];
        int light1 = SCRATCH_LIGHT[index + 1];

        // 面1(左端が上、右端が下へ傾いた面): A=(x-px,y+WIDTH,z+pz), B=(x+px,y,z-pz)
        addQuad(buffer, pose,
                p0x - px, p0y + WIDTH, p0z + pz, 0.0F, v0, light0,
                p0x + px, p0y, p0z - pz, 1.0F, v0, light0,
                p1x + px, p1y, p1z - pz, 1.0F, v1, light1,
                p1x - px, p1y + WIDTH, p1z + pz, 0.0F, v1, light1);

        // 面2(面1と逆に傾いた面): C=(x-px,y,z+pz), D=(x+px,y+WIDTH,z-pz)
        // この2枚を組み合わせることで、単なる平らなリボンではなく、どの角度から見ても
        // 太さがある(ねじれたような)見た目になる。
        addQuad(buffer, pose,
                p0x - px, p0y, p0z + pz, 0.0F, v0, light0,
                p0x + px, p0y + WIDTH, p0z - pz, 1.0F, v0, light0,
                p1x + px, p1y + WIDTH, p1z - pz, 1.0F, v1, light1,
                p1x - px, p1y, p1z + pz, 0.0F, v1, light1);
    }

    /**
     * 主リボンの分割点 {@code index} から、指定した長さ {@code length} で鉛直下向きに垂れる
     * 小さな蔦を追加する。間隔・生やすかどうか・長さの決定は呼び出し側({@link #render}内の
     * ループ)がまとめて行うので、このメソッド自体は乱数を引かない。
     *
     * <p>{@link #DROOP_TEXTURE} は正方形(16x16)で、上部の葉っぱ状の広がりと下部の細い茎を
     * 1枚に収めた絵柄になっている。これをタイル状に繰り返さず**1回だけ、歪みなく**表示する
     * ため、幅(横)を高さ(={@code length})と同じ値にして正方形の面として描く
     * (テクスチャの縦横比と面の縦横比を一致させることで、上部の葉が横に潰れたり
     * 下部の茎が伸びすぎたりしないようにしている)。そのため見た目の太さは
     * {@code length} が長い蔦ほど太くなる(主リボンのような固定の太さではない)。
     *
     * <p>バニラの草花のような互いに直交する2枚の平面(十字)ではなく、**面1枚だけ**を
     * 経路の進行方向({@code dirX}/{@code dirZ}、水平成分)を向いた面として描く。
     * 主リボンの幅方向(進行方向に**直交**する水平方向)をそのまま使うと、真横(進行方向と
     * 直交する向き)から見たときに主リボンは面(幅広)に見えるのに垂れ蔦は逆に細い断面にしか
     * 見えず、90度ズレて見えてしまう。進行方向そのものを向かせることで、主リボンを面として
     * 見られる角度では垂れ蔦も面として見え、向きが揃う。
     */
    private static void addDroopingTendril(VertexConsumer buffer, Matrix4f pose, CurveGeometryCache cache,
            int index, float length) {
        // 分割点の座標は buildGeometryCache が計算済みの cache から読むだけ、光量だけは
        // render が毎フレーム書き直す SCRATCH_LIGHT から読む(クラスのJavadoc「軽量化」参照)。
        float ox = cache.x()[index];
        // 主リボンの下端(中心線)からではなく、そこから少し持ち上げた位置を起点にする
        // ({@link #DROOP_ORIGIN_LIFT} 参照)。始点・終点も超えて浮かないよう安全側にクランプはしない
        // ——垂れ蔦は経路の中間の候補地点にしか生えない({@link #render} 参照)ため、通常は問題にならない。
        float oy = cache.y()[index] + DROOP_ORIGIN_LIFT;
        float oz = cache.z()[index];
        int light = SCRATCH_LIGHT[index];
        // 幅を長さと同じにする(テクスチャが正方形なので、面も正方形にすれば歪まない)。
        float half = length / 2.0F;
        float px = cache.dirX() * half;
        float pz = cache.dirZ() * half;
        // V は常に 0〜1(テクスチャ全体)。タイル表示ではなく1回だけ表示する。
        float bottomV = 1.0F;

        addQuad(buffer, pose,
                ox - px, oy, oz - pz, 0.0F, 0.0F, light,
                ox + px, oy, oz + pz, 1.0F, 0.0F, light,
                ox + px, oy - length, oz + pz, 1.0F, bottomV, light,
                ox - px, oy - length, oz - pz, 0.0F, bottomV, light);
    }

    /** 4頂点(それぞれ座標・UV・パック済み光量)から、白色(=テクスチャの色をそのまま出す)の四角形を1枚積む。 */
    private static void addQuad(VertexConsumer buffer, Matrix4f pose,
            float x0, float y0, float z0, float u0, float v0, int light0,
            float x1, float y1, float z1, float u1, float v1, int light1,
            float x2, float y2, float z2, float u2, float v2, int light2,
            float x3, float y3, float z3, float u3, float v3, int light3) {
        buffer.addVertex(pose, x0, y0, z0).setColor(1.0F, 1.0F, 1.0F, 1.0F).setUv(u0, v0).setLight(light0);
        buffer.addVertex(pose, x1, y1, z1).setColor(1.0F, 1.0F, 1.0F, 1.0F).setUv(u1, v1).setLight(light1);
        buffer.addVertex(pose, x2, y2, z2).setColor(1.0F, 1.0F, 1.0F, 1.0F).setUv(u2, v2).setLight(light2);
        buffer.addVertex(pose, x3, y3, z3).setColor(1.0F, 1.0F, 1.0F, 1.0F).setUv(u3, v3).setLight(light3);
    }
}
