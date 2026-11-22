An addon to NTM Continuum Space that makes space stations sable contraptions. It gives an ability to rendezvous with other players' space stations, land your space stations on planets, and fly them back up.

Install `sable-orbits-0.2.2-alpha.jar` on the client and server.

When a sable sublevel reaches Y:2000 on any planet, and there are players on it, it automatically goes to space and turns into a space station, but it will fall down on the planet after 5 minutes if it doesn't have an orbital station computer.
Orbital station computer now has a UI, that allows you to create a space station disk, view other space stations orbiting the planet, and request rendezvous with them to meet each other in space.

Mechanics of interplanetary flight are the same as in regular NTM Space, you can use a space station thruster attached to your station, and a processed drive in the Orbital Station Computer to fly between planets

Droppod mechanics are also exactly the same, you can use a drop pod to fly down onto the planet and then back up, and request a drop pod from your station using your station's drive.

On a spacewalk, you leave with the velocity of the spot where you stepped off. Leaving the rim of a spinning deck carries you along its tangent. Once clear of the hull, you coast on that course as the station turns or fires its engines. Landing on another deck picks up its motion.

In zero gravity you swim headfirst and can pass through a one-block opening. Your body and hitbox pivot around your eyes, so you can look around next to a wall. **W/S** accelerate forward and backward, **A/D** strafe, and **Space/Shift** move up and down in your view. You keep swimming against the hull and inside sealed rooms while station gravity is off. On a deck with gravity, **Shift** crouches and lets you approach the edge of a tilted floor safely. Hold **Z/C** to roll left or right, or keep looking up or down to turn a full loop. Turning while coasting preserves your course. Getting up from a Create seat on a tilted station carries the seat's orientation into your spacewalk. The magnet toggle defaults to **X** and the jetpack toggle to **G**. You can rebind these in Controls.

An oxygen-fed air vent applies the station’s gravity setting to its sealed room. With artificial gravity enabled, you can walk and crouch there. Mobs align with the deck and dropped items settle on its floor. Leave the pressurized room to roll freely again. On a planet you can stand still on a Sable floor tilted up to 45°. The camera eases into alignment as you land. You push off perpendicular to the floor, and planetary gravity curves your jump downward. On steeper faces or after leaving the hull, the camera eases back to upright. Manual roll is available only in zero gravity.

Dropped items, projectiles, and free-moving particles inherit their source's motion in orbit. A snowball thrown straight ahead from a moving deck travels forward relative to you, even when the station is moving sideways. Projectiles coast without gravity in orbit. On planets and moons, each projectile's usual gravity scales with the body's surface gravity.

### Rocket engines and reaction wheels

Placed rocket engines show a flame at each nozzle while burning. The bell diameter sets the plume width, and the fuel sets its color.

For an engine that needs both fuel and oxidizer, run two separately typed ducts to the inlet block at the end opposite the exhaust. Connect one duct to the back and the other to an exposed side, or use two exposed sides if the ship covers the back. Either connection can carry either required fluid. Both feed the engine's existing tanks.

Craft a **Reaction Wheel** with an HBM motor in the center, redstone dust on its left and right, copper ingots above and below, and iron ingots in the corners. Place it against the floor, ceiling, or a wall. The two flat ends follow the placement axis and accept HE. Join wheels end to end on the same axis to share their stored energy and controls.

With no signal, powered wheels brake pitch, yaw and roll. Power any curved side to steer the whole stack toward it. The end that tilts toward the signal is the upper end for vertical stacks, the east end for east–west stacks and the south end for north–south stacks. Each side uses its strongest signal along the stack. Opposite signals cancel, and adjacent signals steer diagonally. Power either exposed flat end to switch off both steering and braking for the entire stack. Releasing it restores the current steering or braking command. Each wheel adds 8,000 HE of capacity, contributes torque and uses 80 HE per active tick. The maximum turn rate is 45 degrees per second.

You can also launch a space station the old-fashioned way by launching a rocket with an orbital station module, and then flying there. It will still be a sable sublevel, and you'll still be able to rendezvous with stations and land your station on other planets.


This addon does NOT provide realistic orbital physics. In technical terms, when a sable sublevel becomes a station, it obtains its own orbital dimension. That way two stations will not stumble upon each other without requesting a rendezvous. When everyone leaves a station dimension, its hulls hold their position and orientation. They resume at their previous velocities when someone enters. When you request a rendezvous, your station gets teleported near another person's station. If you fly away from each other, you will eventually get separated into your own dimensions again.

It is done that way to make spaceflight in minecraft fun without the burden of orbital mechanics.
