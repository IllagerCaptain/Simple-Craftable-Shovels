package illagercaptain.simplecraftableshovels;

import com.hypixel.hytale.assetstore.codec.AssetCodec;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.builtin.crafting.CraftingPlugin;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public class SimpleCraftableShovelsPlugin extends JavaPlugin {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String PICKAXE_PREFIX = "Tool_Pickaxe_";
    private static final String SHOVEL_PREFIX = "Tool_Shovel_";

    public SimpleCraftableShovelsPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    protected void setup() {
        Collection<Item> shovels = new java.util.ArrayList<>(List.of());
        EventRegistry eventRegistry = this.getEventRegistry();
        eventRegistry.register(LoadedAssetsEvent.class, Item.class, (LoadedAssetsEvent<String, Item, DefaultAssetMap<String, Item>> loadedAssetsEvent) -> {
            Map<String, Item> assets = loadedAssetsEvent.getLoadedAssets();
            try {
                Field itemLevel = Item.class.getDeclaredField("itemLevel");
                itemLevel.setAccessible(true);
                assets.entrySet().stream().filter(asset -> asset.getKey().startsWith(SHOVEL_PREFIX)).forEach((itemAsset) -> {
                    Item item = itemAsset.getValue();
                    shovels.add(item);
                    try {
                        Item pickaxeEquivalent = assets.get(itemAsset.getKey().replace(SHOVEL_PREFIX, PICKAXE_PREFIX));
                        if (pickaxeEquivalent != null) {
                            itemLevel.setInt(item, pickaxeEquivalent.getItemLevel());
                        }
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
        });

        Map<String, CraftingRecipe> shovelRecipes = new HashMap<>();
        Map<String, CraftingRecipe> pickaxeRecipes = new HashMap<>();
        eventRegistry.register(LoadedAssetsEvent.class, CraftingRecipe.class, (LoadedAssetsEvent<String, CraftingRecipe, DefaultAssetMap<String, CraftingRecipe>> loadedAssetsEvent) -> {
            Collection<CraftingRecipe> assets = loadedAssetsEvent.getLoadedAssets().values();
            shovelRecipes.putAll(convertAssets(assets, SHOVEL_PREFIX));
            pickaxeRecipes.putAll(convertAssets(assets, PICKAXE_PREFIX));
            int recipeIngredientCount = pickaxeRecipes.values().stream()
                    .mapToInt(recipe -> recipe.getInput().length)
                    .max()
                    .orElse(0);
            Map<Integer, Float> recipeRatioMap = new HashMap<>();
            for (int i = 0; i < recipeIngredientCount; i++) {
                int index = i;
                float averagePickaxe = (float) pickaxeRecipes.values().stream().filter(recipe -> index < recipe.getInput().length).mapToInt(recipe -> recipe.getInput()[index].getQuantity()).average().orElseThrow();
                float averageShovel = (float) shovelRecipes.values().stream().filter(recipe -> index < recipe.getInput().length).mapToInt(recipe -> recipe.getInput()[index].getQuantity()).average().orElseThrow();
                recipeRatioMap.put(index, averageShovel / averagePickaxe);
            }
            try {
                Method putAllMethod = DefaultAssetMap.class.getDeclaredMethod(
                        "putAll",
                        String.class,
                        AssetCodec.class,
                        Map.class,
                        Map.class,
                        Map.class
                );
                putAllMethod.setAccessible(true);
                Method onRecipeLoadMethod = CraftingPlugin.class.getDeclaredMethod("onRecipeLoad", LoadedAssetsEvent.class);
                onRecipeLoadMethod.setAccessible(true);
                for (Item shovel : shovels) {
                    CraftingRecipe pickaxeRecipe = pickaxeRecipes.get(shovel.getId().replaceFirst(Pattern.quote(SHOVEL_PREFIX), PICKAXE_PREFIX));
                    if (pickaxeRecipe != null && shovelRecipes.get(shovel.getId()) == null) {
                        List<MaterialQuantity> input = new ArrayList<>();
                        MaterialQuantity output = new MaterialQuantity(shovel.getId(), null, null, 1, null);
                        recipeRatioMap.forEach((key, value) -> {
                            int index = key;
                            float multiplier = value;
                            MaterialQuantity[] specificInputArray = pickaxeRecipe.getInput();
                            if (index < specificInputArray.length) {
                                MaterialQuantity specificInput = specificInputArray[index];
                                input.add(specificInput.clone(Math.max(Math.round((float) specificInput.getQuantity() * multiplier), 1)));
                            }
                        });
                        CraftingRecipe recipe = new CraftingRecipe(
                                input.toArray(MaterialQuantity[]::new),
                                output,
                                new MaterialQuantity[]{output},
                                1,
                                pickaxeRecipe.getBenchRequirement(),
                                pickaxeRecipe.getTimeSeconds(),
                                pickaxeRecipe.isKnowledgeRequired(),
                                pickaxeRecipe.getRequiredMemoriesLevel()
                        );
                        Field idField = CraftingRecipe.class.getDeclaredField("id");
                        idField.setAccessible(true);
                        String id = generateRecipeId(shovel);
                        idField.set(recipe, id);
                        LOGGER.atInfo().log("Registering recipe for %s...", shovel.getId());
                        long startTime = System.currentTimeMillis();
                        putAllMethod.invoke(
                                CraftingRecipe.getAssetMap(),
                                this.getName(),
                                CraftingRecipe.getAssetStore().getCodec(),
                                Map.of(id, recipe),
                                Collections.emptyMap(),
                                Collections.emptyMap()
                        );
                        long endTime = System.currentTimeMillis();
                        long time = endTime - startTime;
                        if (CraftingRecipe.getAssetMap().getAsset(id) == null) {
                            LOGGER.atSevere().log("Failed to register recipe for %s! (%s)", shovel.getId(), time < 1 ? "<1ms" : time + "ms");
                        } else {
                            LOGGER.atInfo().log("Successfully registered recipe for %s! (%s)", shovel.getId(), time < 1 ? "<1ms" : time + "ms");
                            JavaPlugin craftingPlugin = CraftingPlugin.get();
                            String className = loadedAssetsEvent.getClass().getSimpleName();
                            LOGGER.atInfo().log("Sending %s to %s...", className, craftingPlugin.getName());
                            startTime = System.currentTimeMillis();
                            try {
                                onRecipeLoadMethod.invoke(craftingPlugin, loadedAssetsEvent);
                                endTime = System.currentTimeMillis();
                                time = endTime - startTime;
                                LOGGER.atInfo().log("Successfully sent %s to %s! (%s)", className, craftingPlugin.getName(), time < 1 ? "<1ms" : time + "ms");
                            } catch(IllegalAccessException e) {
                                endTime = System.currentTimeMillis();
                                time = endTime - startTime;
                                LOGGER.atInfo().log("Failed to send %s to %s! (%s)", className, craftingPlugin.getName(), time < 1 ? "<1ms" : time + "ms");
                                throw new RuntimeException(e);
                            }
                        }
                    }
                }
            } catch (IllegalAccessException | InvocationTargetException | NoSuchFieldException | NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static Map<String, CraftingRecipe> convertAssets(Collection<CraftingRecipe> assets, String startsWith) {
        return assets.stream().filter(asset -> {
            String itemId = asset.getPrimaryOutput().getItemId();
            if (itemId != null) {
                return itemId.startsWith(startsWith);
            } else {
                return false;
            }
        }).collect(Collectors.toMap(
                asset -> asset.getPrimaryOutput().getItemId(),
                Function.identity()
        ));
    }

    private final PluginManifest manifest = this.getManifest();
    private final String pluginIdentifier = manifest.getGroup() + "_" + manifest.getName();

    private String generateRecipeId(String itemId) {
        return pluginIdentifier + "_" + itemId + "_Recipe_Generated";
    }

    private String generateRecipeId(Item item) {
        return generateRecipeId(item.getId());
    }
}