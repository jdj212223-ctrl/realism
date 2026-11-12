package com.jdj125.realism.client;

import com.jdj125.realism.Era;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** The one-time choice for a new Realism world: start history in year 0, or in today's world (2026). */
public class EraScreen extends Screen {
    public EraScreen() {
        super(Component.translatable("realism.era.screen.title"));
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new EraScreen());
    }

    /** Starts on the computer's own region setting (no GPS: the player confirms or changes it). */
    private static int country = Math.max(0, Era.COUNTRIES.indexOf(guessCountry()));

    /**
     * A first guess from the computer's own settings (no GPS, nothing sent anywhere): the time zone (e.g. Europe/Prague
     * = Czechia), else the system language's country. The player confirms it or clicks to change it.
     */
    static String guessCountry() {
        String tz = java.util.TimeZone.getDefault().getID();
        String byZone = switch (tz) {
            case "Europe/Prague" -> "CZ"; case "Europe/Bratislava" -> "SK"; case "Europe/Berlin" -> "DE"; case "Europe/Vienna" -> "AT";
            case "Europe/Warsaw" -> "PL"; case "Europe/Budapest" -> "HU"; case "Europe/London" -> "GB"; case "Europe/Dublin" -> "IE";
            case "Europe/Paris" -> "FR"; case "Europe/Madrid" -> "ES"; case "Europe/Lisbon" -> "PT"; case "Europe/Rome" -> "IT";
            case "Europe/Amsterdam" -> "NL"; case "Europe/Brussels" -> "BE"; case "Europe/Zurich" -> "CH"; case "Europe/Copenhagen" -> "DK";
            case "Europe/Stockholm" -> "SE"; case "Europe/Oslo" -> "NO"; case "Europe/Helsinki" -> "FI"; case "Europe/Kiev", "Europe/Kyiv" -> "UA";
            case "Europe/Bucharest" -> "RO"; case "Europe/Zagreb" -> "HR"; case "Europe/Ljubljana" -> "SI"; case "Europe/Athens" -> "GR";
            case "Europe/Istanbul" -> "TR"; case "America/Toronto", "America/Vancouver", "America/Edmonton", "America/Winnipeg", "America/Halifax" -> "CA";
            case "America/Mexico_City" -> "MX"; case "America/Sao_Paulo" -> "BR"; case "America/Argentina/Buenos_Aires" -> "AR";
            case "Asia/Tokyo" -> "JP"; case "Asia/Seoul" -> "KR"; case "Asia/Shanghai" -> "CN"; case "Asia/Kolkata" -> "IN";
            case "Pacific/Auckland" -> "NZ"; case "Africa/Johannesburg" -> "ZA"; case "Africa/Cairo" -> "EG";
            default -> tz.startsWith("America/") ? "US" : tz.startsWith("Australia/") ? "AU" : "";
        };
        return !byZone.isEmpty() ? byZone : java.util.Locale.getDefault().getCountry();
    }

    @Override
    protected void init() {
        int cx = width / 2, y = height / 2 + 10;
        addRenderableWidget(Button.builder(countryLabel(), b -> {
            country = (country + (hasShiftDown() ? Era.COUNTRIES.size() - 1 : 1)) % Era.COUNTRIES.size();
            b.setMessage(countryLabel());
        }).bounds(cx - 100, y + 26, 200, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("realism.era.screen.year0"), b -> pick(Era.YEAR_0)).bounds(cx - 155, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("realism.era.screen.today"), b -> pick(Era.TODAY)).bounds(cx + 5, y, 150, 20).build());
    }

    private static Component countryLabel() {
        return Component.translatable("realism.era.screen.country", Era.countryName(Era.COUNTRIES.get(country)));
    }

    private void pick(int year) {
        PacketDistributor.sendToServer(new Era.Choose(year, Era.COUNTRIES.get(country)));
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int cx = width / 2, y = height / 2 - 60;
        g.drawCenteredString(font, title, cx, y, 0xFFFFE07A);
        g.drawCenteredString(font, Component.translatable("realism.era.screen.ask"), cx, y + 18, 0xFFFFFFFF);
        g.drawCenteredString(font, Component.translatable("realism.era.screen.year0_info"), cx, y + 36, 0xFFB0B0B0);
        g.drawCenteredString(font, Component.translatable("realism.era.screen.today_info"), cx, y + 48, 0xFFB0B0B0);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
