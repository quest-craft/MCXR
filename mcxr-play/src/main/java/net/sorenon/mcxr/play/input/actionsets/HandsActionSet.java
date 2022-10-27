package net.sorenon.mcxr.play.input.actionsets;

import net.sorenon.mcxr.play.MCXRPlayClient;
import net.sorenon.mcxr.play.input.ControllerPoses;
import net.sorenon.mcxr.play.input.actions.Action;
import net.sorenon.mcxr.play.input.actions.MultiPoseAction;
import oshi.util.tuples.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class HandsActionSet extends ActionSet {

    public MultiPoseAction grip = new MultiPoseAction("grip", new String[]{"/user/hand/left", "/user/hand/right"});
    public MultiPoseAction aim = new MultiPoseAction("aim", new String[]{"/user/hand/left", "/user/hand/right"});

    public final ControllerPoses[] gripPoses = {new ControllerPoses(), new ControllerPoses()};
    public final ControllerPoses[] aimPoses = {new ControllerPoses(), new ControllerPoses()};

    public HandsActionSet() {
        super("hands", 0);
    }

    @Override
    public List<Action> actions() {
        return List.of(
                grip,
                aim
        );
    }

    @Override
    public void getDefaultBindings(HashMap<String, List<Pair<Action, String>>> map) {

        map.computeIfAbsent("/interaction_profiles/oculus/touch_controller", aLong -> new ArrayList<>()).addAll(
                List.of(
                        new Pair<>(grip, "/user/hand/left/input/grip/pose"),
                        new Pair<>(grip, "/user/hand/right/input/grip/pose"),
                        new Pair<>(aim, "/user/hand/left/input/aim/pose"),
                        new Pair<>(aim, "/user/hand/right/input/aim/pose")
                )
        );
    }
}
