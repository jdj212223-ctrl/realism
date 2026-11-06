package com.jdj125.realism.client;

import com.jdj125.realism.Hospital;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import org.joml.Matrix4f;

/** The patient monitor's screen: the patient, a moving heart trace (flat when they've collapsed), BPM, blood and oxygen. */
public class MonitorRenderer implements BlockEntityRenderer<Hospital.MonitorEntity> {
    private final Font font;

    public MonitorRenderer(BlockEntityRendererProvider.Context ctx) {
        this.font = ctx.getFont();
    }

    @Override
    public void render(Hospital.MonitorEntity m, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (!m.on) return;
        Direction facing = m.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-facing.toYRot()));
        pose.translate(0, 0.32, 0.505);
        float s = 1 / 110f;
        pose.scale(s, -s, s);
        Matrix4f mat = pose.last().pose();
        int full = LightTexture.FULL_BRIGHT;
        if (m.patient.isEmpty()) {
            draw(mat, buffers, "NO PATIENT", 18, 0xFF55AA55, full);
        } else {
            long t = m.getLevel() == null ? 0 : m.getLevel().getGameTime();
            String trace;
            if (m.bpm == 0) trace = "______________";
            else {
                String beat = "__/\\_______";
                int shift = (int) ((t * m.bpm / 300) % beat.length());
                String two = beat + beat;
                trace = two.substring(shift, shift + 14);
            }
            draw(mat, buffers, m.patient, 0, 0xFFFFFFFF, full);
            draw(mat, buffers, trace, 12, m.bpm == 0 ? 0xFFFF4040 : 0xFF40FF60, full);
            draw(mat, buffers, (m.bpm == 0 ? "--" : String.valueOf(m.bpm)) + " BPM", 24, m.bpm == 0 ? 0xFFFF4040 : 0xFF40FF60, full);
            draw(mat, buffers, String.format(java.util.Locale.ROOT, "%.1f l  %d%%", m.blood, m.oxygen), 36, 0xFF70C8FF, full);
        }
        pose.popPose();
    }

    private void draw(Matrix4f mat, MultiBufferSource buffers, String text, int y, int color, int light) {
        font.drawInBatch(text, -font.width(text) / 2f, y, color, false, mat, buffers, Font.DisplayMode.POLYGON_OFFSET, 0, light);
    }
}
