#version 150

#moj_import <fog.glsl>

// Overgrown Vegetation: the climbing vines (client/ClimbingVineRenderer.java). The same as vanilla rendertype_text, plus the
// camera-relative position and the light levels for the canopy light of the terrain (ov_light.glsl), so a vine gets the same
// canopy shade, forest hue, sky tint and night colour as the trunk it climbs (11-05, light inspection 6). Block entities are drawn
// with only the translation in the pose (the camera rotation is in ModelViewMat), so Position is already camera-relative like
// Position + ChunkOffset of the terrain. Keep this file ASCII-only.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec3 ovPos;
out vec2 ovLight;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = fog_distance(Position, FogShape);
    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
    texCoord0 = UV0;
    ovPos = Position;
    ovLight = clamp(vec2(UV2) / 240.0, 0.0, 1.0);
}
