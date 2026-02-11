package illagercaptain.simplecraftableshovels;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.util.Map;

@SuppressWarnings("unused")
public class SimpleCraftableShovelsPlugin extends JavaPlugin {
    public SimpleCraftableShovelsPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    protected void setup() {
        this.getEventRegistry().register(LoadedAssetsEvent.class, Item.class, (LoadedAssetsEvent<String, Item, DefaultAssetMap<String, Item>> loadedAssetsEvent) -> {
            Map<String, Item> assets = loadedAssetsEvent.getLoadedAssets();
            try {
                Field itemLevel = Item.class.getDeclaredField("itemLevel");
                itemLevel.setAccessible(true);
                assets.entrySet().stream().filter(asset -> asset.getKey().startsWith("Tool_Shovel_")).forEach((item) -> {
                    try {
                        Item pickaxeEquivalent = assets.get(item.getKey().replace("Tool_Shovel_", "Tool_Pickaxe_"));
                        if (pickaxeEquivalent != null) {
                            itemLevel.setInt(item.getValue(), pickaxeEquivalent.getItemLevel());
                        }
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
        });
    }
}