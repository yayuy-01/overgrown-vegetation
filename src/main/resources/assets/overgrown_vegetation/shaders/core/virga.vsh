#version 150

// Overgrown Vegetation: virga (client/Virga.java holds the explanation). Keep this file ASCII-only.
// Vertices are relative to the camera. UV0 = (strip seed + across 0..1, along 0..1 from the cloud base down).
// Split = (distance, mode, near, far): the same far / near passes as voxel_clouds.vsh.
// Curve = the same planet curve as voxel_clouds.vsh, so the streaks stay under their sinking clouds.

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec4 Split;
uniform vec4 Curve;

out vec3 ovPos;
out vec2 ovUv;
out vec4 ovColor;

void main() {
    ovPos = Position;
    vec3 bent = ovPos;
    float past = max(length(ovPos.xz) - Curve.y, 0.0);
    bent.y -= Curve.x * past * past * Curve.z;
    vec4 view = ModelViewMat * vec4(bent, 1.0);
    gl_Position = ProjMat * view;
    if (Split.y > 0.5) {
        float n = Split.z;
        float f = Split.w;
        float d = max(-view.z, n);
        float zndc = ((f + n) - 2.0 * f * n / d) / (f - n);
        gl_Position.z = zndc * gl_Position.w;
    }
    ovUv = UV0;
    ovColor = Color;
}
