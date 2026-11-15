package com.creatormc.judgementdaymod.models;

public class ClientHeatData {

    private static float clientHeat = 0f;

    public static void set(float heat) {
        clientHeat = heat;
    }

    public static float get() {
        return clientHeat;
    }
}