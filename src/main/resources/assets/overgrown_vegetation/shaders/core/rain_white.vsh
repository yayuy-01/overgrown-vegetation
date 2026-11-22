#version 150

#moj_import <fog.glsl>

// Overgrown Vegetation: the rain, the snow and the water drop particles in white (client/atmosphere/RainLook.java holds the
// explanation). The same as vanilla particle.vsh, but the light from the lightmap is passed on its own (the fragment shader takes
// only its brightness, so the night never tints the rain blue). Keep this file ASCII-only.

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

out float vertexDistance;
out vec2 texCoord0;
out vec4 vertexColor;
out vec4 lightColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = fog_distance(Position, FogShape);
    texCoord0 = UV0;
    vertexColor = Color;
    lightColor = texelFetch(Sampler2, UV2 / 16, 0);
}
