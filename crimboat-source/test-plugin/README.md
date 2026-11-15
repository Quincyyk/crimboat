# crimboat - custom ship plugin for Aquatic SMP

Paper 1.21.4, Java 21. Build your own pirate ship on the water, drop in an engine, sit on a stair and sail.

## Features
- **Build anything** from wood, wool (sails and flags) and decorations (barrels, banners, lanterns...). No stone.
- **5 engine tiers** (Copper, Iron, Gold, Blaze, Diamond). Each is crafted from the one below.
  Better engines are faster and can move bigger ships (500 up to 2000 blocks).
- **Smooth sailing**: ships glide, steer and slow down smoothly, bob on the waves, leave a wake and puff engine smoke.
- **Seats**: any stair is the Pirate Chair (driver), slabs are passenger seats. Everyone must sit to sail.
- **Solid while sailing**: ships bump into land, blocks and other ships instead of phasing through.
- **Cannons**: place on deck, right-click to man, aim with your mouse (dotted line shows where the ball lands), click to fire.
- **Ship health and sinking**: cannon hits damage the hull. At 0 the ship sinks with an animation and leaves a wreck.

## Works with AquaticSMP
Cannons use the AquaticSMP Cannonball item as ammo (and its texture). Without AquaticSMP this plugin adds its own cannonball.

## Commands (permission `test.admin` for give/reload)
- `/crimboat give engine <tier 1-5> [player] [amount]`
- `/crimboat give cannon [player] [amount]`
- `/crimboat give cannonball [player] [amount]`
- `/crimboat check` - check the ship you're looking at
- `/crimboat reload`

## Build
    mvn package
The jar ends up in `target/`.
