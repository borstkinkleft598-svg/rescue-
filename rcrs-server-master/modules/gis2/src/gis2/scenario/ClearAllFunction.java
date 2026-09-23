package gis2.scenario;

import gis2.GisScenario;

import javax.swing.undo.AbstractUndoableEdit;

/**
 * Function for removing all agents, fires, civilians and refuges.
 */
public class ClearAllFunction extends AbstractFunction {
  /**
   * Construct a clear all function.
   *
   * @param editor The editor instance.
   */
  public ClearAllFunction(ScenarioEditor editor) {
    super(editor);
  }

  @Override
  public String getName() {
    return "Remove All";
  }

  @Override
  public void execute() {
    GisScenario scenario = editor.getScenario();
    ScenarioSnapshot snapshot = new ScenarioSnapshot(scenario);
    ClearAllEdit clearEdit = new ClearAllEdit(snapshot);

    scenario.clearAll();
    editor.setChanged();
    editor.updateOverlays();
    editor.addEdit(clearEdit);
  }

  private class ClearAllEdit extends AbstractUndoableEdit {
    private final ScenarioSnapshot snapshot;

    public ClearAllEdit(ScenarioSnapshot snapshot) {
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
      editor.getScenario().clearAgents();
      editor.updateOverlays();
    }
  }
}
