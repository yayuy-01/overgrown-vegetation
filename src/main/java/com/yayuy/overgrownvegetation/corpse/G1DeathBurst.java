package com.yayuy.overgrownvegetation.corpse;

import com.yayuy.overgrownvegetation.corruption.CorruptionSwitch;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * G1 の進化体(1 段目・2 段目)が死ぬときの破裂(2026-11-12 ユーザー決定。正本は Vault「Mob の能力/G1 1 段目の共通」の death_burst の行と答え 4)。
 *
 * <p>死ぬと death_burst のアニメ(震え → 胞子嚢だけ膨らむ)が流れ、膨らみきった瞬間(= アニメの長さ)に Mob を消して、その場に次の 4 つを出す。
 * <ol>
 *   <li><b>胞子の雲</b>: ゆっくり膨らんで薄れる大きめの粒({@link ModParticles#SPORE_BURST_CLOUD}、11-12 ユーザー「作る」。絵はバニラの焚き火の煙)と、
 *       萌芽体の噴き出しと同じ細かい胞子の粒({@link ModParticles#SPORE_JET}。2〜4 秒漂い、風に流されて薄れる)。
 *       どちらも数(濃さ)は、この破裂で実際に上げる升の浮遊の量に合わせる({@link #cloudDensity})。</li>
 *   <li><b>体のかけら</b>: 肉(腐った肉)・樹皮(汚染の丸太)・苔(汚染の苔)のアイテムの砕けの粒を四方へ飛ばす(重力で落ちる)。</li>
 *   <li><b>湿った破裂音</b>: 新しく合成しない。バニラのスライムのブロック・泥・イカの墨吐きを低くして重ねる。</li>
 *   <li><b>汁の飛び散り</b>: 袋の中の液。バニラの蜂蜜の滴(落ちて地面に小さく散る)を体のまわりから落とす。</li>
 * </ol>
 * 種ごとの違いは<b>量だけ</b>(当たり判定の大きさから雲の広さ・かけらと滴の数・汚染の半径・音の高さを決める)。中身は全種共通。
 *
 * <p>近くのプレイヤーへの害は<b>胞子の場を通してだけ</b>: 周りの汚染は {@link NoisyCorruptionBurst#deathSphere}(1 tick の上限つき)で作り、
 * 升の浮遊はここで {@link SporeFieldServer#emitPoint} を 1 回呼んで {@link #airPercent}(小さい体 +10%・大きい体 +40% ほど)だけ上げる。
 * 11-12 ユーザー決定「G1 だけ小さく」: 球の放出(burstPerRadiusSq × 半径²。半径 2 で +364%)は使わない(球に emitSpores false を渡す)。
 * 直接の暴露は足さない。
 *
 * <p>呼び方(進化体を組み込むとき): Mob の {@code tickDeath} を上書きし、super を呼ばずに {@link #tickDeath(LivingEntity, int)} を呼ぶ
 * (バニラの 20 tick 目の白い煙 = 実体の出来事 60 を出さずに消す。ドロップと経験値はバニラどおり死んだ瞬間の {@code die} で出る)。
 * 死に際の赤い被弾色は描画側(GeckoLib の {@code getPackedOverlay} が {@code deathTime > 0} で赤くする)で消す。
 * 確かめ用のコマンドは {@code /ov perf g1 burst [幅] [高さ]}({@link G1Command})。
 *
 * <p>数値はすべて【仮】(11-12、このクラスの定数。設計データ・config には出していない)。
 */
public final class G1DeathBurst {

    // ------------------------------------------------------------------------------------------
    // 【仮】の数値(2026-11-12)。体の大きさ L = (幅² × 高さ)^(1/3) ブロック(鶏 0.48・ゾンビ 0.89・牛 1.04・クモ 1.21・アイアンゴーレム 1.74)
    // ------------------------------------------------------------------------------------------

    /** 汚染の球の半径 = round(これ + L)、1〜{@link #MAX_RADIUS}。【仮】小さい体 1・牛より大きい体 2。 */
    static final double RADIUS_BASE = 0.5D;
    /** 汚染の球の半径の上限。【仮】 */
    static final int MAX_RADIUS = 3;
    /** 汚染の球の輪郭の揺らぎ(ブロック、±)。【仮】(G4 の死の球は 1.2) */
    static final double SPHERE_NOISE = 0.8D;

    /**
     * 升の浮遊の上げ幅(%。100% = 過繁茂のチャンクの晴れた午後 = spore_field.json の fullAt) = これ × L、
     * {@link #AIR_PERCENT_MIN}〜{@link #AIR_PERCENT_MAX}。【仮】11-12 ユーザー決定「G1 だけ小さく」(案: 小さい体 +10%・大きい体 +40%、
     * 跡にしばらく居ると危ない程度)→ 鶏 11%・ゾンビ 20%・牛 24%・クモ 28%・アイアンゴーレム 40%。
     * 汚染の球の放出(burstPerRadiusSq × 半径²)は使わない(ほかの死に方の量は変えない)。
     */
    static final double AIR_PERCENT_PER_SIZE = 23.0D;
    static final double AIR_PERCENT_MIN = 5.0D;
    static final double AIR_PERCENT_MAX = 60.0D;

    /** 雲の広がり(半径、ブロック) = これ + {@link #CLOUD_RADIUS_PER_SIZE} × L。【仮】鶏 1.3・ゴーレム 2.7 */
    static final double CLOUD_RADIUS_BASE = 0.8D;
    static final double CLOUD_RADIUS_PER_SIZE = 1.1D;
    /** 雲の粒の数 = 濃さ × これ × 雲の半径²(上限 {@link #CLOUD_MAX})。【仮】 */
    static final double CLOUD_DOTS_PER_RADIUS_SQ = 120.0D;
    static final int CLOUD_MAX = 700;
    /** 濃さ 1 になる浮遊の上げ幅(%。100% = 過繁茂のチャンクの晴れた午後)。【仮】大きい体(40%)でちょうど 1 */
    static final double CLOUD_FULL_PERCENT = 40.0D;
    /** 膨らむ雲の粒({@link ModParticles#SPORE_BURST_CLOUD})の数 = 濃さ × これ × 雲の半径²、{@link #PUFFS_MIN}〜{@link #PUFFS_MAX}。【仮】 */
    static final double PUFFS_PER_RADIUS_SQ = 8.0D;
    static final int PUFFS_MIN = 3;
    static final int PUFFS_MAX = 60;
    /** 汚染が止まっていて浮遊を上げないときの雲の濃さ(0 = 出さない)。上げる量に合わせる決まりなので 0。 */
    static final double CLOUD_DENSITY_WHEN_OFF = 0.0D;

    /** かけらの数 = これ + {@link #FRAGMENTS_PER_SIZE} × L、上限 {@link #FRAGMENTS_MAX}。【仮】鶏 15・ゴーレム 32 */
    static final double FRAGMENTS_BASE = 8.0D;
    static final double FRAGMENTS_PER_SIZE = 14.0D;
    static final int FRAGMENTS_MAX = 40;
    /** かけらの横の速さ(ブロック/tick)と上向きの速さ。【仮】 */
    static final double FRAGMENT_SPEED_MIN = 0.12D, FRAGMENT_SPEED_MAX = 0.30D;
    static final double FRAGMENT_UP_MIN = 0.15D, FRAGMENT_UP_MAX = 0.40D;

    /** 汁の滴の数 = これ + {@link #JUICE_PER_SIZE} × L、上限 {@link #JUICE_MAX}。【仮】 */
    static final double JUICE_BASE = 8.0D;
    static final double JUICE_PER_SIZE = 12.0D;
    static final int JUICE_MAX = 36;
    /** 汁の散る広さ(半径、ブロック) = これ + {@link #JUICE_SPREAD_PER_SIZE} × L。【仮】 */
    static final double JUICE_SPREAD_BASE = 0.5D;
    static final double JUICE_SPREAD_PER_SIZE = 1.0D;

    /** 音の高さ = これ − {@link #PITCH_PER_SIZE} × L(0.5〜0.85)。大きい体ほど低い。【仮】 */
    static final float PITCH_BASE = 0.82F;
    static final float PITCH_PER_SIZE = 0.15F;
    /** 音の大きさ = これ + {@link #VOLUME_PER_SIZE} × L。【仮】 */
    static final float VOLUME_BASE = 0.8F;
    static final float VOLUME_PER_SIZE = 0.35F;

    private G1DeathBurst() {
    }

    /**
     * 進化体の {@code tickDeath} の代わり。{@code deathTime} を進め、{@code burstTick}(= death_burst のアニメの長さ、種ごとに約 18 tick)で
     * 破裂させて消す。バニラの 20 tick 目の白い煙より先に消えるよう、{@code burstTick} は 19 以下にする(超えたら 19 に詰める)。
     */
    public static void tickDeath(LivingEntity mob, int burstTick) {
        mob.deathTime++;
        if (mob.deathTime >= Math.min(burstTick, 19) && mob.level() instanceof ServerLevel level && !mob.isRemoved()) {
            burst(level, mob.position(), mob.getBbWidth(), mob.getBbHeight());
            mob.remove(Entity.RemovalReason.KILLED);
        }
    }

    /** 体の大きさ L(ブロック)。幅² × 高さ の立方根。 */
    static double size(float width, float height) {
        return Math.cbrt(Math.max(0.01D, (double) width * width * height));
    }

    /** 汚染の球の半径。 */
    static int sphereRadius(double size) {
        return Mth.clamp((int) Math.round(RADIUS_BASE + size), 1, MAX_RADIUS);
    }

    /** 大きさ L の体の破裂が上げる升の浮遊(%。100% = {@code fullAt})。 */
    static double airPercent(double size) {
        return Mth.clamp(AIR_PERCENT_PER_SIZE * size, AIR_PERCENT_MIN, AIR_PERCENT_MAX);
    }

    /** 雲の濃さ(0〜1)。浮遊の上げ幅に比例し、{@link #CLOUD_FULL_PERCENT} で頭打ち。 */
    static double cloudDensity(double airPercent) {
        return Mth.clamp(airPercent / CLOUD_FULL_PERCENT, 0.0D, 1.0D);
    }

    /**
     * 破裂を起こす(サーバー側)。
     *
     * @param feet   Mob の足元({@code position()})
     * @param width  当たり判定の幅
     * @param height 当たり判定の高さ
     * @return この破裂で上げた升の浮遊(%。汚染が止まっている・汚染を禁じた所では 0)
     */
    public static double burst(ServerLevel level, Vec3 feet, float width, float height) {
        double size = size(width, height);
        RandomSource random = level.random;
        Vec3 mid = feet.add(0.0D, height * 0.5D, 0.0D);

        // 1. 周りの汚染と浮遊(胞子の場への放出は球の中で。オーバーワールドとネザーだけ)
        int radius = sphereRadius(size);
        boolean corrupts = !CorruptionSwitch.isOff()
                && !SporeDeviceData.inhibits(level, BlockPos.containing(feet), SporeDeviceData.Inhibit.CORRUPTION);
        boolean fieldDimension = level.dimension() == Level.OVERWORLD || level.dimension() == Level.NETHER;
        double airPercent = 0.0D;
        if (corrupts) {
            // 球はブロックだけ(球の放出は使わない)。浮遊は G1 の小さな量を死んだその tick に上げる
            NoisyCorruptionBurst.deathSphere(level, feet, radius, SPHERE_NOISE, false);
            if (fieldDimension) {
                airPercent = airPercent(size);
                SporeFieldServer.emitPoint(level, BlockPos.containing(feet), airPercent / 100.0D * DesignData.sporeField().fullAt());
            }
        }

        // 2. 胞子の雲(濃さ = 上げた浮遊): ゆっくり膨らんで薄れる大きめの粒 + 細かい胞子の粒
        double density = corrupts && fieldDimension ? cloudDensity(airPercent) : CLOUD_DENSITY_WHEN_OFF;
        double cloudRadius = CLOUD_RADIUS_BASE + CLOUD_RADIUS_PER_SIZE * size;
        if (density > 0.0D) {
            int puffs = Mth.clamp((int) Math.round(density * PUFFS_PER_RADIUS_SQ * cloudRadius * cloudRadius), PUFFS_MIN, PUFFS_MAX);
            level.sendParticles(ModParticles.SPORE_BURST_CLOUD.get(), mid.x, mid.y, mid.z, puffs,
                    cloudRadius * 0.4D, Math.max(0.2D, height * 0.3D), cloudRadius * 0.4D, 0.02D);
        }
        int dots = Math.min(CLOUD_MAX, (int) Math.round(density * CLOUD_DOTS_PER_RADIUS_SQ * cloudRadius * cloudRadius));
        if (dots > 0) {
            level.sendParticles(ModParticles.SPORE_JET.get(), mid.x, mid.y, mid.z, dots,
                    cloudRadius * 0.45D, Math.max(0.3D, height * 0.35D), cloudRadius * 0.45D, 0.03D);
        }

        // 3. 体のかけら(肉・樹皮・苔。アイテムの砕けの粒は与えた速さで飛ぶ)
        ItemParticleOption flesh = new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.ROTTEN_FLESH));
        ItemParticleOption bark = new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(ModBlocks.OVERGROWN_LOG.get()));
        ItemParticleOption moss = new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(ModBlocks.OVERGROWN_MOSS_LAYER.get()));
        int fragments = Math.min(FRAGMENTS_MAX, (int) Math.round(FRAGMENTS_BASE + FRAGMENTS_PER_SIZE * size));
        double speedScale = 0.8D + 0.3D * size;
        for (int i = 0; i < fragments; i++) {
            float pick = random.nextFloat();
            ItemParticleOption kind = pick < 0.4F ? flesh : pick < 0.75F ? bark : moss;
            double angle = random.nextDouble() * Mth.TWO_PI;
            double speed = Mth.lerp(random.nextDouble(), FRAGMENT_SPEED_MIN, FRAGMENT_SPEED_MAX) * speedScale;
            double up = Mth.lerp(random.nextDouble(), FRAGMENT_UP_MIN, FRAGMENT_UP_MAX);
            double x = feet.x + (random.nextDouble() - 0.5D) * width;
            double y = feet.y + height * Mth.lerp(random.nextDouble(), 0.3D, 0.85D);
            double z = feet.z + (random.nextDouble() - 0.5D) * width;
            // 数 0 で送ると (dx, dy, dz) × 速さ がそのまま速度になる
            level.sendParticles(kind, x, y, z, 0, Math.cos(angle) * speed, up, Math.sin(angle) * speed, 1.0D);
        }

        // 4. 汁の飛び散り(蜂蜜の滴は真下へ落ちて地面に小さく散る。高さをばらして散る時刻をずらす)
        int drops = Math.min(JUICE_MAX, (int) Math.round(JUICE_BASE + JUICE_PER_SIZE * size));
        double spread = JUICE_SPREAD_BASE + JUICE_SPREAD_PER_SIZE * size;
        for (int i = 0; i < drops; i++) {
            double angle = random.nextDouble() * Mth.TWO_PI;
            double r = spread * Math.sqrt(random.nextDouble());
            double y = feet.y + height * Mth.lerp(random.nextDouble(), 0.25D, 0.9D);
            level.sendParticles(ParticleTypes.FALLING_HONEY, feet.x + Math.cos(angle) * r, y, feet.z + Math.sin(angle) * r,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }

        // 5. 湿った破裂音(バニラの湿った音を低くして重ねる。新しい音は作らない)
        float pitch = Mth.clamp(PITCH_BASE - PITCH_PER_SIZE * (float) size, 0.5F, 0.85F);
        float volume = VOLUME_BASE + VOLUME_PER_SIZE * (float) size;
        level.playSound(null, mid.x, mid.y, mid.z, SoundEvents.SLIME_BLOCK_BREAK, SoundSource.HOSTILE, volume, pitch);
        level.playSound(null, mid.x, mid.y, mid.z, SoundEvents.MUD_BREAK, SoundSource.HOSTILE, volume * 0.9F, pitch * 0.9F);
        level.playSound(null, mid.x, mid.y, mid.z, SoundEvents.SQUID_SQUIRT, SoundSource.HOSTILE, volume * 0.6F, pitch * 1.1F);
        return airPercent;
    }
}
