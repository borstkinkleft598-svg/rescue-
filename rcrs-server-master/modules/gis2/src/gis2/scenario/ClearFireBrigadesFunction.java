package gis2.scenario;

import gis2.GisScenario;

import javax.swing.undo.AbstractUndoableEdit;

/**
 * Function for removing all fire brigades.
 */
public class ClearFireBrigadesFunction extends AbstractFunction {
    /**
     * Construct a clear fire brigades function.
     *
     * @param editor The editor instance.
     */
    public ClearFireBrigadesFunction(ScenarioEditor editor) {
        super(editor);
    }

    @Override
    public String getName() {
        return "Remove Fire Brigades";
    }

    @Override
    public void execute() {
        GisScenario scenario = editor.getScenario();
        ScenarioSnapshot snapshot = new ScenarioSnapshot(scenario);
        ClearFireBrigadesEdit clearEdit = new ClearFireBrigadesEdit(snapshot);

        scenario.clearFireBrigades();
        editor.setChanged();
        editor.updateOverlays();
        editor.addEdit(clearEdit);
    }

    private class ClearFireBrigadesEdit extends AbstractUndoableEdit {
        private final ScenarioSnapshot snapshot;

        public ClearFireBrigadesEdit(ScenarioSnapshot snapshot) {
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
            editor.getScenario().clearFireBrigades();
            editor.updateOverlays();
        }
    }
}
