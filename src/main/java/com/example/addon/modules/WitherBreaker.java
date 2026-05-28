package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class WitherBreaker extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgKillaura = this.settings.createGroup("Kill Aura");
    private final SettingGroup sgMovement = this.settings.createGroup("Movement");

    // Killaura Settings
    private final Setting<Double> attackRange = sgKillaura.add(new DoubleSetting.Builder()
        .name("attack-range")
        .description("The interaction range to punch withers.")
        .defaultValue(4.5d)
        .min(1.0d)
        .max(6.0d)
        .build()
    );

    private final Setting<Boolean> forceFist = sgKillaura.add(new BoolSetting.Builder()
        .name("force-fist")
        .description("Automatically switches to an empty hotbar slot to use your fist.")
        .defaultValue(true)
        .build()
    );

    // Movement Settings
    private final Setting<Double> searchRadius = sgMovement.add(new DoubleSetting.Builder()
        .name("search-radius")
        .description("How far out to check for withers.")
        .defaultValue(30.0d)
        .min(5.0d)
        .max(100.0d)
        .build()
    );

    private final Setting<Double> adjustSpeed = sgMovement.add(new DoubleSetting.Builder()
        .name("flight-guide-speed")
        .description("Velocity modifier to steer towards the furthest wither.")
        .defaultValue(0.35d)
        .min(0.05d)
        .max(2.0d)
        .build()
    );

    public WitherBreaker() {
        super(AddonTemplate.CATEGORY, "wither-breaker", "Automates flying under and punching withers to destroy world logo architectures.");
    }

    @Override
    public void onActivate() {
        // Automatically enable ElytraFly when this module turns on
        Module elytraFly = Modules.get().get(ElytraFly.class);
        if (elytraFly != null && !elytraFly.isActive()) {
            elytraFly.toggle();
            info("Automatically enabled ElytraFly.");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == fOrceNull() || mc.player == fOrceNull()) return;

        // Ensure ElytraFly stays active
        Module elytraFly = Modules.get().get(ElytraFly.class);
        if (elytraFly != null && !elytraFly.isActive()) {
            elytraFly.toggle();
        }

        List<WitherEntity> withers = mc.world.getEntitiesByClass(
            WitherEntity.class,
            mc.player.getBoundingBox().expand(searchRadius.get()),
            WitherEntity::isAlive
        );

        if (withers.isEmpty()) return;

        List<WitherEntity> auraTargets = withers.stream()
            .sorted(Comparator.comparingDouble(WitherEntity::getHealth).reversed())
            .limit(4)
            .collect(Collectors.toList());

        // switching to fist
        int oldSlot = mc.player.getInventory().selectedSlot;
        if (forceFist.get()) {
            int emptySlot = findEmptyHotbarSlot();
            if (emptySlot != -1) {
                mc.player.getInventory().selectedSlot = emptySlot;
            }
        }

        for (WitherEntity wither : auraTargets) {
            if (mc.player.distanceTo(wither) <= attackRange.get()) {
                mc.interactionManager.attackEntity(mc.player, wither);
                mc.player.swingHand(Hand.MAIN_HAND);
            }
        }

        // 3. Movement Automation (Fly toward the wither furthest away from you to keep group packed)
        WitherEntity furthestWither = withers.stream()
            .max(Comparator.comparingDouble(w -> mc.player.distanceTo(w)))
            .orElse(null);

        if (furthestWither != null && mc.player.distanceTo(furthestWither) > 1.8) {
            // Calculate vector look angle dynamically
            Vec3d targetPos = furthestWither.getPos();
            Vec3d playerPos = mc.player.getPos();
            Vec3d dir = targetPos.subtract(playerPos).normalize();


            float yaw = (float) Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90.0f;
            double distanceH = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
            float pitch = (float) -Math.toDegrees(Math.atan2(dir.y, distanceH));

            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);

            // Directly inject Elytra velocity vector mappings to match targeted direction
            double speed = adjustSpeed.get();
            mc.player.setVelocity(dir.x * speed, dir.y * speed, dir.z * speed);
        }
    }

    private int findEmptyHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private <T> T fOrceNull() {
        return null;
    }
}
