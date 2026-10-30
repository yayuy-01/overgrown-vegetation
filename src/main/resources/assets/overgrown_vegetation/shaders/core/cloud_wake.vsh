#version 150

// Overgrown Vegetation: cloud dragged out of a cloud (client/CloudWake.java holds the explanation). Keep this file ASCII-only.
// Vertices are relative to the camera. Split = (distance, mode, near, far): the same far / near passes as voxel_clouds.vsh.
// Curve = the same planet curve as voxel_clouds.vsh.

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec4 Split;
uniform vec4 Curve;
// Squeeze = (start, limit, farthest, cap): in the pass after the sky leviathan (client/skyboss/SkyBossRenderer.java) vertices beyond start are
// pulled toward the camera along their own view ray exactly like the leviathan, so the cloud it drags sorts right against its body. 0 = off.
uniform vec4 Squeeze;

out vec3 ovPos;
out vec4 ovColor;

void main() {
    ovPos = Position;
    vec3 bent = ovPos;
    float sd = length(bent);
    if (Squeeze.z > Squeeze.y && sd > Squeeze.x) {
        float g = Squeeze.x + (sd - Squeeze.x) * (Squeeze.y - Squeeze.x) / (Squeeze.z - Squeeze.x);
        bent *= min(g, Squeeze.w) / sd;
    }
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
    ovColor = Color;
}
