package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereLife;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import org.joml.Vector3f;

/**
 * <b>昼の羽虫の柱</b>(一覧 42、2026-10-31 見え方の段。設定: 石炭紀の森は昆虫が多く、鳥はいない)。
 * 晴れた昼({@code gnats.fromHour}〜{@code toHour} 時)、林冠の穴の光の中(空の光がほぼ届く林床の上)で、黒い小さな点が柱になって渦を巻く。
 * 光る粒の ShaftMotes とは別で、光らない(暗い点)。柱はカメラのまわりに {@code columns} 本、離れたら別の所へ移す。値は節 {@code gnats}(仮決め)。
 */
final class GnatSwarms {

    private static final RandomSource RANDOM = RandomSource.create();
    private static final int MAX = 8;
    private static final double[] CX = new double[MAX];
    private static final double[] CY = new double[MAX];
    private static final double[] CZ = new double[MAX];
    private static final float[] SEED = new float[MAX];
    private static final boolean[] SET = new boolean[MAX];
    private static float shown;

    static void clear() {
        java.util.Arrays.fill(SET, false);
        shown = 0.0F;
    }

    static String describe() {
        int n = 0;
        for (boolean b : SET) {
            n += b ? 1 : 0;
        }
        return String.format("羽虫 %.2f 柱 %d", shown, n);
    }

    static void tick(ClientLevel level, LocalPlayer player) {
        if (!VisualQuality.critters()) {
            clear();
            return;
        }
        AtmosphereLife.GnatsParams p = DesignData.atmosphere().gnats();
        float hour = (float) (Math.floorMod(level.getDayTime(), 24000L) / 1000.0 + 6.0) % 24.0F;
        float time = Mth.clamp((hour - p.fromHour()) / 0.5F, 0.0F, 1.0F) * Mth.clamp((p.toHour() - hour) / 0.5F, 0.0F, 1.0F);
        float target = time * (1.0F - level.getRainLevel(1.0F)) * Mth.clamp(ForestPresence.openForest() / 0.3F, 0.0F, 1.0F);
        shown += (target - shown) * 0.03F;
        if (shown <= 0.01F || !VisualQuality.atmosphere()) {
            return;
        }
        // 1 tick に 1 本ずつ、遠すぎる柱か空いた枠を、林冠の穴の光の中へ置き直す。
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int columns = Math.min(MAX, p.columns());
        for (int i = 0; i < columns; i++) {
            double dx = CX[i] - player.getX();
            double dz = CZ[i] - player.getZ();
            if (SET[i] && dx * dx + dz * dz < p.radius() * p.radius() * 1.6) {
                continue;
            }
            double x = player.getX() + (RANDOM.nextDouble() * 2.0 - 1.0) * p.radius();
            double z = player.getZ() + (RANDOM.nextDouble() * 2.0 - 1.0) * p.radius();
            int floor = WaterSigns.floorBelow(level, Mth.floor(x), Mth.floor(player.getY()) + 6, Mth.floor(z), 16, pos);
            if (floor != Integer.MIN_VALUE && level.getBrightness(LightLayer.SKY, pos.set(Mth.floor(x), floor + 1, Mth.floor(z))) >= 14
                    && ModBiomes.isOvergrownLike(level.getBiome(pos))) {
                CX[i] = x;
                CY[i] = floor + 0.8 + RANDOM.nextDouble() * 0.8;
                CZ[i] = z;
                SEED[i] = RANDOM.nextFloat() * 100.0F;
                SET[i] = true;
            }
            break;
        }
    }

    static void render(float partialTick, Vector3f left, Vector3f up) {
        if (!VisualQuality.critters()) {
            return;
        }
        if (shown <= 0.01F) {
            return;
        }
        AtmosphereLife.GnatsParams p = DesignData.atmosphere().gnats();
        int count = p.counts()[Math.min(2, VisualQuality.atmosphereTier())];
        // 動きは全部 speed 倍の時間で(10-31 ユーザー「速すぎてノイズのよう、もう少し遅く」。実際の羽虫より遅くてよい)。揺れの幅は wobble 倍。
        double t = Atmosphere.time(partialTick) * p.speed();
        double wob = p.wobble();
        int columns = Math.min(MAX, p.columns());
        for (int i = 0; i < columns; i++) {
            if (!SET[i]) {
                continue;
            }
            for (int k = 0; k < count; k++) {
                // 虫ごとに決まった半径・高さ・速さで柱のまわりを回り、上下にふらつく。
                float h = hash(SEED[i] + k * 1.7F);
                float h2 = hash(SEED[i] + k * 3.1F + 11.0F);
                float h3 = hash(SEED[i] + k * 5.3F + 23.0F);
                double r = p.width() * (0.2 + 0.8 * h);
                double speed = (h2 < 0.5 ? -1.0 : 1.0) * (2.0 + 4.0 * h3);
                double a = t * speed + h * 40.0;
                double y = CY[i] + p.height() * h2 + Math.sin(t * (3.0 + 5.0 * h) + k) * 0.25 * wob;
                double x = CX[i] + Math.cos(a) * r + Math.sin(t * 7.0 + k) * 0.04 * wob;
                double z = CZ[i] + Math.sin(a) * r + Math.cos(t * 6.0 + k * 2.0) * 0.04 * wob;
                LifeSprites.dot(x, y, z, p.size(), left, up, 0.06F, 0.06F, 0.04F, 0.85F * shown);
            }
        }
    }

    private static float hash(float v) {
        float s = Mth.sin(v * 12.9898F) * 43758.547F;
        return s - Mth.floor(s);
    }

    private GnatSwarms() {
    }
}
