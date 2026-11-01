package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import com.yayuy.overgrownvegetation.data.AtmosphereLife;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Quaternionf;

/**
 * 過繁茂の森の<b>落ち葉</b>(2026-10-30 夜 第 2 段「動き」。元は parked の {@code CanopyLeaves}、2026-10-15 夜 案 A「風の場で光と粒を一緒に動かす」の 1 つ)。
 *
 * <p>プレイヤーの周り {@code leaves.radius} ブロックで、林冠の葉(過繁茂の葉・湿地の葉)の下面から小さな葉のかけらを落とす。
 * 凪ではときどき 1 枚、突風の前線が来るとまとめて舞う(出る数は風の強さの 2 乗に比例)。落ちる間は風下へ流され、左右に揺れながら
 * くるくる回り({@link Wind#current()} と同じ風)、地面に着いたら 2 秒で薄れて消える。巨大樹の傘の下(葉が 30 ブロックより上)では
 * 落ちてくる途中の 1 枚をプレイヤーの上に出す。
 *
 * <p>粒は登録しない(クライアントだけの粒なので {@code ParticleEngine#add} へ直接足す)。絵は落ちてきた葉のブロックのテクスチャの
 * 4×4 画素を切り抜くので、素材は増えない(10-30 夜 了承)。生きている粒は {@code leaves.maxAlive} × 重さの段の粒の量まで。数値は仮決め。
 */
final class FallingLeaves {

    /** 1 tick に葉を探す回数の上限。 */
    private static final int MAX_TRIES = 16;

    private static final RandomSource RANDOM = RandomSource.create();
    /**
     * 生きている(はずの)落ち葉。数えるのは自分の手元の一覧で、粒の {@code remove} では数えない —— ワールドを出たときや
     * 粒の総数の上限で古い粒が消されるときは {@code remove} が呼ばれないので、数だけが残って新しい葉が出なくなる。
     */
    private static final java.util.List<FallingLeaf> LEAVES = new java.util.ArrayList<>();
    private static double carry;

    static void clear() {
        LEAVES.forEach(FallingLeaf::remove);
        LEAVES.clear();
        carry = 0.0;
    }

    /** 毎 tick({@link Atmosphere} から。風を進めた後)。 */
    static void tick(Minecraft mc, ClientLevel level, LocalPlayer player) {
        if (!VisualQuality.motes()) {
            clear();
            return;
        }
        LEAVES.removeIf(leaf -> !leaf.isAlive() || leaf.overdue());
        AtmosphereTable.LeavesParams p = DesignData.atmosphere().leaves();
        int cap = p.maxAlive() <= 0 ? 0 : VisualQuality.particleCap(p.maxAlive());
        if (LEAVES.size() >= cap) {
            return;
        }
        // 1 tick に出す数の見込み: 半径の中の 9 か所の風の強さの 2 乗の平均で(凪でときどき、突風で 1 tick に 1 枚以上)。
        // どの列から落とすかは、その列の風で選ぶ(前線がまだ遠い間は、遠くの風の当たっている木から先に舞う。10-30 夜 提案 2)。
        Wind wind = Wind.current();
        double time = Atmosphere.time(0.0F);
        double ring = p.radius() * 0.7;
        double sum = 0.0;
        double maxW2 = 0.0;
        for (int i = 0; i < 9; i++) {
            double a = i * Math.PI / 4.0;
            double r = i == 8 ? 0.0 : ring;
            double w = wind.strength(player.getX() + Math.cos(a) * r, player.getZ() + Math.sin(a) * r, time);
            sum += w * w;
            maxW2 = Math.max(maxW2, w * w);
        }
        carry += (p.perSecondCalm() + p.perSecondGust() * sum / 9.0) / 20.0 * VisualQuality.particles();
        int want = (int) carry;
        carry -= want;
        int radius = Math.round(p.radius());
        // 凪の分を風の 2 乗に直した下駄(凪の列も少しは選ばれる)。
        double floor = p.perSecondGust() > 0.0F ? p.perSecondCalm() / p.perSecondGust() : 1.0;
        int tries = 0;
        while (want > 0 && tries < MAX_TRIES && LEAVES.size() < cap) {
            tries++;
            if (trySpawn(mc, level, player, radius, wind, time, floor, floor + maxW2)) {
                want--;
            }
        }
    }

    /**
     * 落ち葉を出す葉か。11-01 夜(見え方の直し 1)から封印木・木生シダの芯の葉も(外側の板の葉からは出さない)。
     * 種類ごとの出やすさは {@link #fallChance}。
     */
    private static boolean isCanopyLeaf(BlockState state) {
        return state.is(ModBlocks.OVERGROWN_LEAVES.get()) || state.is(ModBlocks.SIGILLARIA_LEAVES.get()) || state.is(ModBlocks.TREE_FERN_LEAVES.get());
    }

    /** その葉から落とす割合(過繁茂の葉は 1、ほかは設計値 leaves.fallSigillaria・fallTreeFern。1 を超える分は 1)。 */
    private static float fallChance(BlockState state) {
        AtmosphereTable.LeavesParams p = DesignData.atmosphere().leaves();
        if (state.is(ModBlocks.SIGILLARIA_LEAVES.get())) {
            return p.fallSigillaria();
        }
        return state.is(ModBlocks.TREE_FERN_LEAVES.get()) ? p.fallTreeFern() : 1.0F;
    }

    /** 下から見上げたとき通り抜ける板の葉(垂れる板の上の芯からも落とす)。 */
    private static boolean isPlate(BlockState state) {
        return state.getBlock() instanceof com.yayuy.overgrownvegetation.block.SigillariaFringeBlock;
    }

    /**
     * 周りの列を 1 本選び、その列の風で落とすかを決め({@code (floor + w²) / limit} の割合で)、プレイヤーの少し下から上へ見て、
     * 下が空いている葉(林冠の下面)を見つけたら、そこから 1 枚落とす。
     */
    private static boolean trySpawn(Minecraft mc, ClientLevel level, LocalPlayer player, int radius, Wind wind, double time,
                                    double floor, double limit) {
        int x = Mth.floor(player.getX()) + RANDOM.nextInt(radius * 2 + 1) - radius;
        int z = Mth.floor(player.getZ()) + RANDOM.nextInt(radius * 2 + 1) - radius;
        double wc = wind.strength(x + 0.5, z + 0.5, time);
        if (RANDOM.nextDouble() * limit > floor + wc * wc) {
            return false;
        }
        int fromY = Mth.floor(player.getY()) - 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, fromY, z);
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        boolean airBelow = false;
        for (int y = fromY; y < fromY + 30; y++) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                airBelow = true;
                continue;
            }
            if (airBelow && isPlate(state)) {
                continue;
            }
            if (airBelow && isCanopyLeaf(state)) {
                if (!ModBiomes.isOvergrownLike(level.getBiome(pos)) || RANDOM.nextFloat() >= fallChance(state)) {
                    return false;
                }
                TextureAtlasSprite sprite = mc.getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
                FallingLeaf leaf = new FallingLeaf(level, x + RANDOM.nextDouble(), y - 0.05, z + RANDOM.nextDouble(), sprite, false);
                mc.particleEngine.add(leaf);
                LEAVES.add(leaf);
                return true;
            }
            airBelow = false;
        }
        // 葉がずっと上(30 ブロックより上)にしか無い列 —— 巨大樹の傘の下。落ちてくる途中の 1 枚をプレイヤーの 3〜17 ブロック上に出す
        // (2026-10-15 夜、ユーザー「巨大樹の下では発生しない」)。
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (top < fromY + 30) {
            return false;
        }
        pos.setY(top);
        BlockState crown = level.getBlockState(pos);
        if (!isCanopyLeaf(crown) || !ModBiomes.isOvergrownLike(level.getBiome(pos)) || RANDOM.nextFloat() >= fallChance(crown)) {
            return false;
        }
        double y = player.getY() + 3.0 + RANDOM.nextDouble() * 14.0;
        pos.setY(Mth.floor(y));
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }
        TextureAtlasSprite sprite = mc.getBlockRenderer().getBlockModelShaper().getParticleIcon(crown);
        FallingLeaf leaf = new FallingLeaf(level, x + RANDOM.nextDouble(), y, z + RANDOM.nextDouble(), sprite, true);
        mc.particleEngine.add(leaf);
        LEAVES.add(leaf);
        return true;
    }

    /** 落ちる葉のかけら 1 枚。 */
    private static final class FallingLeaf extends TextureSheetParticle {
        private final float u0;
        private final float v0;
        private final float rotSpeed;
        private final float swingPhase;
        /** 葉の傾き(はためき)の速さと位相、今の風の強さ(なめらかに寄せた値。傾きの幅に使う)。 */
        private final float tiltSpeed;
        private final float tiltPhase;
        private float tiltWind;
        private float oTiltWind;
        private int landedAge = -1;
        /** 水面に浮いてからの tick(浮いていなければ −1)と、浮いている長さ(40 水面に落ちて流れる葉)。 */
        private int floatAge = -1;
        private int floatLife;
        /** 生まれたゲーム時刻(一覧から外す目安。粒が外から消されて tick されなくなっても数え続けないように)。 */
        private final long bornAt;
        /** 落ちてくる途中に出した葉(巨大樹の傘の下)。湧き始めと、着地せずに寿命が来たときに薄れる。 */
        private final boolean midAir;
        private static final double[] WIND = new double[2];

        FallingLeaf(ClientLevel level, double x, double y, double z, TextureAtlasSprite sprite, boolean midAir) {
            super(level, x, y, z);
            this.midAir = midAir;
            this.setSprite(sprite);
            // テクスチャの 4×4 画素のかけら(葉の模様の一部。透けた画素は描かれないので、形が葉らしくばらつく)。
            // 最初は 6×6 画素・大きさ 0.07〜0.12 にしたが、カメラの近くを通ると四角いブロックのかけらに見えた(2026-10-15 の撮影)。
            this.u0 = this.random.nextInt(13) / 16.0F;
            this.v0 = this.random.nextInt(13) / 16.0F;
            this.quadSize = 0.045F + this.random.nextFloat() * 0.03F;
            this.setSize(0.05F, 0.05F);
            this.lifetime = 260 + this.random.nextInt(120);
            this.gravity = 0.0F;
            this.friction = 1.0F;
            this.hasPhysics = true;
            this.roll = this.random.nextFloat() * Mth.TWO_PI;
            this.oRoll = this.roll;
            this.rotSpeed = (this.random.nextBoolean() ? 1.0F : -1.0F) * (0.04F + this.random.nextFloat() * 0.1F);
            this.swingPhase = this.random.nextFloat() * Mth.TWO_PI;
            this.tiltSpeed = 0.12F + this.random.nextFloat() * 0.1F;
            this.tiltPhase = this.random.nextFloat() * Mth.TWO_PI;
            float shade = 0.85F + this.random.nextFloat() * 0.15F;
            this.setColor(shade, shade, shade);
            this.xd = 0.0;
            this.yd = -0.01;
            this.zd = 0.0;
            this.bornAt = level.getGameTime();
            if (midAir) {
                this.alpha = 0.0F;
            }
        }

        /** 寿命 + 着地して薄れる時間を過ぎた(外から消されて tick されなくなった粒も、これで一覧から外れる)。 */
        boolean overdue() {
            return this.level.getGameTime() - this.bornAt > this.lifetime + 60L;
        }

        @Override
        public void tick() {
            this.xo = this.x;
            this.yo = this.y;
            this.zo = this.z;
            this.oRoll = this.roll;
            if (this.age++ >= this.lifetime) {
                this.remove();
                return;
            }
            if (this.floatAge >= 0) {
                this.floatTick();
                return;
            }
            if (this.onGround) {
                // 地面に着いたら止まって、2 秒で薄れて消える。
                if (this.landedAge < 0) {
                    this.landedAge = 0;
                }
                this.landedAge++;
                this.alpha = Math.max(0.0F, 1.0F - this.landedAge / 40.0F);
                if (this.landedAge >= 40) {
                    this.remove();
                }
                return;
            }
            if (this.midAir) {
                this.alpha = Math.min(1.0F, this.age / 20.0F) * Math.min(1.0F, (this.lifetime - this.age) / 30.0F);
            }
            // 風の向きは突風ごとに違う(胞子と同じ風)。
            Wind wind = Wind.current();
            wind.velocity(this.x, this.z, Atmosphere.time(0.0F), WIND);
            double w = Math.sqrt(WIND[0] * WIND[0] + WIND[1] * WIND[1]);
            double dirX = w > 1.0E-6 ? WIND[0] / w : wind.dirX();
            double dirZ = w > 1.0E-6 ? WIND[1] / w : wind.dirZ();
            // 風下へ流れる(突風ほど速く)。左右に揺れながら落ちる(木の葉の落ち方)。
            double drift = 0.008 + 0.09 * Math.min(w, 1.2);
            double swing = Math.sin(this.age * 0.22 + this.swingPhase) * (0.012 + 0.01 * w);
            this.xd += (dirX * drift - this.xd) * 0.05 - dirZ * swing * 0.25;
            this.zd += (dirZ * drift - this.zd) * 0.05 + dirX * swing * 0.25;
            double fall = -(0.028 + 0.012 * Math.sin(this.age * 0.3 + this.swingPhase * 2.0)) + 0.012 * w * Math.sin(this.age * 0.11);
            this.yd += (fall - this.yd) * 0.1;
            this.roll += this.rotSpeed * (float) (0.6 + w);
            this.oTiltWind = this.tiltWind;
            this.tiltWind += ((float) Math.min(w, 1.2) - this.tiltWind) * 0.05F;
            this.move(this.xd, this.yd, this.zd);
            this.checkWater();
        }

        /**
         * 葉が水面に着いたら(40、2026-10-31 見え方の段。設定: 森の葉は水に落ちても流れていく)、消さずに浮かべる。水の流れの向き
         * (流れが無ければ風下)へ {@code ripples.floatSpeed} ブロック/秒で {@code floatSecondsMin}〜{@code floatSecondsMax} 秒漂ってから沈む。
         */
        private void checkWater() {
            BlockPos pos = BlockPos.containing(this.x, this.y, this.z);
            FluidState fluid = this.level.getFluidState(pos);
            if (!fluid.is(FluidTags.WATER)) {
                return;
            }
            double surface = pos.getY() + fluid.getHeight(this.level, pos);
            if (this.y > surface + 0.05 || !this.level.getFluidState(pos.above()).isEmpty()) {
                return;
            }
            AtmosphereLife.RipplesParams p = DesignData.atmosphere().ripples();
            this.floatAge = 0;
            this.floatLife = Math.round((p.floatSecondsMin() + this.random.nextFloat() * (p.floatSecondsMax() - p.floatSecondsMin())) * 20.0F);
            this.lifetime = this.age + this.floatLife + 45;
            this.hasPhysics = false;
            this.y = surface + 0.01;
            this.yo = this.y;
            this.alpha = 1.0F;
        }

        private void floatTick() {
            this.floatAge++;
            AtmosphereLife.RipplesParams p = DesignData.atmosphere().ripples();
            BlockPos pos = BlockPos.containing(this.x, this.y - 0.05, this.z);
            FluidState fluid = this.level.getFluidState(pos);
            double step = p.floatSpeed() / 20.0;
            if (this.floatAge > this.floatLife || !fluid.is(FluidTags.WATER)) {
                // 沈む(2 秒で薄れて少し下がる)。
                this.alpha = Math.max(0.0F, this.alpha - 0.025F);
                this.y -= 0.004;
                if (this.alpha <= 0.0F) {
                    this.remove();
                }
                return;
            }
            Vec3 flow = fluid.getFlow(this.level, pos);
            double fx;
            double fz;
            if (flow.horizontalDistanceSqr() > 1.0E-4) {
                Vec3 n = flow.normalize();
                fx = n.x;
                fz = n.z;
            } else {
                Wind wind = Wind.current();
                wind.velocity(this.x, this.z, Atmosphere.time(0.0F), WIND);
                double w = Math.sqrt(WIND[0] * WIND[0] + WIND[1] * WIND[1]);
                fx = w > 1.0E-6 ? WIND[0] / w * Math.min(1.0, w + 0.2) : 0.0;
                fz = w > 1.0E-6 ? WIND[1] / w * Math.min(1.0, w + 0.2) : 0.0;
            }
            this.x += fx * step;
            this.z += fz * step;
            this.y = pos.getY() + fluid.getHeight(this.level, pos) + 0.01;
            this.roll += this.rotSpeed * 0.15F;
            this.alpha = Math.min(1.0F, this.alpha + 0.05F);
        }

        /**
         * 葉が宙で傾いてはためく(10-30 夜 ユーザー「風に揺られている感じに」)。カメラを向く板を、くるくる回る向き(roll)に加えて
         * 横と縦の軸で揺らす。凪では 0.25 ラジアンほど、突風では 0.6 ラジアンほど(10-30 夜「ちょっと激しすぎる」で半分に。裏返るほどは傾けない)。仮決め。
         */
        @Override
        public void render(VertexConsumer buffer, Camera camera, float partialTicks) {
            Quaternionf q = new Quaternionf();
            if (this.floatAge >= 0) {
                // 水面に寝かせる(上を向いた板)。
                q.rotationX(-Mth.HALF_PI).rotateZ(Mth.lerp(partialTicks, this.oRoll, this.roll));
                this.renderRotatedQuad(buffer, camera, q, partialTicks);
                return;
            }
            this.getFacingCameraMode().setRotation(q, camera, partialTicks);
            q.rotateZ(Mth.lerp(partialTicks, this.oRoll, this.roll));
            float t = this.age + partialTicks;
            // 地面に着いた葉は傾けない。
            float amp = this.landedAge >= 0 ? 0.0F : 0.25F + 0.35F * Mth.lerp(partialTicks, this.oTiltWind, this.tiltWind);
            q.rotateX(amp * Mth.sin(t * this.tiltSpeed + this.tiltPhase));
            q.rotateY(0.6F * amp * Mth.sin(t * this.tiltSpeed * 0.7F + this.tiltPhase * 2.0F));
            this.renderRotatedQuad(buffer, camera, q, partialTicks);
        }

        @Override
        protected float getU0() {
            return this.sprite.getU(this.u0);
        }

        @Override
        protected float getU1() {
            return this.sprite.getU(this.u0 + 4.0F / 16.0F);
        }

        @Override
        protected float getV0() {
            return this.sprite.getV(this.v0);
        }

        @Override
        protected float getV1() {
            return this.sprite.getV(this.v0 + 4.0F / 16.0F);
        }

        @Override
        public ParticleRenderType getRenderType() {
            return ParticleRenderType.TERRAIN_SHEET;
        }
    }

    private FallingLeaves() {
    }
}
