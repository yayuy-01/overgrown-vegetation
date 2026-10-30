#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>
#moj_import <ov_cloud.glsl>
#moj_import <ov_mist.glsl>
#moj_import <ov_cave_mist.glsl>
#moj_import <ov_sway.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 ChunkOffset;
uniform int FogShape;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec3 ovPos;
out vec3 ovNormal;
out vec2 ovLight;
// The slowly changing effects, per vertex (2026-10-23): both mists as colour * a + rgb (ov_cave_mist.glsl ovHazeVertex), and
// (two-colour fog colour rgb (ov_mist.glsl ovAerialFog), cloud shadow amount (ov_cloud.glsl ovCloudAmount)).
out vec4 ovHaze;
out vec4 ovFog;
out vec3 ovAlbedo;

// Overgrown Vegetation: the same as vanilla, plus the wind sway of marked vertices (see ov_sway.glsl) and the
// camera-relative position and normal for the canopy shade (ov_light.glsl).
void main() {
    vec3 pos = Position + ChunkOffset;
    float alpha = Color.a;
    pos += ovDisplace(pos, Normal, alpha);
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_distance(pos, FogShape);
    vertexColor = vec4(Color.rgb, alpha) * minecraft_sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    ovPos = pos;
    ovNormal = Normal;
    ovLight = clamp(vec2(UV2) / 240.0, 0.0, 1.0);
    ovAlbedo = Color.rgb;
    ovHaze = ovHazeVertex(pos);
    ovFog = vec4(ovAerialFog(FogColor, pos, vertexDistance, FogStart, FogEnd).rgb, ovCloudAmount(pos, ovLight));
}
