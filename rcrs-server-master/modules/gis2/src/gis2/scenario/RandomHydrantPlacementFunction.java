package gis2.scenario;

import gis2.GisScenario;

import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.undo.AbstractUndoableEdit;

import maps.gml.GMLMap;
import maps.gml.GMLShape;

/**
 * Function for placing agents.
 */
public class RandomHydrantPlacementFunction extends AbstractFunction {
  private final Random random;

  /**
   * Construct a place agents function.
   *
   * @param editor The editor instance.
   */
  public RandomHydrantPlacementFunction(ScenarioEditor editor) {
    super(editor);
    random = new Random();
  }

  @Override
  public String getName() {
    return "Random Hydrant Placement";
  }

  @Override
  public void execute() {
    JPanel panel = new JPanel(new GridLayout(3, 2));
    JSpinner numberSpinner = new JSpinner(new SpinnerNumberModel(1, 0, Integer.MAX_VALUE, 1));
    panel.add(new JLabel("Number: suggested number:" + calcSuggestedNumber()));
    panel.add(numberSpinner);

    if (JOptionPane.showConfirmDialog(null, panel, getName(), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;

    List<GMLShape> candidateRoads = new ArrayList<>(editor.getMap().getRoads());
    GisScenario scenario = editor.getScenario();
    ScenarioSnapshot beforeSnapshot = new ScenarioSnapshot(scenario);
    int number = (int) numberSpinner.getValue();

    Collections.shuffle(candidateRoads, random);
    for (int i = 0; i < number; ++i) {
      int location = candidateRoads.get(i).getID();
      scenario.addHydrant(location);
    }

    ScenarioSnapshot afterSnapshot = new ScenarioSnapshot(scenario);
    RandomHydrantPlacementEdit randomEdit = new RandomHydrantPlacementEdit(beforeSnapshot, afterSnapshot);

    editor.setChanged();
    editor.updateOverlays();
    editor.addEdit(randomEdit);
  }

  private int calcSuggestedNumber() {
    GMLMap map = editor.getMap();
    double height = (map.getMaxX() - map.getMinX());
    double width = (map.getMaxY() - map.getMinY());
    return (int) Math.ceil(height * width / 30000);
  }

  private class RandomHydrantPlacementEdit extends AbstractUndoableEdit {
    private final ScenarioSnapshot beforeSnapshot;
    private final ScenarioSnapshot afterSnapshot;

    public RandomHydrantPlacementEdit(ScenarioSnapshot beforeSnapshot, ScenarioSnapshot afterSnapshot) {
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
