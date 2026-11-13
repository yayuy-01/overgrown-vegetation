#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>
#moj_import <ov_fog.glsl>
#moj_import <ov_wave.glsl>

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

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec4 ovFog;
out vec3 ovPos;
out vec3 ovNormal;
out vec2 ovLight;
out vec3 ovTint;
// 1 for faces that are never water (the spore dust: marker code 64 in the vertex alpha; 11-09).
out float ovNoGlint;

// Overgrown Vegetation: the same as vanilla, plus the height fog of the overgrown forest over water (ov_fog.glsl), and
// (11-04) the camera-relative position, normal, light levels and tint for the cave point lights on water (ov_cave.glsl).
// Keep this file ASCII-only.
void main() {
    vec3 pos = Position + ChunkOffset;
    // The spore dust (client/SporeDustRenderer) carries code 64 in its alpha: not water, so no water glint. Its alpha goes back to 1.
    ovNoGlint = abs(floor((1.0 - Color.a) * 255.0 + 0.5) - 64.0) < 0.5 ? 1.0 : 0.0;
    // The swell of the water surface (ov_wave.glsl, 11-12). Not the spore dust, not white-tinted faces (glass, ice).
    if (ovNoGlint < 0.5 && min(min(Color.r, Color.g), Color.b) < 0.99) {
        pos.y += ovWaveHeight(pos);
    }
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_distance(pos, FogShape);
    vertexColor = vec4(Color.rgb, ovNoGlint > 0.5 ? 1.0 : Color.a) * minecraft_sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    ovPos = pos;
    ovNormal = Normal;
    ovLight = clamp(vec2(UV2) / 240.0, 0.0, 1.0);
    ovFog = ovFogShaded(pos, ovLight.y);
    // The tint without the light, for the cave point lights (ov_cave.glsl).
    ovTint = Color.rgb;
}
