package com.ywzjvehicle.armor;

import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * The client's copy of "is this vehicle's main gun being laid by the commander".
 *
 * <p>The server is the authority for whether an order exists; this map is only what the client
 * needs in order to apply the server's aim to the <em>owner's</em> weapon unit as well. The base
 * mod deliberately leaves the owner's own copy of the aim alone - normally the owner's mouse <em>is</em>
 * the aim - so without this flag the gunner would see the turret move while their scope stayed
 * where it was, and the round would leave along a line neither of them chose.
 *
 * <p>Kept free of client-only types so the packet handler can update it on either side; only the
 * client-only mixin reads it. A stale entry is harmless - the flag is consulted only while the
 * vehicle exists, and entity ids are not reused within a session - and logging out clears the map.
 */
public final class CrewClientState {

    private CrewClientState() {
    }

    private static final Map<Integer, Boolean> SLAVED = new HashMap<>();

    /**
     * Vehicles in the commander's solo-control mode.
     *
     * <p>Server state, mirrored here only for the HUD: the mode is a property of the vehicle, so
     * every rider - not just the commander - should be able to see that one person is working the
     * whole vehicle.
     */
    private static final Map<Integer, Boolean> SOLO = new HashMap<>();

    /** Whether the local player is currently out of action; sent with the rest of the crew state. */
    private static boolean incapacitated;

    /**
     * The entity the commander has marked, or zero.
     *
     * <p>Carried in the same message as the slave flag and previously read and discarded, because
     * the client aims with the server's angles and not with the id. The HUD needs it to say whether
     * there is a mark at all; a name would need the level, and the level is the client's own.
     */
    private static int designated;

    /** Records the state the server just sent for one vehicle. */
    public static void set(int vehicleEntityId, boolean slaved) {
        if (slaved) {
            SLAVED.put(vehicleEntityId, Boolean.TRUE);
        } else {
            SLAVED.remove(vehicleEntityId);
        }
    }

    /** Records whether this vehicle is in the commander's solo-control mode. */
    public static void setSolo(int vehicleEntityId, boolean solo) {
        if (solo) {
            SOLO.put(vehicleEntityId, Boolean.TRUE);
        } else {
            SOLO.remove(vehicleEntityId);
        }
    }

    /** @return whether this vehicle is in the commander's solo-control mode. */
    public static boolean isSolo(Entity vehicle) {
        return vehicle != null && SOLO.containsKey(vehicle.m_19879_());
    }

    /** Records the commander's mark. */
    public static void setDesignated(int targetEntityId) {
        designated = targetEntityId;
    }

    /** @return the marked entity's id, or zero when nothing is marked. */
    public static int designated() {
        return designated;
    }

    public static void setIncapacitated(boolean wounded) {
        incapacitated = wounded;
    }

    /** @return whether the local player's trigger should be refused. */
    public static boolean isIncapacitated() {
        return incapacitated;
    }

    /** @return whether the commander is currently laying this vehicle's main gun. */
    public static boolean isSlaved(Entity vehicle) {
        return vehicle != null && SLAVED.containsKey(vehicle.m_19879_());
    }

    /** Forgets every vehicle and the wound, so nothing survives a trip to another server. */
    public static void clear() {
        SLAVED.clear();
        SOLO.clear();
        incapacitated = false;
        designated = 0;
    }
}
