#version 150

// Overgrown Vegetation: voxel clouds (client/VoxelClouds.java holds the explanation). Keep this file ASCII-only.
// Vertices are relative to the cloud window origin; Offset = window origin + wind drift - camera.
// Color: r = face (0 down, 1 up, 2 north, 3 south, 4 west, 5 east) / 5, g = height within the layer, b = thinness,
// a = 0.5 low layer / 1 storm cloud.
//
// Split = (distance, mode, near, far). mode 1: far pass, drawn with the sky before the terrain; only fragments past the
// distance, with a depth range of their own (near..far) so far clouds keep a fine depth order. mode -1: near pass after the
// terrain with the normal depth; only fragments closer than the distance.

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 Offset;
uniform vec4 Split;
// Curve = (strength, start distance, 1 / (2 R), -): clouds farther than the start sink by (d - start)^2 / (2 R) like the curve of a planet,
// so the far edge of the cloud sea goes down under its own horizon. Only the drawn position bends; ovPos (shading, holes) stays flat.
uniform vec4 Curve;
// Puff = (scale, opacity, -, 1 = a puffed cloud mesh: client/CloudPuffs.java), PuffCenter = its centre in mesh coordinates; the mesh grows
// and shrinks around it.
uniform vec4 Puff;
uniform vec3 PuffCenter;

out vec3 ovPos;
out vec4 ovInfo;
out vec2 ovUv;

void main() {
    vec3 local = Puff.w > 0.5 ? PuffCenter + (Position - PuffCenter) * Puff.x : Position;
    ovPos = local + Offset;
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
    ovInfo = Color;
    ovUv = UV0;
}
