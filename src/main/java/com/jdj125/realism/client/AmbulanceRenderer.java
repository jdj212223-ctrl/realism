package com.jdj125.realism.client;

import com.jdj125.realism.Ambulance;
import com.jdj125.realism.Realism;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/** The ambulance: a white van with red stripes and a light bar that flashes red and blue while the siren is on. */
public class AmbulanceRenderer extends MobRenderer<Ambulance.Van, AmbulanceRenderer.VanModel> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "ambulance"), "main");
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(Realism.MODID, "textures/entity/ambulance.png");

    public AmbulanceRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new VanModel(ctx.bakeLayer(LAYER)), 1.2f);
    }

    @Override
    public ResourceLocation getTextureLocation(Ambulance.Van van) {
        return TEXTURE;
    }

    @Override
    protected boolean shouldShowName(Ambulance.Van van) {
        return false;
    }

    // A van never "dies" by tipping over.
    @Override
    protected float getFlipDegrees(Ambulance.Van van) {
        return 0;
    }

    public static class VanModel extends EntityModel<Ambulance.Van> {
        private final ModelPart root, red, blue;

        public VanModel(ModelPart root) {
            this.root = root;
            this.red = root.getChild("light_red");
            this.blue = root.getChild("light_blue");
        }

        /** Model space: feet at y=24, the front (windscreen) towards -z. Texture 256x128. */
        public static LayerDefinition create() {
            MeshDefinition mesh = new MeshDefinition();
            PartDefinition p = mesh.getRoot();
            p.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0).addBox(-13, -10, -22, 26, 28, 44), PartPose.ZERO);
            CubeListBuilder wheel = CubeListBuilder.create().texOffs(0, 76).addBox(-2, -4, -4, 4, 8, 8);
            p.addOrReplaceChild("wheel_fl", wheel, PartPose.offset(-13, 20, -14));
            p.addOrReplaceChild("wheel_fr", wheel, PartPose.offset(13, 20, -14));
            p.addOrReplaceChild("wheel_bl", wheel, PartPose.offset(-13, 20, 14));
            p.addOrReplaceChild("wheel_br", wheel, PartPose.offset(13, 20, 14));
            p.addOrReplaceChild("light_red", CubeListBuilder.create().texOffs(30, 76).addBox(-8, -13, -18, 8, 3, 4), PartPose.ZERO);
            p.addOrReplaceChild("light_blue", CubeListBuilder.create().texOffs(30, 84).addBox(0, -13, -18, 8, 3, 4), PartPose.ZERO);
            p.addOrReplaceChild("light_off", CubeListBuilder.create().texOffs(30, 92).addBox(-8, -12.9f, -17.9f, 16, 2.8f, 3.8f), PartPose.ZERO);
            return LayerDefinition.create(mesh, 256, 128);
        }

        @Override
        public void setupAnim(Ambulance.Van van, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
            boolean on = van.siren();
            boolean flip = ((int) ageInTicks / 5) % 2 == 0;
            red.visible = on && flip;
            blue.visible = on && !flip;
            for (String w : new String[]{"wheel_fl", "wheel_fr", "wheel_bl", "wheel_br"}) root.getChild(w).xRot = limbSwing * 0.8f;
        }

        @Override
        public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay, int color) {
            root.render(pose, buffer, light, overlay, color);
        }
    }
}
