package com.yayuy.overgrownvegetation.block;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.mixin.LevelChunkTickerInvoker;
import com.yayuy.overgrownvegetation.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link CorruptedLianaBlock} の中身。蔦1本ぶんの始点・終点・垂れ具合を保持する。
 *
 * <p>始点({@link #sourcePos})・終点({@link #targetPos})は、このブロック自身の座標ではなく
 * 両端の木の原木ブロックそのものの絶対座標(=蔦が実際に触れて見える場所)。このブロックは
 * その経路上のどこか(始点側の幹の近くの1マス、以下「主アンカー」)に置かれ、描画は
 * {@code com.yayuy.overgrownvegetation.client.CorruptedLianaRenderer} が
 * {@link #sourcePos} → {@link #targetPos} を毎フレーム描画することで行う。水平方向は常に
 * 始点・終点を結ぶ直線(曲線補間も折れ線化もしない)で、曲線補間するのは {@link #sag} による
 * 垂直方向の垂れのみ。
 *
 * <h2>ぶら下がり蔦(swag、{@link #hasSwag()})</h2>
 * <p>{@link com.yayuy.overgrownvegetation.worldgen.TreeVineConnector} が同じ始点・終点の
 * 組み合わせに対して複数本の蔦を重ねて生成する際(長距離接続、「木々をつなぐ蔦」参照)、
 * 垂れ具合が一番小さい1本だけは通常通り(=木の原木ブロックそのものである {@link #sourcePos}
 * → {@link #targetPos} を直接結ぶ)描画されるが、残りの(垂れ具合が大きい)蔦はそれぞれ、
 * 一番小さい蔦の曲線上のランダムな2点({@link #swagStart}/{@link #swagEnd}、ブロック格子に
 * 縛られない実座標)を独自の始点・終点として、そこの間だけを**自分自身の垂れ具合({@link #sag}）
 * でなめらかに垂れ下がる**、木の原木には一切届かない短いミニ曲線になる。ちょうどブンティング
 * 飾りのように、一番たれ具合の低い蔦からさらに小さな蔦がいくつも垂れ下がって見える。
 * {@link #sourcePos}/{@link #targetPos} はこの場合もマーカー配置・明るさ取得の目安として
 * {@code swagStart}/{@code swagEnd} を丸めた座標を持つが、実際の描画には使われない
 * ({@link #hasSwag()} が true の間は {@link #swagStart}/{@link #swagEnd} が優先される)。
 *
 * <h2>クリック判定用の副アンカー</h2>
 * <p>曲線そのものには当たり判定が無い(ポリゴン描画のみ)ため、主アンカーの1マスしか
 * クリックできないと「見えている蔦のどこを右クリックしても反応しない」状態になってしまう。
 * これを解消するため、{@link com.yayuy.overgrownvegetation.worldgen.TreeVineConnector} は
 * 曲線の経路に沿って(見えないマーカーだけの)副アンカーを複数個追加で置き、各副アンカーの
 * {@link #primaryPos} に主アンカーの座標を持たせておく。主アンカー側は
 * {@link #secondaryPositions} に全副アンカーの座標一覧を持つ。
 * どちらか一方が切られたら({@link CorruptedLianaBlock} 参照)、主アンカー経由で
 * 副アンカー一覧をたどり、蔦を構成する全ブロックをまとめて取り除く。
 *
 * <p>{@link com.yayuy.overgrownvegetation.worldgen.TreeVineConnector} がこれらのセッターを
 * 生成時に1回呼ぶだけで、以後変化しない(静的な装飾)。
 *
 * <h2>親子関係({@link #parentPositions}/{@link #childPositions}、2026-08-24追加)</h2>
 * <p>蔦が別の蔦の曲線上を始点・終点にして生える場合(重複蔦が親の曲線からぶら下がる場合、
 * 別の重複蔦をまたぐ場合、通常の蔦の終点が既存の別の蔦になる場合——いずれも
 * {@link com.yayuy.overgrownvegetation.worldgen.TreeVineConnector} 参照)、生成された側の
 * **主アンカー**は自分がどの主アンカー(たち)の曲線に依存しているかを {@link #parentPositions}
 * に持つ(通常1個、別の重複蔦をまたぐ場合のみ2個)。逆に、依存された側の主アンカーは
 * 自分に依存している子の主アンカー一覧を {@link #childPositions} に持つ
 * ({@link #primaryPos}/{@link #secondaryPositions} と対になる、主アンカーどうしの関係である点に
 * 注意——副アンカーはこの関係に一切関与しない)。{@link CorruptedLianaBlock#cut} が主アンカーを
 * 切る際、この {@link #childPositions} を再帰的にたどって依存する蔦をまとめて連鎖的に除去する
 * (「元の蔦が切られたら、そこから生えている蔦も一緒に落ちる」という直感に合わせるため)。
 * 木の原木どうしを直接結ぶだけの通常の蔦(トップレベルの主線で、既存の蔦を終点にしていないもの)は
 * {@link #parentPositions} が常に空——依存先が無いので当然カットされない。
 *
 * <h2>垂れ蔦(droop)の生成位置({@link #droopIndices} 等、2026-08-24追加)</h2>
 * <p>主リボンの経路上のどこにどんな長さの垂れ蔦が生えるか、そのうち何本が発光する
 * 見た目(overgrown_liana_droop_light)になるかは、以前はクライアント側の
 * {@code CorruptedLianaRenderer} が始点・終点座標から作った決定的な乱数列を毎フレーム
 * 消費して導出していた。発光する垂れ蔦は実際に(見えない光源ブロックを裏側に置くことで)
 * 世界の明るさに影響を与える必要があり、クライアント専用の描画ロジックだけではそれができない
 * (光の伝播はサーバー権威のワールドデータに基づくため)。そのため
 * {@link com.yayuy.overgrownvegetation.worldgen.TreeVineConnector} が生成時に1回だけ
 * サーバー側でこの決定を行い、結果({@link #droopIndices}/{@link #droopLengths}/
 * {@link #droopIsLight}、いずれも同じ添字が同じ1本の垂れ蔦に対応する並列配列)をここに
 * 保存してクライアントへ同期する。レンダラーは自前で乱数を引く代わりにこの配列を読むだけになった
 * (見た目・確率分布は変えていない——決定の主体がクライアントからサーバーへ移っただけ)。
 * 発光する垂れ蔦ぶんだけ実際に置いた光源ブロックの座標は {@link #lightBlockPositions} に持ち、
 * この蔦(またはその依存元)が {@link CorruptedLianaBlock#cut} で切られた際に一緒に撤去する。
 */
public class CorruptedLianaBlockEntity extends BlockEntity {

    @Nullable
    private BlockPos sourcePos;
    @Nullable
    private BlockPos targetPos;
    /** 経路の中央がどれだけ下へ垂れるか(ブロック単位)。曲線の描画にのみ使う(この垂直方向のみ曲線)。 */
    private float sag;
    /**
     * ぶら下がり蔦(swag)の実際の始点(ブロック格子に縛られない実座標)。null なら swag ではなく、
     * {@link #sourcePos}/{@link #targetPos}(木の原木ブロック)を直接結ぶ通常の蔦。
     */
    @Nullable
    private Vec3 swagStart;
    /** ぶら下がり蔦(swag)の実際の終点。{@link #swagStart} と一緒に null/非null が揃う。 */
    @Nullable
    private Vec3 swagEnd;
    /** 自分が副アンカーのとき、主アンカーの座標。主アンカー自身なら null。 */
    @Nullable
    private BlockPos primaryPos;
    /** 自分が主アンカーのとき、全副アンカーの座標一覧。副アンカー自身なら空リスト。 */
    private List<BlockPos> secondaryPositions = List.of();
    /**
     * 自分(主アンカー)の曲線が依存している親の主アンカー一覧(0〜2個)。空なら依存先の無い
     * 独立した蔦(木の原木どうしを直接結ぶ通常の蔦)。クラスの Javadoc「親子関係」参照。
     */
    private List<BlockPos> parentPositions = List.of();
    /** 自分(主アンカー)の曲線に依存している子の主アンカー一覧。クラスの Javadoc「親子関係」参照。 */
    private List<BlockPos> childPositions = List.of();
    /**
     * 垂れ蔦が生える分割点の添字一覧(0..24)。{@link #droopLengths}/{@link #droopIsLight} と
     * 同じ添字が同じ1本の垂れ蔦に対応する並列配列。クラスの Javadoc「垂れ蔦の生成位置」参照。
     */
    private int[] droopIndices = new int[0];
    /** 各垂れ蔦の長さ(ブロック単位)。{@link #droopIndices} と対になる並列配列。 */
    private float[] droopLengths = new float[0];
    /** 各垂れ蔦が発光する見た目(overgrown_liana_droop_light)かどうか。{@link #droopIndices} と対になる並列配列。 */
    private boolean[] droopIsLight = new boolean[0];
    /**
     * 発光する垂れ蔦ぶんだけ実際に置いた(見えない)光源ブロックの座標一覧。
     * この蔦が {@link CorruptedLianaBlock#cut} で切られる(または依存元として連鎖除去される)際、
     * ここに含まれる座標のブロックも一緒に取り除く。
     */
    private List<BlockPos> lightBlockPositions = List.of();
    /**
     * 登る蔓(2026-11-03 試作、設定 vineClimb)の経路。点ごとに {@link #CLIMB_STRIDE} 個の float
     * (x, y, z = この印のマスの角からの相対座標、nx, nz = 幹の外向きの水平の向き)。空なら今までの蔦。
     * 片端(添字 0)は地面か幹の根元。計算は {@code worldgen.ClimbingVine}、描くのは {@code client.ClimbingVineRenderer}。
     */
    private float[] climbPath = new float[0];
    /** 登る蔓のうち、隣の木へ渡る(揺れる)区間の点の添字 [start, end]。渡らない蔓は -1。 */
    private int climbBridgeStart = -1;
    private int climbBridgeEnd = -1;
    /** 登る蔓の太さの倍率(1 = 本体、束に添う細い蔓・垂れる綱は小さく)。 */
    private float climbScale = 1.0F;
    /** 垂れる綱なら、上の端の揺れの重み(渡りから垂れるなら渡りと同じ、林冠からなら 0)。垂れる綱でなければ -1。 */
    private float climbHangTop = -1.0F;
    /** 蔓の種類の印。bit0 = 本体、bit1 = 林冠の細い蔓(2026-11-03 4 回目の試作)。-1 = 2 回目までの印(太さ 0.6 以上を本体とみなす)。 */
    private int climbFlags = -1;
    public static final int CLIMB_MAIN = 1;
    public static final int CLIMB_CANOPY = 2;
    public static final int CLIMB_STRIDE = 5;

    public CorruptedLianaBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OVERGROWN_LIANA.get(), pos, state);
    }

    @Nullable
    public BlockPos getSourcePos() {
        return sourcePos;
    }

    @Nullable
    public BlockPos getTargetPos() {
        return targetPos;
    }

    public float getSag() {
        return sag;
    }

    public boolean hasSwag() {
        return swagStart != null && swagEnd != null;
    }

    @Nullable
    public Vec3 getSwagStart() {
        return swagStart;
    }

    @Nullable
    public Vec3 getSwagEnd() {
        return swagEnd;
    }

    /**
     * 蔦の始点・終点(両方とも木の原木ブロックの絶対座標)・垂れ具合を設定し、クライアントへ
     * 同期する。ぶら下がり蔦(swag)ではない通常の蔦を置く場合に呼ぶ({@link #setSwagEndpoints}
     * との使い分けは呼び出し側の {@link com.yayuy.overgrownvegetation.worldgen.TreeVineConnector}
     * 参照)。
     */
    public void setEndpoints(BlockPos source, BlockPos target, float sag) {
        this.sourcePos = source;
        this.targetPos = target;
        this.sag = sag;
        this.swagStart = null;
        this.swagEnd = null;
        this.renderBoxCache = null;
        syncToClient();
    }

    /**
     * 描画側({@code CorruptedLianaRenderer#getRenderBoundingBox})が求めた描画範囲のキャッシュ。
     * 毎フレーム・蔦 1 本につき 2 回(フラスタム判定と距離判定)問い合わせられるので、始点・終点・
     * 垂れ具合が変わらない限り使い回す(変わる setter と {@link #loadAdditional} で捨てる)。
     * クライアント専用のフィールドで、保存はしない。
     */
    @Nullable
    private AABB renderBoxCache;

    /** 主アンカーの狙いの箱(CorruptedLianaBlock#getShape が経路から求める。2026-11-08)。経路が変わる setClimb と loadAdditional で捨てる。保存しない。 */
    @Nullable
    private net.minecraft.world.phys.shapes.VoxelShape shapeCache;

    @Nullable
    public net.minecraft.world.phys.shapes.VoxelShape getShapeCache() {
        return shapeCache;
    }

    public void setShapeCache(@Nullable net.minecraft.world.phys.shapes.VoxelShape shape) {
        this.shapeCache = shape;
    }

    @Nullable
    public AABB getRenderBoxCache() {
        return renderBoxCache;
    }

    public void setRenderBoxCache(@Nullable AABB box) {
        this.renderBoxCache = box;
    }

    /**
     * ぶら下がり蔦(swag)として、{@code swagStart}〜{@code swagEnd}(ブロック格子に縛られない
     * 実座標、別の蔦の曲線上の点)の間だけを {@code sag} で垂れ下がるよう設定し、クライアントへ
     * 同期する。{@link #sourcePos}/{@link #targetPos} には目安として {@code swagStart}/
     * {@code swagEnd} を丸めた座標を持たせる(マーカー配置・明るさ取得用、描画には使わない)。
     */
    public void setSwagEndpoints(Vec3 swagStart, Vec3 swagEnd, float sag) {
        this.sourcePos = BlockPos.containing(swagStart);
        this.targetPos = BlockPos.containing(swagEnd);
        this.sag = sag;
        this.swagStart = swagStart;
        this.swagEnd = swagEnd;
        this.renderBoxCache = null;
        syncToClient();
    }

    public boolean hasClimb() {
        return climbPath.length >= CLIMB_STRIDE * 2;
    }

    public float[] getClimbPath() {
        return climbPath;
    }

    public int getClimbBridgeStart() {
        return climbBridgeStart;
    }

    public int getClimbBridgeEnd() {
        return climbBridgeEnd;
    }

    public float getClimbScale() {
        return climbScale;
    }

    public float getClimbHangTop() {
        return climbHangTop;
    }

    public int getClimbFlags() {
        return climbFlags;
    }

    /** 本体の蔓か(束に添う細い蔓・垂れる綱でない)。 */
    public boolean isClimbMain() {
        return climbFlags >= 0 ? (climbFlags & CLIMB_MAIN) != 0 : climbScale >= 0.6F && climbHangTop < 0.0F;
    }

    /** 林冠の細い蔓か(葉だけを付け、苔の房は付けない)。 */
    public boolean isClimbCanopy() {
        return climbFlags >= 0 && (climbFlags & CLIMB_CANOPY) != 0;
    }

    public boolean isClimbHanging() {
        return climbHangTop >= 0.0F;
    }

    /** 太さの倍率と垂れる綱かどうか({@link #setClimb} の前に呼ぶ。同期は setClimb が出す)。 */
    public void setClimbStyle(float scale, float hangTop, int flags) {
        this.climbScale = scale;
        this.climbHangTop = hangTop;
        this.climbFlags = flags;
    }

    /**
     * 登る蔓にする。{@code source} は登り始めた幹の根元の原木、{@code target} は最後に貼り付いた幹の原木
     * (木を倒したときの片付け {@code SwampTreeFall} が両端の原木を見るので、今までの蔦と同じ意味で持たせる)。
     */
    public void setClimb(float[] path, int bridgeStart, int bridgeEnd, BlockPos source, BlockPos target) {
        this.climbPath = path.clone();
        this.climbBridgeStart = bridgeStart;
        this.climbBridgeEnd = bridgeEnd;
        this.sourcePos = source;
        this.targetPos = target;
        this.sag = 0.0F;
        this.swagStart = null;
        this.swagEnd = null;
        this.renderBoxCache = null;
        this.shapeCache = null;
        syncToClient();
    }

    @Nullable
    public BlockPos getPrimaryPos() {
        return primaryPos;
    }

    /** このブロックを副アンカーにする(主アンカーの座標を紐付ける)。クライアント同期は不要(描画に関係しないため)。 */
    public void setPrimaryPos(BlockPos primaryPos) {
        this.primaryPos = primaryPos;
        setChanged();
    }

    public List<BlockPos> getSecondaryPositions() {
        return secondaryPositions;
    }

    /** 主アンカーに、紐付く副アンカーの座標一覧を設定する。クライアント同期は不要。 */
    public void setSecondaryPositions(List<BlockPos> positions) {
        this.secondaryPositions = List.copyOf(positions);
        setChanged();
    }

    public List<BlockPos> getParentPositions() {
        return parentPositions;
    }

    /**
     * この主アンカーの曲線が依存している親の主アンカー一覧を設定する(生成時に1回だけ)。
     * クライアント同期は不要(描画にも当たり判定にも関係しない、カット時の連鎖除去にのみ使う)。
     */
    public void setParentPositions(List<BlockPos> positions) {
        this.parentPositions = List.copyOf(positions);
        setChanged();
    }

    public List<BlockPos> getChildPositions() {
        return childPositions;
    }

    /**
     * この主アンカーに依存する子の主アンカーを1個追加する。{@link com.yayuy.overgrownvegetation.worldgen.TreeVineConnector}
     * が子を配置するたびに、その子の親(たち)それぞれに対して1回ずつ呼ぶ(逆方向のリンクである
     * {@link #setParentPositions} と対になる)。クライアント同期は不要。
     */
    public void addChildPosition(BlockPos childPos) {
        List<BlockPos> updated = new ArrayList<>(childPositions);
        updated.add(childPos);
        childPositions = List.copyOf(updated);
        setChanged();
    }

    public int[] getDroopIndices() {
        return droopIndices;
    }

    public float[] getDroopLengths() {
        return droopLengths;
    }

    public boolean[] getDroopIsLight() {
        return droopIsLight;
    }

    /**
     * 垂れ蔦の生成位置一式(生成時に1回だけ決定される)を設定し、クライアントへ同期する
     * (レンダラーが実際にこれを読んで描画するため——クラスの Javadoc「垂れ蔦の生成位置」参照)。
     * 3つの配列は同じ添字が同じ1本の垂れ蔦を指す並列配列で、長さが揃っている前提。
     */
    public void setDroops(int[] indices, float[] lengths, boolean[] isLight) {
        this.droopIndices = indices;
        this.droopLengths = lengths;
        this.droopIsLight = isLight;
        syncToClient();
    }

    public List<BlockPos> getLightBlockPositions() {
        return lightBlockPositions;
    }

    /**
     * 発光する垂れ蔦ぶんだけ実際に置いた光源ブロックの座標一覧を設定する。クライアント同期は
     * 不要(カット時の連鎖除去にのみサーバー側で使うため、描画には関係しない——光源ブロック
     * 自体は既にワールドへ置かれていて、それ自体がクライアントへ同期される)。
     */
    public void setLightBlockPositions(List<BlockPos> positions) {
        this.lightBlockPositions = List.copyOf(positions);
        setChanged();
    }

    // ------------------------------------------------------------------
    // 「蔦の実」— 垂れ蔦の発光(=熟した)/非発光の切り替えと収穫
    //
    // 垂れ蔦(droop)は依然としてブロックを持たない純粋な見た目(レンダラーが描くポリゴン)で、
    // 発光状態は droopIsLight[] の1要素ぶんの真偽値でしかない。ここでは:
    //  - serverTick: 主アンカーだけが、vineFruitRipenIntervalTicks ごとに vineFruitRipenChance で
    //    発光していない垂れ蔦を1本、発光(=熟した)状態にする(+ 見えない光源ブロックを裏に置く)。
    //  - harvestDroop: 素手の右クリックで一番近い発光中の垂れ蔦を1本、非発光へ戻して「蔦の実」を出す。
    // どちらも droopIsLight[] を書き換えて syncToClient() で同期し、光源ブロックの追加/撤去を
    // droopLightPos()(TreeVineConnector#placeDroops と同じ式で先端付近の座標を求める)で行う。
    // ------------------------------------------------------------------

    /**
     * 垂れ蔦の分割点の総数。{@code TreeVineConnector.DROOP_SEGMENTS} および
     * {@code CorruptedLianaRenderer.SEGMENTS} と必ず同じ値であること(droopIndices[] の添字が
     * この分割数を基準に決められているため)。
     */
    private static final int DROOP_SEGMENTS = 24;

    /**
     * {@link #detachTicker} でティッカーを外したかどうか。外したあとで主アンカーに変わったときに
     * {@link #loadAdditional} が付け直すための目印。サーバー側だけのフィールドで、保存はしない
     * (チャンクを読み込み直すとバニラが {@code getTicker} から付け直し、この目印も新しいインスタンスで false に戻る)。
     */
    private boolean tickerDetached;

    /**
     * 主アンカーのサーバーティック。{@link com.yayuy.overgrownvegetation.block.CorruptedLianaBlock#getTicker}
     * から呼ばれる。副アンカー・垂れ蔦を1本も持たない蔦では即座に何もしない。
     * 副アンカーは最初の 1 tick で自分のティッカーを外す({@link #detachTicker})。
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, CorruptedLianaBlockEntity be) {
        if (be.primaryPos == null && !be.segmentsPlaced && be.climbPath.length > 0 && level instanceof ServerLevel serverLevel
                && Math.floorMod(level.getGameTime() + pos.hashCode(), SEGMENT_RETROFIT_SPREAD) == 0) {
            be.retrofitSegments(serverLevel);
        }
        if (be.primaryPos != null || be.droopIndices.length == 0) {
            if (be.primaryPos != null) {
                be.detachTicker(level);
            }
            return;
        }
        int interval = OvergrownVegetationConfig.COMMON.vineFruitRipenIntervalTicks.get();
        if (interval <= 0) {
            return;
        }
        // 蔦ごとに位相をずらし、全ての蔦が同じ tick に一斉に判定しないようにする。
        long phase = Math.floorMod((long) pos.hashCode(), interval);
        if (level.getGameTime() % interval != phase) {
            return;
        }
        double chance = OvergrownVegetationConfig.COMMON.vineFruitRipenChance.get();
        if (chance <= 0.0D || level.getRandom().nextDouble() >= chance) {
            return;
        }
        if (level instanceof ServerLevel serverLevel) {
            be.ripenRandomDroop(serverLevel, level.getRandom());
        }
    }

    /** 狙いのマスを後から足す処理を、読み込んだ蔦ごとにこの tick 数の中でばらす。 */
    private static final int SEGMENT_RETROFIT_SPREAD = 20;

    /**
     * 狙いのマス({@link LianaSegmentBlock})をもう置いたか(2026-11-08)。11-08 より前に生えた登る蔓には無いので、
     * 読み込んだ後の最初の数 tick で経路をたどって 1 回だけ置く({@link #retrofitSegments})。新しく生やした蔓は生やしたときに置いて true。
     */
    private boolean segmentsPlaced;

    public void markSegmentsPlaced() {
        this.segmentsPlaced = true;
        setChanged();
    }

    /** 前からある登る蔓に、経路の空気のマスへ狙いのマスを置く(1 回だけ。読み込まれていないチャンクのマスは飛ばす)。 */
    private void retrofitSegments(ServerLevel level) {
        int stride = CLIMB_STRIDE;
        List<double[]> pts = new ArrayList<>(climbPath.length / stride);
        for (int o = 0; o + 2 < climbPath.length; o += stride) {
            pts.add(new double[]{worldPosition.getX() + climbPath[o], worldPosition.getY() + climbPath[o + 1],
                    worldPosition.getZ() + climbPath[o + 2]});
        }
        double radius = climbScale * com.yayuy.overgrownvegetation.worldgen.ClimbingVine.RADIUS_ROOT + 0.04D;
        List<BlockPos> placed = LianaSegmentBlock.placeAlong(level, worldPosition, pts, radius, Block.UPDATE_CLIENTS);
        if (!placed.isEmpty()) {
            List<BlockPos> all = new ArrayList<>(secondaryPositions);
            all.addAll(placed);
            secondaryPositions = List.copyOf(all);
        }
        segmentsPlaced = true;
        setChanged();
    }

    /**
     * 副アンカーのティッカーを外し、以後このブロックエンティティを毎 tick 回さないようにする(2026-09-24、軽量化)。
     *
     * <p>{@code getTicker} はブロックの状態しか見られないので主アンカーと副アンカーを見分けられず、
     * 蔦のブロックエンティティの 9 割を占める副アンカーまで毎 tick 回っては {@link #serverTick} の先頭で
     * return していた。副アンカーの {@link #primaryPos} が null に戻る道は NBT の読み直ししか無い
     * ({@link #setPrimaryPos} は生成時に非 null で 1 回呼ばれるだけ)ので、最初の 1 tick でバニラの
     * 「ティッカーを持たないブロックエンティティ」と同じ扱いに移す({@code LevelChunk#removeBlockEntityTicker}。
     * 包みが {@code NULL_TICKER} に差し替わり、次の周回で {@code Level#tickBlockEntities} がリストから外す)。
     * 外すのはサーバーのティッカーだけで、ブロックエンティティ・保存内容・同期・描画・切断は何も変わらない。
     *
     * <ul>
     *     <li>チャンクを読み込み直すとバニラが {@code getTicker} から付け直し、また最初の 1 tick で外れる。</li>
     *     <li>垂れ蔦の無い主アンカーは外さない(今までどおり回しておく)。</li>
     *     <li>外したあとで主アンカーに変わった(/data などで "Primary" を消した)ときは {@link #loadAdditional} が付け直す。</li>
     * </ul>
     */
    private void detachTicker(Level level) {
        // getChunkAt ではなく getChunkNow: tick 中のチャンクは必ず読み込み済みで、チケットを足す副作用も無い。
        LevelChunk chunk = level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(worldPosition.getX()), SectionPos.blockToSectionCoord(worldPosition.getZ()));
        if (chunk != null) {
            ((LevelChunkTickerInvoker) chunk).overgrown_vegetation$removeBlockEntityTicker(worldPosition);
            tickerDetached = true;
        }
    }

    /** 発光していない垂れ蔦を1本ランダムに選び、発光(=熟した)状態にして光源ブロックを裏へ置く。 */
    private void ripenRandomDroop(ServerLevel level, RandomSource random) {
        int unlit = 0;
        for (boolean lit : droopIsLight) {
            if (!lit) {
                unlit++;
            }
        }
        if (unlit == 0) {
            return;
        }
        int pick = random.nextInt(unlit);
        int index = -1;
        for (int i = 0; i < droopIsLight.length; i++) {
            if (!droopIsLight[i] && pick-- == 0) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }

        droopIsLight[index] = true;

        int lightLevel = OvergrownVegetationConfig.COMMON.vineDroopLightLevel.get();
        BlockPos lightPos = droopLightPos(index);
        if (lightLevel > 0 && lightPos != null && level.isLoaded(lightPos)) {
            BlockState at = level.getBlockState(lightPos);
            if (at.isAir() || at.canBeReplaced()) {
                level.setBlock(lightPos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, lightLevel),
                        Block.UPDATE_ALL);
                addLightBlockPosition(lightPos);
            }
        }
        syncToClient();
    }

    /**
     * {@code worldPoint}(通常は右クリックのヒット座標)に一番近い発光中の垂れ蔦の添字を返す。
     * {@code maxDistance} ブロックより遠いものは対象外。発光中の垂れ蔦が無ければ {@code -1}。
     */
    public int findNearestRipeDroop(Vec3 worldPoint, double maxDistance) {
        Vec3 source = droopCurveSource();
        Vec3 target = droopCurveTarget();
        if (source == null || target == null) {
            return -1;
        }
        int best = -1;
        double bestSq = maxDistance * maxDistance;
        for (int i = 0; i < droopIndices.length; i++) {
            if (i >= droopIsLight.length || !droopIsLight[i]) {
                continue;
            }
            Vec3 mid = droopMidpoint(source, target, i);
            double distSq = mid.distanceToSqr(worldPoint);
            if (distSq < bestSq) {
                bestSq = distSq;
                best = i;
            }
        }
        return best;
    }

    /**
     * 添字 {@code index} の垂れ蔦を非発光へ戻し(既に非発光なら何もしない)、その光源ブロックを
     * 撤去して、収穫アイテムを出すのに適した座標(その垂れ蔦のだいたい中ほど)を返す。
     */
    public Vec3 harvestDroop(ServerLevel level, int index) {
        Vec3 source = droopCurveSource();
        Vec3 target = droopCurveTarget();
        Vec3 dropPoint = (source != null && target != null)
                ? droopMidpoint(source, target, index)
                : Vec3.atCenterOf(worldPosition);

        if (index >= 0 && index < droopIsLight.length && droopIsLight[index]) {
            droopIsLight[index] = false;
            BlockPos lightPos = droopLightPos(index);
            if (lightPos != null && !anyLitDroopUsesLightPos(lightPos, index)) {
                if (level.getBlockState(lightPos).is(Blocks.LIGHT)) {
                    level.removeBlock(lightPos, false);
                }
                removeLightBlockPosition(lightPos);
            }
            syncToClient();
        }
        return dropPoint;
    }

    /** 添字 {@code index} の垂れ蔦の起点(主リボン上の点)から長さの半分ぶん下がった実座標。 */
    private Vec3 droopMidpoint(Vec3 source, Vec3 target, int index) {
        float t = (float) droopIndices[index] / DROOP_SEGMENTS;
        Vec3 origin = curvePoint(source, target, sag, t);
        double drop = Math.min(0.5, droopLengths[index] * 0.5);
        return new Vec3(origin.x, origin.y - drop, origin.z);
    }

    /** 添字 {@code index} の発光する垂れ蔦の先端付近(見えない光源ブロックを置く場所)。 */
    @Nullable
    private BlockPos droopLightPos(int index) {
        Vec3 source = droopCurveSource();
        Vec3 target = droopCurveTarget();
        if (source == null || target == null) {
            return null;
        }
        float t = (float) droopIndices[index] / DROOP_SEGMENTS;
        Vec3 origin = curvePoint(source, target, sag, t);
        return BlockPos.containing(origin.x, origin.y - droopLengths[index], origin.z);
    }

    /** {@code exclude} 以外の発光中の垂れ蔦のどれかが同じ光源ブロック座標を使っているか。 */
    private boolean anyLitDroopUsesLightPos(BlockPos lightPos, int exclude) {
        for (int i = 0; i < droopIndices.length; i++) {
            if (i == exclude || i >= droopIsLight.length || !droopIsLight[i]) {
                continue;
            }
            if (lightPos.equals(droopLightPos(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 垂れ蔦の曲線計算に使う始点。{@code TreeVineConnector#placeDroops} が渡すのと同じ実座標
     * (通常の蔦なら原木ブロックの中心、ぶら下がり蔦なら swagStart の実座標そのもの)。
     */
    @Nullable
    private Vec3 droopCurveSource() {
        if (swagStart != null) {
            return swagStart;
        }
        return sourcePos == null ? null : Vec3.atCenterOf(sourcePos);
    }

    @Nullable
    private Vec3 droopCurveTarget() {
        if (swagEnd != null) {
            return swagEnd;
        }
        return targetPos == null ? null : Vec3.atCenterOf(targetPos);
    }

    /**
     * {@code source}〜{@code target} を結ぶ直線 + {@code sag} の放物線(レンダラーの
     * {@code curvePoint} / {@code TreeVineConnector#curvePointOnLine} と全く同じ式)上の、
     * 経路の割合 {@code t}(0=始点、1=終点)に対応する実座標。
     */
    private static Vec3 curvePoint(Vec3 source, Vec3 target, float sag, float t) {
        double x = source.x + (target.x - source.x) * t;
        double z = source.z + (target.z - source.z) * t;
        double y = source.y + (target.y - source.y) * t - 4.0 * sag * t * (1.0 - t);
        return new Vec3(x, y, z);
    }

    private void addLightBlockPosition(BlockPos pos) {
        if (lightBlockPositions.contains(pos)) {
            return;
        }
        List<BlockPos> updated = new ArrayList<>(lightBlockPositions);
        updated.add(pos);
        lightBlockPositions = List.copyOf(updated);
        setChanged();
    }

    private void removeLightBlockPosition(BlockPos pos) {
        if (!lightBlockPositions.contains(pos)) {
            return;
        }
        List<BlockPos> updated = new ArrayList<>(lightBlockPositions);
        updated.remove(pos);
        lightBlockPositions = List.copyOf(updated);
        setChanged();
    }

    private void syncToClient() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (sourcePos != null) {
            tag.put("Source", NbtUtils.writeBlockPos(sourcePos));
        }
        if (targetPos != null) {
            tag.put("Target", NbtUtils.writeBlockPos(targetPos));
        }
        tag.putFloat("Sag", sag);
        if (swagStart != null && swagEnd != null) {
            tag.putDouble("SwagStartX", swagStart.x);
            tag.putDouble("SwagStartY", swagStart.y);
            tag.putDouble("SwagStartZ", swagStart.z);
            tag.putDouble("SwagEndX", swagEnd.x);
            tag.putDouble("SwagEndY", swagEnd.y);
            tag.putDouble("SwagEndZ", swagEnd.z);
        }
        if (primaryPos != null) {
            tag.put("Primary", NbtUtils.writeBlockPos(primaryPos));
        }
        if (!secondaryPositions.isEmpty()) {
            long[] packed = new long[secondaryPositions.size()];
            for (int i = 0; i < packed.length; i++) {
                packed[i] = secondaryPositions.get(i).asLong();
            }
            tag.putLongArray("Secondaries", packed);
        }
        if (!parentPositions.isEmpty()) {
            long[] packed = new long[parentPositions.size()];
            for (int i = 0; i < packed.length; i++) {
                packed[i] = parentPositions.get(i).asLong();
            }
            tag.putLongArray("Parents", packed);
        }
        if (!childPositions.isEmpty()) {
            long[] packed = new long[childPositions.size()];
            for (int i = 0; i < packed.length; i++) {
                packed[i] = childPositions.get(i).asLong();
            }
            tag.putLongArray("Children", packed);
        }
        if (droopIndices.length > 0) {
            tag.putIntArray("DroopIndices", droopIndices);
            int[] packedLengths = new int[droopLengths.length];
            for (int i = 0; i < packedLengths.length; i++) {
                packedLengths[i] = Float.floatToIntBits(droopLengths[i]);
            }
            tag.putIntArray("DroopLengths", packedLengths);
            byte[] packedIsLight = new byte[droopIsLight.length];
            for (int i = 0; i < packedIsLight.length; i++) {
                packedIsLight[i] = (byte) (droopIsLight[i] ? 1 : 0);
            }
            tag.putByteArray("DroopIsLight", packedIsLight);
        }
        if (!lightBlockPositions.isEmpty()) {
            long[] packed = new long[lightBlockPositions.size()];
            for (int i = 0; i < packed.length; i++) {
                packed[i] = lightBlockPositions.get(i).asLong();
            }
            tag.putLongArray("LightBlocks", packed);
        }
        if (climbPath.length > 0) {
            int[] packedClimb = new int[climbPath.length];
            for (int i = 0; i < packedClimb.length; i++) {
                packedClimb[i] = Float.floatToIntBits(climbPath[i]);
            }
            tag.putIntArray("ClimbPath", packedClimb);
            tag.putInt("ClimbBridgeStart", climbBridgeStart);
            tag.putInt("ClimbBridgeEnd", climbBridgeEnd);
            tag.putFloat("ClimbScale", climbScale);
            tag.putFloat("ClimbHang", climbHangTop);
            tag.putInt("ClimbFlags", climbFlags);
            if (segmentsPlaced) {
                tag.putBoolean("Segs", true);
            }
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        renderBoxCache = null;
        shapeCache = null;
        sourcePos = NbtUtils.readBlockPos(tag, "Source").orElse(null);
        targetPos = NbtUtils.readBlockPos(tag, "Target").orElse(null);
        sag = tag.getFloat("Sag");
        if (tag.contains("SwagStartX")) {
            swagStart = new Vec3(tag.getDouble("SwagStartX"), tag.getDouble("SwagStartY"), tag.getDouble("SwagStartZ"));
            swagEnd = new Vec3(tag.getDouble("SwagEndX"), tag.getDouble("SwagEndY"), tag.getDouble("SwagEndZ"));
        } else {
            swagStart = null;
            swagEnd = null;
        }
        primaryPos = NbtUtils.readBlockPos(tag, "Primary").orElse(null);
        secondaryPositions = readPositionList(tag, "Secondaries");
        parentPositions = readPositionList(tag, "Parents");
        childPositions = readPositionList(tag, "Children");

        droopIndices = tag.getIntArray("DroopIndices");
        int[] packedLengths = tag.getIntArray("DroopLengths");
        droopLengths = new float[packedLengths.length];
        for (int i = 0; i < packedLengths.length; i++) {
            droopLengths[i] = Float.intBitsToFloat(packedLengths[i]);
        }
        byte[] packedIsLight = tag.getByteArray("DroopIsLight");
        droopIsLight = new boolean[packedIsLight.length];
        for (int i = 0; i < packedIsLight.length; i++) {
            droopIsLight[i] = packedIsLight[i] != 0;
        }
        lightBlockPositions = readPositionList(tag, "LightBlocks");
        int[] packedClimb = tag.getIntArray("ClimbPath");
        climbPath = new float[packedClimb.length];
        for (int i = 0; i < packedClimb.length; i++) {
            climbPath[i] = Float.intBitsToFloat(packedClimb[i]);
        }
        climbBridgeStart = tag.contains("ClimbBridgeStart") ? tag.getInt("ClimbBridgeStart") : -1;
        climbBridgeEnd = tag.contains("ClimbBridgeEnd") ? tag.getInt("ClimbBridgeEnd") : -1;
        climbScale = tag.contains("ClimbScale") ? tag.getFloat("ClimbScale") : 1.0F;
        climbHangTop = tag.contains("ClimbHang") ? tag.getFloat("ClimbHang") : -1.0F;
        climbFlags = tag.contains("ClimbFlags") ? tag.getInt("ClimbFlags") : -1;
        segmentsPlaced = tag.getBoolean("Segs");

        // detachTicker で外したティッカーを、主アンカーに変わったときだけ付け直す(元は付けっぱなしだったので、
        // 元どおり熟成させる)。チャンク読み込み時は新しいインスタンス(tickerDetached=false・level 未設定)なので素通り。
        // ここに来るのは /data などで副アンカーの "Primary" を消したときだけ。
        if (tickerDetached && primaryPos == null && level instanceof ServerLevel serverLevel && !isRemoved()) {
            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(
                    SectionPos.blockToSectionCoord(worldPosition.getX()), SectionPos.blockToSectionCoord(worldPosition.getZ()));
            if (chunk != null) {
                ((LevelChunkTickerInvoker) chunk).overgrown_vegetation$updateBlockEntityTicker(this);
                tickerDetached = false;
            }
        }
    }

    /** {@code key} の long 配列(座標ぶんパック済み)を {@link BlockPos} のリストへ復元する。 */
    private static List<BlockPos> readPositionList(CompoundTag tag, String key) {
        long[] packed = tag.getLongArray(key);
        List<BlockPos> positions = new ArrayList<>(packed.length);
        for (long value : packed) {
            positions.add(BlockPos.of(value));
        }
        return positions;
    }

    /**
     * クライアントへ送る分(チャンク送信と {@link #syncToClient})。
     *
     * <p>2026-09-24 軽量化(監査 1252): 副アンカー・親子・光源ブロックの座標一覧はサーバーだけが使う
     * (切ったときの撤去 {@code CorruptedLianaBlock#cut}。クライアントでは早々に return する)ので送らない。
     * 描画と右クリックの判定が読む Source / Target / Sag / Swag* / Primary / Droop* はそのまま送る。
     * 保存と同じ形から 4 つを抜くだけにしてあるので、それ以外(NeoForge の付随データなど)は前と同じ。
     * クライアントの {@link #loadAdditional} では抜いたキーが空の一覧になるだけ。
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveWithoutMetadata(registries);
        tag.remove("Secondaries");
        tag.remove("Parents");
        tag.remove("Children");
        tag.remove("LightBlocks");
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
