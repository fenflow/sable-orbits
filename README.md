An addon to NTM Continuum Space that makes space stations sable contraptions. It gives an ability to rendezvous with other players' space stations, land your space stations on planets, and fly them back up.

Install `sable-orbits-0.2.3-alpha.jar` on the client and server.

When a sable sublevel reaches Y:2000 on any planet, and there are players on it, it automatically goes to space and turns into a space station, but it will fall down on the planet after 5 minutes if it doesn't have an orbital station computer.
Orbital station computer now has a UI, that allows you to create a space station disk, view other space stations orbiting the planet, and request rendezvous with them to meet each other in space.

Use station thrusters and a processed drive in the Orbital Station Computer to fly between planets. On a Sable station, choose any of the thruster's mounting directions and line up the nozzles. The largest group of aligned thrusters sets the direction for interplanetary flight. A thruster outside that group shows NTM's **Wrong Direction** warning when you look at it and sits out the interplanetary burn.

Droppod mechanics are also exactly the same, you can use a drop pod to fly down onto the planet and then back up, and request a drop pod from your station using your station's drive.

On a spacewalk, you leave with the velocity of the spot where you stepped off. Leaving the rim of a spinning deck carries you along its tangent. Once clear of the hull, you coast on that course as the station turns or fires its engines. Landing on another deck picks up its motion.

Sable sublevels have a linear speed limit of 350 blocks per second in orbit.

In zero gravity you swim headfirst and can pass through a one-block opening. Your body and hitbox pivot around your eyes, so you can look around next to a wall. **W/S** accelerate forward and backward, **A/D** strafe, and **Space/Shift** move up and down in your view. You keep swimming against the hull and inside sealed rooms while station gravity is off. On a deck with gravity, **Shift** crouches and lets you approach the edge of a tilted floor safely. Hold **Z/C** to roll left or right, or keep looking up or down to turn a full loop. Turning while coasting preserves your course. Getting up from a Create seat on a tilted station carries the seat's orientation into your spacewalk. The magnet toggle defaults to **X** and the jetpack toggle to **G**. You can rebind these in Controls.

An oxygen-fed air vent applies the station’s gravity setting to its sealed room. With artificial gravity enabled, you can walk and crouch there. Mobs align with the deck and dropped items settle on its floor. Leave the pressurized room to roll freely again. On a planet you can stand still on a Sable floor tilted up to 45°. The camera eases into alignment as you land. You push off perpendicular to the floor, and planetary gravity curves your jump downward. On steeper faces or after leaving the hull, the camera eases back to upright. Manual roll is available only in zero gravity.

While floating, hold **Shift** and right-click to place blocks against containers and use other sneak interactions.

Dropped items, projectiles, and free-moving particles inherit their source's motion in orbit. A snowball thrown straight ahead from a moving deck travels forward relative to you, even when the station is moving sideways. Projectiles coast without gravity in orbit. On planets and moons, each projectile's usual gravity scales with the body's surface gravity.

### Rocket engines, fuselages and reaction wheels

On planets with an atmosphere, a firing engine produces the Soyuz launch flare and leaves a smoke trail. The flare and fresh exhaust take their color from the fuel. In vacuum, each bell shows its flame and ejects small white particles. In orbit those particles keep the speed and direction of the nozzle at release, even if the station turns. Firing engines play NTM rocket sounds.

Keep clear of a live nozzle. The exhaust sets exposed entities on fire and pushes them away. It can also push another Sable hull and turn it if the jet strikes off-center. Opening a pressurized cabin to space kicks the station away from the escaping gas at the breach.

Aim at a block while holding an engine or fuselage to see the HBM placement outline. Right-click to place it along the targeted face. Engine nozzles point away from that face, or back toward it if you hold Shift. On a Sable craft, the clicked face follows the craft's rotation. Sneak to place against an existing fuselage or another block with a menu. Liquid fuselages have fixed tanks for their listed fuel and, where applicable, oxidizer. Universal liquid fuselages, including Size 20 12m, have two independent tanks. Either tank can take another liquid once empty. Insert an HBM fluid identifier beside a gauge to set a lasting filter for that tank. Each tank holds ten times the capacity listed for rocket assembly. Use the tank screen to load or unload HBM fluid containers, including creative infinite barrels. The HBM mode icon is blue for **Input only**, green for **Input + export**, and orange for **Export only**. Every block on either flat end accepts a compatible duct or item feeder. A pickaxe dismantles the assembly. Items still in its slots drop separately.

Placed fuselages contribute structural mass to a Sable craft. Each occupied block contributes 2.5 mass units for Size 10, 4 for Size 15, or 16 for Size 20. Tapered fuselages use their wider end. Longer or wider tanks accelerate more slowly on the same engine. Fuel level does not change this structural mass.

Every block on the back of a liquid engine can receive either required fluid. Dual-propellant engines also accept ducts on the exposed sides of the main inlet block. To feed an engine directly, attach a compatible fuselage end to its back and enable export. Set fuselages in a stack to **Input + export** to pass fuel through to the engine.

Placed liquid engines consume ten times the previous fuel and oxidizer per burn tick.

Solid fuselages store charge from HBM solid-fuel items. Regular solid fuel provides 1,000 units per item, rocket propellant provides 2,000, and balefire provides 4,000. Set the magazines to **Input + export** to feed through a stack into a solid engine. Solid engines also accept those items by hand, hopper, or compatible item automation at every rear face. Placed solid engines burn 10 fuel units per active tick. NTM rocket launches retain their native fuel costs.

Craft a **Reaction Wheel** with an HBM motor in the center, redstone dust on its left and right, copper ingots above and below, and iron ingots in the corners. Place it against the floor, ceiling, or a wall. The two flat ends follow the placement axis and accept HE. Join wheels end to end on the same axis to share their stored energy and controls.

Surround a Size 10 wheel with four iron ingots in a cross to craft a **Size 15 Reaction Wheel**, then repeat with Size 15 to craft **Size 20**. Both are 1 m thick and occupy a 2×2 footprint. Their models are 1.5 m and 2 m across, and they deliver four and six times the Size 10 torque, respectively. The HBM hologram previews the full footprint. Every cell on either flat end accepts HE. Size 15 and Size 20 wheels can stack together and snap to the flat ends of two-block-wide fuselages. Sneak to place a wheel against a fuselage with a menu.

With no signal, powered wheels brake pitch, yaw and roll. Power any curved side to steer the whole stack toward it. The end that tilts toward the signal is the upper end for vertical stacks, the east end for east–west stacks and the south end for north–south stacks. Each side uses its strongest signal along the stack. Opposite signals cancel, and adjacent signals steer diagonally. Power either exposed flat end to switch off both steering and braking for the entire stack. Releasing it restores the current steering or braking command. Each wheel adds 8,000 HE of capacity, contributes torque and uses 80 HE per active tick. The maximum turn rate is 45 degrees per second.

You can also launch a space station the old-fashioned way by launching a rocket with an orbital station module, and then flying there. It will still be a sable sublevel, and you'll still be able to rendezvous with stations and land your station on other planets.


This addon does NOT provide realistic orbital physics. In technical terms, when a sable sublevel becomes a station, it obtains its own orbital dimension. That way two stations will not stumble upon each other without requesting a rendezvous. When everyone leaves a station dimension, its hulls hold their position and orientation. They resume at their previous velocities when someone enters. When you request a rendezvous, your station gets teleported near another person's station. If you fly away from each other, you will eventually get separated into your own dimensions again.

It is done that way to make spaceflight in minecraft fun without the burden of orbital mechanics.
