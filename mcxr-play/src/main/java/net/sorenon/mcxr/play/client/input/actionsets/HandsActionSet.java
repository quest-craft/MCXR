package net.sorenon.mcxr.play.client.input.actionsets;

import net.sorenon.mcxr.play.client.input.ControllerPosesImpl;
import net.sorenon.mcxr.play.client.input.actions.Action;
import net.sorenon.mcxr.play.client.input.actions.MultiPoseAction;
import oshi.util.tuples.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class HandsActionSet extends ActionSet {

    public MultiPoseAction grip = new MultiPoseAction("grip", new String[]{"/user/hand/left", "/user/hand/right"});
    public MultiPoseAction aim = new MultiPoseAction("aim", new String[]{"/user/hand/left", "/user/hand/right"});

    public final ControllerPosesImpl[] gripPoses = {new ControllerPosesImpl(), new ControllerPosesImpl()};
    public final ControllerPosesImpl[] aimPoses = {new ControllerPosesImpl(), new ControllerPosesImpl()};

    public HandsActionSet() {
        super("hands");
    }

    @Override
    public List<Action> actions() {
        return List.of(
                grip,
                aim
        );
    }

    @Override
    public void getBindings(HashMap<String, List<Pair<Action, String>>> map) {
        map.computeIfAbsent("/interaction_profiles/khr/simple_controller", aLong -> new ArrayList<>()).addAll(
                List.of(
                        new Pair<>(grip, "/user/hand/left/input/grip/pose"),
                        new Pair<>(grip, "/user/hand/right/input/grip/pose")
                )
        );
        map.computeIfAbsent("/interaction_profiles/oculus/touch_controller", aLong -> new ArrayList<>()).addAll(
                List.of(
                        new Pair<>(grip, "/user/hand/left/input/grip/pose"),
                        new Pair<>(grip, "/user/hand/right/input/grip/pose")
                )
        );
        map.computeIfAbsent("/interaction_profiles/valve/index_controller", aLong -> new ArrayList<>()).addAll(
                List.of(
                        new Pair<>(grip, "/user/hand/left/input/grip/pose"),
                        new Pair<>(grip, "/user/hand/right/input/grip/pose")
                )
        );
        map.computeIfAbsent("/interaction_profiles/microsoft/motion_controller", aLong -> new ArrayList<>()).addAll(
                List.of(
                        new Pair<>(grip, "/user/hand/left/input/grip/pose"),
                        new Pair<>(grip, "/user/hand/right/input/grip/pose")
                )
        );
    }
}
