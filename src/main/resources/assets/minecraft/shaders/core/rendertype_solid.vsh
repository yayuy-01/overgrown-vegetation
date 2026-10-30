#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

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
out vec3 ovPos;
out vec3 ovNormal;
out vec2 ovLight;

// Overgrown Vegetation: the same as vanilla, plus the camera-relative position, the normal and the light levels for the
// canopy light (ov_light.glsl). Keep this file ASCII-only.
void main() {
    vec3 pos = Position + ChunkOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_distance(pos, FogShape);
    vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    ovPos = pos;
    ovNormal = Normal;
    ovLight = clamp(vec2(UV2) / 240.0, 0.0, 1.0);
}
