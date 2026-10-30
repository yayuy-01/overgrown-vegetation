#version 330 core

// Overgrown Vegetation: Sodium's terrain vertex shader (sodium:blocks/block_layer_opaque.vsh, Sodium 0.8.x) with the same
// additions as our vanilla rendertype_cutout_mipped.vsh: the wind sway of marked vertices (ov_sway.glsl), the leaf colour
// code (ov_leaf.glsl) and the camera-relative position for the fragment effects. client/SodiumTerrain.java swaps this in
// for Sodium's file and expands the #ov_import lines. Keep this file ASCII-only.

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_vertex.glsl>
#import <sodium:include/chunk_matrices.glsl>
#ov_import <ov_sway.glsl>
#ov_import <ov_leaf.glsl>
#ov_import <ov_plant.glsl>
#ov_import <ov_cloud.glsl>
#ov_import <ov_mist.glsl>
#ov_import <ov_cave_mist.glsl>

out vec4 v_Color;
out vec2 v_TexCoord;

flat out uint v_Material;

#ifdef USE_FOG
out float v_FragDistance;
#endif

out vec3 ovPos;
out vec2 ovLight;
out vec3 ovAlbedo;
out vec4 ovLeaf;
// 1 for this mod's thin plants moved from the cutout layer (ov_plant.glsl).
out float ovThin;
// The slowly changing effects, per vertex (2026-10-23, like rendertype_*.vsh): both mists as colour * a + rgb, and
// (two-colour fog colour rgb, cloud shadow amount).
out vec4 ovHaze;
out vec4 ovFog;

uniform int u_FogShape;
uniform vec4 u_FogColor;
uniform float u_FogStart;
uniform float u_FogEnd;
uniform vec3 u_RegionOffset;
uniform vec2 u_TexCoordShrink;

uniform sampler2D u_LightTex; // The light map texture sampler

uvec3 _get_relative_chunk_coord(uint pos) {
    // Packing scheme is defined by LocalSectionIndex
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

// Sodium stores the light as 8..248 (the lightmap value 0..240 clamped) / 256; ours is 0..1 of 240.
float ovLightLevel(float raw) {
    return raw < 8.5 ? 0.0 : clamp(raw / 240.0, 0.0, 1.0);
}

void main() {
    _vert_init();

    // Transform the chunk-local vertex position into world model space (camera-relative, like vanilla Position + ChunkOffset)
    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;

    float group = ovPlantGroup(_vert_color);
    float alpha = ovPlantAlpha(_vert_color, group);
    vec3 tint = _vert_color.rgb;
    // Vanilla decodes the leaf colour code in the cutout_mipped layer only. Sodium merges cutout and cutout_mipped into one
    // pass (and may move leaves into the solid pass), so tell them apart by the material's alpha cutoff (2 = cutout_mipped).
    uint cutoff = (_material_params >> 1u) & 3u;
    ovLeaf = cutoff == 2u && group < 0.5 ? ovLeafDecode(_vert_color, tint) : vec4(0.0);
    // Sodium has no vertex normal; planar sway takes its axis from the marker code (see ov_sway.glsl).
    position += ovDisplace(position, vec3(0.0), alpha);
    position = ovPlantSink(position, _vert_position + _get_draw_translation(_draw_id), u_RegionOffset, group);
    ovThin = group > 0.5 ? 1.0 : 0.0;

#ifdef USE_FOG
    v_FragDistance = getFragDistance(u_FogShape, position);
#endif

    // Transform the vertex position into model-view-projection space
    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    // Add the light color to the vertex color, and pass the texture coordinates to the fragment shader
    // Glowing faces (group 3, client/NightGlow.java) are dimmed by day (ov_plant.glsl ovGlowLightmap).
    vec4 lightmap = ovGlowLightmap(texture(u_LightTex, _vert_tex_light_coord),
            texture(u_LightTex, vec2(8.0 / 256.0, _vert_tex_light_coord.y)), group);
    v_Color = vec4(tint, alpha) * lightmap;
    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord; // FMA for precision

    v_Material = _material_params;

    ovPos = position;
    vec2 rawLight = _vert_tex_light_coord * 256.0;
    ovLight = vec2(ovLightLevel(rawLight.x), ovLightLevel(rawLight.y));
    ovAlbedo = tint;
    ovHaze = ovHazeVertex(position);
    ovFog = vec4(ovAerialFog(u_FogColor, position, getFragDistance(u_FogShape, position), u_FogStart, u_FogEnd).rgb,
            ovCloudAmount(position, ovLight));
}
