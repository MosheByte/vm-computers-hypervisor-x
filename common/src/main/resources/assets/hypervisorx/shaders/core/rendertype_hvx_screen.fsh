#version 150

#moj_import <fog.glsl>

// HyperVisor X screen shader: "sharp bilinear" magnification. When a VM pixel covers more than one
// screen pixel, the interpolation is squeezed into a one-pixel band at texel edges, so text stays
// crisp up close without the shimmer of nearest-neighbour. Minification uses the normal trilinear
// mip chain (and anisotropic filtering) set on the texture.

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec2 size = vec2(textureSize(Sampler0, 0));
    vec2 px = texCoord0 * size;
    vec2 fw = fwidth(px);
    vec2 uv = texCoord0;
    if (max(fw.x, fw.y) < 1.0) {
        vec2 base = floor(px - 0.5) + 0.5;
        vec2 f = px - base;
        vec2 scale = 1.0 / max(fw, vec2(1e-4));
        f = clamp((f - 0.5) * scale + 0.5, 0.0, 1.0);
        uv = (base + f) / size;
    }
    vec4 color = texture(Sampler0, uv) * vertexColor * ColorModulator;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
