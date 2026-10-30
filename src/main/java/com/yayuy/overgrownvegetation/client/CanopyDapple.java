package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 汚染地域の<b>地面の木漏れ日</b>(2026-10-15 試作。ユーザー「次は地面の木漏れ日の模様を」)。
 *
 * <h2>どこに出すか</h2>
 * プレイヤーの周り {@link #RADIUS} ブロックの地面を毎 tick {@link #SCAN_PER_TICK} か所ずつ見て、地面から太陽の方へ線をたどる。
 * 途中で通り抜けたのが<b>葉のような半透明のブロック(当たり判定はあるが光を遮り切らない)だけ</b>で、1〜{@link #MAX_LEAVES} 層なら、
 * (層 = 通った樹冠の数。厚い塊は 3 ブロックごとに 1 層。{@link CanopyLight#lastCrowns}。2026-10-16 までは葉のブロックの数で、厚い樹冠 1 つで消えた)
 * そのマスの上面に木漏れ日の斑点を置く(葉が薄いほど明るい)。幹・地面など不透明なブロックに当たったら日は届かない。
 * 何も通らなかった(じかに日が当たる)所には置かない —— そこは林冠の穴で、光の筋({@link CanopyLight})の落ちる所。
 * 巨大樹の傘のような高い林冠(最初の葉が地面から 32 ブロックより上)は葉を少なく数え({@link CanopyLight#highCanopyAllowance})、
 * 斑点を大きく薄くする(葉の隙間が地面に映す太陽の像は、隙間の高さに比例して大きくぼやける)。
 * 過繁茂の葉はバニラの葉のタグを持たないので、葉かどうかは「当たり判定があって不透明でない」で見る。
 *
 * <h2>描き方</h2>
 * 1 マスに斑点を 1〜3 個、マスの上面からはみ出さない大きさで。太陽が低いほど太陽の向きに伸びた楕円になり、真上からは丸い
 * (光の筋のぼかしと同じ太陽の高さの流れ)。風に揺れる葉のように、斑点ごとにゆっくり明滅して少し揺れる。
 * 太陽が動いて日が届かなくなったマスは 2.5 秒かけて消え、新しく届くようになったマスに 3 秒かけて現れる(飛ばない)。
 * 光の筋と同じ型(加算)・同じ色・同じ設定({@code canopyLightEnabled}・{@code canopyLightIntensity})。数値はすべて仮決め。
 *
 * <h2>木漏れ日の上のうっすらした筋</h2>
 * 2026-10-16、ユーザー「木漏れ日にも光の筋がうっすらと出るように」。木漏れ日のマスの {@link #RAY_SHARE} に、斑点から太陽の方へ、
 * 葉(最初に通った葉の高さ)までの細く薄い帯を立てる(林冠の穴の光の筋 {@link CanopyLight} の 1/6 ほどの濃さ、幅は斑点ほど)。
 * 上端は葉の中へ溶け、下端は斑点に着く。斑点と一緒に明滅し、枝の揺れで下端が行き来する。上端は毎フレームの太陽から求める。
 * 加算なので、たくさん重なると霞になる —— 近くの {@link #RAY_RANGE} ブロックのマスだけ、長さは {@link #RAY_MAX_LENGTH} まで。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CanopyDapple {

    /** 木漏れ日を探す半径(ブロック)。 */
    private static final int RADIUS = 20;
    /** 1 tick に調べる地面の数。 */
    private static final int SCAN_PER_TICK = 32;
    /** 木漏れ日のマスの上限。 */
    private static final int MAX_PATCHES = 420;
    /** 通り抜けてよい葉の層(樹冠の数)。これより厚い林冠の下には届かない。 */
    private static final int MAX_LEAVES = 3;
    /** 葉の層ごとの明るさ(1 層・2 層・3 層)。 */
    private static final float[] LEAF_BRIGHTNESS = {1.0F, 0.8F, 0.55F};
    /** 条件を満たしたマスのうち、斑点を置く割合(全部に置くと林床が一様に明るくなり、模様に見えない)。 */
    private static final float PATCH_SHARE = 0.8F;
    /** 木漏れ日のマスのうち、うっすらした筋を立てる割合。仮決め。 */
    private static final float RAY_SHARE = 0.5F;
    /** うっすらした筋を描く、目からの水平の距離(ブロック)。 */
    private static final float RAY_RANGE = 16.0F;
    /** うっすらした筋の長さの上限(ブロック。巨大樹の傘のように高い葉からの筋は、上を霞へ溶かす)。 */
    private static final double RAY_MAX_LENGTH = 24.0;
    /** うっすらした筋の芯の濃さ(林冠の穴の光の筋の芯は 0.28)。仮決め。 */
    private static final float RAY_ALPHA = 0.045F;

    private static final List<Patch> PATCHES = new ArrayList<>();
    private static final LongSet TAKEN = new LongOpenHashSet();
    private static final RandomSource RANDOM = RandomSource.create();
    private static ClientLevel patchLevel;
    private static long ticks;
    private static boolean broken;

    /** 1 マスの木漏れ日。 */
    private static final class Patch {
        final int x;
        final int z;
        final float y;
        final float brightness;
        /** 斑点ごとの (u, v, 半径, 位相)。u・v はマスの中心からのずれ。 */
        final float[] spots;
        /** 斑点がマスの中心からはみ出してよい範囲(0.5 = マスの中だけ、1 = 平らな周りのマスまで)。 */
        final float bound;
        final int life;
        /** うっすらした筋の、地面から最初に通った葉までの高さ(ブロック)と半分の幅。筋を立てないマスは高さ NaN。 */
        float rayRise = Float.NaN;
        float rayHalf;
        int age;
        int dyingAt = -1;

        Patch(int x, int z, float y, float brightness, float[] spots, float bound, int life) {
            this.x = x;
            this.z = z;
            this.y = y;
            this.brightness = brightness;
            this.spots = spots;
            this.bound = bound;
            this.life = life;
        }

        float presence() {
            float in = Mth.clamp(this.age / 60.0F, 0.0F, 1.0F);
            float out = this.dyingAt < 0 ? 1.0F : 1.0F - Mth.clamp((this.age - this.dyingAt) / 50.0F, 0.0F, 1.0F);
            return CanopyLight.smooth(in) * CanopyLight.smooth(out);
        }

        boolean gone() {
            return this.dyingAt >= 0 && this.age - this.dyingAt >= 50;
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level != patchLevel) {
            PATCHES.clear();
            TAKEN.clear();
            CanopyShade.clear();
            patchLevel = level;
        }
        if (level == null || player == null || mc.isPaused()) {
            return;
        }
        if (!OvergrownVegetationConfig.COMMON.canopyLightEnabled.get() || !level.dimensionType().hasSkyLight()) {
            PATCHES.clear();
            TAKEN.clear();
            CanopyShade.clear();
            return;
        }
        ticks++;
        Vec3 sun = CanopyLight.sunDirection(level, 1.0F);
        float day = CanopyLight.daylight(level, sun, 1.0F) * CloudShadows.sunVisibility();  // 雲が太陽を隠している間は弱める(CloudShadows、2026-10-17)

        for (Iterator<Patch> it = PATCHES.iterator(); it.hasNext(); ) {
            Patch p = it.next();
            p.age++;
            if (p.dyingAt < 0) {
                double dx = p.x + 0.5 - player.getX();
                double dz = p.z + 0.5 - player.getZ();
                boolean far = dx * dx + dz * dz > (RADIUS + 4) * (RADIUS + 4);
                boolean recheck = (p.age + (p.x * 7 + p.z * 13 & 31)) % 32 == 0;
                // 強い風のさなかは模様が入れ替わる(葉が大きく動いて、日の通り道が開いたりふさがったりする)。
                double w = CanopyWind.strength(p.x + 0.5, p.z + 0.5, CanopyWind.time(0.0F));
                boolean blownAway = w > 0.5 && RANDOM.nextFloat() < 0.012F * (float) (w - 0.5);
                if (far || day <= 0.0F || p.age >= p.life || blownAway || recheck && leavesOnSunPath(level, p.x, p.y, p.z, sun) < 1) {
                    p.dyingAt = p.age;
                }
            }
            if (p.gone()) {
                TAKEN.remove(BlockPos.asLong(p.x, 0, p.z));
                it.remove();
            }
        }

        if (day > 0.0F) {
            for (int i = 0; i < SCAN_PER_TICK && PATCHES.size() < VisualQuality.shaftCap(MAX_PATCHES); i++) {
                tryFindPatch(level, player, sun);
            }
        }
        CanopyShade.tick(level, player, sun, day);
        if (DEV_LOG && ticks % 100 == 0) {
            OvergrownVegetation.LOGGER.info("CanopyDapple patches={}", PATCHES.size());
        }
    }

    /** Claude のテストクライアント(-Dov.devbridge=true)だけ、木漏れ日のマスの数を 5 秒ごとにログへ出す。 */
    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    private static void tryFindPatch(ClientLevel level, LocalPlayer player, Vec3 sun) {
        // 近くほど多く探す(半径を 1.6 乗で手前へ寄せる)。一様に探すと、閉じた林冠の下では上限が遠くのマスで埋まり、
        // 足元に斑点がほとんど出なかった(2026-10-15 の撮影)。
        double dist = RADIUS * Math.pow(RANDOM.nextDouble(), 1.6);
        double angle = RANDOM.nextDouble() * Mth.TWO_PI;
        int x = Mth.floor(player.getX() + Math.cos(angle) * dist);
        int z = Mth.floor(player.getZ() + Math.sin(angle) * dist);
        long key = BlockPos.asLong(x, 0, z);
        if (TAKEN.contains(key) || !level.hasChunkAt(new BlockPos(x, 0, z))) {
            return;
        }
        float ground = findGround(level, x, Mth.floor(player.getY()), z);
        if (Float.isNaN(ground)) {
            return;
        }
        if (!ModBiomes.isOvergrownLike(level.getBiome(new BlockPos(x, Mth.floor(ground), z)))) {
            return;
        }
        int leaves = leavesOnSunPath(level, x, ground, z, sun);
        if (leaves < 1 || RANDOM.nextFloat() >= PATCH_SHARE) {
            return;
        }
        // 葉が高い所にあるほど、斑点(葉の隙間が地面に映す太陽の像)は大きく、ぼやけて薄い(像の大きさは隙間の高さに比例する)。
        // 巨大樹の傘(100 ブロック上)の下では 1.6 倍ほど・明るさ 7 割。ふつうの林冠(20〜30 ブロック)は変わらない。
        double rise = CanopyLight.lastFirstLeafRise;
        float high = Double.isNaN(rise) ? 0.0F : Mth.clamp((float) ((rise - 32.0) / 80.0), 0.0F, 1.0F);
        float spotScale = 1.0F + 0.6F * high;
        float dim = 1.0F - 0.3F * high;
        // 周り 4 マスの地面が同じ高さなら、斑点がマスをまたいでよい(大きな日だまりになる)。段差のある所はマスの中に収める。
        // マスの中だけだと点が散っているだけに見えた(2026-10-15 の撮影)。
        int py = Mth.floor(player.getY());
        boolean flat = ground == findGround(level, x + 1, py, z) && ground == findGround(level, x - 1, py, z)
                && ground == findGround(level, x, py, z + 1) && ground == findGround(level, x, py, z - 1);
        float bound = flat ? 1.0F : 0.5F;
        int count = 1 + RANDOM.nextInt(3);
        float[] spots = new float[count * 4];
        for (int i = 0; i < count; i++) {
            float radius = Math.min(bound * 0.95F, (flat ? 0.22F + RANDOM.nextFloat() * 0.4F : 0.14F + RANDOM.nextFloat() * 0.24F) * spotScale);
            float room = Math.max(0.0F, bound - radius);
            spots[i * 4] = (RANDOM.nextFloat() * 2.0F - 1.0F) * room;
            spots[i * 4 + 1] = (RANDOM.nextFloat() * 2.0F - 1.0F) * room;
            spots[i * 4 + 2] = radius;
            spots[i * 4 + 3] = RANDOM.nextFloat() * Mth.TWO_PI;
        }
        Patch patch = new Patch(x, z, ground, LEAF_BRIGHTNESS[leaves - 1] * dim, spots, bound, 500 + RANDOM.nextInt(700));
        // うっすらした筋: 葉の高さは、上で leavesOnSunPath がたどった直後の値(見直しのたどりで書き換わるので、ここで写す)。
        if (!Double.isNaN(rise) && rise >= 2.0 && RANDOM.nextFloat() < RAY_SHARE) {
            float widest = 0.0F;
            for (int i = 0; i < count; i++) {
                widest = Math.max(widest, spots[i * 4 + 2]);
            }
            patch.rayRise = (float) rise;
            patch.rayHalf = Mth.clamp(widest * 0.6F, 0.08F, 0.3F);
        }
        PATCHES.add(patch);
        TAKEN.add(key);
    }

    /**
     * プレイヤーの高さの近く(上 3〜下 12)で、上が空いている(当たり判定の無い草・空気)地面の上面の高さ。葉の上(林冠)は地面にしない。
     * 見つからなければ NaN。
     */
    static float findGround(ClientLevel level, int x, int playerY, int z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, playerY + 3, z);
        boolean openAbove = false;
        for (int y = playerY + 3; y >= playerY - 12; y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                openAbove = true;
                continue;
            }
            if (openAbove && state.canOcclude()) {
                return y + (float) shape.max(net.minecraft.core.Direction.Axis.Y);
            }
            openAbove = false;
        }
        return Float.NaN;
    }

    /**
     * 地面 (x, y, z) のマスの中心から太陽の方へたどり、空へ抜けるまでに通り抜ける葉(当たり判定があって不透明でないブロック)の枚数。
     * 不透明なブロックに当たる・{@link #MAX_LEAVES} 枚を超えたら −1。何も通らずに空へ抜けたら 0。
     */
    static int leavesOnSunPath(ClientLevel level, int x, float y, int z, Vec3 sun) {
        if (sun.y < 0.05) {
            return -1;
        }
        // たどり方は光の筋と同じ({@link CanopyLight#traceSun})。前はここに別に書いていて、斜めの光が真上の空いた列で
        // 「空へ抜けた」ことになり、隣の木の樹冠を素通りしていた。
        int trace = CanopyLight.traceSun(level, x + 0.5, y + 0.05, z + 0.5, sun);
        if (trace <= 0) {
            return trace < 0 ? -1 : 0;
        }
        // 葉のブロックの数ではなく、通った樹冠の数(厚さで重み付け)で数える(CanopyLight#lastCrowns。開発用の 1024 で前の数え方)。
        int layers = (CanopyLight.debugHide & 1024) != 0 ? trace : CanopyLight.lastCrowns;
        if (layers <= MAX_LEAVES) {
            return Math.max(1, layers);
        }
        // 高い林冠(巨大樹の傘)の葉は薄く数える(CanopyLight#highCanopyAllowance)。傘の薄い所(外側)の下にだけ木漏れ日が落ちる。
        int extra = CanopyLight.highCanopyAllowance();
        return trace <= MAX_LEAVES + extra ? Math.max(1, trace - extra) : -1;
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || broken) {
            return;
        }
        boolean main = CanopyLight.drawToMainTarget();  // ファビュラスでは加算の光を粒の層でなく主画面へ(CanopyLight#drawToMainTarget)
        try {
            render(event);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("木漏れ日の描画で例外が出たので、このセッションでは描かない", e);
        } finally {
            CanopyLight.restoreTarget(main);
        }
    }

    private static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || level != patchLevel) {
            return;
        }
        Camera camera = event.getCamera();
        if (camera.getFluidInCamera() != FogType.NONE || mc.player != null
                && (mc.player.hasEffect(MobEffects.BLINDNESS) || mc.player.hasEffect(MobEffects.DARKNESS))) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 sun = CanopyLight.sunDirection(level, partialTick);
        float day = CanopyLight.daylight(level, sun, partialTick) * CloudShadows.sunVisibility();  // 雲が太陽を隠している間は弱める(CloudShadows、2026-10-17)
        float intensity = OvergrownVegetationConfig.COMMON.canopyLightIntensity.get().floatValue();
        if (intensity <= 0.0F || day <= 0.0F) {
            return;
        }
        // 太陽の水平の向き(楕円の長い軸)と伸び。真上からは丸く、低いほど長い。
        double hLen = Math.sqrt(sun.x * sun.x + sun.z * sun.z);
        float ax = hLen > 1.0E-3 ? (float) (sun.x / hLen) : 1.0F;
        float az = hLen > 1.0E-3 ? (float) (sun.z / hLen) : 0.0F;
        float stretch = Mth.clamp(1.0F / (float) Math.max(sun.y, 0.05), 1.0F, 2.2F);
        float warm = 1.0F - Mth.clamp((float) sun.y / 0.5F, 0.0F, 1.0F);
        float r = 1.0F;
        // 光の筋より金色寄り(白っぽいと緑の地面の上で色が抜けて見えた)。
        float g = Mth.lerp(warm, 0.86F, 0.66F);
        float b = Mth.lerp(warm, 0.48F, 0.32F);
        Vec3 eye = camera.getPosition();
        float far = Math.max(12.0F, Math.min(RenderSystem.getShaderFogEnd(), RADIUS + 4.0F));
        double windTime = CanopyWind.time(partialTick);
        // 風の向きを、斑点の座標(u = 太陽の向き、v = その直角)へ直したもの。
        double windU = CanopyWind.dirX() * ax + CanopyWind.dirZ() * az;
        double windV = -CanopyWind.dirX() * az + CanopyWind.dirZ() * ax;

        MultiBufferSource.BufferSource buffers = CanopyLight.immediateBuffers();  // Iris のまとめ描きを避ける(CanopyLight#immediateBuffers)
        // 先に林冠の影(掛け算)で地面を暗くし、その上に木漏れ日を足す。
        // 林冠の影の地図(案 C)が効いている間は、シェーダーが地面も含めて全部の面に影を落とすので、地面の板は描かない(二重に暗くならないように)。
        if (!CanopySunMap.active()) {
            CanopyShade.render(buffers, eye, day * Math.min(1.0F, intensity), far);
        }
        if (PATCHES.isEmpty()) {
            return;
        }
        VertexConsumer c = buffers.getBuffer(CanopyLight.renderType());
        // うっすらした筋: 目からその点への向きが太陽に近いほど明るい(光の筋と同じ {@link CanopyLight#phase}。1 段ごと)。色は光の筋と同じ淡い金(空気の中の光)。
        float rayG = Mth.lerp(warm, 0.95F, 0.72F);
        float rayB = Mth.lerp(warm, 0.68F, 0.42F);
        // 雲が太陽を隠しているかは斑点の地面の所で引く(地面の雲の影と同じ)。カメラの所の値(day)で引くと、カメラを上下に動かすだけで
        // 雲の層で引く点が横へずれ、下りると斑点が一斉に消え・上ると戻った(2026-10-22、DH 入りのユーザーの runClient)。
        float sunlight = CanopyLight.daylight(level, sun, partialTick);
        CloudShadows.GroundSun groundSun = CloudShadows.groundSun(level, partialTick);
        for (Patch p : PATCHES) {
            double cx = p.x + 0.5 - eye.x;
            double cy = p.y + 0.02 - eye.y;
            double cz = p.z + 0.5 - eye.z;
            double dist = Math.sqrt(cx * cx + cy * cy + cz * cz);
            float fade = 1.0F - CanopyLight.smooth(Mth.clamp((float) ((dist - far * 0.6) / (far * 0.4)), 0.0F, 1.0F));
            float patchDay = sunlight * groundSun.at(p.x + 0.5, p.y, p.z + 0.5);
            float base = 0.55F * p.brightness * p.presence() * patchDay * intensity * fade;
            if (base <= 0.003F) {
                continue;
            }
            // 風: 同じ枝の下の斑点はまとめて風の向きへ行き来し(枝の揺れ、毎秒 0.25 回前後)、1 粒ずつは葉のはためきで
            // 明滅する(凪ではかすかにきらめくだけ、突風では毎秒 3〜4 回、強い所では一瞬消える)。
            // 2026-10-15 夜、ユーザー「葉が風に揺られないので木漏れ日がずっと同じ場所にい続けておかしく見える」。
            double w = CanopyWind.strength(p.x + 0.5, p.z + 0.5, windTime);
            double gust = Math.min(w, 1.0);
            double patchPhase = (p.x * 12.9898 + p.z * 78.233) % (Math.PI * 2.0);
            double branch = (0.02 + 0.22 * gust) * CanopyWind.branch(windTime, patchPhase);
            for (int i = 0; i < p.spots.length; i += 4) {
                float phase = p.spots[i + 3];
                float flicker = (float) CanopyWind.flutter(windTime, phase + patchPhase, w);
                double jitter = 0.06 * gust * Math.sin(2.0 * Math.PI * 1.3 * windTime / 20.0 + phase * 2.0);
                float shiftU = (float) (branch * windU - jitter * windV);
                float shiftV = (float) (branch * windV + jitter * windU);
                float rad = p.spots[i + 2];
                float major = Math.min(rad * stretch, p.bound);
                float u = Mth.clamp(p.spots[i] + shiftU, -p.bound + major, p.bound - major);
                float v = Mth.clamp(p.spots[i + 1] + shiftV, -p.bound + rad, p.bound - rad);
                // u は太陽の向き、v はその直角(マスの中で回す)。
                float sx = (float) cx + u * ax - v * az;
                float sz = (float) cz + u * az + v * ax;
                if ((CanopyLight.debugHide & 32) == 0) {
                    spot(c, sx, (float) cy, sz, major, rad, ax, az, base * Math.max(0.0F, flicker), r, g, b);
                }
            }
            if (!Float.isNaN(p.rayRise) && (CanopyLight.debugHide & 16) == 0 && cx * cx + cz * cz < RAY_RANGE * RAY_RANGE) {
                // 下端は枝の揺れで斑点と一緒に行き来し、明るさは斑点と一緒に明滅する。上端は今の太陽の向きで葉の高さまで。
                float flicker = (float) CanopyWind.flutter(windTime, patchPhase, w);
                double shiftX = branch * CanopyWind.dirX();
                double shiftZ = branch * CanopyWind.dirZ();
                double length = Math.min(p.rayRise / Math.max(sun.y, 0.05), RAY_MAX_LENGTH);
                float edgeFade = 1.0F - CanopyLight.smooth(Mth.clamp((float) ((Math.sqrt(cx * cx + cz * cz) - RAY_RANGE * 0.65) / (RAY_RANGE * 0.35)), 0.0F, 1.0F));
                float alpha = RAY_ALPHA * p.brightness * p.presence() * patchDay * intensity * edgeFade * Math.max(0.0F, flicker);
                if (alpha > 0.002F) {
                    ray(c, cx + shiftX, cy, cz + shiftZ, sun, length, p.rayHalf, alpha, r, rayG, rayB);
                }
            }
        }
        buffers.endBatch(CanopyLight.renderType());
    }

    /**
     * 地面の (gx, gy, gz)(目からの位置)から太陽の方へ {@code length} ブロックの、カメラを向く細い帯(縁 0・中心 {@code alpha})。
     * 地面の手前でわずかに立ち上がり、上の 3 割で葉の中へ溶ける。筋の軸に沿って見る(真下から見上げる)ほど薄くする(光の筋と同じ)。
     */
    private static void ray(VertexConsumer c, double gx, double gy, double gz, Vec3 sun, double length, float half, float alpha,
                            float r, float g, float b) {
        final int rows = 4;
        float pX = 0.0F;
        float pY = 0.0F;
        float pZ = 0.0F;
        float pSX = 0.0F;
        float pSY = 0.0F;
        float pSZ = 0.0F;
        float pA = 0.0F;
        boolean havePrev = false;
        for (int i = 0; i <= rows; i++) {
            float t = i / (float) rows;
            double x = gx + sun.x * length * t;
            double y = gy + sun.y * length * t;
            double z = gz + sun.z * length * t;
            // 帯の横 = 軸 × 目への向き(目は原点)。
            double ex = -x;
            double ey = -y;
            double ez = -z;
            double sx = sun.y * ez - sun.z * ey;
            double sy = sun.z * ex - sun.x * ez;
            double sz = sun.x * ey - sun.y * ex;
            double len = Math.sqrt(sx * sx + sy * sy + sz * sz);
            double toEye = Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (len < 1.0E-5 || toEye < 1.0E-5) {
                havePrev = false;
                continue;
            }
            float facing = CanopyLight.smooth(Mth.clamp((float) ((len / toEye - 0.12) / 0.33), 0.0F, 1.0F));
            float a = Math.min(1.0F, alpha * facing * CanopyLight.smooth(Mth.clamp(t / 0.1F, 0.0F, 1.0F))
                    * (1.0F - CanopyLight.smooth(Mth.clamp((t - 0.7F) / 0.3F, 0.0F, 1.0F)))
                    * CanopyLight.phase(x, y, z, sun));
            float k = (float) (half / len);
            float cX = (float) x;
            float cY = (float) y;
            float cZ = (float) z;
            float sX = (float) sx * k;
            float sY = (float) sy * k;
            float sZ = (float) sz * k;
            if (havePrev) {
                // 左の縁 → 中心、中心 → 右の縁 の 2 枚。
                c.addVertex(pX - pSX, pY - pSY, pZ - pSZ).setColor(r, g, b, 0.0F);
                c.addVertex(cX - sX, cY - sY, cZ - sZ).setColor(r, g, b, 0.0F);
                c.addVertex(cX, cY, cZ).setColor(r, g, b, a);
                c.addVertex(pX, pY, pZ).setColor(r, g, b, pA);
                c.addVertex(pX, pY, pZ).setColor(r, g, b, pA);
                c.addVertex(cX, cY, cZ).setColor(r, g, b, a);
                c.addVertex(cX + sX, cY + sY, cZ + sZ).setColor(r, g, b, 0.0F);
                c.addVertex(pX + pSX, pY + pSY, pZ + pSZ).setColor(r, g, b, 0.0F);
            }
            pX = cX;
            pY = cY;
            pZ = cZ;
            pSX = sX;
            pSY = sY;
            pSZ = sZ;
            pA = a;
            havePrev = true;
        }
    }

    /** 地面に寝かせた楕円 1 つ(中心が明るく縁が 0 の 8 枚の三角)。長い軸は (ax, az) の向き。 */
    private static void spot(VertexConsumer c, float x, float y, float z, float major, float minor, float ax, float az,
                             float alpha, float r, float g, float b) {
        for (int i = 0; i < 8; i++) {
            float a0 = i * Mth.TWO_PI / 8.0F;
            float a1 = (i + 1) * Mth.TWO_PI / 8.0F;
            float u0 = Mth.cos(a0) * major;
            float v0 = Mth.sin(a0) * minor;
            float u1 = Mth.cos(a1) * major;
            float v1 = Mth.sin(a1) * minor;
            c.addVertex(x, y, z).setColor(r, g, b, alpha);
            c.addVertex(x + u0 * ax - v0 * az, y, z + u0 * az + v0 * ax).setColor(r, g, b, 0.0F);
            c.addVertex(x + u1 * ax - v1 * az, y, z + u1 * az + v1 * ax).setColor(r, g, b, 0.0F);
            c.addVertex(x, y, z).setColor(r, g, b, alpha);
        }
    }

    private CanopyDapple() {
    }
}
