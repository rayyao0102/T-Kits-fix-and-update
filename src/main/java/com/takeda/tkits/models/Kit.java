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
    private int kitNumber;          
    private UUID owner;             
    private String name;            
    private KitContents contents;   
    private KitContents enderChestContents; 
    private boolean global;         
}