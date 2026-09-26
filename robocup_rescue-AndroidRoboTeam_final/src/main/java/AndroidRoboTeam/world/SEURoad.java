package AndroidRoboTeam.world;

import AndroidRoboTeam.module.algorithm.PolygonInflater;
import AndroidRoboTeam.module.algorithm.Ruler;
import AndroidRoboTeam.module.algorithm.GraphService;
import AndroidRoboTeam.module.algorithm.MyEdge;
import AndroidRoboTeam.module.algorithm.Node;
import adf.core.agent.info.AgentInfo;
import rescuecore2.misc.Pair;
import rescuecore2.misc.geometry.GeometryTools2D;
import rescuecore2.misc.geometry.Line2D;
import rescuecore2.misc.geometry.Point2D;
import rescuecore2.standard.entities.*;
import rescuecore2.worldmodel.EntityID;

import java.awt.*;
import java.awt.geom.Area;
import java.util.List;
import java.util.*;

public class SEURoad {
	private double CLEAR_WIDTH;

	private Road selfRoad;
	private EntityID selfId;
	private SEUWorldService world;
	private GraphService graph;
	private AgentInfo agentInfo;

	private SEULineOfSightPerception lineOfSightPerception;
	private List<EntityID> observableAreas;

	private List<SEUEdge> SEUEdges;
	private List<SEUBlockade> SEUBlockades = new ArrayList<>();

	private Pair<Line2D, Line2D> pfClearLines = null;
	private Area pfClearArea = null;

	private int lastUpdateTime = 0;
	private Polygon polygon;
	private int passablyLastResetTime = 0;
	private List<SEULineOfSightPerception.SEURay> lineOfSight;
	private Set<EntityID> visibleFrom;

	private Line2D roadCenterLine = null;

	private boolean isEntrance = false;
	private boolean isRoadCenterBlocked = false;
	private static final double COLLINEAR_THRESHOLD = 1.0E-3D;

	public SEURoad(Road road, SEUWorldService world) {
		this.world = world;
		this.graph = world.getGraph();
		this.agentInfo = world.getAgentInfo();
		this.selfRoad = road;
		this.selfId = road.getID();
		this.lineOfSightPerception = new SEULineOfSightPerception(world);
		this.SEUEdges = createSEUEdges();

		this.CLEAR_WIDTH = world.getConfig().repairRad;
		this.lineOfSight = new ArrayList<>();
		this.visibleFrom = new HashSet<>();
		createPolygon();
	}

	public SEURoad(EntityID roadId, List<SEUEdge> edges) {
		this.selfId = roadId;
		this.SEUEdges = edges;
	}

	public void update() {
		lastUpdateTime = world.getTime();
		if (selfRoad.isBlockadesDefined()) {
			for (SEUEdge next : SEUEdges) {
				next.setOpenPart(next.getLine());
				next.setBlocked(false);
			}
			for (MyEdge myEdge : graph.getMyEdgesInArea(selfId)) {
				myEdge.setPassable(true);
			}
			this.SEUBlockades = createSEUBlockade();
			if (selfRoad.isBlockadesDefined()) {
				for (SEUEdge SEUEdge : SEUEdges) {
					if (SEUEdge.isPassable()) {
						SEUEdge.setOpenPart(SEUEdge.getLine());
						List<SEUBlockade> blockedStart = new ArrayList<>();
						List<SEUBlockade> blockedEnd = new ArrayList<>();
						for (SEUBlockade SEUBlockade : SEUBlockades) {

							if (Ruler.getDistance(SEUBlockade.getPolygon(),
									SEUEdge.getStart()) < SEUConstants.AGENT_PASSING_THRESHOLD_SMALL) {
								blockedStart.add(SEUBlockade);
							}
							if (Ruler.getDistance(SEUBlockade.getPolygon(),
									SEUEdge.getEnd()) < SEUConstants.AGENT_PASSING_THRESHOLD_SMALL) {
								blockedEnd.add(SEUBlockade);
							}
						}
						setSEUEdgeOpenPart(SEUEdge);
						if (SEUBlockades.size() == 1) {
							if (Util.containsEach(blockedEnd, blockedStart)) {
								SEUBlockades.get(0).addBlockedEdges(SEUEdge);
								SEUEdge.setBlocked(true);
							}
						} else {
							for (SEUBlockade block1 : blockedStart) {
								for (SEUBlockade block2 : blockedEnd) {
									if (Util.isPassable(block1.getPolygon(), block2.getPolygon(),
											SEUConstants.AGENT_PASSING_THRESHOLD_SMALL)) {
										SEUEdge.setBlocked(true);
										block1.addBlockedEdges(SEUEdge);
										block2.addBlockedEdges(SEUEdge);
									}

								}
							}
						}
					} else {
						for (SEUBlockade SEUBlockade : SEUBlockades) {
							double distance = Ruler.getDistance(SEUEdge.getLine(), SEUBlockade.getPolygon());

							if (distance < SEUConstants.AGENT_PASSING_THRESHOLD_SMALL) {
								SEUEdge.setBlocked(true);
								SEUBlockade.addBlockedEdges(SEUEdge);
							}

						}
					}
				}
			}
			updateNodePassably();
			updateMyEdgePassably();
		}
	}

	private boolean isTimeToResetPassably() {
		int resetTime = SEUConstants.ROAD_PASSABLY_RESET_TIME_IN_MEDIUM_MAP;
		return passablyLastResetTime <= lastUpdateTime && agentInfo.getTime() - passablyLastResetTime > resetTime &&
				agentInfo.getTime() - lastUpdateTime > resetTime;
	}

	public void resetPassably() {
		boolean isSeen = world.getRoadsSeen().contains(selfId);

		if (agentInfo.me() instanceof PoliceForce || passablyLastResetTime > lastUpdateTime) {
			return;
		}
		if (isTimeToResetPassably()) {
			reset();
		}
	}

	private void reset() {
		if (!(agentInfo.me() instanceof Human)) {
			return;
		}
		for (SEUEdge SEUEdge : SEUEdges) {
			SEUEdge.setBlocked(false);
			SEUEdge otherEdge = SEUEdge.getOtherSideEdge();
			SEUEdge.setOpenPart(SEUEdge.getLine());
			if (otherEdge != null) {
				SEURoad SEURoad = world.getSEURoad(SEUEdge.getNeighbours().second());
				if (SEURoad.getLastUpdateTime() < lastUpdateTime) {
					otherEdge.setOpenPart(otherEdge.getLine());
				}
			}
			rescuecore2.standard.entities.Area neighbour = (rescuecore2.standard.entities.Area) world
					.getEntity(SEUEdge.getNeighbours().second());
			if (SEUEdge.isPassable()) {
				Node node = graph.getNode((SEUEdge.getMiddlePoint()));
				if (node == null) {
					System.out.println("node == null in " + selfId);
					continue;
				}
				if (neighbour instanceof Road) {
					SEURoad SEURoad = world.getSEURoad(neighbour.getID());
					SEUEdge neighbourEdge = SEURoad.getSEUEdgeInPoint(SEUEdge.getMiddlePoint());
					if (neighbourEdge != null && !neighbourEdge.isBlocked()) {
						node.setPassable(true, agentInfo.getTime());
					}
				} else {
					node.setPassable(true, agentInfo.getTime());
				}
			}
		}
		for (MyEdge myEdge : graph.getMyEdgesInArea(selfId)) {
			myEdge.setPassable(true);
		}
		passablyLastResetTime = agentInfo.getTime();
	}

	private void updateNodePassably() {
		for (SEUEdge SEUEdge : SEUEdges) {
			if (SEUEdge.isPassable()) {
				Node node = graph.getNode(SEUEdge.getMiddlePoint());
				if (node == null) {
					continue;
				}
				if (SEUEdge.isBlocked() || SEUEdge.getOtherSideEdge().isBlocked()) {
					node.setPassable(false, agentInfo.getTime());
				} else {
					node.setPassable(true, agentInfo.getTime());
				}
			}
		}
	}

	private void updateMyEdgePassably() {
		for (int i = 0; i < SEUEdges.size() - 1; i++) {
			SEUEdge edge1 = SEUEdges.get(i);
			if (!edge1.isPassable()) {
				continue;
			}
			for (int j = i + 1; j < SEUEdges.size(); j++) {
				SEUEdge edge2 = SEUEdges.get(j);
				if (!edge2.isPassable()) {
					continue;
				}
				setMyEdgePassably(edge1, edge2, isPassable(edge1, edge2));
			}
		}
	}

	private void setMyEdgePassably(SEUEdge edge1, SEUEdge edge2, boolean passably) {
		if (!(agentInfo.me() instanceof Human)
				|| !edge1.getNeighbours().second().equals(edge2.getNeighbours().second())) {
			return;
		}
		Node node1 = graph.getNode(edge1.getMiddlePoint());
		Node node2 = graph.getNode(edge2.getMiddlePoint());
		MyEdge myEdge = graph.getMyEdge(selfId, new Pair<>(node1, node2));
		if (myEdge != null) {
			myEdge.setPassable(passably);
		}
	}

	public boolean isPassable(SEUEdge from, SEUEdge to) {
		if (!from.getNeighbours().second().equals(to.getNeighbours().second())) {
			System.err.println("this 2 edge is not in a same area!!!");
			return false;
		}
		if (from.isBlocked() || to.isBlocked())
			return false;
		Pair<List<SEUEdge>, List<SEUEdge>> edgesBetween = getEdgesBetween(from, to, false);

		int count = SEUBlockades.size();
		List<SEUEdge> blockedEdges = new ArrayList<>();
		if (count == 1) {
			blockedEdges.addAll(SEUBlockades.get(0).getBlockedEdges());
		} else if (count > 1) {
			for (int i = 0; i < count - 1; i++) {
				SEUBlockade block1 = SEUBlockades.get(i);
				for (int j = i + 1; j < count; j++) {
					SEUBlockade block2 = SEUBlockades.get(j);
					if (isBlockedTwoSides(block1, edgesBetween)) {
						return false;
					}
					if (isBlockedTwoSides(block2, edgesBetween)) {
						return false;
					}
					if (isInSameSide(block1, block2, edgesBetween)) {
						continue;
					}
					if (Util.isPassable(block1.getPolygon(), block2.getPolygon(),
							SEUConstants.AGENT_PASSING_THRESHOLD)) {
						blockedEdges.removeAll(block1.getBlockedEdges());
						blockedEdges.addAll(block1.getBlockedEdges());
						blockedEdges.removeAll(block2.getBlockedEdges());
						blockedEdges.addAll(block2.getBlockedEdges());
					}
				}
			}
		} else if (count == 0) {
			return !(from.isBlocked() || to.isBlocked());
		}
		return !(Util.containsEach(blockedEdges, edgesBetween.first())
				&& Util.containsEach(blockedEdges, edgesBetween.second()));
	}

	private Pair<List<SEUEdge>, List<SEUEdge>> getEdgesBetween(SEUEdge edge1, SEUEdge edge2, boolean justImPassable) {
		List<SEUEdge> leftSideEdges = new ArrayList<>();
		List<SEUEdge> rightSideEdges = new ArrayList<>();
		Point2D startPoint1 = edge1.getStart();
		Point2D endPoint1 = edge1.getEnd();
		Point2D startPoint2 = edge2.getStart();
		Point2D endPoint2 = edge2.getEnd();

		boolean finishedLeft = false;
		boolean finishedRight = false;
		for (SEUEdge edge : SEUEdges) {
			if (finishedLeft && finishedRight)
				break;
			for (SEUEdge ed : SEUEdges) {
				if (finishedLeft && finishedRight)
					break;
				if (ed.equals(edge1) || ed.equals(edge2)) {
					continue;
				}
				if (startPoint1.equals(startPoint2) || startPoint1.equals(endPoint2)) {
					finishedLeft = true;
				}
				if (endPoint1.equals(startPoint2) || endPoint1.equals(endPoint2)) {
					finishedRight = true;
				}

				if (ed.getStart().equals(startPoint1) && !finishedLeft && !leftSideEdges.contains(ed)) {
					startPoint1 = ed.getEnd();
					if (!justImPassable || !ed.isPassable())
						leftSideEdges.add(ed);
					continue;
				}
				if (ed.getEnd().equals(startPoint1) && !finishedLeft && !leftSideEdges.contains(ed)) {
					startPoint1 = ed.getStart();
					if (!justImPassable || !ed.isPassable())
						leftSideEdges.add(ed);
					continue;
				}
				if (ed.getStart().equals(endPoint1) && !finishedRight && !rightSideEdges.contains(ed)) {
					endPoint1 = ed.getEnd();
					if (!justImPassable || !ed.isPassable())
						rightSideEdges.add(ed);
					continue;
				}
				if (ed.getEnd().equals(endPoint1) && !finishedRight && !rightSideEdges.contains(ed)) {
					endPoint1 = ed.getStart();
					if (!justImPassable || !ed.isPassable())
						rightSideEdges.add(ed);
					continue;
				}
			}
		}
		return new Pair<>(leftSideEdges, rightSideEdges);
	}

	private boolean isInSameSide(SEUBlockade block1, SEUBlockade block2,
			Pair<List<SEUEdge>, List<SEUEdge>> edgesBetween) {
		return edgesBetween.first().containsAll(block1.getBlockedEdges()) &&
				edgesBetween.first().containsAll(block2.getBlockedEdges()) ||
				edgesBetween.second().containsAll(block1.getBlockedEdges()) &&
						edgesBetween.second().containsAll(block2.getBlockedEdges());
	}

	private boolean isBlockedTwoSides(SEUBlockade block1, Pair<List<SEUEdge>, List<SEUEdge>> edgesBetween) {
		return Util.containsEach(edgesBetween.first(), block1.getBlockedEdges()) &&
				Util.containsEach(edgesBetween.second(), block1.getBlockedEdges());
	}

	private void createPolygon() {
		int[] apexList = selfRoad.getApexList();
		polygon = Util.getPolygon(apexList);
	}

	private List<SEUEdge> createSEUEdges() {
		List<SEUEdge> result = new ArrayList<>();

		for (Edge next : selfRoad.getEdges()) {
			result.add(new SEUEdge(world, next, selfRoad.getID()));
		}

		return result;
	}

	private List<SEUBlockade> createSEUBlockade() {
		List<SEUBlockade> result = new ArrayList<>();
		if (!selfRoad.isBlockadesDefined())
			return result;
		for (EntityID next : selfRoad.getBlockades()) {
			StandardEntity entity = world.getEntity(next, StandardEntity.class);
			if (entity == null)
				continue;
			if (!(entity instanceof Blockade))
				continue;
			Blockade bloc = (Blockade) entity;
			if (!bloc.isApexesDefined())
				continue;
			if (bloc.getApexes().length < 6)
				continue;
			result.add(new SEUBlockade(next, world));
		}

		return result;
	}

	private void setSEUEdgeOpenPart(SEUEdge edge) {
		List<Pair<Point2D, Point2D>> blockadePartPoints = new ArrayList<>();
		Point2D edgeStart = edge.getStart();
		Point2D edgeEnd = edge.getEnd();
		boolean isBlocked = false;
		List<SEUBlockade> totalBlockades = new ArrayList<>(SEUBlockades);
		SEURoad neighborRoad = world.getSEURoad(edge.getNeighbours().first());
		if (neighborRoad != null) {
			List<SEUBlockade> neighborBlockades = neighborRoad.getSEUBlockades();
			if (neighborBlockades != null) {
				totalBlockades.addAll(neighborBlockades);
			}
		}
		for (SEUBlockade blockade : totalBlockades) {
			boolean isStartBlocked = false;
			boolean isEndBlocked = false;
			if (blockade.getPolygon().contains(selfRoad.getX(), selfRoad.getY())) {
				isRoadCenterBlocked = true;
			}
			Polygon expand = Util.scaleBySize(blockade.getPolygon(), 10);
			if (expand.contains(edgeStart.getX(), edgeStart.getY())) {
				isStartBlocked = true;
			}
			if (expand.contains(edgeEnd.getX(), edgeEnd.getY())) {
				isEndBlocked = true;
			}

			Set<Point2D> intersections = Util.getIntersections(expand, edge.getLine());

			if (isStartBlocked && isEndBlocked) {
				isBlocked = true;
				blockadePartPoints.add(new Pair<>(edgeStart, edgeEnd));
				break;
			} else if (isStartBlocked) {
				double maxDistance = Double.MIN_VALUE, distance;
				Point2D blockadePartEnd = null;
				for (Point2D point : intersections) {
					distance = distance(point, edgeStart);
					if (distance > maxDistance) {
						maxDistance = distance;
						blockadePartEnd = point;
					}
				}
				if (blockadePartEnd != null) {
					blockadePartPoints.add(new Pair<>(edgeStart, blockadePartEnd));
				}
			} else if (isEndBlocked) {
				double maxDistance = Double.MIN_VALUE, distance;
				double mx = 1, haha;
				Point2D blockadePartStart = null;
				for (Point2D point : intersections) {
					distance = distance(point, edgeEnd);
					if (distance > maxDistance) {
						maxDistance = distance;
						blockadePartStart = point;
					}
				}
				if (blockadePartStart != null) {
					blockadePartPoints.add(new Pair<>(blockadePartStart, edgeEnd));
				}

			} else {
				if (!intersections.isEmpty() && intersections.size() > 1) {
					Pair<Point2D, Point2D> twoFarthestPoints = getTwoFarthestPoints(intersections);
					double distanceToFirst = Ruler.getDistance(edgeStart, twoFarthestPoints.first());
					double distanceToSecond = Ruler.getDistance(edgeStart, twoFarthestPoints.second());
					if (distanceToFirst < distanceToSecond) {
						blockadePartPoints.add(new Pair<>(twoFarthestPoints.first(), twoFarthestPoints.second()));
					} else {
						blockadePartPoints.add(new Pair<>(twoFarthestPoints.second(), twoFarthestPoints.first()));
					}
				}
			}
		}

		if (isBlocked) {
			edge.setBlocked(true);
			edge.setOpenPart(null);
		} else {
			List<Line2D> openPartLines = calcOpenPart(blockadePartPoints, edgeStart, edgeEnd);
			if (!openPartLines.isEmpty()) {
				edge.setOpenPart(openPartLines.get(openPartLines.size() - 1));
				if (Ruler.getLength(edge.getOpenPart()) <= SEUConstants.AGENT_MINIMUM_PASSING_THRESHOLD) {
					edge.setBlocked(true);
				} else {
					edge.setBlocked(false);
				}
			}
		}
	}

	private Pair<Point2D, Point2D> getTwoFarthestPoints(Set<Point2D> points) {
		double maxDistance = Double.MIN_VALUE;
		Point2D p1 = null;
		Point2D p2 = null;
		for (Point2D p3 : points) {
			for (Point2D p4 : points) {
				double distance = Ruler.getDistance(p3, p4);
				if (distance > maxDistance) {
					maxDistance = distance;
					p1 = p3;
					p2 = p4;
				}
			}
		}
		return new Pair<>(p1, p2);
	}

	private List<Line2D> calcOpenPart(List<Pair<Point2D, Point2D>> blockadePartPoints, Point2D edgeStart,
			Point2D edgeEnd) {
		blockadePartPoints.sort(new Util.DistanceComparator(edgeStart));
		blockadePartPoints.add(0, new Pair<>(null, edgeStart));
		blockadePartPoints.add(blockadePartPoints.size(), new Pair<>(edgeEnd, null));
		List<Line2D> openPartLines = new ArrayList<>();
		for (int i = 0; i < blockadePartPoints.size() - 1; i++) {
			if (Ruler.getDistance(blockadePartPoints.get(i).second(), edgeStart) < Ruler
					.getDistance(blockadePartPoints.get(i + 1).first(), edgeStart)) {
				openPartLines
						.add(new Line2D(blockadePartPoints.get(i).second(), blockadePartPoints.get(i + 1).first()));
			}

		}
		openPartLines.sort(new Util.LengthComparator());

		return openPartLines;
	}

	public Road getSelfRoad() {
		return selfRoad;
	}

	public EntityID getId() {
		return this.selfId;
	}

	public List<EntityID> getObservableAreas() {
		if (observableAreas == null || observableAreas.isEmpty()) {
			observableAreas = lineOfSightPerception.getVisibleAreas(getId());
		}
		return observableAreas;
	}

	public SEUEdge getSEUEdgeInPoint(Point2D middlePoint) {
		for (SEUEdge next : SEUEdges) {
			if (contains(next.getLine(), middlePoint, 1.0))
				return next;
		}

		return null;
	}

	public boolean isNeedlessToClear() {
		double buildingEntranceLength = 0.0;
		double maxUnpassableEdgeLength = Double.MIN_VALUE;
		double length;

		Edge buildingEntrance = null;

		for (Edge next : selfRoad.getEdges()) {
			if (next.isPassable()) {
				StandardEntity entity = world.getEntity(next.getNeighbour(), StandardEntity.class);
				if (entity instanceof Building) {
					buildingEntranceLength = distance(next.getStart(), next.getEnd());
					buildingEntrance = next;
				}
			} else {
				length = distance(next.getStart(), next.getEnd());
				if (length > maxUnpassableEdgeLength) {
					maxUnpassableEdgeLength = length;
				}
			}
		}

		if (buildingEntrance == null)
			return true;
		double rad = buildingEntranceLength + maxUnpassableEdgeLength;
		Area entranceArea = entranceArea(buildingEntrance.getLine(), rad);

		Set<EntityID> blockadeIds = new HashSet<>();

		if (selfRoad.isBlockadesDefined()) {
			blockadeIds.addAll(selfRoad.getBlockades());
		}

		for (EntityID next : selfRoad.getNeighbours()) {
			StandardEntity entity = world.getEntity(next, StandardEntity.class);
			if (entity instanceof Road) {
				Road road = (Road) entity;
				if (road.isBlockadesDefined())
					blockadeIds.addAll(road.getBlockades());
			}
		}

		for (EntityID next : blockadeIds) {
			StandardEntity entity = world.getEntity(next, StandardEntity.class);
			if (entity == null)
				continue;
			if (!(entity instanceof Blockade))
				continue;
			Blockade blockade = (Blockade) entity;
			if (!blockade.isApexesDefined())
				continue;

			if (blockade.getApexes().length < 6)
				continue;
			Polygon po = Util.getPolygon(blockade.getApexes());
			Area blocArea = new Area(po);
			blocArea.intersect(entranceArea);

			if (!blocArea.getPathIterator(null).isDone())
				return false;
		}
		return true;
	}

	private Area entranceArea(Line2D line, double rad) {
		double theta = Math.atan2(line.getEndPoint().getY() - line.getOrigin().getY(),
				line.getEndPoint().getX() - line.getOrigin().getX());
		theta = theta - Math.PI / 2;
		while (theta > Math.PI || theta < -Math.PI) {
			if (theta > Math.PI)
				theta -= 2 * Math.PI;
			else
				theta += 2 * Math.PI;
		}
		int x = (int) (rad * Math.cos(theta)), y = (int) (rad * Math.sin(theta));

		Polygon polygon = new Polygon();
		polygon.addPoint((int) (line.getOrigin().getX() + x), (int) (line.getOrigin().getY() + y));
		polygon.addPoint((int) (line.getEndPoint().getX() + x), (int) (line.getEndPoint().getY() + y));
		polygon.addPoint((int) (line.getEndPoint().getX() - x), (int) (line.getEndPoint().getY() - y));
		polygon.addPoint((int) (line.getOrigin().getX() - x), (int) (line.getOrigin().getY() - y));

		return new Area(polygon);
	}

	public List<SEUEdge> getSEUEdgesTo(EntityID neighbourId) {
		List<SEUEdge> result = new ArrayList<>();

		for (SEUEdge next : SEUEdges) {
			if (next.isPassable() && next.getNeighbours().first().equals(neighbourId)) {
				result.add(next);
			}
		}

		return result;
	}

	public Set<SEUEdge> getPassableEdges() {
		Set<SEUEdge> result = new HashSet<>();

		for (SEUEdge next : SEUEdges) {
			if (next.isPassable() && !next.isBlocked()) {
				result.add(next);
			}
		}

		return result;
	}

	public boolean isRoadCenterBlocked() {
		return this.isRoadCenterBlocked;
	}

	public boolean isPassable() {
		if (isAllEdgePassable() || isOneEdgeUnpassable()) {

			return getPassableEdges().size() > 1;
		} else {
			List<SEUBlockade> blockades = new LinkedList<>(getSEUBlockades());

			for (SEUPoint next : getEscapePoint(this, 500)) {
				blockades.removeAll(next.getRelateBlockade());
			}

			if (blockades.isEmpty())
				return true;
			return false;
		}
	}

	public boolean isPassableForPF() {
		if (isAllEdgePassable() || isOneEdgeUnpassable()) {
			return getPassableEdges().size() > 1;
		}
		boolean isPassable = true;
		List<Polygon> blockadePolygons = getBlockadePolygons(10);
		for (Polygon polygon : blockadePolygons) {
			for (SEUEdge edge : SEUEdges) {
				SEUEdge oppositeEdge = getOppositeEdge(edge);
				if (Util.hasIntersectLine(polygon, Util.improveLineBothSides(edge.getLine(), 300000)) &&
						Util.hasIntersectLine(polygon, Util.improveLineBothSides(oppositeEdge.getLine(), 300000))) {
					isPassable = false;
					break;
				}
			}
			if (!isPassable) {
				break;
			}
		}
		List<SEUBlockade> blockades = new LinkedList<>(getSEUBlockades());

		for (SEUPoint next : getEscapePoint(this, 500)) {
			blockades.removeAll(next.getRelateBlockade());
		}

		return blockades.isEmpty();
	}

	public boolean isEntrancePassable() {
		return false;
	}

	public boolean isAllEdgePassable() {
		for (SEUEdge next : SEUEdges) {
			if (!next.isPassable())
				return false;
		}
		return true;
	}

	public boolean isOneEdgeUnpassable() {
		int count = 0;
		for (SEUEdge next : SEUEdges) {
			if (!next.isPassable())
				count++;
		}

		if (count == 1)
			return true;
		else
			return false;
	}

	private boolean contains(Line2D line, Point2D point, double threshold) {

		double pos = java.awt.geom.Line2D.ptSegDist(line.getOrigin().getX(), line.getOrigin().getY(),
				line.getEndPoint().getX(), line.getEndPoint().getY(), point.getX(), point.getY());
		if (pos <= threshold)
			return true;

		return false;
	}

	private double distance(Point2D first, Point2D second) {
		return Math.hypot(first.getX() - second.getX(), first.getY() - second.getY());
	}

	public List<SEUPoint> getEscapePoint(SEURoad road, int threshold) {
		List<SEUPoint> m_p_points = new ArrayList<>();

		for (SEUBlockade next : road.getSEUBlockades()) {
			if (next == null)
				continue;
			Polygon expan = next.getPolygon();

			for (SEUEdge SEUEdge : road.getSEUEdges()) {
				SEUPoint p = findPoints(SEUEdge, expan, next);
				if (p == null) {
					continue;
				} else {
					m_p_points.add(p);
				}
			}
		}

		filter(road, m_p_points, threshold);
		return m_p_points;
	}

	private SEUPoint findPoints(SEUEdge SEUEdge, Polygon expan, SEUBlockade next) {
		if (SEUEdge.isPassable()) {

		} else {
			if (hasIntersection(expan, SEUEdge.getLine())) {
				return null;
			}
			double minDistance = Double.MAX_VALUE, distance;
			Pair<Integer, Integer> minDistanceVertex = null;

			for (Pair<Integer, Integer> vertex : next.getVertexesList()) {

				Pair<Double, Boolean> dis = ptSegDistSq(SEUEdge.getStart().getX(),
						SEUEdge.getStart().getY(), SEUEdge.getEnd().getX(),
						SEUEdge.getEnd().getY(), vertex.first(), vertex.second());

				if (dis.second().booleanValue())
					continue;
				distance = dis.first().doubleValue();

				if (distance < minDistance) {
					minDistance = distance;
					minDistanceVertex = vertex;
				}
			}

			if (minDistanceVertex == null)
				return null;

			Point2D perpendicular = GeometryTools2D.getClosestPoint(SEUEdge.getLine(),
					new Point2D(minDistanceVertex.first(), minDistanceVertex.second()));

			Point middlePoint = getMiddle(minDistanceVertex, perpendicular);

			Point2D vertex = new Point2D(minDistanceVertex.first(), minDistanceVertex.second());
			Point2D perpenPoint = new Point2D(perpendicular.getX(), perpendicular.getY());

			Line2D lin = new Line2D(vertex, perpenPoint);

			return new SEUPoint(middlePoint, lin, next);
		}

		return null;
	}

	private void filter(SEURoad road, List<SEUPoint> m_p_points, int threshold) {
		Mark: for (Iterator<SEUPoint> itor = m_p_points.iterator(); itor.hasNext();) {

			SEUPoint m_p = itor.next();
			for (SEUEdge edge : road.getSEUEdges()) {
				if (edge.isPassable())
					continue;
				if (contains(edge.getLine(), m_p.getUnderlyingPoint(), threshold / 2)) {
					itor.remove();
					continue Mark;
				}
			}

			for (SEUBlockade blockade : road.getSEUBlockades()) {
				if (blockade == null)
					continue;
				Polygon polygon = blockade.getPolygon();
				Polygon po = PolygonInflater.expandApexes(blockade.getSelfBlockade(), 200);

				if (po.contains(m_p.getLine().getEndPoint().getX(), m_p.getLine().getEndPoint().getY())) {

					Set<Point2D> intersections = Util.getIntersections(polygon, m_p.getLine());

					double minDistance = Double.MAX_VALUE, distance;
					Point2D closest = null;
					boolean shouldRemove = false;
					for (Point2D inter : intersections) {
						distance = Ruler.getDistance(m_p.getLine().getOrigin(), inter);

						if (distance > threshold && distance < minDistance) {
							minDistance = distance;
							closest = inter;
						}
						shouldRemove = true;
					}

					if (closest != null) {
						Point p = getMiddle(m_p.getLine().getOrigin(), closest);
						m_p.getUnderlyingPoint().setLocation(p);
						m_p.addSEUBlockade(blockade);
					} else if (shouldRemove) {
						itor.remove();
						continue Mark;
					}
				}

				if (po.contains(m_p.getUnderlyingPoint())) {
					itor.remove();
					continue Mark;
				}
			}
		}
	}

	private boolean contains(Line2D line, Point point, double threshold) {

		double pos = java.awt.geom.Line2D.ptSegDist(line.getOrigin().getX(),
				line.getOrigin().getY(), line.getEndPoint().getX(), line
						.getEndPoint().getY(),
				point.getX(), point.getY());
		if (pos <= threshold)
			return true;

		return false;
	}

	private Pair<Double, Boolean> ptSegDistSq(double x1, double y1, double x2,
			double y2, double px, double py) {

		x2 -= x1;
		y2 -= y1;

		px -= x1;
		py -= y1;

		double dotprod = px * x2 + py * y2;

		double projlenSq;

		if (dotprod <= 0) {
			projlenSq = 0;
		} else {
			px = x2 - px;
			py = y2 - py;
			dotprod = px * x2 + py * y2;

			if (dotprod <= 0.0) {
				projlenSq = 0.0;
			} else {
				projlenSq = dotprod * dotprod / (x2 * x2 + y2 * y2);
			}
		}

		double lenSq = px * px + py * py - projlenSq;

		if (lenSq < 0)
			lenSq = 0;

		if (projlenSq == 0) {

			return new Pair<Double, Boolean>(Math.sqrt(lenSq), true);
		} else {

			return new Pair<Double, Boolean>(Math.sqrt(lenSq), false);
		}
	}

	public boolean hasIntersection(Polygon polygon, Line2D line) {
		List<Line2D> polyLines = getLines(polygon);
		for (Line2D ln : polyLines) {

			math.geom2d.line.Line2D line_1 = new math.geom2d.line.Line2D(
					line.getOrigin().getX(), line.getOrigin().getY(),
					line.getEndPoint().getX(), line.getEndPoint().getY());

			math.geom2d.line.Line2D line_2 = new math.geom2d.line.Line2D(
					ln.getOrigin().getX(), ln.getOrigin().getY(),
					ln.getOrigin().getX(), ln.getOrigin().getY());

			if (math.geom2d.line.Line2D.intersects(line_1, line_2)) {

				return true;
			}
		}
		return false;
	}

	private List<Line2D> getLines(Polygon polygon) {
		List<Line2D> lines = new ArrayList<>();
		int count = polygon.npoints;
		for (int i = 0; i < count; i++) {
			int j = (i + 1) % count;
			Point2D p1 = new Point2D(polygon.xpoints[i], polygon.ypoints[i]);
			Point2D p2 = new Point2D(polygon.xpoints[j], polygon.ypoints[j]);
			Line2D line = new Line2D(p1, p2);
			lines.add(line);
		}
		return lines;
	}

	private Point getMiddle(Pair<Integer, Integer> first, Point2D second) {
		int x = first.first() + (int) second.getX();
		int y = first.second() + (int) second.getY();

		return new Point(x / 2, y / 2);
	}

	private Point getMiddle(Point2D first, Point2D second) {
		int x = (int) (first.getX() + second.getX());
		int y = (int) (first.getY() + second.getY());

		return new Point(x / 2, y / 2);
	}

	private Point2D getMiddle(Line2D line) {
		double x = line.getOrigin().getX() + line.getEndPoint().getX();
		double y = line.getOrigin().getY() + line.getEndPoint().getY();

		return new Point2D(x / 2, y / 2);
	}

	private int getLength(Line2D line) {
		return (int) Ruler.getDistance(line.getOrigin(), line.getEndPoint());
	}

	public int getLastUpdateTime() {
		return lastUpdateTime;
	}

	public Polygon getPolygon() {
		return polygon;
	}

	public SEUEdge getOppositeEdge(SEUEdge edge) {
		if (!SEUEdges.contains(edge)) {
			return null;
		}
		List<Pair<SEUEdge, Line2D>> edgeLinesExcept = getEdgeLinesExcept(edge);
		edgeLinesExcept.sort(new Util.AngleComparator(edge.getLine()));
		return !edgeLinesExcept.isEmpty() ? edgeLinesExcept.get(0).first() : null;
	}

	public SEUEdge getOppositePassableEdge(SEUEdge edge) {
		if (!SEUEdges.contains(edge)) {
			return null;
		}
		List<Pair<SEUEdge, Line2D>> passableEdgeLinesExcept = getPassableEdgeLinesExcept(edge);
		passableEdgeLinesExcept.sort(new Util.AngleComparator(edge.getLine()));
		return !passableEdgeLinesExcept.isEmpty() ? passableEdgeLinesExcept.get(0).first() : null;
	}

	public List<Pair<SEUEdge, Line2D>> getPassableEdgeLines() {
		List<Pair<SEUEdge, Line2D>> result = new ArrayList<>();
		for (SEUEdge edge : SEUEdges) {
			if (edge.isPassable()) {
				result.add(new Pair<>(edge, edge.getLine()));
			}
		}
		return result;
	}

	public List<Pair<SEUEdge, Line2D>> getPassableEdgeLinesExcept(SEUEdge exceptEdge) {
		List<Pair<SEUEdge, Line2D>> result = new ArrayList<>();
		math.geom2d.line.Line2D exceptLine = Util.convertLine(exceptEdge.getLine());
		for (SEUEdge edge : SEUEdges) {
			math.geom2d.line.Line2D line = Util.convertLine(edge.getLine());
			if (edge.isPassable() && Util.isCollinear(exceptLine, line, COLLINEAR_THRESHOLD)) {
				result.add(new Pair<>(edge, edge.getLine()));
			}
		}
		return result;
	}

	public List<Pair<SEUEdge, Line2D>> getEdgeLinesExcept(SEUEdge exceptEdge) {
		List<Pair<SEUEdge, Line2D>> result = new ArrayList<>();
		math.geom2d.line.Line2D exceptLine = Util.convertLine(exceptEdge.getLine());
		for (SEUEdge edge : SEUEdges) {
			math.geom2d.line.Line2D line = Util.convertLine(edge.getLine());
			if (!Util.isCollinear(exceptLine, line, COLLINEAR_THRESHOLD)) {
				result.add(new Pair<>(edge, edge.getLine()));
			}
		}
		return result;
	}

	public SEURoad getOppositePassableEdgeRoad(SEUEdge edge) {
		SEUEdge oppositeEdge = getOppositePassableEdge(edge);
		EntityID id = oppositeEdge.getNeighbours().first();
		return world.getSEURoad(id);
	}

	public List<Polygon> getBlockadePolygons() {
		List<Polygon> result = new ArrayList<>();
		for (SEUBlockade blockade : SEUBlockades) {
			result.add(blockade.getPolygon());
		}
		return result;
	}

	public List<Polygon> getBlockadePolygons(int scale) {
		List<Polygon> result = new ArrayList<>();
		for (SEUBlockade blockade : SEUBlockades) {
			Polygon polygon = Util.scaleBySize(blockade.getPolygon(), scale);
			result.add(polygon);
		}
		return result;
	}

	public void setSEUBlockades(List<SEUBlockade> blockades) {
		this.SEUBlockades.clear();
		this.SEUBlockades.addAll(blockades);
	}

	public List<SEUEdge> getSEUEdges() {
		return this.SEUEdges;
	}

	public List<SEUBlockade> getSEUBlockades() {
		return this.SEUBlockades;
	}

	public void setLineOfSight(List<SEULineOfSightPerception.SEURay> rays) {
		this.lineOfSight = rays;
	}

	public List<SEULineOfSightPerception.SEURay> getLineOfSight() {
		return lineOfSight;
	}

	public Set<EntityID> getVisibleFrom() {
		return visibleFrom;
	}

}
