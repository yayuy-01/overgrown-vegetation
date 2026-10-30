#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>
#moj_import <ov_fog.glsl>

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

// Overgrown Vegetation: the same as vanilla, plus the height fog of the overgrown forest over water (ov_fog.glsl).
// Keep this file ASCII-only.
void main() {
    vec3 pos = Position + ChunkOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_distance(pos, FogShape);
    vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    ovFog = ovFogShaded(pos, clamp(float(UV2.y) / 240.0, 0.0, 1.0));
}
