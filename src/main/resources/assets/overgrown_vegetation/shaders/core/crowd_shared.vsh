#version 150

// Overgrown Vegetation: the far horde draw with shared poses (client/crowd/HordeImpostors.java, 2026-11-18). ASCII only.
// Same light, fog and colour as crowd_entity.vsh (= vanilla rendertype_entity_cutout_no_cull), but one instance only sends
// where it stands: texel 0 = camera-relative x, y, z and yaw (degrees), texel 1 = scale, pose slot, lightmap u, v.
// The bones come from a shared pose (OvCrowdPoses: per slot and bone, 3 rows of the 3x4 pose + 3 columns of the normal matrix),
// built once per frame for a few animation phases. Instance matrix = T(x, y, z) * S(scale) * Ry(180 - yaw) * T(0, 0.01, 0)
// like GeoEntityRenderer.

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec2 UV0;
in vec3 Normal;
in ivec2 UV1;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform samplerBuffer OvCrowdPoses;
uniform samplerBuffer OvCrowdInstances;
uniform int OvCrowdBones;
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
    int inst = (OvCrowdBase + gl_InstanceID) * 2;
    vec4 where = texelFetch(OvCrowdInstances, inst);
    vec4 more = texelFetch(OvCrowdInstances, inst + 1);
    int boneInfo = UV1.x;
    int bone = boneInfo & 4095;
    int flatBits = boneInfo >> 12;
    int b = (int(more.y + 0.5) * OvCrowdBones + bone) * 6;

    vec4 p = vec4(Position, 1.0);
    vec3 m = vec3(dot(texelFetch(OvCrowdPoses, b), p), dot(texelFetch(OvCrowdPoses, b + 1), p), dot(texelFetch(OvCrowdPoses, b + 2), p));
    vec3 n = mat3(texelFetch(OvCrowdPoses, b + 3).xyz, texelFetch(OvCrowdPoses, b + 4).xyz, texelFetch(OvCrowdPoses, b + 5).xyz) * Normal;
    float a = radians(180.0 - where.w);
    float c = cos(a), s = sin(a);
    // rotation about +Y (JOML rotationY): x' = c x + s z, z' = -s x + c z
    m.y += 0.01;
    vec3 pos = vec3(c * m.x + s * m.z, m.y, -s * m.x + c * m.z) * more.x + where.xyz;
    n = vec3(c * n.x + s * n.z, n.y, -s * n.x + c * n.z);
    // GeckoLib RenderUtil.fixInvertedFlatCube
    if (n.x < 0.0 && (flatBits & 6) != 0) n.x = -n.x;
    if (n.y < 0.0 && (flatBits & 5) != 0) n.y = -n.y;
    if (n.z < 0.0 && (flatBits & 3) != 0) n.z = -n.z;
    n = trunc(clamp(n, -1.0, 1.0) * 127.0) / 127.0;

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_distance(pos, FogShape);
    // 1.20.1 port: pos and n are world-aligned here (ModelViewMat carries the camera rotation), but 1.20.1 puts the lights in view space
    // (Lighting.setupLevel with the camera matrix; 1.21 keeps them in world space), so the normal is turned into view space for the light.
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, mat3(ModelViewMat) * n, vec4(1.0));
    lightMapColor = texelFetch(Sampler2, ivec2(more.zw) / 16, 0);
    overlayColor = texelFetch(Sampler1, ivec2(0, 10), 0);
    texCoord0 = UV0;
}
