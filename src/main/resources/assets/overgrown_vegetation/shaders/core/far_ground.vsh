#version 150

// Overgrown Vegetation: hazy ground under the cloud sea, past the render distance (client/FarGround.java holds the explanation).
// Keep this file ASCII-only.
// A flat disk around the camera (Position.xz relative to the camera), drawn with the sky before the terrain, without depth.
//
// Ground = (ground y - camera y, radius, haze length, strength).
// Curve = (strength, start distance, 1 / (2 R), -): the same bend as the voxel clouds, so the ground stays under the far cloud sea.
// Split = (near, far, -, -): a depth range of its own (the ground goes far past the far plane of the normal projection).

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec4 Ground;
uniform vec4 Curve;
uniform vec4 Split;

out vec3 ovPos;

void main() {
    ovPos = vec3(Position.x, Ground.x, Position.z);
    vec3 bent = ovPos;
    float past = max(length(ovPos.xz) - Curve.y, 0.0);
    bent.y -= Curve.x * past * past * Curve.z;
    vec4 view = ModelViewMat * vec4(bent, 1.0);
    gl_Position = ProjMat * view;
    float n = Split.x;
    float f = Split.y;
    float d = max(-view.z, n);
    float zndc = ((f + n) - 2.0 * f * n / d) / (f - n);
    gl_Position.z = zndc * gl_Position.w;
}
