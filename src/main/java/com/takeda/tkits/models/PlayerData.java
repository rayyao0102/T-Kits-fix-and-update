package com.takeda.tkits.models;

import com.takeda.tkits.TKits; 
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class PlayerData {

    private final UUID playerUUID;
    
    private final Map<Integer, Kit> kits;
    @Setter private int lastLoadedKitNumber = -1; 
     @Setter private boolean saving = false; 

    public PlayerData(UUID playerUUID) {
        this.playerUUID = playerUUID;
        this.kits = new ConcurrentHashMap<>();
    }

    public Kit getKit(int kitNumber) {
        return kits.get(kitNumber);
    }

    public void setKit(int kitNumber, Kit kit) {
         if (kit == null) {
             kits.remove(kitNumber);
         } else {
             if (kit.getKitNumber() != kitNumber || !kit.getOwner().equals(this.playerUUID)) {
                  TKits.getInstance().getMessageUtil().logSevere("CRITICAL: Attempted to add kit with mismatching data! "
                          + " Target UUID: " + this.playerUUID + ", Kit Num: " + kitNumber
                          + " | Actual Kit Data: UUID=" + kit.getOwner() + ", Num=" + kit.getKitNumber());
                  
                 return;
             }
             kits.put(kitNumber, kit);
         }
    }

    public Kit getLastLoadedKit() {
        if (lastLoadedKitNumber > 0 && lastLoadedKitNumber <= TKits.getInstance().getConfigManager().getMainConfig().getInt("kits.max_kits_per_player", 7)) {
           return getKit(lastLoadedKitNumber);
        }
        return null;
    }

    public void removeKit(int kitNumber) {
        Kit removed = kits.remove(kitNumber);
        if (removed != null && lastLoadedKitNumber == kitNumber) {
             lastLoadedKitNumber = -1;
        }
    }

    public boolean hasKit(int kitNumber) {
         return kits.containsKey(kitNumber);
    }

     
    public Map<Integer, Kit> getKits() {
        return Map.copyOf(this.kits); 
    }
}