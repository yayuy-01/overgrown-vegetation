#version 150

// Overgrown Vegetation: light of the sky (client/SkyDome.java). Keep this file ASCII-only.
// A sphere around the camera drawn with the rotation only; the direction is all that matters.

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec3 ovDir;

void main() {
    ovDir = Position;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
}
