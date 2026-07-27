package com.minos.intellij.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public final class ArchitectureGraphPanel extends JPanel {

    private final Map<String, Rectangle2D> hitBoxes = new LinkedHashMap<>();
    private List<Node> nodes = List.of();
    private List<Edge> edges = List.of();
    private String filter = "";
    private Consumer<JsonObject> selectionListener = ignored -> { };

    public ArchitectureGraphPanel() {
        setPreferredSize(new Dimension(720, 520));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                hitBoxes.entrySet().stream()
                        .filter(entry -> entry.getValue().contains(event.getPoint()))
                        .findFirst()
                        .flatMap(entry -> nodes.stream().filter(node -> node.id().equals(entry.getKey())).findFirst())
                        .ifPresent(node -> selectionListener.accept(node.json()));
            }
        });
    }

    public void setSelectionListener(Consumer<JsonObject> listener) {
        selectionListener = listener == null ? ignored -> { } : listener;
    }

    public void setGraph(JsonObject architecture, int maxNodes) {
        JsonArray moduleArray = array(architecture, "modules");
        List<Node> parsed = new ArrayList<>();
        for (JsonElement element : moduleArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject module = element.getAsJsonObject();
            parsed.add(new Node(
                    string(module, "id"),
                    string(module, "name"),
                    optionalString(module, "relativePath"),
                    module));
        }
        parsed.sort(Comparator.comparing(Node::path).thenComparing(Node::name).thenComparing(Node::id));
        int limit = Math.max(10, Math.min(maxNodes, 500));
        nodes = List.copyOf(parsed.subList(0, Math.min(parsed.size(), limit)));
        Map<String, Node> included = new LinkedHashMap<>();
        nodes.forEach(node -> included.put(node.id(), node));

        List<Edge> parsedEdges = new ArrayList<>();
        for (JsonElement element : array(architecture, "moduleDependencies")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject edge = element.getAsJsonObject();
            String source = string(edge, "sourceModuleId");
            String target = string(edge, "targetModuleId");
            if (included.containsKey(source) && included.containsKey(target)) {
                parsedEdges.add(new Edge(source, target, intValue(edge, "dependencyCount")));
            }
        }
        parsedEdges.sort(Comparator.comparing(Edge::source).thenComparing(Edge::target));
        edges = List.copyOf(parsedEdges);
        repaint();
    }

    public void setFilter(String value) {
        filter = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        repaint();
    }

    public int visibleNodeCount() {
        return visibleNodes().size();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            List<Node> visible = visibleNodes();
            if (visible.isEmpty()) {
                g.drawString("No architecture modules match the current filter.", 20, 30);
                return;
            }
            Map<String, Point> points = layout(visible, getWidth(), getHeight());
            Color foreground = getForeground();
            Color background = getBackground();
            g.setStroke(new BasicStroke(1.2f));
            g.setColor(new Color(foreground.getRed(), foreground.getGreen(), foreground.getBlue(), 110));
            for (Edge edge : edges) {
                Point source = points.get(edge.source());
                Point target = points.get(edge.target());
                if (source == null || target == null) {
                    continue;
                }
                g.drawLine(source.x(), source.y(), target.x(), target.y());
                int labelX = (source.x() + target.x()) / 2;
                int labelY = (source.y() + target.y()) / 2;
                g.drawString(Integer.toString(edge.count()), labelX, labelY);
            }

            hitBoxes.clear();
            FontMetrics metrics = g.getFontMetrics();
            for (Node node : visible) {
                Point point = points.get(node.id());
                String label = node.name();
                int width = Math.max(90, Math.min(220, metrics.stringWidth(label) + 20));
                int height = 30;
                Rectangle2D rectangle = new Rectangle2D.Double(point.x() - width / 2.0, point.y() - height / 2.0, width, height);
                hitBoxes.put(node.id(), rectangle);
                g.setColor(background);
                g.fill(rectangle);
                g.setColor(foreground);
                g.draw(rectangle);
                String clipped = clip(label, metrics, width - 12);
                g.drawString(clipped, (int) rectangle.getX() + 6, (int) rectangle.getCenterY() + metrics.getAscent() / 2 - 2);
            }
        } finally {
            g.dispose();
        }
    }

    private List<Node> visibleNodes() {
        if (filter.isBlank()) {
            return nodes;
        }
        return nodes.stream()
                .filter(node -> (node.id() + " " + node.name() + " " + node.path()).toLowerCase(Locale.ROOT).contains(filter))
                .toList();
    }

    private static Map<String, Point> layout(List<Node> nodes, int width, int height) {
        Map<String, Point> result = new LinkedHashMap<>();
        int centerX = Math.max(160, width / 2);
        int centerY = Math.max(120, height / 2);
        int radius = Math.max(70, Math.min(Math.max(160, width) - 180, Math.max(120, height) - 100) / 2);
        for (int index = 0; index < nodes.size(); index++) {
            double angle = -Math.PI / 2 + (2 * Math.PI * index / nodes.size());
            result.put(nodes.get(index).id(), new Point(
                    centerX + (int) Math.round(Math.cos(angle) * radius),
                    centerY + (int) Math.round(Math.sin(angle) * radius)));
        }
        return result;
    }

    private static String clip(String text, FontMetrics metrics, int maximumWidth) {
        if (metrics.stringWidth(text) <= maximumWidth) {
            return text;
        }
        String suffix = "…";
        int end = text.length();
        while (end > 1 && metrics.stringWidth(text.substring(0, end) + suffix) > maximumWidth) {
            end--;
        }
        return text.substring(0, end) + suffix;
    }

    private static JsonArray array(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static String optionalString(JsonObject object, String name) {
        return string(object, name);
    }

    private static int intValue(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? 0 : value.getAsInt();
    }

    private record Node(String id, String name, String path, JsonObject json) {
    }

    private record Edge(String source, String target, int count) {
    }

    private record Point(int x, int y) {
    }
}
