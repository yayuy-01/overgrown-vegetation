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
// PreRain = (base drop in blocks, darkening 0..1, -, -). 11-09 (clouds stage 2a-6): before a scheduled rain the cloud bases sink and darken
// (client/sky/VoxelClouds.java, sky/CloudWeather.java). Every vertex at the bottom of the layer (y 0 = y 300) goes down.
uniform vec4 PreRain;

out vec3 ovPos;
out vec4 ovInfo;
out vec2 ovUv;
out float ovBentY;

void main() {
    vec3 local = Puff.w > 0.5 ? PuffCenter + (Position - PuffCenter) * Puff.x : Position;
    // 11-12: < 2.5 so the lifted storm bases (VoxelClouds.STORM_BASE_LIFT = 2) sink with the floor; the floor tops are at 4 and above.
    if (Position.y < 2.5) {
        local.y -= PreRain.x;
    }
    ovPos = local + Offset;
    vec3 bent = ovPos;
    float past = max(length(ovPos.xz) - Curve.y, 0.0);
    bent.y -= Curve.x * past * past * Curve.z;
    ovBentY = bent.y;
    vec4 view = ModelViewMat * vec4(bent, 1.0);
    gl_Position = ProjMat * view;
    if (Split.y > 0.5) {
        float n = Split.z;
        float f = Split.w;
        float d = max(-view.z, n);
        float zndc = ((f + n) - 2.0 * f * n / d) / (f - n);
        gl_Position.z = zndc * gl_Position.w;
    }
    // 11-23 (user report: clouds flickered where a storm met the ordinary clouds, in 1.21.1 and 1.20.1): the cloud floor (24 / 8 block
    // columns, 4 block steps) and the storm boxes (16 / 48 blocks) share planes wherever their grids meet (every 48 blocks across, every
    // 16 up), so a floor face lying in a storm face fought it pixel by pixel and the stripes changed as the camera moved. Storm vertices
    // (same tag test as voxel_clouds.fsh: storm, giant, nest, far storm) are pushed a few depth steps toward the camera, so on a shared
    // plane the storm always wins. Only the depth moves (nothing shifts on screen) and the push is far smaller than any real gap between
    // the meshes (the lifted storm base sits 2 blocks above the floor under it, which still wins there). Depth steps: the far pass has
    // its own fine depth range, the near pass the coarse normal one, so the near pass gets a smaller push.
    // Floor columns that reach into a storm are drawn as the storm (VoxelClouds.stormColumnTags: 0.91, 0.976, 0.851 near and 0.165, 0.2
    // far) but get no push: pushed like the storm they would fight its faces again on the shared planes.
    bool floorInStorm = (Color.a > 0.905 && Color.a < 0.915) || (Color.a > 0.966 && Color.a < 0.979) || (Color.a > 0.845 && Color.a < 0.857)
            || (Color.a > 0.16 && Color.a < 0.17) || (Color.a > 0.195 && Color.a < 0.205);
    bool stormVertex = !floorInStorm && (Color.a >= 0.9 || (Color.a > 0.72 && Color.a < 0.9) || Color.a < 0.22);
    if (stormVertex) {
        gl_Position.z -= (Split.y > 0.5 ? 1.0e-6 : 2.5e-7) * gl_Position.w;
    }
    ovInfo = Color;
    ovUv = UV0;
}
