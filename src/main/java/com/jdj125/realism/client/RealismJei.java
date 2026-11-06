package com.jdj125.realism.client;

import com.jdj125.realism.Home;
import com.jdj125.realism.Realism;
import com.jdj125.realism.RealismConfig;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.drawable.IDrawableStatic;
import mezz.jei.api.gui.ingredient.ICraftingGridHelper;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.Arrays;
import java.util.List;

/**
 * JEI (only loaded when JEI is installed): with no crafting table in Realism, JEI shows crafting recipes on a
 * "Workbench" page (with the Workbench and the Hammer as the stations) instead of the crafting table page, and its
 * "+" button fills the Workbench's grid for you.
 */
@JeiPlugin
public class RealismJei implements IModPlugin {
    public static final RecipeType<RecipeHolder<CraftingRecipe>> WORKBENCH =
            RecipeType.createRecipeHolderType(ResourceLocation.fromNamespaceAndPath(Realism.MODID, "workbench"));

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(Realism.MODID, "jei");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration reg) {
        reg.addRecipeCategories(new Category(reg.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration reg) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        // Every ordinary crafting recipe, from every mod (special ones like fireworks have no fixed ingredients).
        List<RecipeHolder<CraftingRecipe>> recipes = level.getRecipeManager().getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING)
                .stream().filter(r -> !r.value().isSpecial() && !r.value().getIngredients().isEmpty()).toList();
        reg.addRecipes(WORKBENCH, recipes);
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration reg) {
        reg.addRecipeCatalysts(WORKBENCH, Home.WORKBENCH_ITEM.get(), Home.HAMMER.get());
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration reg) {
        // The Workbench's grid is slots 1-9, the player's inventory 10-45 (like the vanilla crafting screen).
        reg.addRecipeTransferHandler(Home.WorkbenchMenu.class, MenuType.CRAFTING, WORKBENCH, 1, 9, 10, 36);
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        boolean noTable = true;
        try {
            noTable = RealismConfig.NO_CRAFTING_TABLE.get();
        } catch (IllegalStateException e) {
            // The server's config isn't here yet: Realism's default is no crafting table.
        }
        if (noTable) runtime.getRecipeManager().hideRecipeCategory(RecipeTypes.CRAFTING);
        else runtime.getRecipeManager().unhideRecipeCategory(RecipeTypes.CRAFTING);
    }

    /** A 3x3 grid, an arrow with a hammer on it, and the result. */
    static class Category implements IRecipeCategory<RecipeHolder<CraftingRecipe>> {
        private final IDrawable icon, hammer;
        private final IDrawableStatic arrow;
        private final ICraftingGridHelper grid;

        Category(IGuiHelper gui) {
            this.icon = gui.createDrawableItemLike(Home.WORKBENCH_ITEM.get());
            this.hammer = gui.createDrawableItemLike(Home.HAMMER.get());
            this.arrow = gui.getRecipeArrow();
            this.grid = gui.createCraftingGridHelper();
        }

        @Override
        public RecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
            return WORKBENCH;
        }

        @Override
        public Component getTitle() {
            return Component.translatable("realism.jei.workbench");
        }

        @Override
        public IDrawable getIcon() {
            return icon;
        }

        @Override
        public int getWidth() {
            return 116;
        }

        @Override
        public int getHeight() {
            return 54;
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, RecipeHolder<CraftingRecipe> holder, IFocusGroup focuses) {
            CraftingRecipe recipe = holder.value();
            int w = 0, h = 0;
            if (recipe instanceof ShapedRecipe shaped) {
                w = shaped.getWidth();
                h = shaped.getHeight();
            } else builder.setShapeless();
            List<List<ItemStack>> inputs = recipe.getIngredients().stream().map((Ingredient i) -> Arrays.asList(i.getItems())).toList();
            grid.createAndSetInputs(builder, inputs, w, h);
            var level = Minecraft.getInstance().level;
            ItemStack out = level == null ? ItemStack.EMPTY : recipe.getResultItem(level.registryAccess());
            grid.createAndSetOutputs(builder, List.of(out));
        }

        @Override
        public void draw(RecipeHolder<CraftingRecipe> recipe, IRecipeSlotsView slots, GuiGraphics g, double mouseX, double mouseY) {
            arrow.draw(g, 61, 18);
            g.pose().pushPose();
            g.pose().translate(66, 2, 0);
            g.pose().scale(0.75f, 0.75f, 1);
            hammer.draw(g);
            g.pose().popPose();
        }

        @Override
        public ResourceLocation getRegistryName(RecipeHolder<CraftingRecipe> recipe) {
            return recipe.id();
        }
    }
}
