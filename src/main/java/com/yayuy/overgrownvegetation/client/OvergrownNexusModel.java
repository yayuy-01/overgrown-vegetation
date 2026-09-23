package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.entity.OvergrownNexusEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 「過繫茂の苗床」(手作りモデル、蔦 {@code vine}/{@code vine2}/{@code vine3}/{@code vine4})の
 * GeckoLib モデル定義。実体は3つのリソースファイル:
 * <ul>
 *   <li>形状: {@code assets/overgrown_vegetation/geo/overgrown_nexus.geo.json}</li>
 *   <li>アニメーション: {@code assets/overgrown_vegetation/animations/overgrown_nexus.animation.json}
 *       (中身は空の {@code idle} のみ。実際の動きはすべてここ {@link #setCustomAnimations} で行う)</li>
 *   <li>テクスチャ: {@code assets/overgrown_vegetation/textures/entity/overgrown_nexus.png}</li>
 * </ul>
 *
 * <h2>重要: GeckoLib の「毎フレーム自動でレストポーズへ戻る」は、毎フレーム自分で
 * setRotX/Y/Z を呼んでいるボーンには効かない</h2>
 * <p>GeckoLib は、あるボーンに「このフレーム、アニメーションクリップからも
 * カスタムコードからも一度も回転が設定されなかった」場合にのみ、そのボーンを
 * レストポーズへ戻す({@code AnimationProcessor#tickAnimation} 参照)。
 * ところが {@code setRotX/Y/Z} を呼ぶこと自体が「このボーンは変更された」という
 * フラグを立ててしまうため、常時うねらせるボーン(=毎フレーム必ず呼ぶ)は
 * このフラグが恒久的に立ちっぱなしになり、GeckoLib 側のリセットが二度と働かない。
 * その状態で {@code bone.getRotY() + 差分} のような「今の値に足す」書き方をすると、
 * 描画フレームを重ねるたびに際限なく積み上がっていく — これが
 * 「うねりが地面に埋まるほど強くなる」「vine2,3,4 がずっと回転し続ける」の
 * 正体だった(以前見つけた scale が際限なく膨れ上がるバグと全く同じ理屈)。
 *
 * <p>そのため、ここでは常に {@link GeoBone#getInitialSnapshot()}
 * (ボーン登録時に一度だけ保存され、以後誰にも書き換えられない「本当のレストポーズ」)
 * を基準にした絶対値でだけ角度を設定する。相対加算(get→+差分→set)は一切使わない。
 *
 * <p>各鎖の曲がる軸も、モデル自身が geo.json に焼き込んでいる値に合わせてある
 * (vine-3/4/5 は X 軸、vine-6〜20 は Z 軸で曲がるよう作られている)。以前は
 * vine2/3/4 を Y 軸(ヨー)で揺らしていたが、それはこのモデルの関節が実際に
 * 曲がる軸ではなかったため、単に軸の違う回転を親の複雑な姿勢へねじ込むだけになり
 * 「うねっているように見えない、ただ回転しているだけ」に見えていた。
 */
public class OvergrownNexusModel extends GeoModel<OvergrownNexusEntity> {

    private static final ResourceLocation MODEL = OvergrownVegetation.id("geo/overgrown_nexus.geo.json");
    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/overgrown_nexus.png");
    private static final ResourceLocation ANIMATION = OvergrownVegetation.id("animations/overgrown_nexus.animation.json");

    private static final String[] MAIN_CHAIN = {"vine-1", "vine-2", "vine-3", "vine-4", "vine-5"};
    private static final String[] LIMB2_CHAIN = {"vine-6", "vine-7", "vine-8", "vine-9", "vine-10"};
    private static final String[] LIMB3_CHAIN = {"vine-11", "vine-12", "vine-13", "vine-14", "vine-15"};
    private static final String[] LIMB4_CHAIN = {"vine-16", "vine-17", "vine-18", "vine-19", "vine-20"};

    /**
     * 鎖1本(先端まで)がうねって良い最大角度(度、片側)。{@link #applyChainSway} が
     * 鎖を構成する各関節にこの値を等分してから足すので、全関節の位相がたまたま
     * 揃った最悪の場合でも、先端で見える合計の振れはこの値を超えない
     * (三角不等式: 各区間の絶対値の和が全体の絶対値の和の上界になる)。
     */
    private static final double MAIN_CHAIN_TOTAL_SWAY_DEG = 8.0D;
    private static final double LIMB_CHAIN_TOTAL_SWAY_DEG = 10.0D;

    @Override
    public ResourceLocation getModelResource(OvergrownNexusEntity animatable) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(OvergrownNexusEntity animatable) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(OvergrownNexusEntity animatable) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(OvergrownNexusEntity animatable, long instanceId, AnimationState<OvergrownNexusEntity> state) {
        super.setCustomAnimations(animatable, instanceId, state);
        double t = animatable.tickCount + state.getPartialTick();
        double seed = (animatable.getId() % 1000) * 0.37D;

        // 本体(直立、X軸で曲がるよう作られている)と、水平に這う3本(Z軸で曲がるよう作られている)。
        applyChainSway(t, seed, MAIN_CHAIN, Axis.X, MAIN_CHAIN_TOTAL_SWAY_DEG);
        applyChainSway(t, seed + 11.0D, LIMB2_CHAIN, Axis.Z, LIMB_CHAIN_TOTAL_SWAY_DEG);
        applyChainSway(t, seed + 23.0D, LIMB3_CHAIN, Axis.Z, LIMB_CHAIN_TOTAL_SWAY_DEG);
        applyChainSway(t, seed + 37.0D, LIMB4_CHAIN, Axis.Z, LIMB_CHAIN_TOTAL_SWAY_DEG);

        applyBoom(animatable);
        applySweep(animatable);
        applySpore(animatable);
    }

    private enum Axis { X, Z }

    /**
     * 鎖を構成する各ボーンに、根元から先端へ位相をずらした正弦波を1つずつ足し、
     * 触手が波打つように見せる。位相をずらしてあるので、波が根元から先端へ
     * 伝わっていくように見える(全関節が同時に同じ向きへ振れる「棒が振り子のように
     * 揺れる」動きにはならない)。
     */
    private void applyChainSway(double t, double seed, String[] boneNames, Axis axis, double totalMaxDeg) {
        int segments = boneNames.length;
        double perSegmentDeg = totalMaxDeg / segments;

        for (int i = 0; i < segments; i++) {
            double phase = seed + i * 0.85D;
            double deg = smoothNoise(t, 0.05D, phase) * perSegmentDeg;
            float rad = (float) Math.toRadians(deg);

            getBone(boneNames[i]).ifPresent(bone -> {
                BoneSnapshot rest = bone.getInitialSnapshot();
                if (axis == Axis.X) {
                    bone.setRotX(rest.getRotX() + rad);
                } else {
                    bone.setRotZ(rest.getRotZ() + rad);
                }
            });
        }
    }

    /** 2つの正弦波を足し合わせた、常に -1..1 に収まる滑らかな擬似ランダム値。 */
    private static double smoothNoise(double t, double freq, double phase) {
        return Math.sin(t * freq + phase) * 0.65D + Math.sin(t * freq * 1.73D + phase * 1.4D) * 0.35D;
    }

    /**
     * vine4: 溜め中・反動中は {@code vine4}(先端 houkou を含む鎖全体の親ボーン)自体を
     * {@link OvergrownNexusEntity#getBoomAimYaw()}/{@link OvergrownNexusEntity#getBoomAimPitch()}
     * ぶんだけ Y 軸(ヨー)・X 軸(ピッチ)で余分に回し、体ごと対象の方へ向ける —
     * 常時うねりの角度上限({@link #LIMB_CHAIN_TOTAL_SWAY_DEG}）とは完全に別枠で、
     * ここでは一切クランプしない(攻撃動作なので大きく動いて良い)。さらに vine-19/vine-20
     * (先端寄りの2関節)で張り詰め/反動の「うなずき」を上乗せする。
     *
     * <p>Y軸だけでは houkou は狭い範囲しか狙えない(vine4 自身のレストポーズが X・Y・Z
     * 全軸にまたがる複合回転のため)。Y と X を組み合わせて {@link OvergrownNexusEntity#solveBoomAim}
     * が総当たりで求めた組を両方適用することで、実際にはほぼ全方位を狙えるようにしてある
     * (詳細は {@link OvergrownNexusEntity} のクラス javadoc「Y軸だけでは狙えない」を参照)。
     */
    private void applyBoom(OvergrownNexusEntity animatable) {
        int boomState = animatable.getBoomState();
        float dipDeg;
        float aimYawDeg;
        float aimPitchDeg;
        final float scale;
        if (boomState == OvergrownNexusEntity.BOOM_CHARGE) {
            float p = Mth.clamp(animatable.getBoomProgress(), 0.0F, 1.0F);
            dipDeg = p * 16.0F;
            aimYawDeg = animatable.getBoomAimYaw();
            aimPitchDeg = animatable.getBoomAimPitch();
            scale = 1.0F + p * 0.12F;
        } else if (boomState == OvergrownNexusEntity.BOOM_RECOIL) {
            // 反動: 素早く逆側へ振れてからゆっくり元へ戻る。狙いは発射時点の向きのまま保持する。
            float p = Mth.clamp(animatable.getBoomProgress(), 0.0F, 1.0F);
            dipDeg = p < 0.3F ? Mth.lerp(p / 0.3F, 16.0F, -20.0F) : Mth.lerp((p - 0.3F) / 0.7F, -20.0F, 0.0F);
            aimYawDeg = animatable.getBoomAimYaw();
            aimPitchDeg = animatable.getBoomAimPitch();
            scale = 1.0F;
        } else if (boomState == OvergrownNexusEntity.BOOM_RETURN) {
            // 復帰: うなずきはもう解消済み(dip=0)。狙いは NexusSonicBoomGoal#updateReturn が
            // サーバー側で毎tickなめらかにレストポーズへ戻しながら同期しているので、
            // ここではその値をそのまま読むだけでよい(急に0へ飛ばさない)。
            dipDeg = 0.0F;
            aimYawDeg = animatable.getBoomAimYaw();
            aimPitchDeg = animatable.getBoomAimPitch();
            scale = 1.0F;
        } else {
            // アイドル中も明示的にレストポーズへ戻す絶対値を書く(下の注記のとおり、
            // 毎フレーム触るボーンは GeckoLib 自身のリセットに頼れないため)。
            dipDeg = 0.0F;
            aimYawDeg = 0.0F;
            aimPitchDeg = 0.0F;
            scale = 1.0F;
        }
        float dipRad = (float) Math.toRadians(dipDeg);
        float aimYawRad = (float) Math.toRadians(aimYawDeg);
        float aimPitchRad = (float) Math.toRadians(aimPitchDeg);
        getBone("vine4").ifPresent(bone -> {
            BoneSnapshot rest = bone.getInitialSnapshot();
            bone.setRotY(rest.getRotY() + aimYawRad);
            bone.setRotX(rest.getRotX() + aimPitchRad);
        });
        getBone("vine-20").ifPresent(bone -> {
            BoneSnapshot rest = bone.getInitialSnapshot();
            bone.setRotX(rest.getRotX() + dipRad);
            // scale も同じ理由で必ず絶対値。getScaleX() に掛け算すると毎フレーム倍々に膨れ上がる。
            bone.setScaleX(scale);
            bone.setScaleY(scale);
            bone.setScaleZ(scale);
        });
        getBone("vine-19").ifPresent(bone -> {
            BoneSnapshot rest = bone.getInitialSnapshot();
            bone.setRotX(rest.getRotX() + dipRad * 0.5F);
        });
    }

    /**
     * vine2・vine3 の根本(鎖全体の親ボーン)を Y 軸(ワールド鉛直軸まわりのヨー)で振る:
     * 溜め(巻く)→振り(スイング)。常時うねりの角度上限とは別枠で、ここでは一切クランプしない
     * (プレイヤーを弾き飛ばす攻撃動作なので、はっきり視認できる大きさで動かす)。
     *
     * <p>以前は各関節が実際に曲がる軸(Z軸)を使っていたが、vine2/vine3 自身のレストポーズが
     * 複雑な3軸複合回転で据え付けられているため、Z軸の delta は縦に振っているように見えてしまい
     * (「vine4 を対象へ向ける」修正で使ったのと同じ理屈で)、横方向へ確実に振るには
     * ワールド鉛直軸(Y)で回すのが正しい。
     */
    private void applySweep(OvergrownNexusEntity animatable) {
        int sweepState = animatable.getSweepState();
        float yawDeg;
        if (sweepState == OvergrownNexusEntity.SWEEP_WINDUP) {
            float p = Mth.clamp(animatable.getSweepProgress(), 0.0F, 1.0F);
            yawDeg = Mth.lerp(p, 0.0F, 35.0F);
        } else if (sweepState == OvergrownNexusEntity.SWEEP_SWING) {
            float p = Mth.clamp(animatable.getSweepProgress(), 0.0F, 1.0F);
            yawDeg = Mth.lerp(p, 35.0F, -55.0F);
        } else if (sweepState == OvergrownNexusEntity.SWEEP_RETURN) {
            // 振り抜き終わり(-55°)から0へなめらかに戻す — 以前はここが素通しの else 節に
            // 落ちて次のフレームでいきなり0になり、「カクッ」と不自然に見えていた。
            float p = Mth.clamp(animatable.getSweepProgress(), 0.0F, 1.0F);
            yawDeg = Mth.lerp(p, -55.0F, 0.0F);
        } else {
            yawDeg = 0.0F;
        }
        float yawRad = (float) Math.toRadians(yawDeg);
        getBone("vine2").ifPresent(bone -> {
            BoneSnapshot rest = bone.getInitialSnapshot();
            bone.setRotY(rest.getRotY() + yawRad);
        });
        getBone("vine3").ifPresent(bone -> {
            BoneSnapshot rest = bone.getInitialSnapshot();
            bone.setRotY(rest.getRotY() + yawRad);
        });
    }

    private static final String[] HOUSINOU_BONES =
            {"housinou", "housinou2", "housinou3", "housinou4", "housinou5", "housinou6"};
    /** 胞子嚢が最も膨らんだ瞬間のスケール(等倍からの倍率)。 */
    private static final float SPORE_PEAK_SCALE = 1.6F;

    /**
     * vine 頂上の胞子嚢({@code housinou}〜{@code housinou6})を、{@code DATA_SPORE_STATE}/
     * {@code DATA_SPORE_PROGRESS} に合わせて膨らませたりしぼませたりする。
     * {@code getSporeProgress()} は INFLATE 中は 0→1(膨らむ)、DEFLATE 中は 1→0(しぼむ)と
     * すでに「今どれだけ膨らんでいるか」を表す値になっているので、状態を問わず同じ式
     * (等倍 〜 {@link #SPORE_PEAK_SCALE} 倍を {@code progress} で補間)を使うだけでよい。
     * scale は毎フレーム絶対値で設定する(相対倍算だと際限なく膨れ上がるバグになる、
     * {@link #applyBoom} の注記と同じ理由)。
     */
    private void applySpore(OvergrownNexusEntity animatable) {
        int state = animatable.getSporeState();
        float scale;
        if (state == OvergrownNexusEntity.SPORE_IDLE) {
            scale = 1.0F;
        } else {
            float p = Mth.clamp(animatable.getSporeProgress(), 0.0F, 1.0F);
            float eased = p * p * (3.0F - 2.0F * p);
            scale = Mth.lerp(eased, 1.0F, SPORE_PEAK_SCALE);
        }
        for (String name : HOUSINOU_BONES) {
            getBone(name).ifPresent(bone -> {
                bone.setScaleX(scale);
                bone.setScaleY(scale);
                bone.setScaleZ(scale);
            });
        }
    }
}
