package com.yayuy.overgrownvegetation.registry;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraftforge.eventbus.api.IEventBus;
import com.yayuy.overgrownvegetation.port.registries.DeferredHolder;
import com.yayuy.overgrownvegetation.port.registries.DeferredRegister;

/**
 * このMODの独自パーティクル。2026-10-30 に画面の演出と一緒に 5 種とも parked/ へ退避し、10-31 に萌芽体の噴き出し
 * ({@link #SPORE_JET})だけ戻した(ユーザー「萌芽体の噴き出しは作り直さないので戻す」)。
 */
public final class ModParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, OvergrownVegetation.MODID);

    /**
     * 「噴き出す胞子」— 萌芽体の胞子嚢がしぼむ瞬間に先端の 1 点から上へ勢いよく噴く小さな粒(2026-10-23。
     * {@link com.yayuy.overgrownvegetation.client.SporeJetParticle}・出す側は {@link com.yayuy.overgrownvegetation.client.SporeJets})。
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SPORE_JET =
            PARTICLE_TYPES.register("spore_jet", () -> new SimpleParticleType(false));

    /**
     * 「破裂の胞子の雲」— G1 進化体の破裂でその場にゆっくり膨らんで薄れる大きめの粒(2026-11-12 ユーザー「作る」。
     * 吸うと危ない所が雲として見えるように)。絵はバニラの焚き火の煙({@code big_smoke_*})を胞子の色に染めて使う。
     * {@link com.yayuy.overgrownvegetation.client.SporeCloudParticle}・出す側は {@link com.yayuy.overgrownvegetation.corpse.G1DeathBurst}。
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SPORE_BURST_CLOUD =
            PARTICLE_TYPES.register("spore_burst_cloud", () -> new SimpleParticleType(false));

    /**
     * 「泥煙」— 水底を動くと舞い上がる泥(2026-11-13、水まわり ⑥ ユーザー選択)。絵は胞子の雲と同じバニラの煙を泥の茶に。
     * {@link com.yayuy.overgrownvegetation.client.MudCloudParticle}・出す側は {@code client.atmosphere.UnderwaterCritters}。
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> MUD_CLOUD =
            PARTICLE_TYPES.register("mud_cloud", () -> new SimpleParticleType(false));

    /**
     * 水中の生き物と落ち物(2026-11-13、水まわり ⑥ の直し。ユーザー「ドットが浮いているだけ」→「形を付ける」)。ミジンコ・沈む葉のかけら・胞子の綿。
     * {@link com.yayuy.overgrownvegetation.client.UnderwaterLifeParticles}・出す側は {@code client.atmosphere.UnderwaterCritters}。
     */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> WATER_FLEA =
            PARTICLE_TYPES.register("water_flea", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SINKING_LEAF =
            PARTICLE_TYPES.register("sinking_leaf", () -> new SimpleParticleType(false));
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SPORE_FLUFF =
            PARTICLE_TYPES.register("spore_fluff", () -> new SimpleParticleType(false));
    /** 水面に浮く落ち葉(2026-11-13 密林の水辺 ②)。{@link com.yayuy.overgrownvegetation.client.FloatingLeafParticle}・出す側は {@code client.atmosphere.FloatingLeaves}。 */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> FLOATING_LEAF =
            PARTICLE_TYPES.register("floating_leaf", () -> new SimpleParticleType(false));

    public static void register(IEventBus modBus) {
        PARTICLE_TYPES.register(modBus);
    }

    private ModParticles() {
    }
}
