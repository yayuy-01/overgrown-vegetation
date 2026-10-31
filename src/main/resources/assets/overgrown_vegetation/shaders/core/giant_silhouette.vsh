#version 150

// Overgrown Vegetation: GiantSilhouettes (client/atmosphere/GiantSilhouettes.java). Keep this file ASCII-only.

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 Origin;

out vec3 relPos;
out vec4 vertexColor;

void main() {
    vec3 pos = Position + Origin;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    relPos = pos;
    vertexColor = Color;
}
