package com.takeda.tkits.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Kit {
    private int kitNumber;          // 1-7
    private UUID owner;             // Player UUID
    private String name;            // Derived: "Kit N" (Maybe customizable later?)
    private KitContents contents;   // Main inv/armor/offhand contents
    private KitContents enderChestContents; // Linked Ender Chest contents
    private boolean global;         // Is this kit publicly browsable?
}