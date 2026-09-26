package AndroidRoboTeam.world.visualizer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Stroke;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import AndroidRoboTeam.extaction.SEUPF.SEUGuideline;
import rescuecore2.standard.entities.Area;
import rescuecore2.standard.entities.Human;
import rescuecore2.standard.entities.StandardEntity;
import rescuecore2.standard.entities.StandardEntityURN;
import rescuecore2.worldmodel.EntityID;

import AndroidRoboTeam.module.complex.SEUPF.GuidelineCreator;

public class GuidelineVisualizer extends Frame {

    // 聚类数据
    private GuidelineCreator guidelineCreator;
    private List<StandardEntity> allEntities;
    private Map<StandardEntityURN, Color> entityColors;

    // 显示参数
    private static final int WINDOW_WIDTH = 19200;
    private static final int WINDOW_HEIGHT = 10800;
    private static final int MARGIN = 50;
    private static final int POINT_SIZE = 10;
    private static final int CENTER_SIZE = 12;

    // 坐标缩放
    private double minX = Double.MAX_VALUE;
    private double maxX = Double.MIN_VALUE;
    private double minY = Double.MAX_VALUE;
    private double maxY = Double.MIN_VALUE;

    private List<SEUGuideline> guidelines;

    public GuidelineVisualizer(GuidelineCreator guidelineCreator, List<StandardEntity> entities) {
        this.guidelineCreator = guidelineCreator;
        this.allEntities = entities;
        initializeColors();
        calculateBounds();
        setupUI();
    }

    private void initializeColors() {
        entityColors = new HashMap<>();
        entityColors.put(StandardEntityURN.ROAD, Color.LIGHT_GRAY);
        entityColors.put(StandardEntityURN.HYDRANT, Color.CYAN);
        entityColors.put(StandardEntityURN.BUILDING, Color.GRAY);
        entityColors.put(StandardEntityURN.GAS_STATION, Color.RED);
        entityColors.put(StandardEntityURN.REFUGE, Color.GREEN);
        entityColors.put(StandardEntityURN.POLICE_OFFICE, Color.BLUE);
        entityColors.put(StandardEntityURN.FIRE_STATION, Color.RED);
        entityColors.put(StandardEntityURN.AMBULANCE_CENTRE, Color.WHITE);
        entityColors.put(StandardEntityURN.FIRE_BRIGADE, Color.RED);
        entityColors.put(StandardEntityURN.POLICE_FORCE, Color.BLUE);
        entityColors.put(StandardEntityURN.AMBULANCE_TEAM, Color.WHITE);
    }

    private void calculateBounds() {
        for (StandardEntity entity : allEntities) {
            if (entity instanceof Area) {
                Area area = (Area) entity;
                minX = Math.min(minX, area.getX());
                maxX = Math.max(maxX, area.getX());
                minY = Math.min(minY, area.getY());
                maxY = Math.max(maxY, area.getY());
            }
        }

        // 确保边界有效
        if (minX == maxX) {
            minX -= 100;
            maxX += 100;
        }
        if (minY == maxY) {
            minY -= 100;
            maxY += 100;
        }
    }

    private void setupUI() {
        setTitle("K-means Clustering Visualization");
        setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        setBackground(Color.WHITE);

        // 添加窗口关闭事件
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent we) {
                dispose();
            }
        });
    }

    private Point worldToScreen(double worldX, double worldY) {
        int screenX = MARGIN + (int) ((worldX - minX) * (WINDOW_WIDTH - 2 * MARGIN) / (maxX - minX));
        int screenY = WINDOW_HEIGHT - MARGIN - (int) ((worldY - minY) * (WINDOW_HEIGHT - 2 * MARGIN) / (maxY - minY));
        return new Point(screenX, screenY);
    }

    @Override
    public void paint(Graphics g) {
        super.paint(g);
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        drawGrid(g2d);
        drawEntities(g2d);
        // drawClusters(g2d);
        drawGuidelines(g2d);
        drawLegend(g2d);
    }

    private void drawGuidelines(Graphics2D g2d) {
        g2d.setColor(Color.PINK);
        guidelines = guidelineCreator.getGuidelineList();
        for (SEUGuideline guideline : guidelines) {
            Point start = worldToScreen(guideline.getStartPoint().getX(), guideline.getStartPoint().getY());
            Point end = worldToScreen(guideline.getEndPoint().getX(), guideline.getEndPoint().getY());
            Stroke oldStroke = g2d.getStroke();
            g2d.setStroke(new BasicStroke(10f));
            g2d.drawLine(start.x, start.y, end.x, end.y);
            g2d.setStroke(oldStroke);
        }
    }

    private void drawGrid(Graphics2D g2d) {
        g2d.setColor(Color.DARK_GRAY);
        g2d.drawRect(MARGIN, MARGIN, WINDOW_WIDTH - 2 * MARGIN, WINDOW_HEIGHT - 2 * MARGIN);

        // 绘制坐标轴标签
        g2d.setColor(Color.WHITE);
        g2d.drawString(String.format("X: %.1f", minX), MARGIN, WINDOW_HEIGHT - MARGIN + 20);
        g2d.drawString(String.format("X: %.1f", maxX), WINDOW_WIDTH - MARGIN - 40, WINDOW_HEIGHT - MARGIN + 20);
        g2d.drawString(String.format("Y: %.1f", minY), MARGIN - 40, WINDOW_HEIGHT - MARGIN);
        g2d.drawString(String.format("Y: %.1f", maxY), MARGIN - 40, MARGIN + 10);
    }

    private void drawEntities(Graphics2D g2d) {
        for (StandardEntity entity : allEntities) {
            if (entity instanceof Area) {
                Area area = (Area) entity;

                // 根据实体类型设置颜色
                StandardEntityURN urn = entity.getStandardURN();
                Color color = entityColors.getOrDefault(urn, Color.GRAY);
                g2d.setColor(color);

                // 绘制实体边框（使用实体的实际几何形状）
                drawEntityShape(g2d, area, color);
            }

            if (entity instanceof Human) {
                Human human = (Human) entity;
                StandardEntityURN urn = human.getStandardURN();
                Color color = entityColors.getOrDefault(urn, Color.GRAY);
                g2d.setColor(color);
                Point screenPos = worldToScreen(human.getX(), human.getY());
                g2d.fillOval(screenPos.x - POINT_SIZE / 2, screenPos.y - POINT_SIZE / 2, POINT_SIZE, POINT_SIZE);
                g2d.setColor(Color.WHITE);
                g2d.drawOval(screenPos.x - POINT_SIZE / 2, screenPos.y - POINT_SIZE / 2, POINT_SIZE, POINT_SIZE);
            }
        }
    }

    private void drawEntityShape(Graphics2D g2d, Area area, Color color) {
        try {
            // 获取实体的顶点列表（int[]数组）
            int[] apexArray = area.getApexList();
            if (apexArray == null || apexArray.length < 6) { // 至少需要3个点（每个点2个坐标）
                return;
            }

            // 创建多边形对象
            Polygon polygon = new Polygon();

            // 将int[]顶点数据转换为屏幕坐标并添加到多边形
            // 顶点数据格式：[x1, y1, x2, y2, x3, y3, ...]
            for (int i = 0; i < apexArray.length; i += 2) {
                if (i + 1 < apexArray.length) {
                    double worldX = apexArray[i];
                    double worldY = apexArray[i + 1];
                    Point screenPoint = worldToScreen(worldX, worldY);
                    polygon.addPoint(screenPoint.x, screenPoint.y);
                }
            }

            // 设置填充颜色
            Color fillColor = new Color(color.getRed(), color.getGreen(), color.getBlue(), 255);
            g2d.setColor(fillColor);
            g2d.fillPolygon(polygon);

            // 设置边框颜色
            g2d.setColor(Color.BLACK);
            g2d.drawPolygon(polygon);

        } catch (Exception e) {
            // 如果获取顶点失败，回退到绘制中心点
            System.err.println("绘制实体形状时出错: " + e.getMessage());
            Point screenPos = worldToScreen(area.getX(), area.getY());
            g2d.fillOval(screenPos.x - POINT_SIZE / 2, screenPos.y - POINT_SIZE / 2, POINT_SIZE, POINT_SIZE);
        }
    }

    private StandardEntity findEntityById(EntityID id) {
        for (StandardEntity entity : allEntities) {
            if (entity.getID().equals(id)) {
                return entity;
            }
        }
        return null;
    }

    private void drawLegend(Graphics2D g2d) {
        int legendX = WINDOW_WIDTH - 200;
        int legendY = 50;
        int lineHeight = 20;

        g2d.setColor(Color.WHITE);
        g2d.drawString("Legend:", legendX, legendY);

        int index = 1;
        for (Map.Entry<StandardEntityURN, Color> entry : entityColors.entrySet()) {
            g2d.setColor(entry.getValue());
            g2d.fillRect(legendX, legendY + index * lineHeight, 15, 10);
            g2d.setColor(Color.BLACK);
            g2d.drawString(entry.getKey().toString(), legendX + 20, legendY + index * lineHeight + 9);
            index++;
        }
    }

    public void saveScreenshot() {
        try {
            // 创建BufferedImage来捕获屏幕内容
            BufferedImage image = new BufferedImage(WINDOW_WIDTH, WINDOW_HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = image.createGraphics();

            // 设置渲染质量
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 绘制背景
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);

            // 调用paint方法绘制所有内容
            paint(g2d);

            // 生成文件名（包含时间戳）
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss");
            String timestamp = dateFormat.format(new Date());
            String fileName = "Guideline_" + timestamp + ".png";

            // 创建输出目录（如果不存在）
            File outputDir = new File("visualization_output");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            // 保存图像
            File outputFile = new File(outputDir, fileName);
            ImageIO.write(image, "png", outputFile);

            System.out.println("画面已保存至: " + outputFile.getAbsolutePath());

            g2d.dispose();
        } catch (Exception e) {
            System.err.println("保存画面时出错: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void visualize() {
        setVisible(true);

        // 延迟一段时间后自动保存画面
        try {
            Thread.sleep(10000); // 等待3秒确保画面完全渲染
            saveScreenshot();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void saveVisualization() {
        saveScreenshot();
    }

    public static void main(String[] args) {
        // 这里可以创建测试数据来演示可视化工具
        // 由于需要完整的ADF框架环境，实际使用时应在救援模拟环境中调用
        System.out.println("K-means Visualizer - 请在救援模拟环境中使用");
    }
}
