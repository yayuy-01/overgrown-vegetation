package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 汚染地域の<b>落ち葉</b>(2026-10-15 夜。案 A「風の場で光と粒を一緒に動かす」の 1 つ)。
 *
 * <p>プレイヤーの周り {@link #RADIUS} ブロックで、林冠の葉(過繁茂の葉・湿地の葉)の下面から小さな葉のかけらを落とす。
 * 凪ではときどき 1 枚、突風が来るとまとめて舞う(出る数は風の強さの 2 乗に比例)。落ちる間は風下へ流され、左右に揺れながら
 * くるくる回り({@link CanopyWind} と同じ風)、地面に着いたら 2 秒で薄れて消える。
 *
 * <p>粒は登録しない(クライアントだけの粒なので {@code ParticleEngine#add} へ直接足す)。絵は落ちてきた葉のブロックの
 * テクスチャの一部(4×4 画素)を切り取って使うので、素材は増えない。葉の透けた画素は描かれない。
 * 生きている粒は {@link #MAX_ALIVE} まで。設定 {@code canopyFallingLeaves}(と {@code canopyLightEnabled})。数値は仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class CanopyLeaves {

    private static final int RADIUS = 16;
    private static final int MAX_ALIVE = 90;
    /** 1 tick に葉を探す回数の上限。 */
    private static final int MAX_TRIES = 8;

    private static final RandomSource RANDOM = RandomSource.create();
    /**
     * 生きている(はずの)落ち葉。数えるのは自分の手元の一覧で、粒の {@code remove} では数えない —— ワールドを出たときや
     * 粒の総数の上限で古い粒が消されるときは {@code remove} が呼ばれないので、数だけが残って新しい葉が出なくなる。
     */
    private static final java.util.List<FallingLeaf> LEAVES = new java.util.ArrayList<>();
    private static ClientLevel leafLevel;
    private static double carry;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level != leafLevel) {
            LEAVES.clear();
            leafLevel = level;
        }
        if (level == null || player == null || mc.isPaused() || !level.dimensionType().hasSkyLight()) {
            return;
        }
        LEAVES.removeIf(leaf -> !leaf.isAlive() || leaf.overdue());
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.canopyLightEnabled.get() || !cfg.canopyFallingLeaves.get() || LEAVES.size() >= VisualQuality.particleCap(MAX_ALIVE)) {
            return;
        }
        // 1 tick に出す数の見込み: 凪でときどき(0.03)、突風で 1 tick に 1 枚以上。小数は次の tick へ持ち越す。
        double w = CanopyWind.strength(player.getX(), player.getZ(), CanopyWind.time(0.0F));
        carry += (0.03 + 1.1 * w * w) * VisualQuality.particles();
        int want = (int) carry;
        carry -= want;
        int tries = 0;
        while (want > 0 && tries < MAX_TRIES && LEAVES.size() < VisualQuality.particleCap(MAX_ALIVE)) {
            tries++;
            if (trySpawn(mc, level, player)) {
                want--;
            }
        }
    }

    /** 周りの列を 1 本選び、プレイヤーの少し下から上へ見て、下が空いている葉(林冠の下面)を見つけたら、そこから 1 枚落とす。 */
    private static boolean trySpawn(Minecraft mc, ClientLevel level, LocalPlayer player) {
        int x = Mth.floor(player.getX()) + RANDOM.nextInt(RADIUS * 2 + 1) - RADIUS;
        int z = Mth.floor(player.getZ()) + RANDOM.nextInt(RADIUS * 2 + 1) - RADIUS;
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
            if (airBelow && (state.is(ModBlocks.OVERGROWN_LEAVES.get()) || state.is(ModBlocks.OVERGROWN_SWAMP_LEAVES.get()))) {
                if (!ModBiomes.isOvergrownLike(level.getBiome(pos))) {
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
        // 葉がずっと上(30 ブロックより上)にしか無い列 —— 巨大樹の傘(地面から 120〜240 ブロック上)の下。そこから落ちる葉は
        // 長い道のりのどこかに必ずあるので、落ちてくる途中の 1 枚をプレイヤーの 3〜17 ブロック上に出す(すっと現れる)。
        // 2026-10-15 夜、ユーザー「巨大樹の下では発生しない」。
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        if (top < fromY + 30) {
            return false;
        }
        pos.setY(top);
        BlockState crown = level.getBlockState(pos);
        if (!(crown.is(ModBlocks.OVERGROWN_LEAVES.get()) || crown.is(ModBlocks.OVERGROWN_SWAMP_LEAVES.get()))
                || !ModBiomes.isOvergrownLike(level.getBiome(pos))) {
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
        private int landedAge = -1;
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
            // 風の向きは突風ごとに違う(胞子と同じ {@link CanopyWind#velocity})。
            CanopyWind.velocity(this.x, this.z, CanopyWind.time(0.0F), WIND);
            double w = Math.sqrt(WIND[0] * WIND[0] + WIND[1] * WIND[1]);
            double dirX = w > 1.0E-6 ? WIND[0] / w : CanopyWind.dirX();
            double dirZ = w > 1.0E-6 ? WIND[1] / w : CanopyWind.dirZ();
            // 風下へ流れる(突風ほど速く)。左右に揺れながら落ちる(木の葉の落ち方)。
            double drift = 0.008 + 0.09 * Math.min(w, 1.2);
            double swing = Math.sin(this.age * 0.22 + this.swingPhase) * (0.012 + 0.01 * w);
            this.xd += (dirX * drift - this.xd) * 0.05 - dirZ * swing * 0.25;
            this.zd += (dirZ * drift - this.zd) * 0.05 + dirX * swing * 0.25;
            double fall = -(0.028 + 0.012 * Math.sin(this.age * 0.3 + this.swingPhase * 2.0)) + 0.012 * w * Math.sin(this.age * 0.11);
            this.yd += (fall - this.yd) * 0.1;
            this.roll += this.rotSpeed * (float) (0.6 + w);
            this.move(this.xd, this.yd, this.zd);
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

    private CanopyLeaves() {
    }
}
