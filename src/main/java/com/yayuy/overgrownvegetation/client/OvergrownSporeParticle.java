package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 「過繁茂の胞子」パーティクル。過繁茂バイオームの緑の胞子と同系色だが、
 * {@code spore_blossom_air}(速度が内部で固定)と違って与えた初速でそのまま飛び、
 * 毎 tick の摩擦({@code quakeSporeFriction})で減速し、わずかな重力
 * ({@code quakeSporeGravity})で落ちてくる。地形は貫通する(裂け目の中で使うため)。
 *
 * <p>= 花火の火花のような動き: 噴出点で一気に加速 → 徐々に減速 → 広範囲に散る。
 *
 * <h2>短命版({@code overgrown_spore_short})</h2>
 * <p>見た目は同じで、寿命だけ {@value #SHORT_MIN_LIFETIME}〜{@value #SHORT_MAX_LIFETIME} tick と短く、
 * <b>不透明なブロックの中に入ったら消える</b>もの(2026-09-25、全体監査 #479/#991「粒子の見直し」でユーザー承認 2026-09-24)。
 * 爆弾の航跡・爆発、胞子腺、ペディキュラが弾けたときはこちらを使う。この粒子は地割れの噴出用に作られていて
 * 寿命が 90〜200 tick と長く地形も貫通するので、流用先では<b>地面に沈んだ粒が 10 秒近く残っていた</b>
 * (航跡は爆弾 1 個で毎 tick 1 粒、爆発は 1 回 80 粒)。地割れは 1 回きりの演出で、裂け目の中で使うため
 * 地形を貫通する必要もあるので、長命版のまま残してある。
 */
@OnlyIn(Dist.CLIENT)
public class OvergrownSporeParticle extends TextureSheetParticle {

    /** 短命版の寿命(tick)。仮決め 2026-09-25。 */
    static final int SHORT_MIN_LIFETIME = 20;
    static final int SHORT_MAX_LIFETIME = 40;
    /**
     * 短命版が寿命のどこから薄くなり始めるか。長命版の 0.8 のままだと最後の 4〜8 tick で消えて
     * ぷつりと途切れて見えるので、早めに薄くし始める。仮決め 2026-09-25。
     */
    private static final float SHORT_FADE_START = 0.6F;
    /** 短命版が「ブロックに埋まったか」を見る間隔(tick、2 のべき乗 - 1 のマスク)。仮決め 2026-09-25。 */
    private static final int SHORT_SOLID_CHECK_MASK = 3;

    /** 噴出点(生成時)の Y。ここからの上昇量で薄くなっていく。 */
    private final double startY;
    private final float riseFade;
    /** 短命版か。寿命・消え始め・ブロックに埋まったときの扱いが変わる。 */
    private final boolean shortLived;
    /** これまでの最大上昇量。一度薄くなったら落下しても濃く戻らないように。 */
    private float maxRise;

    protected OvergrownSporeParticle(ClientLevel level, double x, double y, double z,
                                     double xd, double yd, double zd, SpriteSet sprites) {
        this(level, x, y, z, xd, yd, zd, sprites, false);
    }

    protected OvergrownSporeParticle(ClientLevel level, double x, double y, double z,
                                     double xd, double yd, double zd, SpriteSet sprites, boolean shortLived) {
        super(level, x, y, z);
        this.shortLived = shortLived;
        this.friction = OvergrownVegetationConfig.COMMON.quakeSporeFriction.get().floatValue();
        // Particle.tick は毎 tick yd -= 0.04 * gravity。設定値(ブロック/tick^2)を 0.04 で割る。
        this.gravity = (float) (OvergrownVegetationConfig.COMMON.quakeSporeGravity.get() / 0.04D);
        this.hasPhysics = false;
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.startY = y;
        this.riseFade = OvergrownVegetationConfig.COMMON.quakeSporeRiseFade.get().floatValue();
        this.lifetime = shortLived
                ? Mth.randomBetweenInclusive(this.random, SHORT_MIN_LIFETIME, SHORT_MAX_LIFETIME)
                : Mth.randomBetweenInclusive(this.random, 90, 200);
        this.quadSize = OvergrownVegetationConfig.COMMON.quakeSporeSize.get().floatValue()
                * (0.6F + this.random.nextFloat() * 0.8F);
        this.setColor(0.34F, 0.72F, 0.27F);
        this.pickSprite(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        // 短命版は、不透明なブロックに埋まったら消す(地面に落ちた航跡・爆発の粒)。
        // 不透明なブロックの中の粒は描いても見えないので、見た目は変わらない。
        if (this.shortLived && !this.removed && (this.age & SHORT_SOLID_CHECK_MASK) == 0) {
            BlockPos pos = BlockPos.containing(this.x, this.y, this.z);
            if (this.level.getBlockState(pos).isSolidRender(this.level, pos)) {
                this.remove();
                return;
            }
        }
        // 噴出点からの上昇量で薄くなる(上に行くほど透明 → 消える)。落下しても戻らない。
        this.maxRise = Math.max(this.maxRise, (float) (this.y - this.startY));
        float byHeight = riseFade <= 0.0F ? 1.0F : Mth.clamp(1.0F - this.maxRise / riseFade, 0.0F, 1.0F);
        // 寿命の終わり際にも短くフェード(唐突に消えないように)。
        float end = this.lifetime * (this.shortLived ? SHORT_FADE_START : 0.8F);
        float byLife = this.age > end
                ? Math.max(0.0F, 1.0F - (this.age - end) / (this.lifetime - end))
                : 1.0F;
        this.alpha = byHeight * byLife;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @OnlyIn(Dist.CLIENT)
    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;
        private final boolean shortLived;

        /** 長命版(地割れの噴出用)。 */
        public Provider(SpriteSet sprites) {
            this(sprites, false);
        }

        private Provider(SpriteSet sprites, boolean shortLived) {
            this.sprites = sprites;
            this.shortLived = shortLived;
        }

        /** 短命版({@code overgrown_spore_short})。{@code registerSpriteSet} に渡す。 */
        public static Provider shortLived(SpriteSet sprites) {
            return new Provider(sprites, true);
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            return new OvergrownSporeParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, this.sprites, this.shortLived);
        }
    }
}
