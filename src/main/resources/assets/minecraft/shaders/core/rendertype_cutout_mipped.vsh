#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>
#moj_import <ov_cloud.glsl>
#moj_import <ov_mist.glsl>
#moj_import <ov_cave_mist.glsl>
#moj_import <ov_sway.glsl>
#moj_import <ov_leaf.glsl>
#moj_import <ov_plant.glsl>

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

out vec4 vertexColor;
// The outputs are packed (2026-10-24, the leaf layer is bound by its vertices, DEV_NOTES "leaf layer"): uv and light,
// position and cloud shadow amount (ov_cloud.glsl ovCloudAmount), and the normal with the thin-plant flag (1 for this
// mod's thin plants moved here from the cutout layer (ov_plant.glsl): no facing-away shade for them), constant over a face.
out vec4 ovTexLight;
out vec4 ovPosC;
flat out vec4 ovNormThin;
// The slowly changing effects, per vertex (2026-10-23): both mists (ov_cave_mist.glsl ovHazeVertex) and then the fog with the
// two-colour fog colour (ov_mist.glsl ovAerialFog), folded into one transform colour * a + rgb (2026-10-24; the fog amount is
// now interpolated between the vertices instead of the distance, the same on block-sized faces).
out vec4 ovMix;
out vec3 ovAlbedo;
out vec4 ovLeaf;

// Overgrown Vegetation: the same as vanilla, plus the wind sway of marked vertices (see ov_sway.glsl) and the
// camera-relative position and normal for the canopy shade (ov_light.glsl), and the colour code of the overgrown leaves
// (ov_leaf.glsl; their tint carries a code, not a colour).
void main() {
    vec3 pos = Position + ChunkOffset;
    float group = ovPlantGroup(Color);
    float alpha = ovPlantAlpha(Color, group);
    vec3 tint = Color.rgb;
    ovLeaf = group > 0.5 ? vec4(0.0) : ovLeafDecode(Color, tint);
    pos += ovDisplace(pos, Normal, alpha);
    pos = ovPlantSink(pos, Position, ChunkOffset, group);
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    float vertexDistance = fog_distance(pos, FogShape);
    // Glowing faces (group 3, client/NightGlow.java) are dimmed by day (ov_plant.glsl ovGlowLightmap).
    vec4 lightmap = ovGlowLightmap(minecraft_sample_lightmap(Sampler2, UV2), minecraft_sample_lightmap(Sampler2, ivec2(0, UV2.y)), group);
    vertexColor = vec4(tint, alpha) * lightmap;
    vec2 light = clamp(vec2(UV2) / 240.0, 0.0, 1.0);
    ovTexLight = vec4(UV0, light);
    ovPosC = vec4(pos, ovCloudAmount(pos, light));
    ovNormThin = vec4(Normal, group > 0.5 ? 1.0 : 0.0);
    ovAlbedo = tint;
    // linear_fog (fog.glsl) of the misty colour: mix(colour * a + rgb, fog colour, fog amount).
    vec4 haze = ovHazeVertex(pos);
    vec3 fogRgb = ovAerialFog(FogColor, pos, vertexDistance, FogStart, FogEnd).rgb;
    float fog = vertexDistance <= FogStart ? 0.0 : (vertexDistance < FogEnd ? smoothstep(FogStart, FogEnd, vertexDistance) : 1.0) * FogColor.a;
    ovMix = vec4(haze.rgb * (1.0 - fog) + fogRgb * fog, haze.a * (1.0 - fog));
}
