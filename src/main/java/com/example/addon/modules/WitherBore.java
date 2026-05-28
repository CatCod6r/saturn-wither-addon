package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class WitherBore extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgKillaura = this.settings.createGroup("Kill Aura");
    private final SettingGroup sgPathfind = this.settings.createGroup("Pathfinding");

    private static final float PHASE_THRESHOLD = 0.40f;

    private final Setting<Double> searchRadius = sgGeneral.add(new DoubleSetting.Builder()
        .name("wither-detection-range")
        .description("How far out to check for withers")
        .defaultValue(40.0d)
        .min(5.0d)
        .max(128.0d)
        .build()
    );

    private final Setting<Boolean> silentRotations = sgGeneral.add(new BoolSetting.Builder()
        .name("silent-rotations")
        .description("Rotate silently on the server without shaking your client camera.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> mineVerticalObstacles = sgGeneral.add(new BoolSetting.Builder()
        .name("mine-obstacles")
        .description("Break blocks manually if they're in the way")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> lowHealthHold = sgGeneral.add(new DoubleSetting.Builder()
        .name("wither-health-target")
        .description("You finna let the bot heal or not")
        .defaultValue(0.4d)
        .min(0.0d)
        .max(1.0d)
        .build()
    );

    // Kill aura
    private final Setting<Double> attackRange = sgKillaura.add(new DoubleSetting.Builder()
        .name("attack-range")
        .description("The range to punch withers")
        .defaultValue(4.5d)
        .min(1.0d)
        .max(6.0d)
        .build()
    );

    private final Setting<Double> cooldownThreshold = sgKillaura.add(new DoubleSetting.Builder()
        .name("cooldown-threshold")
        .description("Phase1 attack cooldown")
        .defaultValue(0.95d)
        .min(0.1d)
        .max(1.0d)
        .build()
    );

    private final Setting<Double> verticalOffset = sgKillaura.add(new DoubleSetting.Builder()
        .name("phase1-hitbox-offset")
        .description("Phase1 distance above the wither")
        .defaultValue(2.0d)
        .min(0.0d)
        .max(5.0d)
        .build()
    );

    // pathfinding
    private final Setting<Integer> heightLimit = sgPathfind.add(new IntSetting.Builder()
        .name("target-height")
        .description("The bore-line Y level")
        .defaultValue(319)
        .min(100)
        .max(319)
        .build()
    );

    private final Setting<Double> playerBelowOffset = sgPathfind.add(new DoubleSetting.Builder()
        .name("player-vertical-offset")
        .description("How many blocks below the boreline the player should fly")
        .defaultValue(2.0d)
        .min(0.5d)
        .max(8.0d)
        .build()
    );

    private final Setting<Double> witherStandoff = sgPathfind.add(new DoubleSetting.Builder()
        .name("player-horizontal-offset")
        .description("Blocks ahead of the wither")
        .defaultValue(4.0d)
        .min(2.0d)
        .max(6.0d)
        .build()
    );

    private final Setting<Double> witherInRangeY = sgPathfind.add(new DoubleSetting.Builder()
        .name("wither-vertical-tolerance")
        .description("Is the wither close enough for me to care about it")
        .defaultValue(4.0d)
        .min(1.0d)
        .max(10.0d)
        .build()
    );

    private final Setting<Integer> obsidianSearchRadius = sgPathfind.add(new IntSetting.Builder()
        .name("block-detection-range")
        .description("Checks where there are blocks to break")
        .defaultValue(32)
        .min(8)
        .max(96)
        .build()
    );

    private final Setting<List<Block>> targetBlocks = sgPathfind.add(new BlockListSetting.Builder()
        .name("target-blocks")
        .description("Whitelist blocks to break")
        .defaultValue(List.of(Blocks.OBSIDIAN))
        .build()
    );

    private final Setting<Boolean> axisLock = sgPathfind.add(new BoolSetting.Builder()
        .name("axis-lock")
        .description("Permanently locks client crosshair horizontally onto the closest snapped grid direction.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> lockDiagonals = sgPathfind.add(new BoolSetting.Builder()
        .name("lock-diagonals")
        .description("Include diagonal 45-degree angle snaps inside the visible crosshair axis lock.")
        .defaultValue(true)
        .build()
    );

    private static final int LOOKAHEAD_BLOCKS  = 3;
    private static final double BORE_SPEED_FACTOR = 0.5;
    private Direction boreDir = null;
    private BlockPos miningBlock = null;
    private Direction miningFace = null;
    private boolean enteredPhase2 = false;

    private float serverYaw = 0f;

    public WitherBore() {
        super(AddonTemplate.CATEGORY, "wither-bore", "Breaks blocks using withers");
    }

    @Override
    public void onActivate() {
        Module elytraFly = Modules.get().get(ElytraFly.class);
        if (elytraFly != null && !elytraFly.isActive()) {
            elytraFly.toggle();
            info("Automatically enabled ElytraFly.");
        }
        boreDir = null;
        if (mc.player != null) {
            serverYaw = mc.player.getYaw();
        }
    }

    @Override
    public void onDeactivate() {
        releaseAllKeys();
        boreDir = null;
        boreTickCounter = 0;
        clearAheadTicks = 0;
        lateralShiftTarget = null;
        postShiftDir = null;
        failedShiftsInARow = 0;
        enteredPhase2 = false;
        cancelMining();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;

        // fah
        if (mc.player.isUsingItem()) {
            releaseMovementKeys();
            cancelMining();
            return;
        }

        Module elytraFly = Modules.get().get(ElytraFly.class);
        if (elytraFly != null && !elytraFly.isActive()) {
            elytraFly.toggle();
        }

        if (!isPlayerGliding() && hasElytraEquipped()) {
            mc.player.networkHandler.sendPacket(
                new net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket(
                    mc.player,
                    net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket.Mode.START_FALL_FLYING
                )
            );
        }

        List<WitherEntity> withers = mc.world.getEntitiesByClass(
            WitherEntity.class,
            mc.player.getBoundingBox().expand(searchRadius.get()),
            WitherEntity::isAlive
        );

        if (withers.isEmpty()) {
            releaseAllKeys();
            boreDir = null;
            enteredPhase2 = false;
            cancelMining();
            return;
        }

        WitherEntity focusWither = withers.stream()
            .min(Comparator.comparingDouble(w -> w.getHealth() / w.getMaxHealth()))
            .orElse(null);
        if (focusWither == null) {
            releaseAllKeys();
            return;
        }

        WitherEntity attackTarget = withers.stream()
            .filter(w -> w.getHealth() / w.getMaxHealth() > PHASE_THRESHOLD)
            .min(Comparator.comparingDouble(WitherEntity::getHealth))
            .orElse(null);

        WitherEntity boreTarget = withers.stream()
            .min(Comparator.comparingDouble(w -> w.getHealth() / w.getMaxHealth()))
            .orElse(focusWither);

        boolean anyBelowThreshold = withers.stream()
            .anyMatch(w -> w.getHealth() / w.getMaxHealth() <= PHASE_THRESHOLD);
        if (anyBelowThreshold) enteredPhase2 = true;

        boolean phase1 = !enteredPhase2 && attackTarget != null;

        float ownHpFrac = mc.player.getHealth() / mc.player.getMaxHealth();
        boolean healWindow = lowHealthHold.get() > 0 && ownHpFrac < lowHealthHold.get();

        if (!healWindow) {
            int slot;
            if (phase1) {
                slot = findBestWeaponHotbarSlot();
            } else {
                float boreHpFrac = boreTarget.getHealth() / boreTarget.getMaxHealth();
                slot = (boreHpFrac >= PHASE_THRESHOLD) ? findBestWeaponHotbarSlot() : findNonWeaponHotbarSlot();
            }
            if (slot != -1 && slot != mc.player.getInventory().selectedSlot) {
                mc.player.getInventory().selectedSlot = slot;
            }
        }

        List<WitherEntity> auraTargets = withers.stream()
            .sorted(Comparator.comparingDouble(WitherEntity::getHealth).reversed())
            .limit(4)
            .collect(Collectors.toList());

        boolean currentlyMining = miningBlock != null;

        if (phase1) {
            if (!healWindow && !currentlyMining
                && mc.player.getAttackCooldownProgress(0.5f) >= cooldownThreshold.get()) {
                for (WitherEntity wither : auraTargets) {
                    mc.interactionManager.attackEntity(mc.player, wither);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    mc.player.resetLastAttackedTicks();
                    break;
                }
            }
        } else {
            boolean holdingSword = isHoldingSwordOrAxe();
            boolean cooldownReady = mc.player.getAttackCooldownProgress(0.5f) >= cooldownThreshold.get();

            if (!healWindow && !currentlyMining && (!holdingSword || cooldownReady)) {
                for (WitherEntity wither : auraTargets) {
                    mc.interactionManager.attackEntity(mc.player, wither);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    if (holdingSword) {
                        mc.player.resetLastAttackedTicks();
                        break;
                    }
                }
            }
        }

        // Movement
        if (phase1) {
            boreDir = null;
            boreTickCounter = 0;
            clearAheadTicks = 0;
            runChasePhase(attackTarget);
        } else {
            WitherEntity pacingWither = withers.stream()
                .max(Comparator.comparingDouble(w -> mc.player.distanceTo(w)))
                .orElse(boreTarget);
            runBorePhase(boreTarget, pacingWither);
        }

        if (mineVerticalObstacles.get()) {
            updateVerticalMining();
        } else {
            cancelMining();
        }
    }

    // PHASE1 - Chase
    private void runChasePhase(WitherEntity wither) {
        double targetY = wither.getY() + verticalOffset.get();
        double dy = targetY - mc.player.getY();

        Vec3d witherPos = wither.getPos();
        double dx = witherPos.x - mc.player.getX();
        double dz = witherPos.z - mc.player.getZ();
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        boolean inAttackPosition = mc.player.distanceTo(wither) <= attackRange.get() * 0.9 && dy <= 0.5;

        if (inAttackPosition) {
            faceVector(wither.getPos().subtract(mc.player.getEyePos()));
            setKey(mc.options.forwardKey, false);
            setKey(mc.options.backKey, false);
            setKey(mc.options.leftKey, false);
            setKey(mc.options.rightKey, false);
            setKey(mc.options.jumpKey,  dy >  0.3);
            setKey(mc.options.sneakKey, dy < -0.5);
            return;
        }

        Vec3d to = new Vec3d(dx, dy, dz);
        faceVector(to);

        if (dy > 0.5) {
            float climbPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(0.5, horizDist)));
            climbPitch = Math.max(climbPitch, -55f);
            if (silentRotations.get()) {
                Rotations.rotate(serverYaw, climbPitch, 10, null);
            } else {
                mc.player.setPitch(climbPitch);
            }
        }

        setKey(mc.options.forwardKey, true);
        setKey(mc.options.backKey, false);
        setKey(mc.options.leftKey, false);
        setKey(mc.options.rightKey, false);
        setKey(mc.options.jumpKey, dy >  0.5);
        setKey(mc.options.sneakKey, dy < -1.5);
    }

    private int boreTickCounter = 0;
    private int clearAheadTicks = 0;
    private static final int CLEAR_TICKS_BEFORE_TURN = 20;

    private static final int LATERAL_SHIFT = 3;
    private BlockPos lateralShiftTarget = null;
    private Direction postShiftDir = null;
    private int failedShiftsInARow = 0;
    private static final int MAX_FAILED_SHIFTS = 2;

    //PHASE 2 - BORING
    private void runBorePhase(WitherEntity wither, WitherEntity pacingWither) {
        int scanY = heightLimit.get();
        double flyY = scanY - playerBelowOffset.get();

        if (boreDir == null) {
            BlockPos at = BlockPos.ofFloored(mc.player.getX(), scanY, mc.player.getZ());
            boreDir = pickBestBoreDirection(at);
            clearAheadTicks = 0;
        }

        if (mc.player.getY() > flyY + 1.0) {
            if (silentRotations.get()) {
                Rotations.rotate(serverYaw, 80f, 10, null);
            } else {
                mc.player.setPitch(80f);
            }
            setKey(mc.options.forwardKey, true);
            setKey(mc.options.sneakKey, true);
            setKey(mc.options.jumpKey, false);
            setKey(mc.options.leftKey, false);
            setKey(mc.options.rightKey, false);
            setKey(mc.options.backKey, false);
            return;
        }

        double dyToPacing = pacingWither.getY() - mc.player.getY();
        double distToPacing = mc.player.distanceTo(pacingWither);
        boolean pacingInRange = distToPacing <= attackRange.get() + playerBelowOffset.get() && Math.abs(dyToPacing) <= witherInRangeY.get() + playerBelowOffset.get();

        if (!pacingInRange) {
            faceVector(pacingWither.getPos().subtract(mc.player.getEyePos()));

            double horizDistToPacing = Math.sqrt(Math.pow(pacingWither.getX() - mc.player.getX(), 2) + Math.pow(pacingWither.getZ() - mc.player.getZ(), 2));

            double dy = flyY - mc.player.getY();
            boolean closeHorizontal = horizDistToPacing > 1.0;
            setKey(mc.options.forwardKey, closeHorizontal);
            setKey(mc.options.backKey, false);
            setKey(mc.options.leftKey, false);
            setKey(mc.options.rightKey, false);
            setKey(mc.options.jumpKey, dy >  0.3);
            setKey(mc.options.sneakKey, dy < -0.3);
            return;
        }

        BlockPos here = BlockPos.ofFloored(mc.player.getX(), scanY, mc.player.getZ());
        boolean anyBlocksNearby = false;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (scanSide(here, d)) { anyBlocksNearby = true; break; }
        }
        if (!anyBlocksNearby && !mc.world.getBlockState(here).isAir()) anyBlocksNearby = true;

        if (!anyBlocksNearby) {
            BlockPos target = findNearestBlockAtScanY(here, obsidianSearchRadius.get());
            if (target != null) {
                flyTowardBlock(target, flyY);
                return;
            }

            faceVector(wither.getPos().subtract(mc.player.getEyePos()));
            double dy0 = flyY - mc.player.getY();
            setKey(mc.options.forwardKey, false);
            setKey(mc.options.backKey, false);
            setKey(mc.options.leftKey, false);
            setKey(mc.options.rightKey, false);
            setKey(mc.options.jumpKey, dy0 > 0.3);
            setKey(mc.options.sneakKey, dy0 < -0.3);
            return;
        }

        if (lateralShiftTarget != null) {
            double horizDistToShiftTarget = Math.sqrt(Math.pow(lateralShiftTarget.getX() + 0.5 - mc.player.getX(), 2) + Math.pow(lateralShiftTarget.getZ() + 0.5 - mc.player.getZ(), 2));

            if (horizDistToShiftTarget > 0.8) {
                flyTowardBlock(lateralShiftTarget, flyY);
                return;
            }

            boreDir = postShiftDir;
            BlockPos newRow = BlockPos.ofFloored(mc.player.getX(), scanY, mc.player.getZ());
            BlockPos nextInRow = newRow.offset(boreDir, 1);
            boolean newRowHasBlocks = !mc.world.getBlockState(nextInRow).isAir() || scanSide(newRow, boreDir);
            if (newRowHasBlocks) {
                failedShiftsInARow = 0;
            } else {
                failedShiftsInARow++;
            }

            lateralShiftTarget = null;
            postShiftDir = null;
            clearAheadTicks = 0;

            if (failedShiftsInARow >= MAX_FAILED_SHIFTS) {
                boreDir = pickBestBoreDirection(newRow);
                failedShiftsInARow = 0;
            }
            here = newRow;
        }

        BlockPos rightInFront = here.offset(boreDir, 1);
        boolean airImmediatelyAhead = mc.world.getBlockState(rightInFront).isAir();

        if (airImmediatelyAhead) {
            clearAheadTicks++;
        } else {
            clearAheadTicks = 0;
        }

        if (clearAheadTicks >= CLEAR_TICKS_BEFORE_TURN && lateralShiftTarget == null) {
            Direction leftDir = boreDir.rotateYCounterclockwise();
            lateralShiftTarget = here.offset(leftDir, LATERAL_SHIFT);
            postShiftDir = boreDir.getOpposite();
            clearAheadTicks = 0;
            setKey(mc.options.forwardKey, false);
            setKey(mc.options.backKey, false);
            return;
        }

        Vec3d faceVec = Vec3d.of(boreDir.getVector());
        float yaw = (float) Math.toDegrees(Math.atan2(faceVec.z, faceVec.x)) - 90.0f;

        if (axisLock.get()) {
            float normalizedYaw = ((yaw % 360f) + 360f) % 360f;
            float step = lockDiagonals.get() ? 45f : 90f;
            float snappedYaw = Math.round(normalizedYaw / step) * step;
            float diff = snappedYaw - normalizedYaw;
            if (diff > 180f) diff -= 360f;
            if (diff < -180f) diff += 360f;
            yaw = yaw + diff;

            // Permanent visible client lock
            mc.player.setYaw(yaw);
        }

        serverYaw = yaw;
        if (silentRotations.get()) {
            Rotations.rotate(yaw, -20f, 10, null);
        } else {
            mc.player.setYaw(yaw);
            mc.player.setPitch(-20f);
        }

        double dy = flyY - mc.player.getY();
        setKey(mc.options.jumpKey, dy >  0.3);
        setKey(mc.options.sneakKey, dy < -0.3);
        setKey(mc.options.leftKey, false);
        setKey(mc.options.rightKey, false);

        boreTickCounter++;
        double factor = BORE_SPEED_FACTOR;
        int total = 10;
        int pressed = (int) Math.round(factor * total);
        int phaseTick = boreTickCounter % total;
        boolean throttledForward = phaseTick < pressed;

        double horizDistToWither = Math.sqrt(Math.pow(pacingWither.getX() - mc.player.getX(), 2) + Math.pow(pacingWither.getZ() - mc.player.getZ(), 2));
        boolean tooFar = horizDistToWither > 1.5;
        boolean tooNear = horizDistToWither < 0.5;

        boolean pressForward = false;
        boolean pressBack = false;

        if (tooFar) {
            pressBack = true;
        } else if (tooNear) {
            pressForward = false;
        } else {
            pressForward = throttledForward;
        }

        setKey(mc.options.forwardKey, pressForward);
        setKey(mc.options.backKey,    pressBack);
    }

    // helper functions
    private void updateVerticalMining() {
        boolean wantUp = mc.options.jumpKey.isPressed();
        boolean wantDown = mc.options.sneakKey.isPressed();

        if (!wantUp && !wantDown) {
            cancelMining();
            return;
        }

        var bb = mc.player.getBoundingBox();
        BlockPos obstruction = null;
        Direction face = null;

        if (wantUp) {
            int x = (int) Math.floor(mc.player.getX());
            int z = (int) Math.floor(mc.player.getZ());
            int y = (int) Math.floor(bb.maxY + 0.05);
            BlockPos check = new BlockPos(x, y, z);
            if (isSolidMineable(check)) {
                obstruction = check;
                face = Direction.DOWN;
            }
        } else {
            int x = (int) Math.floor(mc.player.getX());
            int z = (int) Math.floor(mc.player.getZ());
            int y = (int) Math.floor(bb.minY - 0.05);
            BlockPos check = new BlockPos(x, y, z);
            if (isSolidMineable(check)) {
                obstruction = check;
                face = Direction.UP;
            }
        }

        if (obstruction == null) {
            cancelMining();
            return;
        }

        Vec3d eye = mc.player.getEyePos();
        Vec3d blockCenter = Vec3d.ofCenter(obstruction);
        if (eye.distanceTo(blockCenter) > 5.0) {
            cancelMining();
            return;
        }

        faceVector(blockCenter.subtract(eye));
        if (miningBlock == null || !miningBlock.equals(obstruction)) {
            if (miningBlock != null) {
                mc.interactionManager.updateBlockBreakingProgress(miningBlock, miningFace);
                mc.interactionManager.cancelBlockBreaking();
            }
            miningBlock = obstruction;
            miningFace = face;
            mc.interactionManager.attackBlock(miningBlock, miningFace);
        }

        mc.interactionManager.updateBlockBreakingProgress(miningBlock, miningFace);
        mc.player.swingHand(Hand.MAIN_HAND);

        if (mc.world.getBlockState(miningBlock).isAir()) {
            cancelMining();
        }
    }

    private void cancelMining() {
        if (miningBlock != null) {
            mc.interactionManager.cancelBlockBreaking();
        }
        miningBlock = null;
        miningFace = null;
    }

    private boolean isSolidMineable(BlockPos pos) {
        var state = mc.world.getBlockState(pos);
        if (state.isAir()) return false;
        return !state.getCollisionShape(mc.world, pos).isEmpty();
    }

    private void faceVector(Vec3d dir) {
        Vec3d n = dir.normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(n.z, n.x)) - 90.0f;
        double horiz = Math.sqrt(n.x * n.x + n.z * n.z);
        float pitch = (float) -Math.toDegrees(Math.atan2(n.y, horiz));

        if (axisLock.get()) {
            float normalizedYaw = ((yaw % 360f) + 360f) % 360f;
            float step = lockDiagonals.get() ? 45f : 90f;
            float snappedYaw = Math.round(normalizedYaw / step) * step;
            float diff = snappedYaw - normalizedYaw;
            if (diff > 180f) diff -= 360f;
            if (diff < -180f) diff += 360f;
            yaw = yaw + diff;

            // Permanent visible horizontal client lock
            mc.player.setYaw(yaw);
        }

        serverYaw = yaw;
        if (silentRotations.get()) {
            Rotations.rotate(yaw, pitch, 10, null);
        } else {
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
        }
    }

    private boolean scanSide(BlockPos origin, Direction side) {
        for (int i = 1; i <= LOOKAHEAD_BLOCKS; i++) {
            BlockPos check = origin.offset(side, i);
            if (!mc.world.getBlockState(check).isAir()) return true;
        }
        return false;
    }

    private Direction pickBestBoreDirection(BlockPos origin) {
        Direction[] candidates = { Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };
        Direction best = null;
        int bestCount = 0;
        int scanRange = Math.max(LOOKAHEAD_BLOCKS, 6);
        for (Direction d : candidates) {
            int count = 0;
            for (int i = 1; i <= scanRange; i++) {
                BlockPos check = origin.offset(d, i);
                if (!mc.world.getBlockState(check).isAir()) count++;
            }
            if (count > bestCount) {
                bestCount = count;
                best = d;
            }
        }
        if (best != null) return best;
        return horizontalFromYaw(serverYaw);
    }

    private Direction horizontalFromYaw(float yaw) {
        float y = ((yaw % 360f) + 360f) % 360f;
        if (y >= 315f || y < 45f) return Direction.SOUTH;
        if (y < 135f) return Direction.WEST;
        if (y < 225f) return Direction.NORTH;
        return Direction.EAST;
    }

    private BlockPos findNearestBlockAtScanY(BlockPos origin, int radius) {
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                BlockPos top = origin.add(dx, 0, -r);
                BlockPos bot = origin.add(dx, 0,  r);
                if (isTargetBlock(top)) return top;
                if (isTargetBlock(bot)) return bot;
            }
            for (int dz = -r + 1; dz <= r - 1; dz++) {
                BlockPos left  = origin.add(-r, 0, dz);
                BlockPos right = origin.add( r, 0, dz);
                if (isTargetBlock(left))  return left;
                if (isTargetBlock(right)) return right;
            }
        }
        return null;
    }

    private boolean isTargetBlock(BlockPos pos) {
        Block block = mc.world.getBlockState(pos).getBlock();
        return targetBlocks.get().contains(block);
    }

    private void flyTowardBlock(BlockPos target, double flyY) {
        Vec3d targetPos = new Vec3d(target.getX() + 0.5, flyY, target.getZ() + 0.5);
        Vec3d toTarget = targetPos.subtract(mc.player.getPos());

        float yaw = (float) Math.toDegrees(Math.atan2(toTarget.z, toTarget.x)) - 90.0f;
        double horiz = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);
        float pitch = (float) -Math.toDegrees(Math.atan2(toTarget.y, horiz));

        if (axisLock.get()) {
            float normalizedYaw = ((yaw % 360f) + 360f) % 360f;
            float step = lockDiagonals.get() ? 45f : 90f;
            float snappedYaw = Math.round(normalizedYaw / step) * step;
            float diff = snappedYaw - normalizedYaw;
            if (diff > 180f) diff -= 360f;
            if (diff < -180f) diff += 360f;
            yaw = yaw + diff;

            // Permanent visible horizontal client lock
            mc.player.setYaw(yaw);
        }

        serverYaw = yaw;
        if (silentRotations.get()) {
            Rotations.rotate(yaw, pitch, 10, null);
        } else {
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
        }

        double dy = flyY - mc.player.getY();
        setKey(mc.options.forwardKey, true);
        setKey(mc.options.backKey, false);
        setKey(mc.options.leftKey, false);
        setKey(mc.options.rightKey, false);
        setKey(mc.options.jumpKey, dy >  0.3);
        setKey(mc.options.sneakKey, dy < -0.3);
    }

    private int findNonWeaponHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!(stack.getItem() instanceof SwordItem) && !(stack.getItem() instanceof AxeItem)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isHoldingSwordOrAxe() {
        ItemStack held = mc.player.getMainHandStack();
        return held.getItem() instanceof SwordItem || held.getItem() instanceof AxeItem;
    }

    private boolean isPlayerGliding() {
        String poseName = mc.player.getPose().name();
        return poseName.equals("GLIDING") || poseName.equals("FALL_FLYING");
    }

    private boolean hasElytraEquipped() {
        ItemStack chest = mc.player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST);
        if (chest.isEmpty()) return false;
        net.minecraft.util.Identifier id = net.minecraft.registry.Registries.ITEM.getId(chest.getItem());
        return id.getPath().equals("elytra");
    }

    private int findBestWeaponHotbarSlot() {
        int bestSlot = -1;
        double bestScore = -1.0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            double score = weaponScore(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestScore > 0 ? bestSlot : -1;
    }

    private double weaponScore(ItemStack stack) {
        boolean isSword = stack.getItem() instanceof SwordItem;
        boolean isAxe = stack.getItem() instanceof AxeItem;
        if (!isSword && !isAxe) return 0.0;

        String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();
        double tier;
        if (id.contains("netherite")) tier = 6.0;
        else if (id.contains("diamond")) tier = 5.0;
        else if (id.contains("iron")) tier = 4.0;
        else if (id.contains("stone")) tier = 3.0;
        else if (id.contains("golden")) tier = 2.0;
        else if (id.contains("wooden")) tier = 1.0;
        else tier = 0.5;

        return (isSword ? 100.0 : 0.0) + tier;
    }

    private void setKey(KeyBinding key, boolean pressed) {
        if (key == null) return;
        key.setPressed(pressed);
    }

    private void releaseMovementKeys() {
        setKey(mc.options.forwardKey, false);
        setKey(mc.options.backKey, false);
        setKey(mc.options.leftKey, false);
        setKey(mc.options.rightKey, false);
        setKey(mc.options.jumpKey, false);
        setKey(mc.options.sneakKey, false);
    }

    private void releaseAllKeys() {
        releaseMovementKeys();
    }
}
