package gis2.scenario;

import javax.swing.Icon;

/**
 * Interface for a scenario editing tool.
 */
public interface Tool {
  /**
   * Get the name of this tool.
   *
   * @return The name of the tool.
   */
  String getName();

  /**
   * Get the icon of this tool.
   *
   * @return The icon of the tool.
   */
  Icon getIcon();

  /**
   * Activate this tool.
   */
  void activate();

  /**
   * Deactivate this tool.
   */
  void deactivate();
}