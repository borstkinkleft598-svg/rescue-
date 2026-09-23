package gis2.scenario;

import gis2.GisScenario;

import javax.swing.undo.AbstractUndoableEdit;

/**
 * Function for removing all ambulance teams.
 */
public class ClearAmbulanceTeamsFunction extends AbstractFunction {
    /**
     * Construct a clear ambulance teams function.
     *
     * @param editor The editor instance.
     */
    public ClearAmbulanceTeamsFunction(ScenarioEditor editor) {
        super(editor);
    }

    @Override
    public String getName() {
        return "Remove Ambulance Teams";
    }

    @Override
    public void execute() {
        GisScenario scenario = editor.getScenario();
        ScenarioSnapshot snapshot = new ScenarioSnapshot(scenario);
        ClearAmbulanceTeamsEdit clearEdit = new ClearAmbulanceTeamsEdit(snapshot);

        scenario.clearAmbulanceTeams();
        editor.setChanged();
        editor.updateOverlays();
        editor.addEdit(clearEdit);
    }

    private class ClearAmbulanceTeamsEdit extends AbstractUndoableEdit {
        private final ScenarioSnapshot snapshot;

        public ClearAmbulanceTeamsEdit(ScenarioSnapshot snapshot) {
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
            editor.getScenario().clearAmbulanceTeams();
            editor.updateOverlays();
        }
    }
}
