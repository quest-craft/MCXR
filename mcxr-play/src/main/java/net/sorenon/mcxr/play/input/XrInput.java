package net.sorenon.mcxr.play.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.math.Quaternion;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;
import net.sorenon.mcxr.core.JOMLUtil;
import net.sorenon.mcxr.core.Pose;
import net.sorenon.mcxr.play.MCXRGuiManager;
import net.sorenon.mcxr.play.MCXRPlayClient;
import net.sorenon.mcxr.play.PlayOptions;
import net.sorenon.mcxr.play.gui.QuickMenu;
import net.sorenon.mcxr.play.input.actions.Action;
import net.sorenon.mcxr.play.input.actions.SessionAwareAction;
import net.sorenon.mcxr.play.input.actionsets.GuiActionSet;
import net.sorenon.mcxr.play.input.actionsets.HandsActionSet;
import net.sorenon.mcxr.play.input.actionsets.VanillaGameplayActionSet;
import net.sorenon.mcxr.play.mixin.accessor.MouseHandlerAcc;
import net.sorenon.mcxr.play.openxr.OpenXRInstance;
import net.sorenon.mcxr.play.openxr.OpenXRSession;
import net.sorenon.mcxr.play.openxr.XrException;
import net.sorenon.mcxr.play.openxr.XrRuntimeException;
import net.sorenon.mcxr.play.rendering.MCXRCamera;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.openxr.*;
import oshi.util.tuples.Pair;

import java.util.HashMap;
import java.util.List;

import net.minecraft.sounds.SoundEvents;

import static net.sorenon.mcxr.core.JOMLUtil.convert;
import static org.lwjgl.system.MemoryStack.stackPointers;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class XrInput {
    public static final HandsActionSet handsActionSet = new HandsActionSet();
    public static final VanillaGameplayActionSet vanillaGameplayActionSet = new VanillaGameplayActionSet();
    public static final GuiActionSet guiActionSet = new GuiActionSet();

    private static long lastPollTime = 0;
    private static Pose gripPointOld = new Pose();
    private static boolean reachBack = false;
    public static int eatDelay = 0;
    public static int attackDelay = 0;

    private static int motionPoints = 0;
    public static int maxMotionPoints = 16;
    public static float extendReach = 0.0f;
    public static HitResult lastHit = null;
    public static HitResult newHit = null;
    public static boolean teleport = false;

    public static float lastHealth = 0;
    private static Vec3 gripPosOld = new Vec3(0, 0, 0);

    private XrInput() {
    }

    //TODO registryify this
    public static void reinitialize(OpenXRSession session) throws XrException {
        OpenXRInstance instance = session.instance;

        handsActionSet.createHandle(instance);
        vanillaGameplayActionSet.createHandle(instance);
        guiActionSet.createHandle(instance);

        HashMap<String, List<Pair<Action, String>>> defaultBindings = new HashMap<>();
        handsActionSet.getDefaultBindings(defaultBindings);
        vanillaGameplayActionSet.getDefaultBindings(defaultBindings);
        guiActionSet.getDefaultBindings(defaultBindings);

        try (var stack = stackPush()) {
            for (var entry : defaultBindings.entrySet()) {
                var bindingsSet = entry.getValue();

                XrActionSuggestedBinding.Buffer bindings = XrActionSuggestedBinding.malloc(bindingsSet.size(), stack);

                for (int i = 0; i < bindingsSet.size(); i++) {
                    var binding = bindingsSet.get(i);
                    bindings.get(i).set(
                            binding.getA().getHandle(),
                            instance.getPath(binding.getB())
                    );
                }

                XrInteractionProfileSuggestedBinding suggested_binds = XrInteractionProfileSuggestedBinding.malloc(stack).set(
                        XR10.XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING,
                        NULL,
                        instance.getPath(entry.getKey()),
                        bindings
                );

                try {
                    instance.checkPanic(XR10.xrSuggestInteractionProfileBindings(instance.handle, suggested_binds), "xrSuggestInteractionProfileBindings");
                } catch (XrRuntimeException e) {
                    StringBuilder out = new StringBuilder(e.getMessage() + "\ninteractionProfile: " + entry.getKey());
                    for (var pair : bindingsSet) {
                        out.append("\n").append(pair.getB());
                    }
                    throw new XrRuntimeException(e.result, out.toString());
                }
            }

            XrSessionActionSetsAttachInfo attach_info = XrSessionActionSetsAttachInfo.malloc(stack).set(
                    XR10.XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO,
                    NULL,
                    stackPointers(vanillaGameplayActionSet.getHandle().address(), guiActionSet.getHandle().address(), handsActionSet.getHandle().address())
            );
            // Attach the action set we just made to the session
            instance.checkPanic(XR10.xrAttachSessionActionSets(session.handle, attach_info), "xrAttachSessionActionSets");
        }

        for (var action : handsActionSet.actions()) {
            if (action instanceof SessionAwareAction sessionAwareAction) {
                sessionAwareAction.createHandleSession(session);
            }
        }
    }

    /**
     * Pre-tick + Pre-render, called once every frame
     */
    public static void pollActions() {
        long time = System.nanoTime();
        if(Minecraft.getInstance().player != null) {
            lastHealth = Minecraft.getInstance().player.getHealth();
        }

        if (lastPollTime == 0) {
            lastPollTime = time;
        }

        if (MCXRPlayClient.INSTANCE.MCXRGuiManager.isScreenOpen()) {
            if (guiActionSet.exit.changedSinceLastSync) {
                if (guiActionSet.exit.currentState) {
                    if (Minecraft.getInstance().screen != null) {
                        Minecraft.getInstance().screen.keyPressed(256, 0, 0);
                    }
                }
            }
        }

        if (MCXRPlayClient.INSTANCE.MCXRGuiManager.isScreenOpen()) {
            return;
        }

        VanillaGameplayActionSet actionSet = vanillaGameplayActionSet;

        if(actionSet.menu.currentState && actionSet.menu.changedSinceLastSync) {
            Minecraft.getInstance().pauseGame(false);
        }

        if (actionSet.teleport.changedSinceLastSync && !actionSet.teleport.currentState) {
            XrInput.teleport = true;
        }

        //==immersive controls test==
        if(PlayOptions.immersiveControls){
            ItemStack held = Minecraft.getInstance().player.getItemInHand(InteractionHand.MAIN_HAND);
            MouseHandlerAcc mouseHandler = (MouseHandlerAcc) Minecraft.getInstance().mouseHandler;
            Pose gripPoint = handsActionSet.gripPoses[MCXRPlayClient.getMainHand()].getStagePose();
            Camera cam = Minecraft.getInstance().gameRenderer.getMainCamera();
            float delta = (time - lastPollTime) / 1_000_000_000f;
            //velocity calcs
            Vec3 gripPos = convert(gripPoint.getPos());
            Vec3 gripPosOld = convert(gripPointOld.getPos());
            double velo = gripPos.distanceTo(gripPosOld) / delta;
            //Quaternionf gripOri = gripPoint.getOrientation().normalize();
            //Quaternionf gripOriDiff = gripOri.conjugate().mul(gripPointOld.getOrientation().normalize());
            //double angVelo = gripOriDiff.angle()/ delta;
            //Vector3f eulers = new Vector3f(0,0,0);
            //gripOriDiff.getEulerAnglesZYX(eulers);
            //double angVelo = convert(eulers).length()/ delta;
            double angVelo = (Mth.abs(gripPoint.getMCPitch() - gripPointOld.getMCPitch())+Mth.abs(gripPoint.getMCYaw() - gripPointOld.getMCYaw()))/delta;
            boolean moving=angVelo>50 || velo>1;
            boolean swinging = velo>0.8;

            //delay before attacking starts by building up motion points, used to determine gesture delay
            if(swinging && motionPoints<maxMotionPoints){motionPoints+=velo+0.3;}
            else if(!swinging){motionPoints=0;}//resets when a swing ends

            //distance for hit detection
            if(held.getItem() instanceof SwordItem || held.getItem() instanceof DiggerItem || held.getItem() instanceof TridentItem){
                extendReach=0.7f;
            }
            else{extendReach=0.0f;}
            double minDist = 0.3+extendReach;

            HitResult hitResult = Minecraft.getInstance().hitResult;//=last hit or new hit
            //newHit = Minecraft.getInstance().hitResult;
            Pose handPoint = handsActionSet.aimPoses[MCXRPlayClient.getMainHand()].getMinecraftPose();
            Vec3 handPos = convert(handPoint.getPos());

            if (hitResult != null && hitResult.getType() != HitResult.Type.MISS) {//something in ray
                double curDist = handPos.distanceTo(hitResult.getLocation());
                double newDist = handPos.distanceTo(newHit.getLocation());//using this doesn't detect entities?
                double oldDist=0;
                if(lastHit!=null) {
                    oldDist = handPos.distanceTo(lastHit.getLocation());
                }

                if (lastHit == null || (oldDist-0.1)>newDist) {//new target or closer target
                    if(velo>PlayOptions.immersiveAttackMinSpeed) {
                        if (hitResult.getType() == HitResult.Type.ENTITY && curDist<minDist+1.5) {//make hit distance for entities more practical
                            attackDelay = 8;
                            lastHit = hitResult;//some reason newHit doesn't work for entities.
                        }
                        else if (hitResult.getType() == HitResult.Type.BLOCK && newDist<minDist) {
                            if(lastHit == null || (lastHit != null && lastHit.getType() == HitResult.Type.BLOCK)) {
                                attackDelay = motionPoints+2;
                                lastHit = newHit;
                            }
                        }
                    }
                }
                else if (lastHit.getType() == HitResult.Type.BLOCK){//continue on current target or let go, with larger leeway to continue swings
                    if (hitResult.getType() == HitResult.Type.ENTITY && velo>PlayOptions.immersiveAttackMinSpeed) {//prioritise entites in range, doesnt work since newHit doesn't like entities
                        lastHit = newHit;
                        attackDelay = 8;
                    }
                    //else if (oldDist < minDist*1.2) {//continue clicking
                    else if (lastHit.getLocation().distanceTo(newHit.getLocation()) < 0.3) {//continue clicking
                        attackDelay += motionPoints;
                    }
                }
            }

            //decay attackDelay without conditions
            if (attackDelay > 0) {
                attackDelay -= 1;
                if(attackDelay>maxMotionPoints)attackDelay=maxMotionPoints;
            }
            else {
                lastHit = null;
            }
            if(attackDelay > 2){//stop pressing before unlocking crosshair
                mouseHandler.callOnPress(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
            }
            else{
                if (!actionSet.attack.currentState) {//only if not pressing attack
                    mouseHandler.callOnPress(Minecraft.getInstance().getWindow().getWindow(), GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
                }
            }

            //==item eating==
            InputConstants.Key useKey = Minecraft.getInstance().options.keyUse.getDefaultKey();
            boolean edible=held.isEdible() || held.getUseAnimation() == UseAnim.DRINK;
            Vec3 faceVec = handPos.subtract(cam.getPosition().add(cam.getLookVector().x()*0.15,-0.03+cam.getLookVector().y()*0.15,cam.getLookVector().z()*0.15));
            if(edible && faceVec.length()<0.1 && moving){
                KeyMapping.click(useKey);
                KeyMapping.set(useKey, true);
                eatDelay=5;
            }
            else{
                if(eatDelay>0){eatDelay-=1;}
                if(!actionSet.use.currentState && eatDelay==0){
                    KeyMapping.set(useKey, false);
                }
                if(eatDelay==0 && faceVec.length()<0.1){eatDelay=1;}//for item near face animation
            }

            //==item swapping==
            Vec3 backVec = handPos.subtract(cam.getPosition().add(-cam.getLookVector().x()*0.2,-cam.getLookVector().y()*0.2,-cam.getLookVector().z()*0.2));
            InputConstants.Key swapKey = Minecraft.getInstance().options.keySwapOffhand.getDefaultKey();
            if(backVec.length()<0.17 && !reachBack){
                KeyMapping.click(swapKey);
                KeyMapping.set(swapKey, true);
                reachBack=true;
            } else {
                KeyMapping.set(swapKey, false);
                if(backVec.length()>0.18) {
                    reachBack = false;
                }
            }

            gripPointOld.set(gripPoint);
        }

        if (actionSet.teleport.changedSinceLastSync && !actionSet.teleport.currentState) {
            XrInput.teleport = true;
        }

        //==immersive controls test==
        if(PlayOptions.immersiveControls){
            Pose gripPoint = handsActionSet.gripPoses[MCXRPlayClient.getMainHand()].getStagePose();
            Vec3 gripPos = convert(gripPoint.getPos());
            float delta = (time - lastPollTime) / 1_000_000_000f;
            double velo = gripPos.distanceTo(gripPosOld)/delta;
            //delay before attacking starts/stops by building up motion points
            if(velo>1 && motionPoints < 16){
                motionPoints+=Math.abs(velo);
             }
            else if(motionPoints>0){motionPoints-=1;}

            gripPosOld = gripPos;
        }

        if (PlayOptions.smoothTurning) {
            if (Math.abs(actionSet.turn.currentState) > 0.4) {
                float delta = (time - lastPollTime) / 1_000_000_000f;

                MCXRPlayClient.stageTurn += Math.toRadians(PlayOptions.smoothTurnRate) * -Math.signum(actionSet.turn.currentState) * delta;
                Vector3f newPos = new Quaternionf().rotateLocalY(MCXRPlayClient.stageTurn).transform(MCXRPlayClient.viewSpacePoses.getStagePose().getPos(), new Vector3f());
                Vector3f wantedPos = new Vector3f(MCXRPlayClient.viewSpacePoses.getPhysicalPose().getPos());

                MCXRPlayClient.stagePosition = wantedPos.sub(newPos).mul(1, 0, 1);
            }
        } else {
            if (actionSet.turn.changedSinceLastSync) {
                float value = actionSet.turn.currentState;
                if (actionSet.turnActivated) {
                    actionSet.turnActivated = Math.abs(value) > 0.15f;
                } else if (Math.abs(value) > 0.7f) {
                    MCXRPlayClient.stageTurn += Math.toRadians(PlayOptions.snapTurnAmount) * -Math.signum(value);
                    Vector3f newPos = new Quaternionf().rotateLocalY(MCXRPlayClient.stageTurn).transform(MCXRPlayClient.viewSpacePoses.getStagePose().getPos(), new Vector3f());
                    Vector3f wantedPos = new Vector3f(MCXRPlayClient.viewSpacePoses.getPhysicalPose().getPos());

                    MCXRPlayClient.stagePosition = wantedPos.sub(newPos).mul(1, 0, 1);

                    actionSet.turnActivated = true;

                    if (PlayOptions.snapTurnSound) {
                        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.WOOL_STEP, 2.5f, 0.3f));
                    }
                }
            }
        }

        if (actionSet.hotbar.changedSinceLastSync) {
            var value = actionSet.hotbar.currentState;
            if (actionSet.hotbarActivated) {
                actionSet.hotbarActivated = Math.abs(value) > 0.15f;
            } else if (Math.abs(value) >= 0.7f) {
                if (Minecraft.getInstance().player != null)
                    Minecraft.getInstance().player.getInventory().swapPaint(-value);
                actionSet.hotbarActivated = true;
            }
        }

        if (actionSet.hotbarLeft.currentState && actionSet.hotbarLeft.changedSinceLastSync) {
            if (Minecraft.getInstance().player != null)
                Minecraft.getInstance().player.getInventory().swapPaint(+1);
        }
        if (actionSet.hotbarRight.currentState && actionSet.hotbarRight.changedSinceLastSync) {
            if (Minecraft.getInstance().player != null)
                Minecraft.getInstance().player.getInventory().swapPaint(-1);
        }

        if (actionSet.turnLeft.currentState && actionSet.turnLeft.changedSinceLastSync) {
            MCXRPlayClient.stageTurn += Math.toRadians(22);
            Vector3f newPos = new Quaternionf().rotateLocalY(MCXRPlayClient.stageTurn).transform(MCXRPlayClient.viewSpacePoses.getStagePose().getPos(), new Vector3f());
            Vector3f wantedPos = new Vector3f(MCXRPlayClient.viewSpacePoses.getPhysicalPose().getPos());

            MCXRPlayClient.stagePosition = wantedPos.sub(newPos).mul(1, 0, 1);
        }
        if (actionSet.turnRight.currentState && actionSet.turnRight.changedSinceLastSync) {
            MCXRPlayClient.stageTurn -= Math.toRadians(22);
            Vector3f newPos = new Quaternionf().rotateLocalY(MCXRPlayClient.stageTurn).transform(MCXRPlayClient.viewSpacePoses.getStagePose().getPos(), new Vector3f());
            Vector3f wantedPos = new Vector3f(MCXRPlayClient.viewSpacePoses.getPhysicalPose().getPos());

            MCXRPlayClient.stagePosition = wantedPos.sub(newPos).mul(1, 0, 1);
        }
        if (actionSet.menu.currentState && actionSet.menu.changedSinceLastSync) {
            Minecraft.getInstance().pauseGame(false);
        }

        if (actionSet.inventory.changedSinceLastSync) {
            if (!actionSet.inventory.currentState) {
                Minecraft client = Minecraft.getInstance();
                if (client.screen == null) {
                    if (client.player != null && client.gameMode != null) {
                        if (client.gameMode.isServerControlledInventory()) {
                            client.player.sendOpenInventory();
                        } else {
                            client.getTutorial().onOpenInventory();
                            client.setScreen(new InventoryScreen(client.player));
                        }
                    }
                }
            }
        }

        if (actionSet.quickmenu.changedSinceLastSync) {
            if (!actionSet.quickmenu.currentState) {
                Minecraft client = Minecraft.getInstance();
                if (client.screen == null) {
                    client.setScreen(new QuickMenu(new TranslatableComponent("mcxr.quickmenu")));
                }
            }
        }

        if (actionSet.sprintAnalog.changedSinceLastSync) {
            float value = actionSet.sprintAnalog.currentState;
            Minecraft client = Minecraft.getInstance();
            if (value > 0.8f && !actionSet.sprintAnalogOn) {//sprint
                actionSet.sprintAnalogOn=true;
                client.options.keySprint.setDown(true);
                //disable sneak
                if (actionSet.sneakAnalogOn){
                    actionSet.sneakAnalogOn=false;
                    client.options.keyShift.setDown(false);
                    if (client.player != null) {
                        client.player.setShiftKeyDown(true);
                    }
                }
            } else if (actionSet.sprintAnalogOn && value < 0.6f){
                actionSet.sprintAnalogOn=false;
                client.options.keySprint.setDown(false);
                if (client.player != null) {
                    client.player.setSprinting(false);
                }
            }
        }

        if (actionSet.sneak.changedSinceLastSync) {
            Minecraft client = Minecraft.getInstance();
            InputConstants.Key key = client.options.keySwapOffhand.getDefaultKey();
            if (actionSet.swapHands.currentState) {
                KeyMapping.click(key);
                KeyMapping.set(key, true);
            } else {
                KeyMapping.set(key, false);
            }
        }
        if (actionSet.sneakAnalog.changedSinceLastSync) {
            float value = actionSet.sneakAnalog.currentState;
            Minecraft client = Minecraft.getInstance();
            if (value < -0.8f && !actionSet.sneakAnalogOn) {//sneak activate
                actionSet.sneakAnalogOn=true;
                client.options.keyShift.setDown(true);
                if (client.player != null) {
                    client.player.setShiftKeyDown(true);
                }
                //disable sprint
                if (actionSet.sprintAnalogOn){
                    actionSet.sprintAnalogOn=false;
                    client.options.keySprint.setDown(false);
                    if (client.player != null) {
                        client.player.setSprinting(false);
                    }
                }

            } else if (actionSet.sneakAnalogOn && value > -0.6f){
                actionSet.sneakAnalogOn=false;
                client.options.keyShift.setDown(false);
                if (client.player != null) {
                    client.player.setShiftKeyDown(true);
                }
            }
        }

        if (actionSet.swapHands.changedSinceLastSync) {
            Minecraft client = Minecraft.getInstance();
            InputConstants.Key key = client.options.keySwapOffhand.getDefaultKey();
            if (actionSet.swapHands.currentState) {
                KeyMapping.click(key);
                KeyMapping.set(key, true);
            } else {
                KeyMapping.set(key, false);
            }
        }
//        if (actionSet.attackState.changedSinceLastSync()) {
//            MinecraftClient client = MinecraftClient.getInstance();
//            InputUtil.Key key = client.options.keyAttack.getDefaultKey();
//            if (actionSet.attackState.currentState()) {
//                KeyBinding.onKeyPressed(key);
//                KeyBinding.setKeyPressed(key, true);
//            } else {
//                KeyBinding.setKeyPressed(key, false);
//            }
//        }
        if (actionSet.use.changedSinceLastSync) {
            Minecraft client = Minecraft.getInstance();
            InputConstants.Key key = client.options.keyUse.getDefaultKey();
            if (actionSet.use.currentState) {
                KeyMapping.click(key);
                KeyMapping.set(key, true);
            } else {
                KeyMapping.set(key, false);
            }
        }

        lastPollTime = time;
    }

    /**
     * Post-tick + Pre-render, called once every frame
     */
    public static void postTick(long predictedDisplayTime) {
        MCXRGuiManager FGM = MCXRPlayClient.INSTANCE.MCXRGuiManager;
        MouseHandlerAcc mouseHandler = (MouseHandlerAcc) Minecraft.getInstance().mouseHandler;
        if(Minecraft.getInstance().player != null) {
            Player player = Minecraft.getInstance().player;
            if (player.getHealth() < lastHealth) {
                applyHaptics(300, 1, XR10.XR_FREQUENCY_UNSPECIFIED);
            }
            if(player.isSprinting()) {
                applyHaptics(300, 0.5f, XR10.XR_FREQUENCY_UNSPECIFIED);
            }
            if(player.getUseItemRemainingTicks() > 0) {
                applyHaptics(300, 0.6f, XR10.XR_FREQUENCY_UNSPECIFIED);
            }
        }

        if (FGM.isScreenOpen()) {
            Pose pose = handsActionSet.gripPoses[MCXRPlayClient.getMainHand()].getUnscaledPhysicalPose();
            Vector3d pos = new Vector3d(pose.getPos());
            Vector3f dir = pose.getOrientation().rotateX((float) Math.toRadians(PlayOptions.handPitchAdjust), new Quaternionf()).transform(new Vector3f(0, -1, 0));
            Vector3d result = FGM.guiRaycast(pos, new Vector3d(dir));
            if (result != null) {
                Vector3d vec = result.sub(convert(FGM.position));
                FGM.orientation.invert(new Quaterniond()).transform(vec);
                vec.y *= ((double) FGM.guiFramebufferWidth / FGM.guiFramebufferHeight);

                vec.x /= FGM.size;
                vec.y /= FGM.size;

                mouseHandler.callOnMove(
                        Minecraft.getInstance().getWindow().getWindow(),
                        FGM.guiFramebufferWidth * (0.5 - vec.x),
                        FGM.guiFramebufferHeight * (1 - vec.y)
                );
            }
            GuiActionSet actionSet = guiActionSet;
            if (actionSet.pickup.changedSinceLastSync || actionSet.quickMove.changedSinceLastSync) {
                if (actionSet.pickup.currentState || actionSet.quickMove.currentState) {
                    mouseHandler.callOnPress(Minecraft.getInstance().getWindow().getWindow(),
                            GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
                } else {
                    mouseHandler.callOnPress(Minecraft.getInstance().getWindow().getWindow(),
                            GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
                }
            }

            if (actionSet.split.changedSinceLastSync) {
                if (actionSet.split.currentState) {
                    mouseHandler.callOnPress(Minecraft.getInstance().getWindow().getWindow(),
                            GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_PRESS, 0);
                } else {
                    mouseHandler.callOnPress(Minecraft.getInstance().getWindow().getWindow(),
                            GLFW.GLFW_MOUSE_BUTTON_RIGHT, GLFW.GLFW_RELEASE, 0);
                }
            }
            if (actionSet.resetGUI.changedSinceLastSync && actionSet.resetGUI.currentState) {
                FGM.needsReset = true;
            }
            var scrollState = actionSet.scroll.currentState;
            //TODO replace with a better acc alg
            double sensitivity = 0.25;
            if (Math.abs(scrollState.y()) > 0.9 && scrollState.length() > 0.95) {
                mouseHandler.callOnScroll(Minecraft.getInstance().getWindow().getWindow(),
                        -scrollState.x() * sensitivity, 1.5 * Math.signum(scrollState.y()));
            } else if (Math.abs(scrollState.y()) > 0.1) {
                mouseHandler.callOnScroll(Minecraft.getInstance().getWindow().getWindow(),
                        -scrollState.x() * sensitivity, 0.1 * Math.signum(scrollState.y()));
            }
        } else {
            VanillaGameplayActionSet actionSet = vanillaGameplayActionSet;
            if (actionSet.attack.changedSinceLastSync) {
                if (actionSet.attack.currentState) {
                    mouseHandler.callOnPress(Minecraft.getInstance().getWindow().getWindow(),
                            GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
                    if(MCXRPlayClient.getMainHand() == 1) {
                        applyHapticsRight(300, 1, XR10.XR_FREQUENCY_UNSPECIFIED);
                    } else {
                        applyHapticsLeft(300, 1, XR10.XR_FREQUENCY_UNSPECIFIED);
                    }
                    attackDelay=0;
                    lastHit=null;
                }
            }
            if (!actionSet.attack.currentState) {
                mouseHandler.callOnPress(Minecraft.getInstance().getWindow().getWindow(),
                        GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);

            }
            if (actionSet.inventory.currentState) {
                long heldTime = predictedDisplayTime - actionSet.inventory.lastChangeTime;
                if (heldTime * 1E-09 > 1) {
                    Minecraft.getInstance().pauseGame(false);
                }
            }

            //==immersive control test
            if(PlayOptions.immersiveControls){
                var hitResult = Minecraft.getInstance().hitResult;
                Pose handPoint = handsActionSet.aimPoses[MCXRPlayClient.getMainHand()].getMinecraftPose();
                Vec3 handPos = convert(handPoint.getPos());
                if(motionPoints>11) {
                    if (hitResult != null) {
                        double dist = handPos.distanceTo(hitResult.getLocation());
                        if (hitResult.getType() == HitResult.Type.BLOCK && dist < 0.4) {
                            mouseHandler.callOnPress(Minecraft.getInstance().getWindow().getWindow(),
                                    GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
                        } else if (hitResult.getType() == HitResult.Type.ENTITY && dist < 4) {
                            mouseHandler.callOnPress(Minecraft.getInstance().getWindow().getWindow(),
                                    GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
                            motionPoints = 0;
                        }

                        if(MCXRPlayClient.getMainHand() == 1) {
                            applyHapticsRight(300, 1f, XR10.XR_FREQUENCY_UNSPECIFIED);
                        } else {
                            applyHapticsLeft(300, 1f, XR10.XR_FREQUENCY_UNSPECIFIED);
                        }
                    } //else if (hitResult.getType() !=HitResult.Type.MISS && !lastHit.equals(hitResult)){//let go if hitting new block/entity
                    // mouseHandler.callOnPress(Minecraft.getInstance().getWindow().getWindow(),
                    //GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
                    //lastHit=null;
                    //motionPoints=0;
                    //}
                } else if(motionPoints < 1) {//let go when no more motionPoints
                    if(!actionSet.attack.currentState) {//only if not pressing attack
                        mouseHandler.callOnPress(Minecraft.getInstance().getWindow().getWindow(),
                                GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0);
                        lastHit=null;
                    }
                }
            }
        }
    }

    public static void applyHaptics(long duration, float amplitude, float frequency) {
        applyHapticsLeft(duration, amplitude, frequency);
        applyHapticsRight(duration, amplitude, frequency);
    }

    public static void applyHapticsRight(long duration, float amplitude, float frequency) {
        try(var stack = stackPush()) {
            XrHapticVibration vibrationInfo = XrHapticVibration.calloc(stack).set(
                    XR10.XR_TYPE_HAPTIC_VIBRATION,
                    NULL,
                    duration,
                    frequency,
                    amplitude
            );

            XrHapticActionInfo hapticActionInfo = XrHapticActionInfo.calloc();
            hapticActionInfo.type(XR10.XR_TYPE_HAPTIC_ACTION_INFO);
            hapticActionInfo.action(vanillaGameplayActionSet.rightHaptic.getHandle());

            MCXRPlayClient.OPEN_XR_STATE.instance.checkPanic(XR10.xrApplyHapticFeedback(MCXRPlayClient.OPEN_XR_STATE.session.handle, hapticActionInfo, XrHapticBaseHeader.create(vibrationInfo.address())), "xrApplyHapticFeedback");
        }
    }

    public static void applyHapticsLeft(long duration, float amplitude, float frequency) {
        try(var stack = stackPush()) {
            XrHapticVibration vibrationInfo = XrHapticVibration.calloc(stack).set(
                    XR10.XR_TYPE_HAPTIC_VIBRATION,
                    NULL,
                    duration,
                    frequency,
                    amplitude
            );

            XrHapticActionInfo hapticActionInfo = XrHapticActionInfo.calloc();
            hapticActionInfo.type(XR10.XR_TYPE_HAPTIC_ACTION_INFO);
            hapticActionInfo.action(vanillaGameplayActionSet.leftHaptic.getHandle());

            MCXRPlayClient.OPEN_XR_STATE.instance.checkPanic(XR10.xrApplyHapticFeedback(MCXRPlayClient.OPEN_XR_STATE.session.handle, hapticActionInfo, XrHapticBaseHeader.create(vibrationInfo.address())), "xrApplyHapticFeedback");
        }
    }

    public static void setNewHit(HitResult hit) {
        newHit=hit;
    }
}
