#version 330 core

// Overgrown Vegetation: Sodium's terrain vertex shader (sodium:blocks/block_layer_opaque.vsh, Sodium 0.8.x) with the same
// additions as our vanilla rendertype_*.vsh: the camera-relative position and the light levels for the canopy light
// (ov_light.glsl), and the wind sway of marked vertices (ov_sway.glsl). client/shader/SodiumTerrain.java swaps this in for Sodium's file and expands the #ov_import lines.
// Keep this file ASCII-only.

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_vertex.glsl>
#import <sodium:include/chunk_matrices.glsl>
#ov_import <ov_sway.glsl>
#ov_import <ov_fog.glsl>
#ov_import <ov_leaf.glsl>
#ov_import <ov_wave.glsl>

out vec4 v_Color;
out vec2 v_TexCoord;

flat out uint v_Material;

#ifdef USE_FOG
out float v_FragDistance;
#endif

out vec3 ovPos;
out vec2 ovLight;
out vec4 ovFog;
out vec4 ovLeaf;
out vec3 ovTint;
// 1 for faces that are never water (the spore dust: marker code 64 in the vertex alpha; 11-09). No water glint on them.
out float ovNoGlint;

uniform int u_FogShape;
// 1 while Sodium draws the translucent pass (client/shader/SodiumTerrain sets it; the fragment shader reads it too).
uniform int ovSodiumPass;
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
    // Plants sway in the wind (ov_sway.glsl). Sodium has no vertex normal; planar sway takes its axis from the marker code.
    vec4 color = _vert_color;
    // The overgrown leaves carry a colour code in their tint (ov_leaf.glsl). Vanilla decodes it in the cutout_mipped layer only;
    // Sodium merges the layers, so tell them apart by the material alpha cutoff (2 = cutout_mipped). Before the sway resets alpha.
    vec3 tint = color.rgb;
    ovLeaf = ((_material_params >> 1u) & 3u) == 2u ? ovLeafDecode(color, tint) : vec4(0.0);
    color.rgb = tint;
    position += ovDisplace(position, vec3(0.0), color.a);
    // The spore dust (client/SporeDustRenderer) carries code 64 in its alpha: not water, so no water glint. Its alpha goes back to 1.
    ovNoGlint = abs(floor((1.0 - color.a) * 255.0 + 0.5) - 64.0) < 0.5 ? 1.0 : 0.0;
    if (ovNoGlint > 0.5) {
        color.a = 1.0;
    }
    // The swell of the water surface (ov_wave.glsl, 11-12): the translucent pass only, not the spore dust, not white-tinted faces
    // (glass, ice).
    if (ovSodiumPass == 1 && ovNoGlint < 0.5 && min(min(color.r, color.g), color.b) < 0.99) {
        position.y += ovWaveHeight(position);
    }

#ifdef USE_FOG
    v_FragDistance = getFragDistance(u_FogShape, position);
#endif

    // Transform the vertex position into model-view-projection space
    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    // Add the light color to the vertex color, and pass the texture coordinates to the fragment shader
    v_Color = color * texture(u_LightTex, _vert_tex_light_coord);
    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord; // FMA for precision

    v_Material = _material_params;

    ovPos = position;
    vec2 rawLight = _vert_tex_light_coord * 256.0;
    ovLight = vec2(ovLightLevel(rawLight.x), ovLightLevel(rawLight.y));
    ovFog = ovFogShaded(position, ovLight.y);
    // The tint without the light, for the cave point lights (ov_cave.glsl).
    ovTint = color.rgb;
}
