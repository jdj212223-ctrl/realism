package com.jdj125.realism.client;

import com.jdj125.realism.Ambulance;
import com.jdj125.realism.Hospital;
import com.jdj125.realism.Realism;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Registers 1.0.0's renderers: the ambulance and the patient monitor's screen. */
@EventBusSubscriber(modid = Realism.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class LifeRenderers {
    private LifeRenderers() {
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Ambulance.TYPE.get(), AmbulanceRenderer::new);
        event.registerEntityRenderer(com.jdj125.realism.Cars.CAR.get(), CarRenderer::new);
        event.registerBlockEntityRenderer(Hospital.MONITOR_ENTITY.get(), MonitorRenderer::new);
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(AmbulanceRenderer.LAYER, AmbulanceRenderer.VanModel::create);
        event.registerLayerDefinition(CarRenderer.LAYER, CarRenderer.CarModel::create);
    }
}
