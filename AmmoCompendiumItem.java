package com.ywzjvehicle.armor;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The ammunition compendium: right-click to open a list of every configured round and what it
 * penetrates.
 *
 * <p>The screen is opened from the client only, which is not an optimisation - a screen is a
 * client-side object and there is nothing for the server to do. The two sides are told apart the
 * usual way, by asking the level, rather than by the side the class was loaded on.
 */
public class AmmoCompendiumItem extends Item {

    public AmmoCompendiumItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> m_7203_(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.m_21120_(hand);
        if (level.m_5776_()) {
            // Reached only ever on the client, so this reference to a client-only class is never
            // followed anywhere else - the same arrangement the mod constructor already uses to
            // register its client setup.
            ClientSetup.openAmmoCompendium();
        }
        // Reported as a sided success so the hand swings once, on the side that did something.
        return InteractionResultHolder.m_19092_(stack, level.m_5776_());
    }
}
