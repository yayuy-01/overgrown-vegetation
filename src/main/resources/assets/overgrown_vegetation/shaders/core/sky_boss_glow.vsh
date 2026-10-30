#version 150

// Sky leviathan glow pass (overgrown_vegetation). Same vertex squeeze as sky_boss.vsh so the glow lands exactly on the body
// (depth LEQUAL against the body drawn just before). Only position and UV are used.

in vec3 Position;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

uniform float SqueezeStart;
uniform float SqueezeLimit;
uniform float SqueezeMax;
uniform float HazeFull;
uniform float HazeMax;

out float hazeAmount;
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
    texCoord0 = UV0;
}
