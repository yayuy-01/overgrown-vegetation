#version 150

#moj_import <light.glsl>

// Sky leviathan (overgrown_vegetation). Same inputs as rendertype_entity_cutout_no_cull.
// The body is longer than the far clip plane, so vertices beyond SqueezeStart are pulled toward the camera
// along their own view ray (linear in distance, up to SqueezeMax -> SqueezeLimit). The direction is kept,
// so the image is exact; only the depth of the far part is compressed. Near parts keep their true depth.
// Haze is mixed in by the true distance (vanilla fog is not used).

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

uniform float SqueezeStart;
uniform float SqueezeLimit;
uniform float SqueezeMax;
uniform float HazeFull;
uniform float HazeMax;

out float hazeAmount;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

void main() {
    vec3 pos = Position;
    float d = length(pos);
    if (SqueezeMax > SqueezeLimit && d > SqueezeStart) {
        float g = SqueezeStart + (d - SqueezeStart) * (SqueezeLimit - SqueezeStart) / (SqueezeMax - SqueezeStart);
        pos *= g / d;
    }
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    hazeAmount = clamp(d / HazeFull, 0.0, 1.0) * HazeMax;
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
}
