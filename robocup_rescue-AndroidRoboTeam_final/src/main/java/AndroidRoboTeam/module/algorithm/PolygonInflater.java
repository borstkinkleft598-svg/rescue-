package AndroidRoboTeam.module.algorithm;

import rescuecore2.standard.entities.Blockade;

import java.awt.*;

public class PolygonInflater {

	public static Polygon expandApexes(Blockade blockade, int size) {

		if (!blockade.isApexesDefined() || blockade.getApexes().length < 6
				|| !blockade.isXDefined() || !blockade.isYDefined()) {
			return null;
		}

		int cx = blockade.getX();
		int cy = blockade.getY();
		Polygon polygon = new Polygon();
		int[] apexes = blockade.getApexes();

		for (int i = 0; i < apexes.length; i += 2) {
			int x = apexes[i];
			int y = apexes[i + 1];
			int dx = x - cx;
			int dy = y - cy;
			double distance = getDistance(cx, cy, x, y);
			double coefficient = (distance + size) / distance;

			double newX = cx + (dx * coefficient);
			double newY = cy + (dy * coefficient);
			polygon.addPoint((int) newX, (int) newY);

			x = (apexes[i] + apexes[(i + 2) % apexes.length]) / 2;
			y = (apexes[i + 1] + apexes[(i + 3) % apexes.length]) / 2;
			dx = x - cx;
			dy = y - cy;
			distance = getDistance(cx, cy, x, y);
			coefficient = (distance + size) / distance;

			newX = cx + (dx * coefficient);
			newY = cy + (dy * coefficient);
			polygon.addPoint((int) newX, (int) newY);
		}
		return polygon;
	}

	private static double getDistance(int x1, int y1, int x2, int y2) {
		return Math.hypot((x1 - x2), (y1 - y2));
	}
}