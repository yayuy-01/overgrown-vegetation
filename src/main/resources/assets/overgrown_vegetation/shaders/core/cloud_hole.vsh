#version 150

// Overgrown Vegetation: rings of vapour where a shell or beam punches a hole in the clouds (client/CloudHoles.java).
// Keep this file ASCII-only. Positions are relative to the camera. Split = (distance, mode, near, far): the same far / near
// passes as voxel_clouds.vsh.

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec4 Split;

out vec3 ovPos;
out vec4 ovColor;

void main() {
    ovPos = Position;
    ovColor = Color;
    vec4 view = ModelViewMat * vec4(ovPos, 1.0);
    gl_Position = ProjMat * view;
    if (Split.y > 0.5) {
        float n = Split.z;
        float f = Split.w;
        float d = max(-view.z, n);
        float zndc = ((f + n) - 2.0 * f * n / d) / (f - n);
        gl_Position.z = zndc * gl_Position.w;
    }
}
