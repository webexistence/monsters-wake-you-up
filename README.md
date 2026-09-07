# Monsters Wake You Up

Re-implements that old Beta feature where monsters can interrupt players' sleep if they are sleeping in an unsafe are.

## Why?

This adds a miniscule challenge to sleeping -- you must set up a safe area for your bed before you can skip the night. It effectively prevents sleeping out in an open field.

## Implementation

The implementation is similar to vanilla before it was removed in version 1.0.0. It simulates spawning of up to 25 mobs near the player when they sleep; if one is able to successfully pathfind to the player, their sleep will be interrupted, and they will wake up to a deadly surprise.

Potential mob spawns include whatever can naturally spawn in the area around the player, with some exceptions (no Endermen, Creepers, or Slimes). Witches can spawn.

This could be somewhat buggy, but it seems fairly robust in my testing so far. The current implementation seems to treat double-doors and beds in house corners just fine. Those were common issues with the old implementation from Minecraft Beta.
