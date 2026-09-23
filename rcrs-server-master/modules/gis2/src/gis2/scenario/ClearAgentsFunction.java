package gis2.scenario;

import gis2.GisScenario;

import javax.swing.undo.AbstractUndoableEdit;

/**
 * Function for removing all agents.
 */
public class ClearAgentsFunction extends AbstractFunction {
  /**
   * Construct a clear agents function.
   *
   * @param editor The editor instance.
   */
  public ClearAgentsFunction(ScenarioEditor editor) {
    super(editor);
  }

  @Override
  public String getName() {
    return "Remove Agents";
  }

  @Override
  public void execute() {
    GisScenario scenario = editor.getScenario();
    ScenarioSnapshot snapshot = new ScenarioSnapshot(scenario);
    ClearAgentsEdit clearEdit = new ClearAgentsEdit(snapshot);

    scenario.clearAgents();
    editor.setChanged();
    editor.updateOverlays();
    editor.addEdit(clearEdit);
  }

  private class ClearAgentsEdit extends AbstractUndoableEdit {
    private final ScenarioSnapshot snapshot;

    public ClearAgentsEdit(ScenarioSnapshot snapshot) {
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
