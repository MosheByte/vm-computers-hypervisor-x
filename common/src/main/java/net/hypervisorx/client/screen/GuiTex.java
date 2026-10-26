package net.hypervisorx.client.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/** draws a texture into any float rect on a GUI (sub-pixel exact, mipmapped) */
public final class GuiTex {
    private GuiTex() {
    }

    public static void draw(GuiGraphics g, ResourceLocation tex, float x0, float y0, float x1, float y1) {
        g.flush();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, tex);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        Matrix4f m = g.pose().last().pose();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bb.addVertex(m, x0, y0, 0).setUv(0, 0);
        bb.addVertex(m, x0, y1, 0).setUv(0, 1);
        bb.addVertex(m, x1, y1, 0).setUv(1, 1);
        bb.addVertex(m, x1, y0, 0).setUv(1, 0);
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }
}
