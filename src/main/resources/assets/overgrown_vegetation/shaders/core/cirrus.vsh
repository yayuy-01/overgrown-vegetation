#version 150

// Overgrown Vegetation: cirrus (client/Cirrus.java holds the explanation). Keep this file ASCII-only.
// A flat grid around the camera; Offset lifts it to the cirrus height relative to the camera.
// Split = (distance, mode, near, far): the same far / near passes as voxel_clouds.vsh.

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 Offset;
uniform vec4 Split;

out vec3 ovPos;

void main() {
    ovPos = Position + Offset;
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
