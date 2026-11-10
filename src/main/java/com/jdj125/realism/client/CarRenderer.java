package com.jdj125.realism.client;

import com.jdj125.realism.Cars;
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

/** A small boxy car: body, cabin with windows, four wheels and headlights. One texture per colour. */
public class CarRenderer extends MobRenderer<Cars.Car, CarRenderer.CarModel> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "car"), "main");
    private static final ResourceLocation[] TEXTURES = new ResourceLocation[Cars.COLORS.length];

    static {
        for (int i = 0; i < Cars.COLORS.length; i++)
            TEXTURES[i] = ResourceLocation.fromNamespaceAndPath(Realism.MODID, "textures/entity/car_" + Cars.COLORS[i] + ".png");
    }

    public CarRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new CarModel(ctx.bakeLayer(LAYER)), 1.0f);
    }

    @Override
    public ResourceLocation getTextureLocation(Cars.Car car) {
        return TEXTURES[Math.floorMod(car.color(), TEXTURES.length)];
    }

    @Override
    protected boolean shouldShowName(Cars.Car car) {
        return false;
    }

    @Override
    protected float getFlipDegrees(Cars.Car car) {
        return 0;
    }

    public static class CarModel extends EntityModel<Cars.Car> {
        private final ModelPart root;

        public CarModel(ModelPart root) {
            this.root = root;
        }

        /** Model space: feet at y=24, the front towards -z. Texture 256x128. */
        public static LayerDefinition create() {
            MeshDefinition mesh = new MeshDefinition();
            PartDefinition p = mesh.getRoot();
            p.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0).addBox(-13, 8, -22, 26, 10, 44), PartPose.ZERO);
            p.addOrReplaceChild("cabin", CubeListBuilder.create().texOffs(0, 54).addBox(-11, -2, -8, 22, 10, 22), PartPose.ZERO);
            CubeListBuilder wheel = CubeListBuilder.create().texOffs(96, 54).addBox(-2, -4, -4, 4, 8, 8);
            p.addOrReplaceChild("wheel_fl", wheel, PartPose.offset(-13, 20, -14));
            p.addOrReplaceChild("wheel_fr", wheel, PartPose.offset(13, 20, -14));
            p.addOrReplaceChild("wheel_bl", wheel, PartPose.offset(-13, 20, 14));
            p.addOrReplaceChild("wheel_br", wheel, PartPose.offset(13, 20, 14));
            return LayerDefinition.create(mesh, 256, 128);
        }

        @Override
        public void setupAnim(Cars.Car car, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
            for (String w : new String[]{"wheel_fl", "wheel_fr", "wheel_bl", "wheel_br"}) root.getChild(w).xRot = limbSwing * 0.8f;
        }

        @Override
        public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay, int color) {
            root.render(pose, buffer, light, overlay, color);
        }
    }
}
