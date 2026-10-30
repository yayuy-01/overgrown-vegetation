package com.yayuy.overgrownvegetation.client.corpse;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.corpse.CorpseCollapse;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * バニラの描画のまま、胞子感染で死んだ Mob を死体の姿勢へ崩れ落とす(2026-10-09、ユーザー指定)。
 *
 * <ul>
 *   <li>{@link #applyRoot}: モデル全体を倒れる軸のまわりに回す(モデルの空間 = {@code translate(0,-1.501,0)} の後。
 *   村人の 0.9375 などレンダラーの拡大の内側なので、感染体・死体の GeckoLib の描画と大きさが合う)。重力で倒れるように加速し、
 *   着いたところで少しだけ跳ね返る。最後に settle で地面へ寝かせる。</li>
 *   <li>{@link #applyParts}: setupAnim の後に、各部位を死体の姿勢へ寄せる(頭が先に力を失い、腕、体と脚の順)。
 *   バニラのモデルは<b>同じ種の全個体で共有</b>なので、描き終えたら {@link #restore} で必ず元へ戻す(戻さないと次の生きた個体が歪む)。</li>
 * </ul>
 * 値は {@link CorpsePoses}(生成器の {@code corpse_pose.json})。
 */
public final class CollapsePoser {

    private CollapsePoser() {
    }

    /** 崩れ落ちの進み 0〜1(サーバーは {@link CorpseCollapse#COLLAPSE_TICKS} で死体に入れ替える)。 */
    public static float progress(LivingEntity entity, float partialTick) {
        return Mth.clamp((entity.deathTime + partialTick - 1.0F) / (CorpseCollapse.COLLAPSE_TICKS - 1.0F), 0.0F, 1.0F);
    }

    /** 体全体: 0.8 までに加速して倒れ切り、残りで少し跳ね返って戻る。 */
    static float rootWeight(float p) {
        if (p < 0.8F) {
            float x = p / 0.8F;
            return x * x;
        }
        float u = (p - 0.8F) / 0.2F;
        return 1.0F - 0.05F * Mth.sin((float) Math.PI * u);
    }

    static float settleWeight(float p) {
        return smooth((p - 0.45F) / 0.55F);
    }

    /** 部位: 頭が先に力を失い、腕・翼、体と脚の順。 */
    static float partWeight(String name, float p) {
        if (name.contains("head") || name.equals("hat") || name.equals("beak") || name.equals("red_thing") || name.equals("nose")) {
            return smooth(p / 0.55F);
        }
        if (name.contains("arm") || name.contains("wing")) {
            return smooth((p - 0.08F) / 0.7F);
        }
        return smooth((p - 0.15F) / 0.75F);
    }

    static float smooth(float x) {
        x = Mth.clamp(x, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }

    /** 崩れ落ちの途中の体のいちばん下の目安(モデルの空間 y、下向き +)。地面 24 から 0.5 だけ沈んだ所 = 死体の settle と同じ。 */
    static final float GROUND = 24.5F;

    /**
     * モデル全体を倒れる軸のまわりに回す(モデルの空間、1/16 ブロック)。<b>部位を寄せた後</b>({@link #applyParts} の後、描く前)に呼ぶ。
     * 回した体がどこかで地面に潜るなら、そのぶん持ち上げる(10-09: クモは足元の軸で 176 度裏返るので、90 度を過ぎると体が地面の下へ回り込む。
     * 脚を畳む途中なども同じ)。持ち上げは「settle を寄せた量」と「地面に潜らない量」の高い方で、終わりは settle そのもの(死体ブロックと合う)。
     */
    public static void applyRoot(PoseStack poseStack, EntityModel<?> model, ClientCorpseCollapse.Collapse collapse, float p) {
        CorpsePoses.Pose pose = collapse.pose();
        float wr = rootWeight(p);
        float ws = settleWeight(p);
        float[] h = pose.hinge();
        float[] r = pose.rotation();
        float[] s = pose.settle();
        Quaternionf full = new Quaternionf().rotationZYX(r[2] * Mth.DEG_TO_RAD, r[1] * Mth.DEG_TO_RAD, r[0] * Mth.DEG_TO_RAD);
        Quaternionf turn = new Quaternionf().slerp(full, wr);
        float dy = s[1] * ws;
        float low = lowest(model, collapse, h, turn);
        if (!Float.isNaN(low)) {
            dy = Math.min(dy, GROUND - low);   // y は下向き: 小さい方 = 高く持ち上げる方
        }
        // 終わりは死体の settle ちょうどへ寄せる(バニラの箱と死体の geo の違いで、入れ替わりの瞬間に段がつかないように)。
        // 10-09 に 11 種の終わりの高さをログで比べた: 10 種は一致、村人だけバニラの組んだ腕(胸の前の箱。死体の geo には無い)が 2.6 下へ出る。
        dy = Mth.lerp(smooth((p - 0.8F) / 0.2F), dy, s[1]);
        poseStack.translate(s[0] * ws / 16.0F, dy / 16.0F, s[2] * ws / 16.0F);
        poseStack.translate(h[0] / 16.0F, h[1] / 16.0F, h[2] / 16.0F);
        poseStack.mulPose(turn);
        poseStack.translate(-h[0] / 16.0F, -h[1] / 16.0F, -h[2] / 16.0F);
    }

    /**
     * 倒れる回転を掛けた体(部位は寄せた後)のいちばん下の y(モデルの空間、1/16 ブロック、下向き +)。部位が 1 つも無ければ NaN。
     * バニラの部位の箱の角を {@link ModelPart#visit} で数える(子の部位も入る)。子どものモデル(チビゾンビ)は
     * {@code AgeableListModel#renderToBuffer} と同じ縮め(HumanoidModel: 頭 0.75 倍・16 下げ、体と hat 0.5 倍・24 下げ)を挟む。
     */
    static float lowest(EntityModel<?> model, ClientCorpseCollapse.Collapse collapse, float[] h, Quaternionf turn) {
        PoseStack ps = new PoseStack();
        ps.translate(h[0] / 16.0F, h[1] / 16.0F, h[2] / 16.0F);
        ps.mulPose(turn);
        ps.translate(-h[0] / 16.0F, -h[1] / 16.0F, -h[2] / 16.0F);
        float[] max = {Float.NEGATIVE_INFINITY};
        boolean youngHumanoid = model.young && model instanceof HumanoidModel<?>;
        for (String name : collapse.pose().parts().keySet()) {
            if (name.indexOf('/') >= 0) {
                continue;   // 子の部位(耳・毛)は親の visit に入っている
            }
            ModelPart part = find(model, name);
            if (part == null || !part.visible) {
                continue;
            }
            ps.pushPose();
            if (youngHumanoid) {
                if (name.equals("head")) {
                    ps.scale(0.75F, 0.75F, 0.75F);
                    ps.translate(0.0F, 1.0F, 0.0F);
                } else {
                    ps.scale(0.5F, 0.5F, 0.5F);
                    ps.translate(0.0F, 1.5F, 0.0F);
                }
            }
            part.visit(ps, (pose, path, index, cube) -> {
                if (path.endsWith("/hat_rim")) {
                    return;   // 村人の帽子のつば(16×16 の板)。無職の村人は絵が透明で見えないのに、うつ伏せだと顔の下へ 4 はみ出して体が浮いた
                }
                Matrix4f m = pose.pose();
                for (int c = 0; c < 8; c++) {
                    float x = ((c & 1) == 0 ? cube.minX : cube.maxX) / 16.0F;
                    float y = ((c & 2) == 0 ? cube.minY : cube.maxY) / 16.0F;
                    float z = ((c & 4) == 0 ? cube.minZ : cube.maxZ) / 16.0F;
                    max[0] = Math.max(max[0], m.m01() * x + m.m11() * y + m.m21() * z + m.m31());
                }
            });
            ps.popPose();
        }
        return max[0] == Float.NEGATIVE_INFINITY ? Float.NaN : max[0] * 16.0F;
    }

    /**
     * 部位を死体の姿勢へ寄せる。戻すための元の姿勢を返す(何も動かさなければ空)。
     * 角度は球面補間(10-09: 死体の頭は (−1.60, 1.24, −1.43) のような合成の角度なので、軸ごとに直線で寄せると途中で首がねじれる)。
     */
    public static Map<ModelPart, PartPose> applyParts(EntityModel<?> model, ClientCorpseCollapse.Collapse collapse, float p) {
        Map<ModelPart, PartPose> saved = new IdentityHashMap<>();
        Vector3f euler = new Vector3f();
        for (Map.Entry<String, CorpsePoses.Part> e : collapse.pose().parts().entrySet()) {
            ModelPart part = find(model, e.getKey());
            if (part == null) {
                continue;
            }
            saved.putIfAbsent(part, part.storePose());
            float w = partWeight(e.getKey(), p);
            if (w <= 0.0F) {
                continue;
            }
            CorpsePoses.Part t = e.getValue();
            part.x = Mth.lerp(w, part.x, t.x());
            part.y = Mth.lerp(w, part.y, t.y());
            part.z = Mth.lerp(w, part.z, t.z());
            // ModelPart#translateAndRotate と同じ rotationZYX(z, y, x)
            Quaternionf from = new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot);
            Quaternionf to = new Quaternionf().rotationZYX(t.zRot(), t.yRot(), t.xRot());
            eulerZYX(from.slerp(to, w), euler);
            part.xRot = euler.x;
            part.yRot = euler.y;
            part.zRot = euler.z;
        }
        return saved;
    }

    /**
     * {@code rotationZYX(z, y, x)} の逆(行列 Rz·Ry·Rx から角度を取り出す)。JOML 1.10.5 の {@code getEulerAnglesZYX} は
     * {@code rotationZYX} の逆になっていない(10-09 に乱数 10 万回で確かめた。こちらは誤差 4e-7)。
     */
    static Vector3f eulerZYX(Quaternionf q, Vector3f out) {
        Matrix3f m = new Matrix3f().set(q);
        out.y = (float) Math.asin(Mth.clamp(-m.m02, -1.0F, 1.0F));
        out.x = (float) Math.atan2(m.m12, m.m22);
        out.z = (float) Math.atan2(m.m01, m.m00);
        return out;
    }

    public static void restore(@Nullable Map<ModelPart, PartPose> saved) {
        if (saved != null) {
            saved.forEach(ModelPart::loadPose);
        }
    }

    /**
     * バニラのモデルの root 直下の部位を名前で引く。HierarchicalModel(村人・クリーパー・クモ)は root の子、
     * HumanoidModel(ゾンビ・スケルトン・エンダーマン・ウィザースケルトン)は public のフィールド、ピグリン 3 種(PlayerModel)は外側の層も public。
     * 牛・豚・羊(毛)・鶏・ホグリン・ゾグリンは Accessor({@code mixin.client.QuadrupedModelAccessor}・{@code ChickenModelAccessor}・{@code HoglinModelAccessor})。
     * ストライダーは HierarchicalModel。
     */
    @Nullable
    static ModelPart find(EntityModel<?> model, String name) {
        int slash = name.indexOf('/');
        if (slash >= 0) {
            // 子の部位(10-09 夜): 「親/子」で引く(ストライダーの毛 body/right_top_bristle、ホグリン・ピグリンの耳 head/left_ear)。
            // 姿勢の値は親から見た相対(生成器 G1#vanillaPose)なので、そのまま子の部位に入れればよい。
            ModelPart part = find(model, name.substring(0, slash));
            for (String child : name.substring(slash + 1).split("/")) {
                if (part == null || !part.hasChild(child)) {
                    return null;
                }
                part = part.getChild(child);
            }
            return part;
        }
        if (model instanceof HierarchicalModel<?> h) {
            ModelPart root = h.root();
            return root.hasChild(name) ? root.getChild(name) : null;
        }
        if (model instanceof HumanoidModel<?> h) {
            ModelPart part = switch (name) {
                case "head" -> h.head;
                case "hat" -> h.hat;
                case "body" -> h.body;
                case "right_arm" -> h.rightArm;
                case "left_arm" -> h.leftArm;
                case "right_leg" -> h.rightLeg;
                case "left_leg" -> h.leftLeg;
                default -> null;
            };
            if (part == null && model instanceof net.minecraft.client.model.PlayerModel<?> p) {
                // ピグリン 3 種(PiglinModel は PlayerModel)の外側の層。root 直下の別部位で、setupAnim で手足から写される
                // → 崩れ落ちでも手足と同じ姿勢へ動かさないと服だけ立ったまま残る(2026-10-09)。
                part = switch (name) {
                    case "jacket" -> p.jacket;
                    case "right_sleeve" -> p.rightSleeve;
                    case "left_sleeve" -> p.leftSleeve;
                    case "right_pants" -> p.rightPants;
                    case "left_pants" -> p.leftPants;
                    default -> null;
                };
            }
            return part;
        }
        if (model instanceof net.minecraft.client.model.HoglinModel<?> hg) {
            com.yayuy.overgrownvegetation.mixin.client.HoglinModelAccessor a = (com.yayuy.overgrownvegetation.mixin.client.HoglinModelAccessor) hg;
            return switch (name) {
                case "head" -> a.overgrown_vegetation$head();
                case "body" -> a.overgrown_vegetation$body();
                case "right_front_leg" -> a.overgrown_vegetation$rightFrontLeg();
                case "left_front_leg" -> a.overgrown_vegetation$leftFrontLeg();
                case "right_hind_leg" -> a.overgrown_vegetation$rightHindLeg();
                case "left_hind_leg" -> a.overgrown_vegetation$leftHindLeg();
                default -> null;
            };
        }
        if (model instanceof net.minecraft.client.model.QuadrupedModel<?> q) {
            com.yayuy.overgrownvegetation.mixin.client.QuadrupedModelAccessor a = (com.yayuy.overgrownvegetation.mixin.client.QuadrupedModelAccessor) q;
            return switch (name) {
                case "head" -> a.overgrown_vegetation$head();
                case "body" -> a.overgrown_vegetation$body();
                case "right_hind_leg" -> a.overgrown_vegetation$rightHindLeg();
                case "left_hind_leg" -> a.overgrown_vegetation$leftHindLeg();
                case "right_front_leg" -> a.overgrown_vegetation$rightFrontLeg();
                case "left_front_leg" -> a.overgrown_vegetation$leftFrontLeg();
                default -> null;
            };
        }
        if (model instanceof net.minecraft.client.model.ChickenModel<?> c) {
            com.yayuy.overgrownvegetation.mixin.client.ChickenModelAccessor a = (com.yayuy.overgrownvegetation.mixin.client.ChickenModelAccessor) c;
            return switch (name) {
                case "head" -> a.overgrown_vegetation$head();
                case "beak" -> a.overgrown_vegetation$beak();
                case "red_thing" -> a.overgrown_vegetation$redThing();
                case "body" -> a.overgrown_vegetation$body();
                case "right_leg" -> a.overgrown_vegetation$rightLeg();
                case "left_leg" -> a.overgrown_vegetation$leftLeg();
                case "right_wing" -> a.overgrown_vegetation$rightWing();
                case "left_wing" -> a.overgrown_vegetation$leftWing();
                default -> null;
            };
        }
        return null;
    }
}
