package gis2.scenario;

import gis2.GisScenario;

import javax.swing.undo.AbstractUndoableEdit;

/**
 * Function for removing all civilians.
 */
public class ClearCiviliansFunction extends AbstractFunction {
    /**
     * Construct a clear civilians function.
     *
     * @param editor The editor instance.
     */
    public ClearCiviliansFunction(ScenarioEditor editor) {
        super(editor);
    }

    @Override
    public String getName() {
        return "Remove Civilians";
    }

    @Override
    public void execute() {
        GisScenario scenario = editor.getScenario();
        ScenarioSnapshot snapshot = new ScenarioSnapshot(scenario);
        ClearCiviliansEdit clearEdit = new ClearCiviliansEdit(snapshot);

        scenario.clearCivilians();
        editor.setChanged();
        editor.updateOverlays();
        editor.addEdit(clearEdit);
    }

    private class ClearCiviliansEdit extends AbstractUndoableEdit {
        private final ScenarioSnapshot snapshot;

        public ClearCiviliansEdit(ScenarioSnapshot snapshot) {
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
            editor.getScenario().clearCivilians();
            editor.updateOverlays();
        }
    }
}
