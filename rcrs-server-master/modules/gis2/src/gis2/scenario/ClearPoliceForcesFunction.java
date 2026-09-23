package gis2.scenario;

import gis2.GisScenario;

import javax.swing.undo.AbstractUndoableEdit;

/**
 * Function for removing all police forces.
 */
public class ClearPoliceForcesFunction extends AbstractFunction {
    /**
     * Construct a clear police forces function.
     *
     * @param editor The editor instance.
     */
    public ClearPoliceForcesFunction(ScenarioEditor editor) {
        super(editor);
    }

    @Override
    public String getName() {
        return "Remove Police Forces";
    }

    @Override
    public void execute() {
        GisScenario scenario = editor.getScenario();
        ScenarioSnapshot snapshot = new ScenarioSnapshot(scenario);
        ClearPoliceForcesEdit clearEdit = new ClearPoliceForcesEdit(snapshot);

        scenario.clearPoliceForces();
        editor.setChanged();
        editor.updateOverlays();
        editor.addEdit(clearEdit);
    }

    private class ClearPoliceForcesEdit extends AbstractUndoableEdit {
        private final ScenarioSnapshot snapshot;

        public ClearPoliceForcesEdit(ScenarioSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public void undo() {
            super.undo();
            snapshot.restore(editor.getScenario());
            editor.updateOverlays();
        }

        @Override
        public void redo() {
            super.redo();
            editor.getScenario().clearPoliceForces();
            editor.updateOverlays();
        }
    }
}
