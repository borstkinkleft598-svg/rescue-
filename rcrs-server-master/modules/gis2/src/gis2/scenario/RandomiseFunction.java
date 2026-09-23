package gis2.scenario;

import gis2.GisScenario;

import javax.swing.undo.AbstractUndoableEdit;
import java.util.Random;

/**
 * Function for randomizing a scenario.
 */
public class RandomiseFunction extends AbstractFunction {
  private final Random random;

  /**
   * Construct a randomizer function.
   *
   * @param editor The editor instance.
   */
  public RandomiseFunction(ScenarioEditor editor) {
    super(editor);
    random = new Random();
  }

  @Override
  public String getName() {
    return "Randomise";
  }

  @Override
  public void execute() {
    RandomScenarioGenerator generator = new RandomScenarioGenerator();
    GisScenario randomisedScenario = generator.makeRandomScenario(editor.getMap(), random);
    ScenarioSnapshot beforeSnapshot = new ScenarioSnapshot(editor.getScenario());
    ScenarioSnapshot afterSnapshot = new ScenarioSnapshot(randomisedScenario);
    RandomiseEdit randomiseEdit = new RandomiseEdit(beforeSnapshot, afterSnapshot);

    afterSnapshot.restore(editor.getScenario());
    editor.setChanged();
    editor.updateOverlays();
    editor.addEdit(randomiseEdit);
  }

  private class RandomiseEdit extends AbstractUndoableEdit {
    private final ScenarioSnapshot beforeSnapshot;
    private final ScenarioSnapshot afterSnapshot;

    public RandomiseEdit(ScenarioSnapshot beforeSnapshot, ScenarioSnapshot afterSnapshot) {
      this.beforeSnapshot = beforeSnapshot;
      this.afterSnapshot = afterSnapshot;
    }

    @Override
    public void undo() {
      super.undo();
      beforeSnapshot.restore(editor.getScenario());
      editor.updateOverlays();
    }

    @Override
    public void redo() {
      super.redo();
      afterSnapshot.restore(editor.getScenario());
      editor.updateOverlays();
    }
  }
}