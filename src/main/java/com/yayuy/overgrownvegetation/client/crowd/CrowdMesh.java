package com.yayuy.overgrownvegetation.client.crowd;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.util.RenderUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * GeckoLib の形({@link BakedGeoModel})を 1 回だけ GPU に置いたもの(2026-11-18 大群の軽量化 C・GPU スキニング)。
 *
 * <p>骨は GeckoLib が描く順(上の骨から深さ優先)に番号を振る。頂点は「骨の行列を掛ける前」= 箱の向き(箱の中心と回転)まで
 * 掛け終えた位置で持ち、どの骨に付くかの番号を添える。毎フレームは骨の行列だけを送り、頂点シェーダーで掛ける
 * (GeckoLib の {@code renderCube} と同じ掛け算の順: 骨 × 箱 × 頂点)。</p>
 *
 * <p>頂点 1 つ = 位置 3・UV 2・法線 3(箱の回転まで掛けた物)の float と、骨の番号 + 平らな箱の印の int 1 つ(36 バイト)。
 * 平らな箱の印は GeckoLib の {@code RenderUtil.fixInvertedFlatCube}(大きさが 0 の向きの法線を裏返す)をシェーダーで同じにするため。</p>
 */
public final class CrowdMesh {

    static final int STRIDE = 36;

    /** 骨の数(深さ優先の順)。 */
    final int boneCount;
    /** 骨 i から始まる部分木の骨の数(自分を含む)。子を隠す骨のとき、残りを飛ばすのに使う。 */
    final int[] subtree;
    final int indexCount;

    private int vao;
    private int vbo;
    private int ebo;

    private CrowdMesh(int boneCount, int[] subtree, int indexCount) {
        this.boneCount = boneCount;
        this.subtree = subtree;
        this.indexCount = indexCount;
    }

    static CrowdMesh build(BakedGeoModel model, CrowdShader.Locations loc) {
        RenderSystem.assertOnRenderThread();
        List<GeoBone> order = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        for (GeoBone top : model.topLevelBones()) collect(top, order, sizes);
        int[] subtree = new int[order.size()];
        for (int i = 0; i < subtree.length; i++) subtree[i] = sizes.get(i);

        int quads = 0;
        for (GeoBone bone : order) {
            for (GeoCube cube : bone.getCubes()) {
                for (GeoQuad quad : cube.quads()) if (quad != null) quads++;
            }
        }
        ByteBuffer vertices = MemoryUtil.memAlloc(Math.max(1, quads * 4 * STRIDE));
        IntBuffer indices = MemoryUtil.memAllocInt(Math.max(1, quads * 6));
        try {
            PoseStack cubeStack = new PoseStack();
            Vector4f p = new Vector4f();
            Vector3f n = new Vector3f();
            int base = 0;
            for (int b = 0; b < order.size(); b++) {
                for (GeoCube cube : order.get(b).getCubes()) {
                    cubeStack.pushPose();
                    RenderUtil.translateToPivotPoint(cubeStack, cube);
                    RenderUtil.rotateMatrixAroundCube(cubeStack, cube);
                    RenderUtil.translateAwayFromPivotPoint(cubeStack, cube);
                    Matrix4f pose = cubeStack.last().pose();
                    Matrix3f normal = cubeStack.last().normal();
                    int flat = (cube.size().x() == 0 ? 1 : 0) | (cube.size().y() == 0 ? 2 : 0) | (cube.size().z() == 0 ? 4 : 0);
                    int info = b | (flat << 12);
                    for (GeoQuad quad : cube.quads()) {
                        if (quad == null) continue;
                        normal.transform(n.set(quad.normal()));
                        for (GeoVertex v : quad.vertices()) {
                            Vector3f pos = v.position();
                            pose.transform(p.set(pos.x(), pos.y(), pos.z(), 1.0F));
                            vertices.putFloat(p.x()).putFloat(p.y()).putFloat(p.z())
                                    .putFloat(v.texU()).putFloat(v.texV())
                                    .putFloat(n.x()).putFloat(n.y()).putFloat(n.z())
                                    .putInt(info);
                        }
                        indices.put(base).put(base + 1).put(base + 2).put(base + 2).put(base + 3).put(base);
                        base += 4;
                    }
                    cubeStack.popPose();
                }
            }
            vertices.flip();
            indices.flip();

            CrowdMesh mesh = new CrowdMesh(order.size(), subtree, quads * 6);
            mesh.vao = GL30.glGenVertexArrays();
            mesh.vbo = GL15.glGenBuffers();
            mesh.ebo = GL15.glGenBuffers();
            GL30.glBindVertexArray(mesh.vao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, mesh.vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, mesh.ebo);
            GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
            attrib(loc.position(), 3, 0);
            attrib(loc.uv0(), 2, 12);
            attrib(loc.normal(), 3, 20);
            if (loc.boneInfo() >= 0) {
                GL20.glEnableVertexAttribArray(loc.boneInfo());
                GL30.glVertexAttribIPointer(loc.boneInfo(), 1, GL11.GL_INT, STRIDE, 32);
            }
            GL30.glBindVertexArray(0);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            return mesh;
        } finally {
            MemoryUtil.memFree(vertices);
            MemoryUtil.memFree(indices);
        }
    }

    private static void attrib(int location, int size, int offset) {
        if (location < 0) return;
        GL20.glEnableVertexAttribArray(location);
        GL20.glVertexAttribPointer(location, size, GL11.GL_FLOAT, false, STRIDE, offset);
    }

    /** GeckoLib の描く順(親 → 子の深さ優先)。sizes[i] = 骨 i の部分木の骨の数。 */
    private static int collect(GeoBone bone, List<GeoBone> order, List<Integer> sizes) {
        int at = order.size();
        order.add(bone);
        sizes.add(1);
        int count = 1;
        for (GeoBone child : bone.getChildBones()) count += collect(child, order, sizes);
        sizes.set(at, count);
        return count;
    }

    void bind() {
        GL30.glBindVertexArray(this.vao);
    }

    void delete() {
        RenderSystem.assertOnRenderThread();
        if (this.vao != 0) GL30.glDeleteVertexArrays(this.vao);
        if (this.vbo != 0) GL15.glDeleteBuffers(this.vbo);
        if (this.ebo != 0) GL15.glDeleteBuffers(this.ebo);
        this.vao = this.vbo = this.ebo = 0;
    }
}
