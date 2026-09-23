package gis2.scenario;

import gis2.GisScenario;

import javax.swing.undo.AbstractUndoableEdit;

/**
 * Function for removing all fires.
 */
public class ClearFiresFunction extends AbstractFunction {
  /**
   * Construct a clear fires function.
   *
   * @param editor The editor instance.
   */
  public ClearFiresFunction(ScenarioEditor editor) {
    super(editor);
  }

  @Override
  public String getName() {
    return "Remove Fires";
  }

  @Override
  public void execute() {
    GisScenario scenario = editor.getScenario();
    ScenarioSnapshot snapshot = new ScenarioSnapshot(scenario);
    ClearFiresEdit clearEdit = new ClearFiresEdit(snapshot);

    scenario.clearFires();
    editor.setChanged();
    editor.updateOverlays();
    editor.addEdit(clearEdit);
  }

  private class ClearFiresEdit extends AbstractUndoableEdit {
    private final ScenarioSnapshot snapshot;

    public ClearFiresEdit(ScenarioSnapshot snapshot) {
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
      editor.getScenario().clearFires();
      editor.updateOverlays();
    }
  }
}
