#version 150

// Overgrown Vegetation: the crowd draw (client/crowd/CrowdDraw.java, 2026-11-18). Keep this file ASCII-only.
// Same as vanilla rendertype_entity_cutout_no_cull.vsh, except that the position and the normal come from the bone matrices
// of the instance (a texture buffer, RGBA32F texels), as GeckoLib does on the CPU: bone * cube * vertex.
// Per instance: texel 0 = lightmap u, v, overlay u, v; texel 1 = colour; then per bone 3 rows of the pose (3x4) and
// 3 columns of the normal matrix.

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec2 UV0;
in vec3 Normal;
in int BoneInfo;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform samplerBuffer OvCrowdBones;
uniform int OvCrowdStride;
uniform int OvCrowdBase;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

void main() {
    int inst = (OvCrowdBase + gl_InstanceID) * OvCrowdStride;
    vec4 head = texelFetch(OvCrowdBones, inst);
    vec4 tint = texelFetch(OvCrowdBones, inst + 1);
    int bone = BoneInfo & 4095;
    int flatBits = BoneInfo >> 12;
    int b = inst + 2 + bone * 6;

    vec4 p = vec4(Position, 1.0);
    vec3 pos = vec3(dot(texelFetch(OvCrowdBones, b), p), dot(texelFetch(OvCrowdBones, b + 1), p), dot(texelFetch(OvCrowdBones, b + 2), p));
    vec3 n = mat3(texelFetch(OvCrowdBones, b + 3).xyz, texelFetch(OvCrowdBones, b + 4).xyz, texelFetch(OvCrowdBones, b + 5).xyz) * Normal;
    // GeckoLib RenderUtil.fixInvertedFlatCube
    if (n.x < 0.0 && (flatBits & 6) != 0) n.x = -n.x;
    if (n.y < 0.0 && (flatBits & 5) != 0) n.y = -n.y;
    if (n.z < 0.0 && (flatBits & 3) != 0) n.z = -n.z;
    // the vanilla vertex format keeps the normal as signed bytes
    n = trunc(clamp(n, -1.0, 1.0) * 127.0) / 127.0;

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_distance(pos, FogShape);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, n, tint);
    lightMapColor = texelFetch(Sampler2, ivec2(head.xy) / 16, 0);
    overlayColor = texelFetch(Sampler1, ivec2(head.zw), 0);
    texCoord0 = UV0;
}
